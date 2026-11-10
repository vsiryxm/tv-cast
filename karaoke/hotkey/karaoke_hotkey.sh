#!/system/bin/sh
# 双击遥控器「*」键 -> 打开谢姐K歌台
# 适用：海信 LED50EC280JD / Android 4.0.4 / API 15（root）
#
# 为什么不用 getevent：这台电视的 toolbox getevent 把 stdout 接到管道/文件时是
# 全缓冲，长期运行不 flush（只有 -c N 退出才刷），守护会永远读不到事件。
# 所以直接读 /dev/input/eventX 的 16 字节 struct input_event，用 dd + hexdump 解析：
#   timeval(8) + u16 type + u16 code + s32 value  => 8 个 16 位字
# 每个设备保持一个常驻 fd（exec 3<），事件不会因反复开关设备而丢失。
#
# 只读监听，不拦截不消费，单击 * 完全保持系统原有行为；
# 只有同一个扫描码在 90~700ms 内连按两次才触发。
#
# 自启：/data/local/misc/Arun.zip 内的 run.sh（海信官方 /system/bin/autorun.sh 开机解压执行）
# 撤销：./install.sh --remove
# 日志：karaoke_hotkey.keys 记录每次按下的设备/扫描码，用于把 CODES 收敛成精确值。

PKG="com.xinmin.karaoke"
ACT="com.xinmin.karaoke/.KaraokeActivity"
PIDFILE="/data/local/tmp/karaoke_hotkey.pid"
KEYLOG="/data/local/tmp/karaoke_hotkey.keys"
LOGF="/data/local/tmp/karaoke_hotkey.log"
STATE="/data/local/tmp/karaoke_hotkey.state"

# 各 keylayout 里映射到 STAR 的 Linux 扫描码（十六进制，四位）：
# 0037=55  0060=96  00e8=232  020a=522  0302=770  0303=771
CODES=" 0037 0060 00e8 020a 0302 0303 "
MIN_MS=90          # 小于 90ms 视为抖动 / 重复码
MAX_MS=700         # 大于 700ms 不算双击

# 五个可能的遥控/按键设备；排除 auto_test_board、little_white、vminb 等调试设备
DEVS="/dev/input/event0 /dev/input/event1 /dev/input/event2 /dev/input/event6 /dev/input/event7"

# ---- 单实例 ----
if [ -f "$PIDFILE" ]; then
    old=`cat "$PIDFILE" 2>/dev/null`
    if [ -n "$old" ] && [ -r "/proc/$old/cmdline" ] && \
       grep -q 'karaoke_hotkey.sh' "/proc/$old/cmdline" 2>/dev/null; then
        exit 0
    fi
fi
echo $$ > "$PIDFILE"

echo "start pid=$$" >> "$LOGF"
: > "$KEYLOG"
echo "0 -" > "$STATE"

launch() {
    # 已经在前台就不重复拉起，避免叠加多个 Activity
    if dumpsys activity top 2>/dev/null | head -5 | grep -q "$PKG"; then
        return 0
    fi
    am start -n "$ACT" >/dev/null 2>&1
    echo "double-star -> launch karaoke" >> "$LOGF"
}

# 每个设备一个常驻子进程：fd 3 一直开着，逐条读 16 字节事件
watch() {
    dev="$1"
    exec 3< "$dev" 2>/dev/null || return 1
    while true; do
        raw=`dd bs=16 count=1 <&3 2>/dev/null | busybox hexdump -e '8/2 "%u " "\n"'`
        [ -n "$raw" ] || continue
        set -- $raw
        [ $# -ge 8 ] || continue
        [ "${5}" -eq 1 ] || continue                     # EV_KEY
        v=$(( ${7} + ${8} * 65536 ))
        [ "$v" -eq 1 ] || continue                        # 只认按下，忽略抬起/长按重复
        hex=`printf "%04x" "${6}"`
        # 内核时间戳换算成毫秒，避免依赖 wall clock 与 shell 时间精度
        ms=$(( (${1} + ${2} * 65536) * 1000 + (${3} + ${4} * 65536) / 1000 ))
        echo "$dev $hex $ms" >> "$KEYLOG"

        case "$CODES" in
            *" $hex "*) ;;
            *) echo "0 -" > "$STATE"; continue ;;
        esac

        read last_ms last_hex < "$STATE"
        last_ms=${last_ms:-0}
        last_hex=${last_hex:--}
        d=$(( ms - last_ms ))
        if [ "$last_hex" = "$hex" ] && [ "$d" -ge "$MIN_MS" ] && [ "$d" -le "$MAX_MS" ]; then
            echo "0 -" > "$STATE"
            launch
        else
            echo "$ms $hex" > "$STATE"
        fi
    done
}

pids=""
for d in $DEVS; do
    watch "$d" &
    pids="$pids $!"
done

trap 'for p in $pids; do kill "$p" 2>/dev/null; done; rm -f "$PIDFILE"; exit 0' TERM INT

wait
