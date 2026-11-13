#!/bin/bash
# Copyright 2026 Simon Yang <https://github.com/vsiryxm/tv-cast>
# SPDX-License-Identifier: Apache-2.0
# 为 Android 4.0.4 / API 15 构建电视端离线 K 歌机 APK。
# 与 receiver 共用同一把 debug 签名，保证 adb install -r 不会签名冲突。
# 曲库只有「歌名.mp3 / 歌名.lrc / 歌名.伴奏.mp3」，单实例 MediaPlayer 在
# 原唱与伴奏两个文件间切换。
# 台名、直播提醒时间由同目录 karaoke.conf 决定（直播时间未配置就不提醒）。
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
JH=/opt/homebrew/rea-engines/jdk-21.jdk/Contents/Home
BT=/opt/homebrew/opt/android-legacy/android-9
PLAT=/opt/homebrew/opt/android-legacy/android-4.0.4/android.jar
AAPT2=/opt/homebrew/bin/aapt2
APKSIGNER="${BT}/lib/apksigner.jar"
OUT="${HERE}/karaoke.apk"
KS="${HERE}/../receiver/debug.keystore"

echo "[1/6] 读取 karaoke.conf，生成 res/values/strings.xml 与 assets/karaoke.conf"
# 台名只有一个来源：karaoke.conf 的 APP_NAME（键名大小写不敏感）。
# 写进 strings.xml 后，Manifest 的 @string/app_name 与 App 内的 config 提示都用它；
# App 运行时还能被 U 盘根目录的 karaoke.conf（app_name=）再覆盖一次。
# 白名单里的键另存一份到 assets/karaoke.conf，作为 APK 内的默认值，
# 这样换台名、改台词停留秒数只改本地 conf，不用碰代码。
# 开场台词不在配置里：有没有台词只看 U 盘上有没有 karaoke_openings.txt。
rm -rf "${HERE}/build"
mkdir -p "${HERE}/build/classes" "${HERE}/build/assets" "${HERE}/res/values"
/usr/bin/python3 - "${HERE}/karaoke.conf" "${HERE}/res/values/strings.xml" \
                  "${HERE}/build/assets/karaoke.conf" <<'PY'
import sys, os
from xml.sax.saxutils import escape

conf, strings_out, assets_out = sys.argv[1], sys.argv[2], sys.argv[3]
WHITELIST = ("app_name", "welcome", "opening_secs", "live_start", "live_end")

kv = {}
if os.path.isfile(conf):
    for line in open(conf, encoding='utf-8-sig'):
        line = line.strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, val = line.split('=', 1)
        key = key.strip().lower().replace('appname', 'app_name')
        val = val.strip().strip('"').strip("'").strip()
        if key in WHITELIST and val:
            kv[key] = val

name = kv.get('app_name') or "K歌台"      # 未配置时的默认台名

open(strings_out, 'w', encoding='utf-8').write(
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<!-- 由 build.sh 依据 karaoke.conf 的 APP_NAME 生成，勿手改 -->\n'
    '<resources>\n'
    '    <string name="app_name">%s</string>\n'
    '</resources>\n' % escape(name, {'"': '&quot;'}))

os.makedirs(os.path.dirname(assets_out), exist_ok=True)
with open(assets_out, 'w', encoding='utf-8') as f:
    f.write('# 由 build.sh 从 karaoke/karaoke.conf 生成（只收白名单键）；'
            'U 盘根目录的同名文件可覆盖这里的值\n')
    for k in WHITELIST:
        if k in kv:
            f.write('%s=%s\n' % (k, kv[k]))

print("   台名 = %s，台词最多 %s 秒（有没有台词看 U 盘台词文件）" % (
    name, kv.get('opening_secs', '30')))
PY

echo "[2/6] 编译 Java"
"${JH}/bin/javac" --release 8 -nowarn -encoding UTF-8 -cp "${PLAT}" \
    -d "${HERE}/build/classes" \
    "${HERE}/src/com/xinmin/karaoke/KaraokeActivity.java"

echo "[3/6] 生成 classes.dex"
"${JH}/bin/java" -cp "${BT}/lib/dx.jar" com.android.dx.command.Main \
    --dex --min-sdk-version=15 --output="${HERE}/build/classes.dex" \
    "${HERE}/build/classes"

echo "[4/6] 编译启动图标并打包 Manifest + 资源"
# 图标由 make_icon.swift 生成到 res/drawable-*；aapt2 先编译成 .flat 再 link，
# Manifest 里的 @drawable/ic_launcher、@string/app_name 才能解析成真正的资源 ID。
# Apache-2.0 要求二进制分发附带 NOTICE，所以把根目录 NOTICE 打进 assets/。
mkdir -p "${HERE}/build/compiled" "${HERE}/build/assets"
cp "${HERE}/../NOTICE" "${HERE}/build/assets/NOTICE"
"${AAPT2}" compile --dir "${HERE}/res" -o "${HERE}/build/compiled"
"${AAPT2}" link -o "${HERE}/build/base.apk" -I "${PLAT}" \
    --manifest "${HERE}/AndroidManifest.xml" \
    -A "${HERE}/build/assets" \
    --min-sdk-version 15 --target-sdk-version 15 \
    --no-compile-sdk-metadata \
    "${HERE}"/build/compiled/*.flat

echo "[5/6] 合入 dex"
/usr/bin/python3 - "${HERE}/build/base.apk" "${HERE}/build/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('   APK 条目:', zipfile.ZipFile(apk).namelist())
PY
cp "${HERE}/build/base.apk" "${HERE}/build/unsigned.apk"

echo "[6/6] v1 签名（兼容 Android 4.0.4）"
"${JH}/bin/java" -jar "${APKSIGNER}" sign \
    --ks "${KS}" --ks-key-alias tvcast \
    --ks-pass pass:tvcast123 --key-pass pass:tvcast123 \
    --min-sdk-version 15 \
    --v1-signing-enabled true --v2-signing-enabled false \
    --out "${OUT}" "${HERE}/build/unsigned.apk"
"${JH}/bin/java" -jar "${APKSIGNER}" verify --min-sdk-version 15 --verbose "${OUT}" | head -8

echo
echo "完成: ${OUT}  ($(stat -f%z "${OUT}") bytes)"
