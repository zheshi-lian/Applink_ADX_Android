package com.zhuque.adsdk

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Xml
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import android.util.DisplayMetrics
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * AdView —— 极简原生广告 SDK（Android）
 *
 * 【设计原则】
 *   · 单文件、无外部依赖（仅 Android SDK + Kotlin stdlib）
 *   · 一个 AdView 控件 + 多 format 的 load 方法：loadBanner / loadInterstitial / loadRewarded / loadNative / loadPush
 *   · HTTP 调用：POST /ssp/bid（OpenRTB 2.5+），与平台竞价引擎直连，不经过中间层
 *   · 客户端不可信：SDK 只负责 竞价→解析→渲染→上报；发奖由 App 服务端 S2S 回调平台裁决，SDK 绝不本地判定发奖
 *
 * 【安全边界｜客户端不可信】
 *   1. SDK 拿到的是一次性签名令牌（/ssp/bid 返回 ext.rw.token），仅用于上报，无法自证完播。
 *   2. 激励视频发奖：SDK 把「观看证据」(impid/cid/crid/watchedMs/durationMs) 交给 App 服务端；
 *      服务端用 api_key 做 HMAC-SHA256 签名后回调 /s2s/reward，平台校验 签名→令牌未重放→设备指纹→完播比例→才 granted。
 *   3. SDK 本身不持有 rwToken 也不本地发奖，杜绝客户端伪造完播刷量。
 *
 * 【用法示例】
 *   val adView = AdView(context)
 *   adView.adUnitId = "首页-banner"
 *   adView.siteDomain = "mygame.example.com"
 *   adView.adxBase = "https://dellai.xyz"
 *   adView.appServerRewardUrl = "https://你的服务端/reward"   // S2S 发奖回调（仅 rewarded 需要）
 *
 *   // Banner / Interstitial（HTML 创意 → WebView）
 *   adView.loadBanner(AdView.Listener { ad -> adView.setBannerHtml(ad.html) },
 *                     { reason -> Log.w("Ad", "banner:$reason") })
 *   // Rewarded（VAST 4.0 → VideoView，完播后 S2S 发奖）
 *   adView.loadRewarded(AdView.Listener { ad -> adView.playRewarded(videoView, ad) { reward -> /* 发奖 */ } },
 *                     { reason -> Log.w("Ad", "rewarded:$reason") })
 *   // Native（结构化 JSON，由 App 自渲染样式）
 *   adView.loadNative(AdView.Listener { ad -> renderMyOwn(ad.nativeJson) },
 *                    { reason -> Log.w("Ad", "native:$reason") })
 */
class AdView(context: Context) : FrameLayout(context) {

    // ---------- 配置 ----------
    var adUnitId: String = ""
    var siteDomain: String = ""
    var adxBase: String = "https://dellai.xyz"
    var appServerRewardUrl: String = ""   // App 服务端接口：由它做 S2S 签名回调 /s2s/reward（仅 rewarded 需要）

    // ---------- 数据模型 ----------
    data class Ad(
        val impId: String,
        val cid: String,            // 计划 ID（归因用）
        val crid: String,           // 创意 ID（归因到具体素材）
        val price: Long,            // 价格（micros，1 元 = 1_000_000）
        val format: String,         // banner / interstitial / splash / rewarded / native / icon / push
        val admType: String,        // html / vast4 / native_json / push
        val html: String = "",      // admType=html 时的 HTML 创意
        val vastUrl: String = "",   // admType=vast4 时的视频地址
        val vastDuration: String = "",
        val nativeJson: String = "",// admType=native_json 时的结构化 JSON
        val pushJson: String = "",  // admType=push 时的推送内容（由推送系统下发）
        val rwToken: String = "",   // 激励视频一次性令牌（仅上报用，不可本地发奖）
        val tracking: Map<String, String> = emptyMap(), // VAST tracking events
        val advertiser: String = "" // 创意归属广告主（归因展示用）
    )

    // ---------- 回调 ----------
    fun interface Listener { fun onAdLoaded(ad: Ad) }
    fun interface FailListener { fun onFailed(reason: String) }
    interface RewardListener { fun onRewardGranted(reward: String); fun onRewardDenied(reason: String) }

    // ---------- 内部 ----------
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var currentBannerWebView: WebView? = null

