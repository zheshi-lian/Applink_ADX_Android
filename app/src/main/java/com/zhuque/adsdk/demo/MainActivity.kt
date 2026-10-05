package com.zhuque.adsdk.demo

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import com.zhuque.adsdk.AdView
import com.zhuque.adsdk.RewardedAdSdk

/**
 * 演示宿主工程：展示 SDK 两大入口
 *   1) AdView.loadBanner → HTML 创意在 WebView 渲染
 *   2) RewardedAdSdk.load → VAST 激励视频，完播后由 App 服务端 S2S 裁决发奖
 *
 * 仅用于编译验证与集成参考；adxBase/siteDomain 请替换为你自己的 ADX 配置。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val container = findViewById<FrameLayout>(R.id.adContainer)
        val btnBanner = findViewById<Button>(R.id.btnLoadBanner)
        val btnReward = findViewById<Button>(R.id.btnLoadRewarded)

        // ① 横幅：HTML 创意 → 内置 WebView
        btnBanner.setOnClickListener {
            val adView = AdView(this).also {
                it.adxBase = "https://dellai.xyz"
                it.siteDomain = "demo.example.com"
                it.adUnitId = "demo-banner"
            }
            adView.loadBanner(
                AdView.Listener { ad ->
                    adView.setBannerHtml(ad.html)
                    container.addView(adView)
                },
                AdView.FailListener { Toast.makeText(this, "no fill: $it", Toast.LENGTH_SHORT).show() }
            )
        }

        // ② 激励视频：VAST → 完播后 App 服务端 S2S 回调 ADX 裁决（SDK 不本地发奖）
        btnReward.setOnClickListener {
            RewardedAdSdk.load(
                RewardedAdSdk.Config(
                    adxBase = "https://dellai.xyz",
                    siteDomain = "demo.example.com",
                    appServerRewardUrl = "https://demo.example.com/reward"
                ),
                "休闲游戏,激励视频",
                object : RewardedAdSdk.LoadCallback {
                    override fun onLoaded(ad: RewardedAdSdk.Ad) {
                        Toast.makeText(this@MainActivity, "rewarded loaded", Toast.LENGTH_SHORT).show()
                    }
                    override fun onFailed(reason: String) {
                        Toast.makeText(this@MainActivity, "fail: $reason", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }
}
