#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""通用屏幕文字定位：只用 Vision OCR 已返回的归一化坐标找「聊天文字带」。

不绑任何 App：抖音直播伴侣、抖音观众端、微信视频号、微信群文字直播、横屏全屏
都用同一套几何规则，不需要新模型、不需要联网。

坐标约定：Vision 的 boundingBox 原点在左下，y 越大越靠上；
返回给 PIL 的 (top, bottom) 是从上往下量的比例。
"""
from PIL import Image

GUARD_TOP = 0.13       # 排除顶部状态栏 / 直播间标题 / 主播信息
GUARD_BOTTOM = 0.05    # 排除底部输入框 / 手势条
WINDOW = 0.28          # 文字带滑窗高度（占屏比）
MIN_BLOCKS = 2         # 一条带里至少几个文字块
CHAT_COVER = 0.52      # 文字铺满屏高超过此比例 → 判为纯文字页
CHAT_MAX_GAP = 0.14    # 且行间无大空隙（区分「顶部标题+底部弹幕」）
SWITCH_DIFF = 0.15     # 低频缩略图差异阈值：判定布局/App 切换


def parse_blocks(out):
    """vocr 输出 → [(y, x, w, h, text)]，均为归一化坐标。"""
    blocks = []
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
            blocks.append((y, x, w, h, txt))
    return blocks


def lines_from_blocks(blocks):
    """按 y 聚成行、按 x 拼回同一条消息（用于纯文字页，省掉二次 OCR）。

    全屏图的行距占比远小于裁切放大图，阈值必须比 parse_ocr 的 0.045 小，
    否则顶部几行会被串成一行。
    """
    rows = [(y, x, txt) for y, x, w, h, txt in blocks]
    rows.sort(key=lambda r: (-r[0], r[1]))
    groups = []
    for y, x, txt in rows:
        if groups and abs(groups[-1][0] - y) < 0.012:
            groups[-1][1].append((x, txt))
        else:
            groups.append([y, [(x, txt)]])
    return [" ".join(t for _, t in sorted(g[1])) for g in groups]


def band_from_blocks(blocks, fallback=(0.60, 0.90)):
    """返回 (top, bottom, mode)：mode = live / chat / fallback。

    live  : 直播画面，弹幕集中在下半屏 → 裁出文字带放大精读
    chat  : 纯文字页（微信群等），文字铺满屏幕 → 直接用全屏 OCR 结果
    fallback: 没找到可信文字带 → 用写死的兜底区间，保证不黑屏
    """
    if not blocks:
        return fallback[0], fallback[1], "fallback"

    def center(b):
        return b[0] + b[3] / 2.0

    kept = [b for b in blocks if GUARD_BOTTOM < center(b) < 1 - GUARD_TOP]
    if not kept:
        kept = blocks
    cs = sorted(center(b) for b in kept)
    span = cs[-1] - cs[0]
    gaps = [cs[i + 1] - cs[i] for i in range(len(cs) - 1)]

    # 纯文字页：文字连续铺满大半个屏幕，且行间没有大空隙
    if span > CHAT_COVER and len(cs) >= 6 and (not gaps or max(gaps) < CHAT_MAX_GAP):
        return 0.0, 1.0, "chat"

    # 直播画面：滑窗找文字最密集、且偏下的连续带
    best, score = None, -1.0
    for c in cs:
        lo, hi = c - WINDOW / 2, c + WINDOW / 2
        hit = [b for b in kept if lo <= center(b) <= hi]
        if len(hit) < MIN_BLOCKS:
            continue
        s = len(hit) * (1.0 + 0.8 * (1.0 - c))   # 越靠下越可能是弹幕区
        if s > score:
            score, best = s, hit
    if best is None:
        return fallback[0], fallback[1], "fallback"

    ys = [b[0] for b in best] + [b[0] + b[3] for b in best]
    lo = max(GUARD_BOTTOM, min(ys) - 0.02)
    hi = min(1 - GUARD_TOP, max(ys) + 0.02)
    top, bottom = max(0.0, 1 - hi), min(1.0, 1 - lo)
    if bottom - top < 0.05:
        return fallback[0], fallback[1], "fallback"
    return top, bottom, "live"


def thumb(img):
    """16x16 灰度缩略图：只保留布局/明暗，压掉主播动作等画面内容变化。"""
    return bytes(img.convert("L").resize((16, 16), Image.BILINEAR).tobytes())


def layout_diff(a, b):
    if a is None or b is None or len(a) != len(b):
        return 1.0
    return sum(abs(x - y) for x, y in zip(a, b)) / (255.0 * len(a))


# 短视频信息流 / 推荐页的特征词：这些页面上的文字是标题、话题、广告，不是弹幕。
# 要求同时出现 >=2 个不同标记才暂停入库，避免直播间商品卡、评论区被误伤。
FEED_MARKERS = ("拍同款", "相关搜索", "作者声明", "人推荐", "听抖音",
                "点击进入直播间", "展开", "首页", "消息")


def is_feed(blocks):
    """当前屏幕是否为「非直播间」的信息流页面。纯文字页（chat）不参与此判定。"""
    text = " ".join(b[4] for b in blocks)
    return sum(1 for k in FEED_MARKERS if k in text) >= 2
