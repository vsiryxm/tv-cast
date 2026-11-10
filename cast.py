#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Copyright 2026 Simon Yang <https://github.com/vsiryxm/tv-cast>
# SPDX-License-Identifier: Apache-2.0
"""tv-cast: 零硬件把小米手机屏幕投到海信 LED50EC280JD。

主通道(已实测可用): 画面源 -> ffmpeg 逐帧 JPEG -> HTTP 单帧接口 -> 电视浏览器 JS 轮询
备用通道: 画面源 -> ffmpeg H.264/MPEG-TS -> 电视 DLNA 播放器
免本机模式: --open 让电视浏览器直接打开手机端串流页面，之后本机可关机。

用法见 cast.sh。
"""
import argparse
import http.server
import os
import queue
import re
import subprocess
import threading
import time
import urllib.request
from xml.sax.saxutils import escape as xesc

from tvconf import TV_IP, MAC_IP, ADB

PORT = 8899
AVTR = f"http://{TV_IP}:38400/upnp/control/mingusavtr"
SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
FFMPEG = os.environ.get("FFMPEG_BIN", "/opt/homebrew/bin/ffmpeg")
TV_DEV = f"{TV_IP}:5555"
BROWSER = "com.android.browser"

STOP = threading.Event()
FRAME = {"jpg": None}
FRAME_LOCK = threading.Lock()


class Hub:
    """TS 直播流广播，慢客户端丢旧数据。"""

    def __init__(self, maxq=64):
        self.subs = []
        self.lock = threading.Lock()
        self.maxq = maxq

    def subscribe(self):
        q = queue.Queue(maxsize=self.maxq)
        with self.lock:
            self.subs.append(q)
        return q

    def unsubscribe(self, q):
        with self.lock:
            try:
                self.subs.remove(q)
            except ValueError:
                pass

    def publish(self, data):
        with self.lock:
            subs = list(self.subs)
        for q in subs:
            if q.full():
                try:
                    q.get_nowait()
                except queue.Empty:
                    pass
            try:
                q.put_nowait(data)
            except queue.Full:
                pass


TS_HUB = Hub()


def page(src, interval=70):
    """老 WebKit 不支持 multipart MJPEG，用 Image 预加载 + 短轮询单帧 JPEG。"""
    return f'''<html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="margin:0;background:#000;overflow:hidden">
<img id="v" style="width:100%;height:100%;display:block" src="{src}">
<script>
var n=0,S="{src}";
function tick(){{var i=new Image();
i.onload=function(){{document.getElementById('v').src=i.src;setTimeout(tick,{interval});}};
i.onerror=function(){{setTimeout(tick,400);}};
i.src=S+(S.indexOf('?')>=0?'&':'?')+'t='+(++n)+'_'+Date.now();}}
setTimeout(tick,300);
</script></body></html>'''.encode("utf-8")


HTML = page("/frame.jpg")


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, fmt, *args):
        if os.environ.get("TVCAST_VERBOSE"):
            print(f"[http] {self.client_address[0]} {fmt % args}", flush=True)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/frame.jpg":
            with FRAME_LOCK:
                f = FRAME["jpg"]
            if not f:
                self.send_response(503)
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
            self.send_header("Content-Length", str(len(f)))
            self.end_headers()
            self.wfile.write(f)
        elif path.startswith("/view"):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(HTML)))
            self.end_headers()
            self.wfile.write(HTML)
        elif path == "/live.ts":
            self.send_response(200)
            self.send_header("Content-Type", "video/vnd.dlna.mpeg-tts")
            self.send_header("Cache-Control", "no-store, no-cache")
            self.send_header("Connection", "close")
            self.send_header("transferMode.dlna.org", "Streaming")
            self.send_header(
                "contentFeatures.dlna.org",
                "DLNA.ORG_PN=MPEG_TS_SD_EU_T;DLNA.ORG_OP=01;DLNA.ORG_CI=0;"
                "DLNA.ORG_FLAGS=01700000000000000000000000000000",
            )
            self.end_headers()
            q = TS_HUB.subscribe()
            try:
                while not STOP.is_set():
                    try:
                        data = q.get(timeout=1.0)
                    except queue.Empty:
                        continue
                    self.wfile.write(data)
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            finally:
                TS_HUB.unsubscribe(q)
        elif path in ("/", "/health"):
            body = b"tv-cast ok"
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_error(404)

    def do_HEAD(self):
        self.send_response(200)
        self.send_header("Content-Type", "video/vnd.dlna.mpeg-tts")
        self.send_header("Content-Length", "0")
        self.end_headers()


