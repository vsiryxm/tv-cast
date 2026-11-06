#!/opt/homebrew/bin/python3
# -*- coding: utf-8 -*-
"""
直播间「文字看板」——把手机屏幕上的直播文字（弹幕 / 进入提示 / 群文字直播）
OCR 出来，重排成 1920x1080 大字看板，供电视接收端当单帧 JPEG 显示。

通用定位（B 方案）：先整屏低倍 OCR 拿到 Vision 的文字块坐标，按密度自动找出
「聊天文字带」，再只放大那一带精读。抖音直播伴侣 / 抖音观众端 / 微信视频号 /
微信群文字页 / 横屏全屏都自适应；检测到 App 或布局切换会重新定位并清空累积。

链路: 手机 8080/stream.jpeg -> 全屏定位 -> 裁切放大 -> macOS Vision 中文 OCR
      -> 投票/去重/分类/累积 -> 渲染 wall.jpg -> 电视接收端

注意：本机 macOS 会静默丢弃 Homebrew Python 的 LAN 入站连接，
      因此对外静态服务用系统 /usr/bin/python3 起，本进程只负责写文件。
运行:  ./cast.sh wall        (前台常驻，Ctrl+C 结束)
"""
import argparse
import difflib
import io
import json
import os
import re
import signal
import socket
import subprocess
import sys
import time
import urllib.request
from PIL import Image, ImageDraw, ImageEnhance, ImageFont

from tvconf import PHONE_IP, TV_IP, ADB
from locate import (parse_blocks, lines_from_blocks, band_from_blocks,
                    thumb, layout_diff, is_feed, SWITCH_DIFF)

HERE = os.path.dirname(os.path.abspath(__file__))
VOCR = os.path.join(HERE, "vocr")
VOCR_SRC = os.path.join(HERE, "vocr.swift")

PHONE = PHONE_IP
STREAM = "http://%s:8080/stream.jpeg" % PHONE
PORT = 8897
OUT_DIR = "/tmp/tvcast_wall"
TOP = 0.60          # 兜底区间：自动定位失败时仍保证有画面
BOTTOM = 0.90
SCALE = 3
INTERVAL = 0.8      # OCR 采样间隔（秒）
MAX_ITEMS = 11
DEDUP_WINDOW = 180  # 秒：同一句弹幕在这个窗口内只收一次
LOCATE_EVERY = 5    # 每 N 次采样做一次全屏定位，其余帧复用上次文字带
VOTE_TIMEOUT = 1.6  # 秒：单帧弹幕等不到第二帧时兜底提交，避免快滚动漏收
PENDING_TTL = 4.0   # 秒：投票槽存活时间

W, H = 1920, 1080
FONT_PATH = "/System/Library/Fonts/Hiragino Sans GB.ttc"
BG = (13, 16, 23)
WHITE = (239, 243, 250)
GRAY = (128, 140, 158)
CHAT = (86, 190, 246)
ENTER = (255, 190, 92)

EMOJI = re.compile("[\U0001F000-\U0001FAFF\u25A0-\u27BF\uFE0F\u200D\u2190-\u21FF]")
BADGE = re.compile(r"^[\s◆◇◎•●▪°*·、,，]{0,3}\d{1,3}[\s:：]*(?=[\u4e00-\u9fa5])")
BADGE2 = re.compile(r"^[\u4e00-\u9fa5]\d{1,3}\s+(?=[\u4e00-\u9fa5])")
NOISE = re.compile(r"\b[A-Z]{2,}\b")      # emoji 被 OCR 成的乱码串（不再误删英文昵称）
# 跨 App 文案：抖音「来了」、视频号「进入直播间」、微信群口语都覆盖
ENTER_KW = ("来了", "来啦", "来到", "来到了", "进来了", "进来", "进入了",
            "进入直播间", "进入房间", "走进了", "围观", "关注了", "关注了主播",
            "加入了", "加入直播间", "加入粉丝团")
INTERACT_KW = ("点赞", "分享", "送出", "送出了", "礼物", "粉丝团", "打赏", "为主播点赞")

state = {"count": 0, "last_ocr": "", "error": "",
         "band": [TOP, BOTTOM], "layout": "fallback"}
items = []
seen = {}
pending = []   # 连续帧投票：同一条弹幕每帧 OCR 出的错字变体先归到一个槽


