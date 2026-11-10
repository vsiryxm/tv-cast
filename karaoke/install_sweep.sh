#!/bin/bash
# 把刚编译出来的 karaoke.apk 装到电视（默认 192.168.2.98:5555）。
# 装前自动 adb pull 备份电视上的旧版到 apk-backup-<时间戳>/，回滚一条命令。
# 用法: ./install_sweep.sh [电视地址:端口]
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
TV="${1:-192.168.2.98:5555}"
PKG=com.xinmin.karaoke

adb connect "$TV"
# 这台电视的 adb shell 会混入内核日志噪声行，只取 package: 后面的路径
APK_PATH="$(adb -s "$TV" shell pm path "$PKG" | tr -d '\r' \
  | grep -o 'package:[^[:space:]]*' | tail -1 | cut -d: -f2-)"
[ -n "$APK_PATH" ] || { echo "电视上未找到 $PKG"; exit 1; }

BAK="$HERE/apk-backup-$(date +%Y%m%d-%H%M)"
mkdir -p "$BAK"
adb -s "$TV" pull "$APK_PATH" "$BAK/$PKG.apk"
echo "旧版已备份: $BAK/$PKG.apk"

adb -s "$TV" install -r "$HERE/karaoke.apk"
echo "安装完成。"
echo "回滚: adb -s $TV install -r '$BAK/$PKG.apk'"