def soap(action, body_xml):
    env = (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
        's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
        f'<s:Body><u:{action} xmlns:u="{SERVICE}">{body_xml}</u:{action}></s:Body></s:Envelope>'
    )
    req = urllib.request.Request(
        AVTR,
        data=env.encode("utf-8"),
        headers={
            "Content-Type": 'text/xml; charset="utf-8"',
            "SOAPACTION": f'"{SERVICE}#{action}"',
            "Connection": "close",
        },
    )
    with urllib.request.urlopen(req, timeout=6) as r:
        return r.read().decode("utf-8", "replace")


def tv_set_uri():
    url = f"http://{MAC_IP}:{PORT}/live.ts"
    didl = (
        '<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
        'xmlns:dc="http://purl.org/dc/elements/1.1/" '
        'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
        '<item id="tvcast" parentID="0" restricted="1">'
        "<dc:title>Live Cast</dc:title>"
        "<upnp:class>object.item.videoItem.movie</upnp:class>"
        '<res protocolInfo="http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=MPEG_TS_SD_EU_T">'
        f"{url}</res></item></DIDL-Lite>"
    )
    return soap(
        "SetAVTransportURI",
        "<InstanceID>0</InstanceID>"
        f"<CurrentURI>{xesc(url)}</CurrentURI>"
        f"<CurrentURIMetaData>{xesc(didl)}</CurrentURIMetaData>",
    )


def tv_play():
    return soap("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")


def tv_stop():
    return soap("Stop", "<InstanceID>0</InstanceID>")


def tv_state():
    try:
        r = soap("GetTransportInfo", "<InstanceID>0</InstanceID>")
        m = re.search(r"<CurrentTransportState>(.*?)</CurrentTransportState>", r, re.S)
        return m.group(1).strip() if m else "?"
    except Exception as e:
        return f"ERR:{type(e).__name__}"


def adb(*args, timeout=20):
    try:
        return subprocess.run(
            [ADB, "-s", TV_DEV, *args], capture_output=True, timeout=timeout
        ).stdout.decode("utf-8", "replace").strip()
    except Exception:
        return ""


def open_browser(url):
    adb("shell", f"am force-stop {BROWSER}")
    time.sleep(0.4)
    adb("shell", f'am start -a android.intent.action.VIEW -d "{url}"')


def snapshot(path):
    remote = f"/data/local/tmp/{os.path.basename(path)}"
    adb("shell", f"screencap -p {remote}")
    subprocess.run(
        [ADB, "-s", TV_DEV, "pull", remote, path],
        capture_output=True, timeout=20,
    )
    return path if os.path.exists(path) else None


FIT_JPEG = (
    "scale=960:540:force_original_aspect_ratio=decrease,"
    "pad=960:540:(ow-iw)/2:(oh-ih)/2:black,fps=10"
)
FIT_TS = (
    "scale=1280:720:force_original_aspect_ratio=decrease,"
    "pad=1280:720:(ow-iw)/2:(oh-ih)/2:black,fps=15"
)
JPEG_OUT = ["-an", "-c:v", "mjpeg", "-q:v", "5", "-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1"]
TS_OUT = [
    "-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
    "-profile:v", "baseline", "-level", "3.1", "-pix_fmt", "yuv420p",
    "-g", "30", "-b:v", "1800k", "-maxrate", "2200k", "-bufsize", "1200k",
    "-f", "mpegts", "-mpegts_flags", "+resend_headers", "pipe:1",
]


def build_commands(args):
    """返回 (串联命令列表, 断线是否重连)。"""
    fit = FIT_JPEG if args.mode == "jpeg" else FIT_TS
    tail = JPEG_OUT if args.mode == "jpeg" else TS_OUT
    if args.selftest:
        return [[
            FFMPEG, "-hide_banner", "-loglevel", "error", "-re",
            "-f", "lavfi", "-i", "testsrc2=size=960x540:rate=10",
            "-vf", fit,
        ] + tail], False
    if args.url:
        return [[
            FFMPEG, "-hide_banner", "-loglevel", "error",
            "-i", args.url, "-vf", fit,
        ] + tail], True
    adb_cmd = [
        ADB, "-s", args.phone, "exec-out", "screenrecord",
        "--output-format=h264", "--bit-rate", "4000000",
        "--time-limit", "170", "-",
    ]
    ff_cmd = [
        FFMPEG, "-hide_banner", "-loglevel", "error",
        "-f", "h264", "-i", "pipe:0", "-vf", fit, "-an",
    ] + tail
    return [adb_cmd, ff_cmd], True