def lan_ip():
    s = socket.socket()
    try:
        s.connect((PHONE, 8080))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        try:
            s.close()
        except Exception:
            pass


def font(size):
    return ImageFont.truetype(FONT_PATH, size, index=0)


F_TITLE = font(36)
F_SUB = font(26)
F_TIME = font(26)
F_TAG = font(28)
F_NAME = font(48)
F_BODY = font(54)


def clean(s):
    s = EMOJI.sub("", s)
    s = NOISE.sub("", s)
    s = BADGE.sub("", s)
    s = BADGE2.sub("", s)
    s = re.sub(r"\s+", " ", s)
    s = re.sub(r"[|｜·•…]{2,}", "", s)
    return s.strip(" ：:·、-—_")


def norm(s):
    return re.sub(r"[^\u4e00-\u9fa5A-Za-z0-9]", "", s)


def valid(n):
    """必须含至少 2 个汉字：滤掉 emoji/等级徽章被 OCR 成的纯字母数字噪声。"""
    zh = len(re.findall(r"[\u4e00-\u9fa5]", n))
    return 2 <= len(n) <= 40 and zh >= 2


def sim(a, b):
    return difflib.SequenceMatcher(None, a, b).ratio()


ENTER_VERB = (r"(来了|来啦|来到|来到了|进来了|进来|进入了|进入直播间|进入房间|"
              r"走进了|围观|关注了主播|关注了|加入了|加入直播间|加入粉丝团)")


def split_name(s):
    parts = re.split(r"[：:]", s, maxsplit=1)
    if len(parts) == 2 and parts[0].strip():
        return parts[0].strip(), parts[1].strip()
    m = re.match(r"^(.{1,14}?)\s*" + ENTER_VERB + r".*$", s)
    if m:
        return m.group(1).strip(), m.group(2)
    return "", s


def classify(s, body):
    text = s + body
    # 推荐流里的「点击进入直播间」是入口卡片，不是某人进房提示
    if any(k in text for k in ("点击进入", "点击", "拍同款", "相关搜索")):
        return "chat"
    if len(s) <= 30 and any(k in text for k in INTERACT_KW):
        return "interact"
    if len(s) <= 30 and any(k in text for k in ENTER_KW):
        return "enter"
    return "chat"


def add_line(text):
    t = clean(text)
    n = norm(t)
    if not valid(n):
        return False
    now = time.time()
    for k in list(seen):
        if now - seen[k] > DEDUP_WINDOW:
            del seen[k]
    for k, ts in seen.items():
        # 半透明淡出弹幕的 OCR 错字变体差异较大，放宽相似度但要求长度接近，
        # 避免把真正不同的短弹幕误合并
        if now - ts < DEDUP_WINDOW and abs(len(n) - len(k)) <= 3 and sim(n, k) > 0.58:
            seen[k] = now
            return False
    seen[n] = now
    name, body = split_name(t)
    items.append({
        "name": name, "body": body or t, "kind": classify(t, body),
        "time": time.strftime("%H:%M:%S"), "ts": now,
    })
    del items[:-MAX_ITEMS]
    return True


def observe(text):
    """同一条弹幕会在屏幕上停留数秒，逐帧 OCR 会给出略不同的错字。
    先按相似度聚到一个槽，取 medoid 写法入库；等不到第二帧的快滚动弹幕
    由 flush_pending() 按超时兜底提交，避免漏收。"""
    t = clean(text)
    n = norm(t)
    if not valid(n):
        return False
    now = time.time()
    pending[:] = [p for p in pending if now - p["last"] < PENDING_TTL]
    hit = None
    for p in pending:
        if not p["committed"] and sim(n, p["n"]) > 0.55:
            hit = p
            break
    if hit is None:
        pending.append({"n": n, "texts": [t], "first": now,
                        "last": now, "committed": False})
        return False
    hit["texts"].append(t)
    hit["last"] = now
    cands = list(dict.fromkeys(hit["texts"]))
    best = max(cands, key=lambda c: sum(sim(norm(c), norm(o)) for o in cands))
    hit["committed"] = True
    return add_line(best)


def flush_pending():
    """只被抓到一帧的弹幕（滚动太快）超时后也提交，配合 add_line 去重。"""
    now = time.time()
    added = 0
    for p in list(pending):
        if p["committed"]:
            continue
        if now - p["first"] >= VOTE_TIMEOUT:
            p["committed"] = True
            if add_line(p["texts"][-1]):
                added += 1
    return added


