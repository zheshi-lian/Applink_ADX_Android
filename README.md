# AppLink ADX · Android 原生广告 SDK

激励视频 + 原生 `AdView`（横幅/插屏/激励/原生）一体化 SDK。**无第三方依赖**（仅 Android SDK + Kotlin stdlib）。

> 责任边界（客户端不可信原则）：SDK 只负责「竞价 → 解析 → 渲染 → tracking 上报 → 交付观看证据」；
> **SDK 绝不自行判定是否发奖**。发奖由你的 App 服务端 S2S 回调 ADX `/s2s/reward`（HMAC 签名）裁决，再由你的服务端下发奖励，客户端无法伪造。

## 集成（二选一）

### 方式 A：直接用 AAR（推荐，对标 AppLuck SDK 的分发方式）

1. 获取 `applink-adsdk-release.aar`：
   - **Release 下载**：本仓库 Releases 页（打 `v*` tag 由 CI 自动附上 AAR）；或
   - **本地自建**：`pwsh -File ./build-aar.ps1`（Windows）/ `./gradlew assembleRelease`（任意平台，需 JDK17 + Android SDK）→ `build/outputs/aar/applink-adsdk-release.aar`
2. 把 `applink-adsdk-release.aar` 放进你工程的 `app/libs/`。
3. 你工程的 `app/build.gradle` 加依赖：
   ```gradle
   dependencies {
       implementation fileTree(dir: 'libs', include: ['*.jar', '*.aar'])
   }
   ```
4. 你工程的 `AndroidManifest.xml` 加权限（SDK 内部已声明，宿主仍需显式声明一次）：
   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
   ```

Maven 坐标（若走私有 Maven 仓库，本工程 `publishReleasePublicationToMavenLocal` 可产出）：
```gradle
implementation 'com.zhuque.adsdk:applink-adsdk:1.0.0'
```

### 方式 B：源码集成

用 Android Studio 直接打开本目录作为 library module，或把
`src/main/java/com/zhuque/adsdk/AdView.kt` 与 `RewardedAdSdk.kt` 拷进你的工程（包名 `com.zhuque.adsdk`）。

## 自动出包（GitHub Actions，无需本地装 Android SDK）

本仓库内置 `.github/workflows/build-aar.yml`：
- 任意 `git push` 到 `main` → 在仓库 **Actions** 页面的 **Artifacts** 里下载 `applink-adsdk-aar`（含 `applink-adsdk-release.aar`）。
- 打版本标签并推送（`git tag v1.0.0 && git push --tags`）→ 自动在 **Releases** 页生成带 AAR 的发布。

> 只有 `repository` 才有 Actions 能力；请新建一个 **Repository**（不是 Issue、也不是 Codespace），
> 把本目录内容推上去即可。详见仓库根目录 `QUICKSTART.md`。

## 本地出包（可选，需自备工具链）

若本机已装 **JDK 17 + Android SDK + Gradle**，可本地编译：
- Windows：`pwsh -File ./build-aar.ps1`
- 任意平台：`gradle assembleRelease`（或先 `gradle wrapper` 生成 `gradlew` 再 `./gradlew assembleRelease`）
- 产物：`build/outputs/aar/applink-adsdk-release.aar`

## 快速开始

```kotlin
// 激励视频
RewardedAdSdk.load(
    RewardedAdSdk.Config(
        adxBase = "https://<你的ADX域名>",          // 平台竞价域名
        siteDomain = "<你在ADX注册的媒体域名>",      // 须与注册域名一致
        appServerRewardUrl = "https://你的App服务端/reward"
    ),
    "休闲游戏,激励视频",                              // Placement / 广告位标签
    object : RewardedAdSdk.LoadCallback {
        override fun onLoaded(ad: RewardedAdSdk.Ad) { /* 缓存成功 */ }
        override fun onFailed(reason: String) { /* 拉不到广告 */ }
    }
)

// 播放 + 上报
RewardedAdSdk.show(videoView, ad, object : RewardedAdSdk.RewardCallback {
    override fun onGranted(reward: String) { /* 你的服务端已确认发奖 */ }
    override fun onDenied(reason: String) { /* 未达标，不发 */ }
})
```

原生 `AdView`（横幅/插屏/原生）：

```kotlin
val adView = AdView(context)
adView.loadBanner(adUnitId = "<ad_unit_id>", listener = object : AdView.Listener {
    override fun onAdLoaded() {}
    override fun onAdFailed(reason: String) {}
})
```

## 服务端发奖回调（你 → ADX，S2S + HMAC）

```
POST {ADX}/s2s/reward
body: { impid, cid, publisher, watchedMs, durationMs, ts, sig }
sig  = HMAC_SHA256(api_key, "impid|cid|watchedMs|durationMs|ts")
```

## 常见问题
- 拉不到广告：确认 `siteDomain` 与 ADX 注册域名一致、`adUnitId` 有效；用 `/integrity.html` 或 `GET /api/publisher/integrity` 自检。
- 收益为 0：确认已配置收款（`POST /api/publisher/payment`）且有胜出（`/api/publisher/report`）。