def spawn(cmds):
    procs, prev_out = [], None
    for cmd in cmds:
        p = subprocess.Popen(
            cmd,
            stdin=prev_out,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        if prev_out is not None:
            prev_out.close()
        prev_out = p.stdout
        procs.append(p)
    return procs, procs[-1].stdout


def pump(args):
    cmds, loop = build_commands(args)
    while not STOP.is_set():
        procs, stream = spawn(cmds)
        buf = b""
        try:
            while not STOP.is_set():
                data = stream.read1(65536)
                if not data:
                    break
                if args.mode == "jpeg":
                    buf += data
                    while True:
                        s = buf.find(b"\xff\xd8")
                        if s < 0:
                            buf = b""
                            break
                        e = buf.find(b"\xff\xd9", s + 2)
                        if e < 0:
                            buf = buf[s:]
                            break
                        with FRAME_LOCK:
                            FRAME["jpg"] = buf[s:e + 2]
                        buf = buf[e + 2:]
                else:
                    TS_HUB.publish(data)
        except (OSError, ValueError):
            pass
        finally:
            for p in procs:
                try:
                    p.kill()
                except Exception:
                    pass
        if not loop:
            break
        if not STOP.is_set():
            print("[cast] 采集链路断开，1 秒后自动重连…", flush=True)
            time.sleep(1.0)


def open_url(url):
    """让电视浏览器打开手机端串流页面；之后本机可关机。"""
    open_browser(url)
    print(f"[cast] 电视浏览器已打开: {url}", flush=True)
    print("[cast] 本机可关机，保持手机端串流服务运行即可", flush=True)
    time.sleep(6)
    p = snapshot("/tmp/tvcast_open.png")
    print(f"[cast] 验证截图: {p or '抓取失败'}", flush=True)


def main():
    ap = argparse.ArgumentParser(description="零硬件投屏 → 海信 LED50EC280JD")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--selftest", action="store_true", help="测试图案自检，20 秒后自动停止")
    g.add_argument("--phone", metavar="IP:PORT", help="小米手机无线调试地址")
    g.add_argument("--url", help="拉任意视频/直播流")
    g.add_argument("--open", metavar="URL", help="让电视浏览器打开手机端串流页面，之后本机可关机")
    g.add_argument("--stop", action="store_true", help="停止投屏")
    ap.add_argument("--mode", choices=["jpeg", "ts"], default="jpeg",
                    help="jpeg=浏览器轮询(默认,已实测)；ts=DLNA 播放器")
    ap.add_argument("--duration", type=int, default=0, help="运行秒数，0=一直运行")
    args = ap.parse_args()

    if args.stop:
        try:
            tv_stop()
        except Exception:
            pass
        adb("shell", f"am force-stop {BROWSER}")
        print("已停止投屏")
        return
    if args.open:
        open_url(args.open)
        return
    if args.phone:
        r = subprocess.run([ADB, "connect", args.phone], capture_output=True, timeout=20)
        print("[cast] adb connect:", (r.stdout + r.stderr).decode("utf-8", "replace").strip(), flush=True)

    srv = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    print(f"[cast] 本机服务: http://{MAC_IP}:{PORT}/view", flush=True)

    threading.Thread(target=pump, args=(args,), daemon=True).start()
    time.sleep(2)

    if args.mode == "jpeg":
        open_browser(f"http://{MAC_IP}:{PORT}/view?t={int(time.time())}")
        print("[cast] 已打开电视浏览器", flush=True)
    else:
        try:
            tv_set_uri()
            tv_play()
            print("[cast] 已下发 DLNA SetAVTransportURI + Play", flush=True)
        except Exception as e:
            print("[cast] DLNA 控制失败:", e, flush=True)
            return

    limit = args.duration or (20 if args.selftest else 0)
    start, checked = time.time(), False
    try:
        while not STOP.is_set():
            time.sleep(1)
            elapsed = time.time() - start
            if not checked and elapsed >= 8:
                checked = True
                if args.mode == "jpeg":
                    print(f"[cast] 当前帧大小: {len(FRAME['jpg']) if FRAME['jpg'] else 0} 字节", flush=True)
                else:
                    print(f"[cast] 电视播放状态: {tv_state()}", flush=True)
                p = snapshot("/tmp/tvcast_live.png")
                print(f"[cast] 电视截图: {p or '抓取失败'}", flush=True)
            if limit and elapsed >= limit:
                break
    except KeyboardInterrupt:
        pass
    finally:
        STOP.set()
        try:
            tv_stop()
        except Exception:
            pass
        if args.mode == "jpeg":
            adb("shell", f"am force-stop {BROWSER}")
        srv.shutdown()
        print("[cast] 已退出", flush=True)


if __name__ == "__main__":
    main()