def parse_ocr(out):
    rows = []
    for line in out.splitlines():
        p = line.split("\t")
        if len(p) < 5:
            continue
        try:
            x, y, w, h = (float(v) for v in p[:4])
        except ValueError:
            continue
        txt = p[4].strip()
        if txt:
            rows.append((y, x, txt))
    # Vision 原点在左下：y 越大越靠上。先按行聚类，再按 x 拼回同一条弹幕
    rows.sort(key=lambda r: (-r[0], r[1]))
    groups = []
    for y, x, txt in rows:
        if groups and abs(groups[-1][0] - y) < 0.045:
            groups[-1][1].append((x, txt))
        else:
            groups.append([y, [(x, txt)]])
    return [" ".join(t for _, t in sorted(g[1])) for g in groups]


def merge_wrapped(lines):
    """弹幕换行时 OCR 会拆成两行（如「刘大姐：」+「这里」），拼回去。"""
    out = []
    for line in lines:
        if out and re.search(r"[：:]\s*$", out[-1]) and len(clean(line)) <= 8:
            out[-1] = out[-1].rstrip() + " " + line
        else:
            out.append(line)
    return out


def render():
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, W, 96), fill=(20, 25, 35))
    d.rectangle((0, 94, W, 96), fill=(52, 62, 80))
    d.text((56, 24), "直播间 · 实时文字看板", font=F_TITLE, fill=WHITE)
    band = state.get("band", [TOP, BOTTOM])
    if state.get("paused"):
        focus = "当前不是直播间 · 已暂停抓取"
    elif state.get("layout") == "chat":
        focus = "整屏文字"
    else:
        focus = "自动聚焦 %.2f~%.2f" % (band[0], band[1])
    sub = "%s   ·   共 %d 条   ·   %s" % (time.strftime("%H:%M:%S"), state["count"], focus)
    d.text((W - 56 - d.textlength(sub, font=F_SUB), 36), sub, font=F_SUB, fill=GRAY)

    row_h = 82
    y0 = 118
    TAG = {"enter": "进入", "interact": "互动", "chat": "发言"}
    if not items:
        msg = "等待进入直播间…" if state.get("paused") else "等待屏幕上的文字…"
        d.text(((W - d.textlength(msg, font=F_NAME)) / 2, 430), msg,
               font=F_NAME, fill=GRAY)
    for i, it in enumerate(list(items)):
        y = y0 + i * row_h
        if y + row_h > H - 54:
            break
        kind = it["kind"] if it["kind"] in TAG else "chat"
        color = CHAT if kind == "chat" else ENTER
        fresh = time.time() - it["ts"] < 4
        if fresh:
            d.rounded_rectangle((40, y + 2, W - 40, y + row_h - 6), radius=10, fill=(30, 38, 52))
            d.rectangle((40, y + 2, 46, y + row_h - 6), fill=color)
        d.text((62, y + 24), it["time"], font=F_TIME, fill=GRAY)
        tag = TAG[kind]
        d.rounded_rectangle((222, y + 20, 306, y + 60), radius=8, fill=(38, 46, 60))
        d.text((236, y + 25), tag, font=F_TAG, fill=color)

        x = 330
        head = (it["name"] + "：") if it["name"] else ""
        if head:
            d.text((x, y + 14), head, font=F_NAME, fill=color)
            x += d.textlength(head, font=F_NAME)
        body = it["body"]
        maxw = W - x - 66
        while body and d.textlength(body, font=F_BODY) > maxw:
            body = body[:-1]
        if body != it["body"]:
            body = body.rstrip() + "…"
        d.text((x, y + 9), body, font=F_BODY, fill=WHITE)

    d.rectangle((0, H - 48, W, H), fill=(20, 25, 35))
    d.text((56, H - 38),
           "只看文字 · 消息不会一闪而过 · 遥控器左右键可切回画面模式",
           font=F_SUB, fill=GRAY)

    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=78, optimize=True)
    return buf.getvalue()


def atomic_write(name, data):
    path = os.path.join(OUT_DIR, name)
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


