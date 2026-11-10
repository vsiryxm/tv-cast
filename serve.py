#!/usr/bin/env python3
# Copyright 2026 Simon Yang <https://github.com/vsiryxm/tv-cast>
# SPDX-License-Identifier: Apache-2.0
"""tv-cast 一键装机台：托管 ScreenStream APK + 二维码，并自动接管电视最后一公里。

运行: python3 serve.py
流程: 电视显示装机二维码 -> 手机扫码安装并 Start -> 本服务检测到推流
      -> 自动把电视切回接收端全屏 -> 抓图存 result.png。
"""

import argparse
import http.server
import json
import os
import socket
import subprocess
import threading
import time
import urllib.request

from tvconf import TV_IP, PHONE_IP, MAC_IP, ADB

HERE = os.path.dirname(os.path.abspath(__file__))
TV = TV_IP + ":5555"
PKG = "com.xinmin.tvcast"
ACT = PKG + "/.ReceiverActivity"
APK = os.path.join(HERE, "ScreenStream.apk")
QR = os.path.join(HERE, "qr.png")
RESULT = os.path.join(HERE, "result.png")

PHONE = PHONE_IP
PORT = 8899
STREAM = "http://" + PHONE + ":8080/stream.mjpeg"

state = {"phone": False, "casting": False, "note": "等待手机启动推流", "ts": 0}
lock = threading.Lock()


def log(*a):
    print("[serve]", *a, flush=True)


def adb(*args, timeout=25):
    try:
        r = subprocess.run(
            [ADB, "-s", TV] + list(args),
            capture_output=True, timeout=timeout,
        )
        return (r.stdout + r.stderr).decode("utf-8", "replace").strip()
    except Exception as e:
        return "adb error: " + str(e)


def phone_streaming():
    """ScreenStream 只有点 Start 之后才会在 8080 监听，端口通即可判定就绪。"""
    try:
        s = socket.create_connection((PHONE, 8080), 0.7)
        s.close()
    except Exception:
        return False
    try:
        with urllib.request.urlopen("http://" + PHONE + ":8080/", timeout=2) as r:
            return r.status == 200
    except Exception:
        return True


def take_over_tv():
    log("检测到手机推流，自动接管电视")
    with lock:
        state["note"] = "检测到推流，正在切换电视"
    adb("shell", "am force-stop " + PKG)
    out = adb("shell", "am start -n " + ACT + " --es url '" + STREAM + "'")
    log("am start:", out.splitlines()[-1] if out else "(无输出)")
    time.sleep(4)
    adb("shell", "screencap -p /data/local/tmp/tvcast_result.png")
    try:
        subprocess.run(
            [ADB, "-s", TV, "pull", "/data/local/tmp/tvcast_result.png", RESULT],
            capture_output=True, timeout=40,
        )
    except Exception as e:
        log("截图拉取失败:", e)
    ok = os.path.exists(RESULT) and os.path.getsize(RESULT) > 20000
    with lock:
        state["casting"] = ok
        state["note"] = "已自动上屏，截图 result.png" if ok else "电视已切换，请确认画面"
    log(state["note"])


def watcher():
    triggered = False
    while True:
        ok = phone_streaming()
        with lock:
            state["phone"] = ok
            state["ts"] = time.time()
        if ok and not triggered:
            triggered = True
            take_over_tv()
        elif ok and triggered:
            with lock:
                if state["note"].startswith("手机推流已断开"):
                    state["note"] = "投屏运行中 · 手机推流正常"
        elif not ok and triggered:
            with lock:
                if not state["casting"]:
                    triggered = False
                state["note"] = "手机推流已断开，等待恢复"
        time.sleep(2)


def show_qr_on_tv():
    time.sleep(1)
    out = adb(
        "shell",
        "am start -a android.intent.action.VIEW -d http://" + MAC_IP + ":" + str(PORT) + "/",
    )
    log("电视已显示装机二维码:", out.splitlines()[-1] if out else "(无输出)")


def ensure_qr(url):
    """用 macOS 自带 CoreImage 现场生成二维码；仓库里不必保存含家里 IP 的图片。"""
    src = os.path.join(HERE, "qrgen.swift")
    exe = os.path.join(HERE, "qrgen")
    try:
        if not os.path.exists(exe) or os.path.getmtime(src) > os.path.getmtime(exe):
            subprocess.run(["/usr/bin/swiftc", src, "-o", exe], check=True, timeout=180)
        subprocess.run([exe, url, QR], check=True, timeout=60)
        log("二维码已生成:", url)
    except Exception as e:
        log("二维码生成失败（页面上的下载按钮仍可用）:", type(e).__name__, str(e)[:100])


