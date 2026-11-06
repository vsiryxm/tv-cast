#!/bin/bash
# 为 Android 4.0.4 / API 15 构建极简投屏接收端 APK。
# 工具链全部位于 /opt/homebrew 中立位置，不依赖 Android Studio / Gradle / Rosetta。
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
JH=/opt/homebrew/rea-engines/jdk-21.jdk/Contents/Home
BT=/opt/homebrew/opt/android-legacy/android-9
PLAT=/opt/homebrew/opt/android-legacy/android-4.0.4/android.jar
AAPT2=/opt/homebrew/bin/aapt2
APKSIGNER="${BT}/lib/apksigner.jar"
OUT="${HERE}/tvcast.apk"
KS="${HERE}/debug.keystore"

echo "[1/6] 编译 Java"
rm -rf "${HERE}/build"
mkdir -p "${HERE}/build/classes"
"${JH}/bin/javac" --release 8 -nowarn -cp "${PLAT}" \
    -d "${HERE}/build/classes" \
    "${HERE}/src/com/xinmin/tvcast/ReceiverActivity.java"

echo "[2/6] 生成 classes.dex"
"${JH}/bin/java" -cp "${BT}/lib/dx.jar" com.android.dx.command.Main \
    --dex --min-sdk-version=15 --output="${HERE}/build/classes.dex" \
    "${HERE}/build/classes"

echo "[3/6] 打包二进制 Manifest"
"${AAPT2}" link -o "${HERE}/build/base.apk" -I "${PLAT}" \
    --manifest "${HERE}/AndroidManifest.xml" \
    --min-sdk-version 15 --target-sdk-version 15 \
    --no-compile-sdk-metadata

echo "[4/6] 合入 dex"
/usr/bin/python3 - "${HERE}/build/base.apk" "${HERE}/build/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('   APK 条目:', zipfile.ZipFile(apk).namelist())
PY
cp "${HERE}/build/base.apk" "${HERE}/build/unsigned.apk"

echo "[5/6] 生成签名密钥"
if [ ! -f "${KS}" ]; then
    "${JH}/bin/keytool" -genkeypair -alias tvcast -keyalg RSA -keysize 2048 \
        -sigalg SHA1withRSA -validity 10000 -keystore "${KS}" \
        -storepass tvcast123 -keypass tvcast123 \
        -dname "CN=TVCast, OU=Home, O=Home, L=NA, ST=NA, C=CN" >/dev/null 2>&1
fi

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