def publish():
    atomic_write("wall.jpg", render())
    status = {
        "items": [{"time": i["time"], "name": i["name"],
                   "body": i["body"], "kind": i["kind"]} for i in items],
        "count": state["count"], "last_ocr": state["last_ocr"],
        "error": state["error"],
        "band": state["band"], "layout": state["layout"],
        "paused": state.get("paused", False),
    }
    atomic_write("status.json", json.dumps(status, ensure_ascii=False).encode("utf-8"))


def ocr_loop():
    """两阶段：整屏低倍 OCR 拿坐标 → 自动聚焦文字带精读；布局切换自动重定位。"""
    tmp = "/tmp/tvcast_ocr_input.png"
    full_tmp = "/tmp/tvcast_ocr_full.png"
    band = (TOP, BOTTOM)
    mode = "fallback"
    paused = False
    was_paused = False
    last_thumb = None
    switch_hits = 0
    tick = 0
    while True:
        try:
            raw = urllib.request.urlopen(STREAM, timeout=4).read()
            img = Image.open(io.BytesIO(raw)).convert("RGB")
            w, h = img.size

            # 低频缩略图判 App / 布局切换：连续 2 次大变化才触发，避开特效抖动
            cur = thumb(img)
            diff = layout_diff(last_thumb, cur)
            last_thumb = cur
            switch_hits = switch_hits + 1 if diff > SWITCH_DIFF else max(0, switch_hits - 1)
            force = False
            if switch_hits >= 2:
                items[:] = []
                seen.clear()
                pending[:] = []
                state["count"] = 0
                switch_hits = 0
                force = True
                print("[wall] 检测到 App / 布局切换，已清空累积并重新定位", flush=True)

            need_locate = force or tick % LOCATE_EVERY == 0 or mode == "fallback"
            blocks = []
            if need_locate or mode == "chat":
                # 整屏只做轻度增强：目的是拿全局文字块坐标，不追求读得多准
                full = ImageEnhance.Contrast(img).enhance(1.12)
                full.save(full_tmp)
                r = subprocess.run([VOCR, full_tmp], capture_output=True, timeout=20)
                blocks = parse_blocks(r.stdout.decode("utf-8", "replace"))
            if need_locate:
                top, bottom, mode = band_from_blocks(blocks, (TOP, BOTTOM))
                band = (top, bottom)
                state["band"] = [round(top, 3), round(bottom, 3)]
                state["layout"] = mode
                # 刷短视频 / 看推荐流时不要把标题、话题、广告灌进看板
                # 对 chat 模式同样生效：文字铺满的图文页也不是直播文字
                paused = is_feed(blocks)
                state["paused"] = paused
                print("[wall] 自动定位 %.3f~%.3f (%s, %d 个文字块)%s" %
                      (top, bottom, mode, len(blocks), " · 非直播页暂停" if paused else ""),
                      flush=True)
                if paused != was_paused:
                    print("[wall] %s" % ("已离开直播间，暂停抓取" if paused
                                         else "已回到直播/文字页，恢复抓取"), flush=True)
                    was_paused = paused

            if mode == "chat":
                # 微信群等纯文字页：全屏 OCR 结果本身就是消息行，不必再裁切
                lines = merge_wrapped(lines_from_blocks(blocks))
            else:
                top, bottom = band
                if bottom - top < 0.05:
                    top, bottom = TOP, BOTTOM
                crop = img.crop((0, int(h * top), w, int(h * bottom)))
                crop = crop.resize((crop.width * SCALE, crop.height * SCALE), Image.LANCZOS)
                crop = ImageEnhance.Contrast(crop).enhance(1.18)
                crop = ImageEnhance.Sharpness(crop).enhance(1.5)
                crop.save(tmp)
                r = subprocess.run([VOCR, tmp], capture_output=True, timeout=20)
                lines = merge_wrapped(parse_ocr(r.stdout.decode("utf-8", "replace")))

            if paused:
                lines = []
            added = flush_pending()
            for line in lines:
                if observe(line):
                    added += 1
            state["count"] = len(items)
            state["last_ocr"] = time.strftime("%H:%M:%S")
            state["error"] = ""
            publish()
            if added:
                print("[wall] 新增 %d 条：%s" % (added, " | ".join(clean(x) for x in lines)), flush=True)
        except Exception as e:
            state["error"] = "%s: %s" % (type(e).__name__, e)
            print("[wall] 采样异常:", type(e).__name__, str(e)[:100], flush=True)
        tick += 1
        time.sleep(INTERVAL)


