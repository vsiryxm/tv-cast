#!/system/bin/sh
# 开机自启入口：由海信官方 /system/bin/autorun.sh 从
# /data/local/misc/Arun.zip 解压到 /tmp/run.sh 后执行（class main / user root / oneshot）。
# 必须尽快返回，所以只负责把常驻守护拉到后台。

S="/data/local/tmp/karaoke_hotkey.sh"
P="/data/local/tmp/karaoke_hotkey.pid"

alive=0
if [ -f "$P" ]; then
    p=`cat "$P" 2>/dev/null`
    if [ -n "$p" ] && [ -r "/proc/$p/cmdline" ] && \
       grep -q 'karaoke_hotkey.sh' "/proc/$p/cmdline" 2>/dev/null; then
        alive=1
    fi
fi

if [ "$alive" -eq 0 ] && [ -f "$S" ]; then
    /sbin/setsid /system/bin/sh "$S" >/dev/null 2>&1 </dev/null &
fi

exit 0