    // ========== ① 竞价请求 ==========
    fun loadBanner(onAd: Listener, onFail: FailListener) = load("banner", onAd, onFail)
    fun loadInterstitial(onAd: Listener, onFail: FailListener) = load("interstitial", onAd, onFail)
    fun loadRewarded(onAd: Listener, onFail: FailListener) = load("rewarded", onAd, onFail)
    fun loadNative(onAd: Listener, onFail: FailListener) = load("native", onAd, onFail)
    fun loadPush(onAd: Listener, onFail: FailListener) = load("push", onAd, onFail)

    /** 通用入口：adType = banner/interstitial/splash/rewarded/native/icon/push */
    fun load(adType: String, onAd: Listener, onFail: FailListener) {
        val base = if (adxBase.isBlank()) "https://dellai.xyz" else adxBase.removeSuffix("/")
        val impId = "imp_${UUID.randomUUID()}"
        io.execute {
            try {
                val body = JSONObject().apply {
                    put("id", impId)
                    put("site", JSONObject().put("domain", siteDomain))
                    put("imp", JSONArray().put(JSONObject().apply {
                        put("id", impId)
                        put("bidfloor", 1.0)
                        put("ext", JSONObject().put("ad_unit_id", adUnitId).put("ad_type", adType))
                    }))
                    put("device", deviceJson())
                }
                val res = JSONObject(postJson(base + "/ssp/bid", body.toString()))
                val seat = res.optJSONArray("seatbid")?.optJSONObject(0) ?: throw Exception("NO_FILL")
                val bid = seat.optJSONArray("bid")?.optJSONObject(0) ?: throw Exception("NO_FILL")
                val adm = bid.optString("adm", "")
                if (adm.isBlank()) throw Exception("NO_FILL")
                val ext = bid.optJSONObject("ext") ?: JSONObject()
                val fmt = ext.optString("ad_format", adType)
                val admType = ext.optString("adm_type", "html")
                val cid = ext.optString("cid", "")
                val crid = bid.optString("crid", "")
                val advertiser = ext.optString("advertiser", "")

                val (vastUrl, vastDuration, tracking) = if (fmt == "rewarded" && admType == "vast4") {
                    val v = parseVast(adm) ?: throw Exception("BAD_VAST")
                    Triple(v.mediaUrl, v.duration, v.tracking)
                } else Triple("", "", emptyMap())

                val rw = ext.optJSONObject("rw") ?: JSONObject()
                val ad = Ad(
                    impId = impId, cid = cid, crid = crid, price = bid.optLong("price", 0),
                    format = fmt, admType = admType,
                    html = if (admType == "html") adm else "",
                    vastUrl = vastUrl, vastDuration = vastDuration, tracking = tracking,
                    nativeJson = if (admType == "native_json") adm else "",
                    pushJson = if (admType == "push") (ext.optJSONObject("push")?.toString() ?: "") else "",
                    rwToken = rw.optString("token", ""),
                    advertiser = advertiser
                )
                main.post { onAd.onAdLoaded(ad) }
            } catch (e: Exception) {
                main.post { onFail.onFailed(e.message ?: "UNKNOWN") }
            }
        }
    }

    // ========== ② 渲染 ==========
    /** 渲染 Banner（HTML 创意到内置 WebView） */
    fun setBannerHtml(html: String) {
        val webView = currentBannerWebView ?: WebView(context).also {
            it.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            it.setBackgroundColor(0x00000000)
            addView(it); currentBannerWebView = it
        }
        webView.settings.javaScriptEnabled = true
        webView.settings.loadsImagesAutomatically = true
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = false
        }
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    /** 展示全屏插屏（返回 WebView 供调用方添加到 Activity） */
    fun showInterstitial(html: String): WebView {
        val webView = WebView(context)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        webView.setBackgroundColor(0xFFFFFFFF.toInt())
        webView.settings.javaScriptEnabled = true
        webView.settings.loadsImagesAutomatically = true
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        return webView
    }

