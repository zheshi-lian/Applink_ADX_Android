#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AppLink ADX · 参考 SSP 服务端（ADX 侧）

实现两个端点，和 Android SDK 形成完整闭环：
  POST /ssp/bid      —— 接收 SDK 的 OpenRTB 竞价请求，返回创意（HTML / VAST）
  POST /s2s/reward   —— 接收 App 服务端转发的「观看证据」，HMAC 校验后裁决是否发奖

纯标准库实现（python3 server.py 即可运行），仅用于联调/演示。
生产环境请用真实 SSP 替换 /ssp/bid，并把 api_key 放在服务端、走 HTTPS。

运行：  python ssp.py            # 默认监听 :8080
"""
import hashlib
import hmac
import json
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

API_KEY = "demo_api_key"          # ★ 生产环境：只在 ADX 与 App 服务端共享，绝不进 SDK
SSP_PORT = 8080

# —— 演示用素材（生产由真实竞价引擎填充）——
SAMPLE_VIDEO = "https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
SAMPLE_HTML = (
    '<div style="width:100%;height:100%;background:#1a73e8;color:#fff;'
    'display:flex;align-items:center;justify-content:center;font-size:20px">'
    '示例广告 · AppLink ADX</div>'
)
TRACK_BASE = "https://ssp.example.com/track"   # 仅作 beacon 示例，本地不可达时静默失败

# 一次性令牌存储：impid -> token（发奖后标记已用，防重放）
_TOKEN_STORE: dict[str, dict] = {}


def _bid_request_is_valid(body: dict) -> bool:
    app = body.get("app") or {}
    pub = app.get("publisher") or {}
    app_key = pub.get("id") or (body.get("ext") or {}).get("app_key")
    # 演示：只放行已知 appKey；生产应查数据库
    return app_key in ("demo_appkey", "YOUR_APPKEY")


def _build_html_bid(impid: str, ad_type: str) -> dict:
    return {
        "id": "bid_" + impid,
        "impid": impid,
        "price": 1500000,                      # micros（1.5 元）
        "adm": SAMPLE_HTML,
        "crid": "cr_html_001",
        "ext": {
            "cid": "camp_html_001",
            "ad_format": ad_type,
            "adm_type": "html",
            "advertiser": "示例广告主",
        },
    }


def _build_vast_bid(impid: str) -> dict:
    token = uuid.uuid4().hex
    _TOKEN_STORE[impid] = {"token": token, "used": False}
    vast = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<VAST version="4.0">'
        '<Ad><InLine>'
        '<AdSystem>AppLinkADX</AdSystem>'
        f'<AdTitle>rewarded-{impid}</AdTitle>'
        '<Impression><![CDATA[' + f"{TRACK_BASE}?ev=impression&imp={impid}" + ']]></Impression>'
        '<Creatives><Creative><Linear>'
        f'<Duration>00:00:10</Duration>'
        '<TrackingEvents>'
        f'<Tracking event="start"><![CDATA[{TRACK_BASE}?ev=start&imp={impid}]]></Tracking>'
        f'<Tracking event="complete"><![CDATA[{TRACK_BASE}?ev=complete&imp={impid}]]></Tracking>'
        '</TrackingEvents>'
        '<MediaFiles>'
        f'<MediaFile type="video/mp4" delivery="progressive" width="1280" height="720">'
        f'{SAMPLE_VIDEO}</MediaFile>'
        '</MediaFiles>'
        '</Linear></Creative></Creatives>'
        '</InLine></Ad>'
        '</VAST>'
    )
    return {
        "id": "bid_" + impid,
        "impid": impid,
        "price": 3000000,
        "adm": vast,
        "crid": "cr_vast_001",
        "ext": {
            "cid": "camp_vast_001",
            "ad_format": "rewarded",
            "adm_type": "vast4",
            "advertiser": "示例广告主",
            "rw": {"token": token},            # 一次性令牌：SDK 上报时带回，用于防重放
        },
    }


def handle_bid(body: dict) -> dict:
    if not _bid_request_is_valid(body):
        # 鉴权失败：返回空 seatbid（无填充），不泄露原因
        return {"id": body.get("id", ""), "seatbid": []}
    imp = (body.get("imp") or [{}])[0]
    ext = imp.get("ext") or {}
    ad_type = ext.get("ad_type", "banner")
    impid = imp.get("id", "imp_" + uuid.uuid4().hex)

    bid = _build_vast_bid(impid) if ad_type == "rewarded" else _build_html_bid(impid, ad_type)
    return {
        "id": body.get("id", ""),
        "seatbid": [{"seat": "applink", "bid": [bid]}],
    }


def _verify_sig(payload: dict) -> bool:
    sig = payload.get("sig", "")
    ts = payload.get("ts", 0)
    if time.time() - int(ts) > 300:           # 5 分钟防重放窗口
        return False
    msg = "|".join(str(payload.get(k, "")) for k in ("impid", "cid", "token", "watchedMs", "durationMs", "ts"))
    expect = hmac.new(API_KEY.encode(), msg.encode(), hashlib.sha256).hexdigest()
    return hmac.compare_digest(expect, sig)


def handle_reward(payload: dict) -> dict:
    if not _verify_sig(payload):
        return {"ok": False, "reason": "BAD_SIG"}
    impid = payload.get("impid", "")
    rec = _TOKEN_STORE.get(impid)
    if not rec or rec["used"]:
        return {"ok": False, "reason": "REPLAY_OR_UNKNOWN"}
    if payload.get("durationMs", 0) and payload["watchedMs"] / payload["durationMs"] < 0.95:
        return {"ok": False, "reason": "INCOMPLETE"}     # 完播比例不足
    rec["used"] = True
    return {"ok": True, "reward": "100金币"}             # 裁决通过：发放奖励


class Handler(BaseHTTPRequestHandler):
    def _send(self, obj, code=200):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw or b"{}")
        except Exception:
            body = {}
        path = urlparse(self.path).path
        if path == "/ssp/bid":
            self._send(handle_bid(body))
        elif path == "/s2s/reward":
            self._send(handle_reward(body))
        else:
            self._send({"error": "not found"}, 404)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"SSP (ADX) listening on :{SSP_PORT}")
    ThreadingHTTPServer(("0.0.0.0", SSP_PORT), Handler).serve_forever()
