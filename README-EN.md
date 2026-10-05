# AppLink ADX · Android Native Ad SDK

All-in-one Android SDK for **rewarded video (VAST 4.0)** + native **AdView** (banner / interstitial /
splash / native / push). **Zero third-party dependencies** (only Android SDK + Kotlin stdlib).
Produces `applink-adsdk-release.aar`.

> **Security boundary (client is untrusted)**: the SDK only does *bid → parse → render → tracking →
> deliver view evidence*. It **never decides reward locally**. Reward is granted by YOUR app server via
> an S2S callback to ADX `/s2s/reward` (HMAC-signed), so clients cannot forge completion.

## Layout

```
.
├── build.gradle                 # :adsdk library (produces AAR)
├── settings.gradle              # multi-module: :adsdk (lib) + :app (demo)
├── .github/workflows/build-aar.yml   # CI: push → Actions artifact; tag v* → GitHub Release
├── src/main/...                 # SDK sources (AdView.kt / RewardedAdSdk.kt)
├── app/                         # runnable demo host (MainActivity / DemoApp)
└── QUICKSTART.md                # how to publish via GitHub
```

## Integrate (pick one)

### A. AAR (recommended)
1. Get `applink-adsdk-release.aar` from Releases (tag `v*`) or Actions artifacts.
2. Drop it into your app's `app/libs/`.
3. Add to `app/build.gradle`:
   ```gradle
   dependencies { implementation fileTree(dir: 'libs', include: ['*.aar']) }
   ```
4. Declare permissions in `AndroidManifest.xml`:
   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
   ```

### B. Source
Open this repo in Android Studio as a multi-module project, or copy
`src/main/java/com/zhuque/adsdk/*.kt` into your project (package `com.zhuque.adsdk`).

## Quick start

```kotlin
// Banner / interstitial: HTML → WebView
val adView = AdView(context)
adView.adxBase = "https://<your-adx>"
adView.siteDomain = "<your-registered-domain>"
adView.adUnitId = "home-banner"
adView.loadBanner(
    AdView.Listener { ad -> adView.setBannerHtml(ad.html) },
    AdView.FailListener { reason -> Log.w("Ad", "banner:$reason") }
)

// Rewarded: VAST 4.0 → VideoView, reward via S2S
RewardedAdSdk.load(
    RewardedAdSdk.Config(
        adxBase = "https://<your-adx>",
        siteDomain = "<your-registered-domain>",
        appServerRewardUrl = "https://your-app-server/reward"
    ),
    "casual,rewarded",
    object : RewardedAdSdk.LoadCallback {
        override fun onLoaded(ad: RewardedAdSdk.Ad) {}
        override fun onFailed(reason: String) {}
    }
)
```

## Server reward callback (app server → ADX, S2S + HMAC)

```
POST {ADX}/s2s/reward
body: { impid, cid, publisher, watchedMs, durationMs, ts, sig }
sig  = HMAC_SHA256(api_key, "impid|cid|watchedMs|durationMs|ts")
```