    /** 播放激励视频（VideoView + VAST 4.0 tracking）；完播后把观看证据交给 App 服务端 S2S 裁决 */
    fun playRewarded(videoView: VideoView, ad: Ad, onReward: RewardListener) {
        if (ad.vastUrl.isBlank()) { main.post { onReward.onRewardDenied("BAD_URL") }; return }
        val startMs = System.currentTimeMillis()
        val durationMs = parseDurationMs(ad.vastDuration)
        fireTracking(ad.tracking["impression"], ad)
        videoView.setVideoURI(Uri.parse(ad.vastUrl))
        videoView.setOnPreparedListener {
            fireTracking(ad.tracking["start"], ad)
            videoView.start()
        }
        videoView.setOnCompletionListener {
            val watchedMs = (System.currentTimeMillis() - startMs).toInt()
            fireTracking(ad.tracking["complete"], ad)
            reportToAppServer(ad, watchedMs, durationMs.toInt(), onReward) // ★ 不本地发奖
        }
        videoView.setOnErrorListener { _, _, _ ->
            main.post { onReward.onRewardDenied("VIDEO_ERROR") }; true
        }
    }

    // ========== ③ VAST 4.0 解析 ==========
    private data class VastAd(val mediaUrl: String, val duration: String, val tracking: Map<String, String>)
    private fun parseVast(xml: String): VastAd? {
        val p = Xml.newPullParser(); p.setInput(xml.reader())
        var mediaUrl = ""; var duration = ""; val tracking = HashMap<String, String>()
        var event = p.eventType; var curEvent: String? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "Duration" -> duration = p.nextText()
                    "Tracking" -> curEvent = p.getAttributeValue(null, "event")
                    "Impression" -> tracking["impression"] = p.nextText()
                }
                XmlPullParser.TEXT -> {
                    val text = p.text.trim()
                    if (text.startsWith("http")) {
                        if (curEvent != null) tracking[curEvent!!] = text
                        else if (mediaUrl.isBlank()) mediaUrl = text
                    }
                }
                XmlPullParser.END_TAG -> if (p.name == "Tracking") curEvent = null
            }
            event = p.next()
        }
        return if (mediaUrl.isBlank()) null else VastAd(mediaUrl, duration, tracking)
    }

    // ========== ④ 结算：上报观看证据给 App 服务端（真正的裁决在服务端） ==========
    private fun reportToAppServer(ad: Ad, watchedMs: Int, durationMs: Int, onReward: RewardListener) {
        if (appServerRewardUrl.isBlank()) { main.post { onReward.onRewardDenied("NO_APP_SERVER_URL") }; return }
        io.execute {
            try {
                val body = JSONObject().apply {
                    put("impid", ad.impId); put("cid", ad.cid); put("crid", ad.crid)
                    put("watchedMs", watchedMs); put("durationMs", durationMs)
                }
                val r = JSONObject(postJson(appServerRewardUrl, body.toString()))
                val ok = r.optBoolean("ok", false)
                val reward = r.optString("reward", "")
                val reason = r.optString("reason", "SERVER_DENIED")
                main.post { if (ok) onReward.onRewardGranted(reward) else onReward.onRewardDenied(reason) }
            } catch (e: Exception) {
                main.post { onReward.onRewardDenied("NETWORK") }
            }
        }
    }

    // ========== ⑤ 工具方法 ==========
    private fun deviceJson(): JSONObject = JSONObject().apply {
        put("ua", System.getProperty("http.agent") ?: "")
        put("os", "Android"); put("osv", Build.VERSION.RELEASE)
        put("w", screenW()); put("h", screenH())
        put("make", Build.MANUFACTURER); put("model", Build.MODEL)
        put("geo", JSONObject().put("country", "CN"))
    }
    private fun screenW(): Int {
        val dm = DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getMetrics(dm); return dm.widthPixels
    }
    private fun screenH(): Int {
        val dm = DisplayMetrics()
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getMetrics(dm); return dm.heightPixels
    }
    private fun parseDurationMs(d: String): Long {
        val parts = d.split(":").map { it.toLongOrNull() ?: 0L }
        return if (parts.size == 3) (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L else 0L
    }
    private fun fireTracking(url: String?, ad: Ad) {
        if (url.isNullOrBlank()) return
        io.execute {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"; conn.connectTimeout = 5000; conn.responseCode; conn.disconnect()
            } catch (e: Exception) { /* fire-and-forget */ }
        }
    }
    private fun postJson(urlStr: String, body: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            conn.doOutput = true
            conn.outputStream.write(body.toByteArray())
            conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
    }
}
