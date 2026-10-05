# 通过 GitHub Contents API 上传整个 SDK 工程（绕过被墙的 git 协议）
# 用法：在该目录用 PowerShell 运行  .\push-via-api.ps1
$owner = "zheshi-lian"
$repo  = "Applink_ADX_Android"
$branch = "main"
$base  = Split-Path -Parent $MyInvocation.MyCommand.Path

$token = Read-Host -Prompt "粘贴你的 GitHub PAT（ghp_...）"
$headers = @{
    Authorization = "Bearer $token"
    Accept        = "application/vnd.github+json"
}

# 取分支最新 commit sha（用于更新已存在文件）
$ref = Invoke-RestMethod "https://api.github.com/repos/$owner/$repo/git/refs/heads/$branch" -Headers $headers
Write-Host "分支 $branch 当前 HEAD: $($ref.object.sha)"

# 收集文件：排除 .git / build / 本脚本
$files = Get-ChildItem -Path $base -Recurse -File | Where-Object {
    $_.FullName -notmatch '[\\/]\.git[\\/]' -and
    $_.FullName -notmatch '[\\/]build[\\/]' -and
    $_.Name -ne 'push-via-api.ps1'
}
# 排序：先把非 .github 文件传完，最后再传 .github/workflows（确保触发构建时源码已就位）
$files = $files | Sort-Object { ($_.FullName -match '[\\/]\.github[\\/]') }, FullName

$ok = 0; $fail = 0
foreach ($f in $files) {
    $rel  = $f.FullName.Substring($base.Length + 1) -replace '\\', '/'
    $b64  = [Convert]::ToBase64String([IO.File]::ReadAllBytes($f.FullName))
    $url  = "https://api.github.com/repos/$owner/$repo/contents/$rel"

    $body = @{ message = "chore: add $rel"; content = $b64; branch = $branch }
    try {
        $existing = Invoke-RestMethod $url -Headers $headers
        $body.sha = $existing.sha   # 已存在 → 更新
    } catch { }                     # 不存在 → 新建

    try {
        Invoke-RestMethod -Method PUT $url -Headers $headers -Body ($body | ConvertTo-Json) -ContentType "application/json"
        Write-Host "OK   $rel"
        $ok++
    } catch {
        Write-Host "FAIL $rel -> $_"
        $fail++
    }
}
Write-Host "`n完成：成功 $ok 个，失败 $fail 个"
if ($fail -eq 0) { Write-Host "现在去仓库 Actions 标签看构建（最后上传的是 .github/workflows/build-aar.yml，会触发首次构建）" }