_child = {"p": None}


def restore_tv():
    """退出时把电视切回「免本机」的弹幕看板，避免 Mac 一关电视就黑屏。"""
    adb = ADB
    dev = TV_IP + ":5555"
    act = "com.xinmin.tvcast/.ReceiverActivity"
    try:
        subprocess.run([adb, "-s", dev, "shell", "am force-stop com.xinmin.tvcast"],
                       capture_output=True, timeout=15)
        subprocess.run([adb, "-s", dev, "shell",
                        "am start -n %s --ei mode 4 --ei fps 12 --es url 'http://%s:8080'"
                        % (act, PHONE)], capture_output=True, timeout=20)
        print("[wall] 电视已切回免本机的弹幕看板", flush=True)
    except Exception as e:
        print("[wall] 恢复电视未完成:", type(e).__name__, str(e)[:80], flush=True)


def cleanup(signum=None, frame=None):
    print("\n[wall] 正在退出，恢复电视…", flush=True)
    restore_tv()
    p = _child["p"]
    if p is not None and p.poll() is None:
        p.terminate()
    sys.exit(0)


def ensure_ocr():
    if os.path.exists(VOCR) and os.access(VOCR, os.X_OK):
        return
    print("[wall] 首次使用，编译 Vision OCR 工具…", flush=True)
    subprocess.run(["/usr/bin/swiftc", "-O", VOCR_SRC, "-o", VOCR], check=True)


def start_static_server(port):
    """系统 Python 才能接受 LAN 入站连接；Homebrew Python 会被 macOS 静默丢弃。"""
    os.makedirs(OUT_DIR, exist_ok=True)
    preview = ("<html><head><meta charset='utf-8'><meta http-equiv='refresh' content='2'>"
               "<title>直播间文字看板</title></head>"
               "<body style='margin:0;background:#0d1017;text-align:center'>"
               "<img src='wall.jpg' style='width:100%;max-width:1920px'></body></html>")
    atomic_write("index.html", preview.encode("utf-8"))
    publish()
    # 先清掉可能残留的同端口进程
    out = subprocess.run(["lsof", "-nP", "-iTCP:%d" % port, "-sTCP:LISTEN", "-t"],
                         capture_output=True, timeout=10).stdout.decode().split()
    for pid in out:
        try:
            os.kill(int(pid), 15)
        except Exception:
            pass
    if out:
        time.sleep(0.8)
    p = subprocess.Popen(
        ["/usr/bin/python3", os.path.join(HERE, "static_server.py"),
         str(port), OUT_DIR],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1.2)
    return p


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cast", action="store_true", help="启动后自动把电视切到本看板")
    ap.add_argument("--port", type=int, default=PORT)
    a = ap.parse_args()

    ensure_ocr()
    child = start_static_server(a.port)
    ip = lan_ip()
    url = "http://%s:%d/wall.jpg" % (ip, a.port)
    print("[wall] 看板地址: %s" % url, flush=True)
    print("[wall] 预览: http://127.0.0.1:%d/   状态: /status.json" % a.port, flush=True)

    import threading
    threading.Thread(target=ocr_loop, daemon=True).start()

    if a.cast:
        time.sleep(1.5)
        adb = ADB
        dev = TV_IP + ":5555"
        act = "com.xinmin.tvcast/.ReceiverActivity"
        subprocess.run([adb, "connect", dev], capture_output=True, timeout=20)
        subprocess.run([adb, "-s", dev, "shell", "am force-stop com.xinmin.tvcast"],
                       capture_output=True, timeout=20)
        r = subprocess.run([adb, "-s", dev, "shell",
                            "am start -n %s --es url '%s' --ei fps 2 --ei mode 3" % (act, url)],
                           capture_output=True, timeout=25)
        print("[wall] 电视已切换:", (r.stdout + r.stderr).decode("utf-8", "replace").strip()[:160], flush=True)
    _child["p"] = child
    for s in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(s, cleanup)
    while True:
        time.sleep(1)
        if child.poll() is not None:
            print("[wall] 静态服务意外退出，重启中…", flush=True)
            child = start_static_server(a.port)
            _child["p"] = child


if __name__ == "__main__":
    main()