HTML = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>手机画面上电视</title><style>
body{margin:0;background:#0d1117;color:#e8e8e8;font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;text-align:center;padding:24px}
h1{font-size:42px;margin:4px 0 6px}
.sub{font-size:21px;color:#9aa4b2;margin-bottom:16px}
img.qr{width:340px;max-width:72vw;background:#fff;padding:10px;border-radius:14px}
.btn{display:inline-block;margin:14px 0;padding:16px 40px;background:#e0433c;color:#fff;font-size:25px;text-decoration:none;border-radius:12px}
ol{display:inline-block;text-align:left;font-size:21px;line-height:1.85;max-width:860px}
.stat{margin-top:14px;font-size:21px;padding:12px 22px;border:1px solid #2b3440;border-radius:10px;display:inline-block}
b{color:#ffd479}
</style></head><body>
<h1>手机画面上电视</h1>
<div class="sub">手机扫下面这个码，直接安装推流 App（4.1 MB · 开源 · 无需 Google 服务）</div>
<img class="qr" src="/qr.png" alt="扫码安装">
<div><a class="btn" href="/ScreenStream.apk">下载安装 ScreenStream</a></div>
<ol>
<li>装完打开 → 选 <b>Local (MJPEG)</b> → 点 <b>Start</b> → 系统弹窗点「立即开始 / 允许」（提示未知来源就点「仍要安装」）</li>
<li>回到抖音直播伴侣开播（ScreenStream 保持后台运行即可，不用再切回来）</li>
<li>电视会自动切成全屏画面，不用遥控器、不用电脑</li>
</ol>
<div class="stat" id="s">状态读取中…</div>
<script>
function tick(){try{var x=new XMLHttpRequest();
x.onreadystatechange=function(){if(x.readyState==4&&x.status==200){
 var d=JSON.parse(x.responseText);
 document.getElementById('s').innerHTML='手机推流：<b>'+(d.phone?'已就绪':'未启动')+'</b>　｜　电视：<b>'+(d.casting?'已上屏':'待命')+'</b>　｜　'+d.note;
}};x.open('GET','/status.json?t='+Date.now(),true);x.send();}catch(e){}}
tick();setInterval(tick,3000);
</script></body></html>""".encode("utf-8")


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, *a):
        pass

    def send_file(self, path, ctype, cache=False, disposition=None):
        if not os.path.exists(path):
            self.send_error(404)
            return
        with open(path, "rb") as f:
            data = f.read()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        if disposition:
            self.send_header("Content-Disposition", disposition)
        if not cache:
            self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_HEAD(self):
        p = self.path.split("?")[0]
        if p == "/ScreenStream.apk" and os.path.exists(APK):
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(os.path.getsize(APK)))
            self.send_header(
                "Content-Disposition", 'attachment; filename="ScreenStream.apk"'
            )
            self.end_headers()
        elif p in ("/", "/index.html", "/qr.png", "/result.png", "/status.json"):
            self.send_response(200)
            self.end_headers()
        else:
            self.send_error(404)

    def do_GET(self):
        p = self.path.split("?")[0]
        if p in ("/", "/index.html"):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(HTML)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(HTML)
        elif p == "/ScreenStream.apk":
            self.send_file(
                APK, "application/vnd.android.package-archive", True,
                'attachment; filename="ScreenStream.apk"',
            )
        elif p == "/qr.png":
            self.send_file(QR, "image/png", True)
        elif p == "/result.png":
            self.send_file(RESULT, "image/png")
        elif p == "/status.json":
            with lock:
                body = json.dumps(state, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_error(404)


def main():
    global PHONE, PORT, STREAM
    ap = argparse.ArgumentParser()
    ap.add_argument("--phone", default=PHONE)
    ap.add_argument("--port", type=int, default=PORT)
    ap.add_argument("--no-tv", action="store_true", help="不自动在电视上显示二维码")
    a = ap.parse_args()
    PHONE, PORT = a.phone, a.port
    STREAM = "http://" + PHONE + ":8080/stream.mjpeg"
    ensure_qr("http://" + MAC_IP + ":" + str(PORT) + "/ScreenStream.apk")

    srv = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    threading.Thread(target=watcher, daemon=True).start()

    log("装机台已启动: http://" + MAC_IP + ":" + str(PORT) + "/")
    log("二维码指向 APK 直链，APK:", APK, os.path.getsize(APK) if os.path.exists(APK) else "缺失")
    log("手机静态 IP:", PHONE, "· 电视:", TV)
    if not a.no_tv:
        threading.Thread(target=show_qr_on_tv, daemon=True).start()
    log("等待手机推流；一旦 Start，自动把电视切成全屏画面")

    try:
        while True:
            time.sleep(5)
            with lock:
                if state["phone"] and state["casting"]:
                    log("状态: 已上屏（手机 → WiFi → 电视，本机可关机）")
    except KeyboardInterrupt:
        log("退出")
    finally:
        srv.shutdown()


if __name__ == "__main__":
    main()
