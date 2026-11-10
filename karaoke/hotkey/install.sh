#!/bin/bash
# 一键部署 / 撤销「双击 * 键打开 K 歌台」
#   ./install.sh            安装并立即生效（不用重启电视）
#   ./install.sh --remove   撤销（删除自启包、停掉守护）
# 地址可用环境变量覆盖：TV_HOST=192.168.2.98:5555 ADB=/path/to/adb ./install.sh
set -e

ADB="${ADB:-/opt/homebrew/bin/adb}"
TV="${TV_HOST:-192.168.2.98:5555}"
HERE="$(cd "$(dirname "$0")" && pwd)"

"$ADB" connect "$TV" >/dev/null

if [ "${1:-}" = "--remove" ]; then
    "$ADB" -s "$TV" shell '
        rm -f /data/local/misc/Arun.zip
        p=`cat /data/local/tmp/karaoke_hotkey.pid 2>/dev/null`
        [ -n "$p" ] && kill "$p" 2>/dev/null
        busybox killall getevent 2>/dev/null
        rm -f /data/local/tmp/karaoke_hotkey.sh /data/local/tmp/run.sh \
              /data/local/tmp/karaoke_hotkey.pid /data/local/tmp/karaoke_hotkey.keys \
              /data/local/tmp/karaoke_hotkey.log /data/local/tmp/karaoke_hotkey.state
        echo removed'
    echo "已撤销；若还有残留的 getevent 进程，重启一次电视即可。"
    exit 0
fi

echo "[1/4] 备份已有 Arun.zip（若存在）"
"$ADB" -s "$TV" shell 'if [ -f /data/local/misc/Arun.zip ]; then cp -f /data/local/misc/Arun.zip /data/local/misc/Arun.zip.bak; fi'

echo "[2/4] 推送守护脚本"
"$ADB" -s "$TV" push "$HERE/karaoke_hotkey.sh" /data/local/tmp/karaoke_hotkey.sh >/dev/null
"$ADB" -s "$TV" push "$HERE/run.sh"           /data/local/tmp/run.sh           >/dev/null

echo "[3/4] 生成开机自启包（autorun.sh 用 busybox tar 解包，名字叫 zip 但必须是 tar）"
"$ADB" -s "$TV" shell '
    chmod 755 /data/local/tmp/karaoke_hotkey.sh /data/local/tmp/run.sh
    cd /data/local/tmp && busybox tar cf /data/local/misc/Arun.zip run.sh
    chmod 644 /data/local/misc/Arun.zip
    busybox tar tf /data/local/misc/Arun.zip'

echo "[4/4] 停掉旧守护并立即启动新版（无需重启）"
"$ADB" -s "$TV" shell '
    p=`cat /data/local/tmp/karaoke_hotkey.pid 2>/dev/null`
    [ -n "$p" ] && kill "$p" 2>/dev/null
    ps | grep "[g]etevent" | while read -r line; do set -- $line; kill "$1" 2>/dev/null; done
    rm -f /data/local/tmp/karaoke_hotkey.pid
    sleep 1
    /sbin/setsid /system/bin/sh /data/local/tmp/karaoke_hotkey.sh >/dev/null 2>&1 </dev/null &
    sleep 1
    echo "pidfile=$(cat /data/local/tmp/karaoke_hotkey.pid 2>/dev/null)"'

echo "完成。"
