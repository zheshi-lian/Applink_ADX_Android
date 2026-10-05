# AppLink ADX · Android 原生广告 SDK

激励视频（VAST 4.0）+ 原生 `AdView`（横幅 / 插屏 / 开屏 / 原生 / 推送）一体化 SDK。
**零第三方依赖**（仅 Android SDK + Kotlin stdlib），可产 `applink-adsdk-release.aar`。

> **安全边界（客户端不可信）**：SDK 只负责「竞价 → 解析 → 渲染 → tracking 上报 → 交付观看证据」；
> **SDK 绝不自行判定是否发奖**。发奖由你的 App 服务端 S2S 回调 ADX `/s2s/reward`（HMAC 签名）裁决，
> 再由服务端下发奖励，客户端无法伪造完播。

## 目录结构（对标商业化闭环）

```
.
├── build.gradle                 # :adsdk 库工程（产出 AAR）
├── settings.gradle              # 多模块：:adsdk（库） + :app（演示）
├── gradle.properties
├── .github/workflows/build-aar.yml   # 自动出包（push → Actions 取 AAR；打 v* tag → Releases 发布）
├── src/main/...                 # SDK 源码（AdView.kt / RewardedAdSdk.kt）
├── app/                         # 可运行 Demo 宿主工程（MainActivity / DemoApp）
├── QUICKSTART.md                # 上 GitHub 自动出包手把手
└── README-EN.md                 # English
```

## 集成方式（三选一）

### A. 直接用 AAR（推荐）
1. 取包：Releases 页下载 `applink-adsdk-release.aar`，或 Actions → Artifacts `applink-adsdk-aar`。
2. 放入宿主工程 `app/libs/`。
3. 宿主 `app/build.gradle` 加：
   ```gradle
   dependencies { implementation fileTree(dir: 'libs', include: ['*.aar']) }
   ```
4. 宿主 `AndroidManifest.xml` 声明权限（SDK 已声明，宿主仍建议显式一次）：
   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
   ```

### B. 源码集成
用 Android Studio 打开本仓库作为多模块工程，或把 `src/main/java/com/zhuque/adsdk/` 下两个 `.kt` 拷进你的工程（包名 `com.zhuque.adsdk`）。

### C. 本地自行出包（需 JDK17 + Android SDK + Gradle）
```powershell
pwsh -File ./build-aar.ps1          # Windows
gradle :adsdk:assembleRelease       # 任意平台
# 产物：build/outputs/aar/applink-adsdk-release.aar
```

## 快速开始

```kotlin
// ① 横幅 / 插屏：HTML 创意 → WebView
val adView = AdView(context)
adView.adxBase = "https://<你的ADX域名>"
adView.siteDomain = "<你在ADX注册的媒体域名>"
adView.adUnitId = "首页-banner"
adView.loadBanner(
    AdView.Listener { ad -> adView.setBannerHtml(ad.html) },
    AdView.FailListener { reason -> Log.w("Ad", "banner:$reason") }
)

// ② 激励视频：VAST 4.0 → VideoView，完播后 S2S 裁决发奖
RewardedAdSdk.load(
    RewardedAdSdk.Config(
        adxBase = "https://<你的ADX域名>",
        siteDomain = "<媒体域名>",
        appServerRewardUrl = "https://你的App服务端/reward"
    ),
    "休闲游戏,激励视频",
    object : RewardedAdSdk.LoadCallback {
        override fun onLoaded(ad: RewardedAdSdk.Ad) { /* 缓存成功 */ }
        override fun onFailed(reason: String) { /* 拉不到广告 */ }
    }
)
```

## 支持的广告形态

| 形态 | 渲染方式 | 说明 |
|---|---|---|
| banner | HTML → WebView | 横幅 |
| interstitial | HTML → WebView（全屏） | 插屏 |
| splash | HTML → WebView | 开屏 |
| rewarded | VAST 4.0 → VideoView | 激励视频，**发奖走 S2S** |
| native | 结构化 JSON | 由 App 自渲染样式 |
| push | 推送系统下发 | 不经 SDK 渲染 |

## 服务端发奖回调（你 → ADX，S2S + HMAC）

```
POST {ADX}/s2s/reward
body: { impid, cid, publisher, watchedMs, durationMs, ts, sig }
sig  = HMAC_SHA256(api_key, "impid|cid|watchedMs|durationMs|ts")
```

## 常见问题
- **拉不到广告**：确认 `siteDomain` 与 ADX 注册域名一致、`adUnitId` 有效；用 `GET /api/publisher/integrity` 自检。
- **收益为 0**：确认已配置收款（`POST /api/publisher/payment`）且有胜出（`/api/publisher/report`）。
- **Actions 没出包**：进仓库 **Actions** 看日志；多半是 `src/` 或 `build-aar.yml` 没推上去。详见 `QUICKSTART.md`。
