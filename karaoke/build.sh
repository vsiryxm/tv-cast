#!/bin/bash
# 为 Android 4.0.4 / API 15 构建电视端离线 K 歌机 APK。
# 与 receiver 共用同一把 debug 签名，保证 adb install -r 不会签名冲突。
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
JH=/opt/homebrew/rea-engines/jdk-21.jdk/Contents/Home
BT=/opt/homebrew/opt/android-legacy/android-9
PLAT=/opt/homebrew/opt/android-legacy/android-4.0.4/android.jar
AAPT2=/opt/homebrew/bin/aapt2
APKSIGNER="${BT}/lib/apksigner.jar"
OUT="${HERE}/karaoke.apk"
KS="${HERE}/../receiver/debug.keystore"

echo "[1/5] 编译 Java"
rm -rf "${HERE}/build"
mkdir -p "${HERE}/build/classes"
"${JH}/bin/javac" --release 8 -nowarn -encoding UTF-8 -cp "${PLAT}" \
    -d "${HERE}/build/classes" \
    "${HERE}/src/com/xinmin/karaoke/KaraokeActivity.java"

echo "[2/5] 生成 classes.dex"
"${JH}/bin/java" -cp "${BT}/lib/dx.jar" com.android.dx.command.Main \
    --dex --min-sdk-version=15 --output="${HERE}/build/classes.dex" \
    "${HERE}/build/classes"

echo "[3/5] 打包二进制 Manifest"
"${AAPT2}" link -o "${HERE}/build/base.apk" -I "${PLAT}" \
    --manifest "${HERE}/AndroidManifest.xml" \
    --min-sdk-version 15 --target-sdk-version 15 \
    --no-compile-sdk-metadata

echo "[4/5] 合入 dex"
/usr/bin/python3 - "${HERE}/build/base.apk" "${HERE}/build/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('   APK 条目:', zipfile.ZipFile(apk).namelist())
PY
cp "${HERE}/build/base.apk" "${HERE}/build/unsigned.apk"

echo "[5/5] v1 签名（兼容 Android 4.0.4）"
"${JH}/bin/java" -jar "${APKSIGNER}" sign \
    --ks "${KS}" --ks-key-alias tvcast \
    --ks-pass pass:tvcast123 --key-pass pass:tvcast123 \
    --min-sdk-version 15 \
    --v1-signing-enabled true --v2-signing-enabled false \
    --out "${OUT}" "${HERE}/build/unsigned.apk"
"${JH}/bin/java" -jar "${APKSIGNER}" verify --min-sdk-version 15 --verbose "${OUT}" | head -8

echo
echo "完成: ${OUT}  ($(stat -f%z "${OUT}") bytes)"
