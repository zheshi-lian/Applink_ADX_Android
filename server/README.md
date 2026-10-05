# 参考服务端（P2 · 真实闭环）

SDK 只做客户端。**竞价必须由真实 SSP 服务端完成**，发奖必须由服务端裁决。本目录给一套可跑的参考实现，
让你在本地就能把 SDK 的「竞价 → 展示 → 完播 → 发奖」整条链路跑通，再平滑替换成生产服务。

```
 Android SDK ──/ssp/bid──▶  ADX (ssp.py :8080)
     │                            │
     └──/reward(证据)──▶ App 服务端 (app_server.py :8090) ──HMAC签名──▶ ADX /s2s/reward
```

## 1. 本地联调

```bash
pip install --upgrade pip        # 仅需 python3 标准库，无需第三方依赖
python ssp.py                     # ADX 侧：:8080 提供 /ssp/bid 与 /s2s/reward
python app_server.py              # App 侧：:8090 接收 SDK 证据并签名转发
```

然后改 Demo 的 `AdSdk.init`：
```kotlin
AdSdk.init(this, AdSdk.Config(
    appKey = "demo_appkey",
    serverUrl = "http://<你电脑局域网IP>:8080",        // 指向 ssp.py
    siteDomain = "demo.example.com",
    appServerRewardUrl = "http://<你电脑局域网IP>:8090/reward"  // 指向 app_server.py
))
```
> Demo 手机/模拟器与运行 server 的电脑要在同一局域网；或用 `adb reverse tcp:8080 tcp:8080` 等。

## 2. 接口契约

### POST /ssp/bid（SDK → ADX，OpenRTB 2.5 子集）
请求（`AdSdk.buildBidRequest` 产出）：
```json
{
  "id": "imp_xxx",
  "app": { "bundle": "com.demo", "publisher": { "id": "demo_appkey", "domain": "demo.example.com" } },
  "device": { "ua": "...", "os": "Android", "osv": "14", "w": 1080, "h": 2400, "geo": { "country": "CN" } },
  "user": { "consent": "" },
  "regs": { "gdpr": 0, "coppa": 0 },
  "imp": [ { "id": "imp_xxx", "bidfloor": 1.0, "ext": { "ad_unit_id": "home-banner", "ad_type": "banner" } } ],
  "ext": { "app_key": "demo_appkey", "ccpa_opt_out": 0, "test": false, "consent": "" }
}
```
响应：
```json
{
  "id": "imp_xxx",
  "seatbid": [ { "seat": "applink", "bid": [ {
    "id": "bid_xxx", "impid": "imp_xxx", "price": 1500000,
    "adm": "<HTML 或 VAST XML>",
    "crid": "cr_001",
    "ext": { "cid": "camp_001", "ad_format": "banner", "adm_type": "html", "advertiser": "示例广告主" }
  } ] } ]
}
```
激励视频时 `ext` 额外带 `"rw": { "token": "<一次性令牌>" }`，且 `adm` 为 VAST 4.0。

### POST /s2s/reward（App 服务端 → ADX）
请求（App 服务端签名后发出）：
```json
{ "impid": "imp_xxx", "cid": "camp_001", "token": "<一次性令牌>",
  "watchedMs": 10000, "durationMs": 10500, "ts": 1690000000,
  "sig": "HMAC_SHA256(api_key, \"impid|cid|token|watchedMs|durationMs|ts\")" }
```
响应：`{ "ok": true, "reward": "100金币" }` 或 `{ "ok": false, "reason": "BAD_SIG|REPLAY_OR_UNKNOWN|INCOMPLETE" }`。

## 3. 接真实 OpenRTB SSP（生产）

1. **替换 `ssp.py`**：用你的真实 SSP 实现 `POST /ssp/bid`，消费上面的请求体（用 `app.publisher.id` 鉴权、`imp.ext.ad_type` 区分形态），
   返回 `seatbid[0].bid[0]`（`adm`=HTML 或 VAST，`ext` 带 `cid/ad_format/adm_type/advertiser`，rewarded 带 `rw.token`）。
2. **保留 `app_server.py`**（或你自己的 App 服务端）：它持有 `api_key`、做 HMAC 签名、转发到 ADX `/s2s/reward`。
   注意：**`api_key` 只能在服务端，SDK 永不持有**（SDK 不可信，不能让它自己签名发奖）。
3. **改 SDK 配置**：`AdSdk.init` 的 `serverUrl` 指向你的 SSP 域名（HTTPS），`appServerRewardUrl` 指向你的 App 服务端（HTTPS）。
4. **发奖落地**：`/s2s/reward` 返回 `ok:true` 后，App 服务端负责把 `reward` 实际发放到用户账户（扣量/风控/对账在你这边）。

## 4. 安全要点
- 客户端不可信：SDK 只转发「观看证据」，不判定、不签名、不持有 `api_key`。
- 防刷：令牌一次性 + 5 分钟签名时效 + 完播比例阈值（`watchedMs/durationMs ≥ 0.95`）。
- 生产必须 HTTPS；`api_key` 通过密钥管理（KMS/环境变量）注入，别硬编码进仓库。
