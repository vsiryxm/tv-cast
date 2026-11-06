#!/bin/bash
# tv-cast 一键入口（终端运行）
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "${HERE}" || exit 1
# 配置：同目录 config.env 或环境变量优先；没配置就用内置默认值
if [ -f "${HERE}/config.env" ]; then . "${HERE}/config.env"; fi
PY=${PY:-/usr/bin/python3}
PYW=${PYW:-/opt/homebrew/bin/python3}
ADB=${ADB_BIN:-/opt/homebrew/bin/adb}
TV_IP=${TV_IP:-192.168.2.98}
PHONE_IP=${PHONE_IP:-192.168.2.83}
DEV="${TV_IP}:5555"
PHONE="${PHONE_IP}"
PKG=com.xinmin.tvcast
ACT="${PKG}/.ReceiverActivity"
APK="$(pwd)/receiver/tvcast.apk"

case "${1}" in
  serve)
    exec "${PY}" serve.py "${@:2}"
    ;;
  wall)
    # AI 文字看板：OCR 弹幕重排成大字全屏，本机需常开（Ctrl+C 结束）
    exec "${PYW}" wall.py --cast "${@:2}"
    ;;
  test)
    exec "${PY}" cast.py --selftest
    ;;
  phone)
    if [ -z "${2}" ]; then
      echo "用法: ./cast.sh phone 手机无线调试地址，例如 192.168.2.50:38279"
      exit 1
    fi
    exec "${PY}" cast.py --phone "${2}"
    ;;
  url)
    if [ -z "${2}" ]; then
      echo "用法: ./cast.sh url '直播流或视频地址'"
      exit 1
    fi
    exec "${PY}" cast.py --url "${2}"
    ;;
  open)
    if [ -z "${2}" ]; then
      echo "用法: ./cast.sh open '手机端串流页面地址'"
      exit 1
    fi
    exec "${PY}" cast.py --open "${2}"
    ;;
  apk)
    # 不填地址就用红米静态 IP 的 ScreenStream 默认端口；接收端会自动补 /stream.mjpeg
    SRC="${2:-http://${PHONE}:8080}"
    if [ -z "${2}" ]; then
      echo "[cast] 未指定地址，使用默认 ${SRC}"
    fi
    "${ADB}" connect "${DEV}" >/dev/null 2>&1
    # 强制停止再启动：老系统对后台 singleTop Activity 只做「带回前台」，不会送 onNewIntent
    "${ADB}" -s "${DEV}" shell "am force-stop ${PKG}" >/dev/null 2>&1
    exec "${ADB}" -s "${DEV}" shell "am start -n ${ACT} --es url '${SRC}'"
    ;;
  mode)
    # 0=满屏填充(默认) 1=裁切铺满 2=拉伸铺满 3=原始比例
    # 老系统对前台 singleTop Activity 只「带回前台」不送 onNewIntent，必须 force-stop 才能带参数
    M="${2:-0}"
    "${ADB}" connect "${DEV}" >/dev/null 2>&1
    "${ADB}" -s "${DEV}" shell "am force-stop ${PKG}" >/dev/null 2>&1
    exec "${ADB}" -s "${DEV}" shell "am start -n ${ACT} --ei mode ${M}"
    ;;
  danmu)
    # 弹幕看板：只把直播下方文字区放大铺满；可微调窗口 ./cast.sh danmu 0.60 0.90
    EXTRA=""
    if [ -n "${2:-}" ]; then EXTRA="--ef top ${2}"; fi
    if [ -n "${3:-}" ]; then EXTRA="${EXTRA} --ef bottom ${3}"; fi
    "${ADB}" connect "${DEV}" >/dev/null 2>&1
    "${ADB}" -s "${DEV}" shell "am force-stop ${PKG}" >/dev/null 2>&1
    # 显式带手机地址：从 AI 文字看板切回来时，避免沿用记住的 Mac 看板地址
    exec "${ADB}" -s "${DEV}" shell "am start -n ${ACT} --ei mode 4 --ei fps 12 --es url 'http://${PHONE}:8080' ${EXTRA}"
    ;;
  check)
    "${PY}" -c '
import socket, sys
ip = sys.argv[1]
def up(p):
    s = socket.socket(); s.settimeout(0.6)
    try:
        s.connect((ip, p)); return True
    except Exception:
        return False
    finally:
        try: s.close()
        except Exception: pass
print("手机静态 IP :", ip)
print("8080 推流   :", "已就绪，可直接 ./cast.sh apk" if up(8080) else "未启动，先在手机上点 ScreenStream 的 Start")
' "${PHONE}"
    ;;
  scan)
    "${ADB}" connect "${DEV}" >/dev/null 2>&1
    "${ADB}" -s "${DEV}" shell "am force-stop ${PKG}" >/dev/null 2>&1
    exec "${ADB}" -s "${DEV}" shell "am start -n ${ACT} --ei scan 1"
    ;;
  build)
    bash receiver/build.sh && exec "${ADB}" -s "${DEV}" install -r "${APK}"
    ;;
  install)
    # 直接安装仓库里现成的接收端 APK，不需要任何编译工具链
    "${ADB}" connect "${DEV}" >/dev/null 2>&1
    exec "${ADB}" -s "${DEV}" install -r "${APK}"
    ;;
  apkstop)
    exec "${ADB}" -s "${DEV}" shell "am force-stop ${PKG}; input keyevent 3"
    ;;
  stop)
    exec "${PY}" cast.py --stop
    ;;
  *)
    echo "tv-cast —— 零硬件投屏到客厅海信 LED50EC280JD (Android 4.0.4)"
    echo ""
    echo "【推荐】一键装机台（电视显示二维码，手机扫码后自动上屏）"
    echo "  ./cast.sh serve              启动装机台 + 自动接管，手机一点 Start 就出画面"
    echo ""
    echo "【推荐】电视端原生接收端（装好后本机可关机）"
    echo "  ./cast.sh install           只装现成 APK，不用编译工具链（推荐）"
    echo "  ./cast.sh check              检查手机 (${PHONE}:8080) 是否已开始推流"
    echo "  ./cast.sh apk               用默认地址直连红米，无需填 IP"
    echo "  ./cast.sh apk '手机串流地址'   手动指定其它 MJPEG/JPEG 流"
    echo "  ./cast.sh scan               接收端自动搜局域网里的推流源"
    echo "  ./cast.sh mode N             显示方式 0=满屏填充 1=裁切 2=拉伸 3=原始 4=弹幕看板"
    echo "  ./cast.sh danmu [上 下]      只看直播下方文字（大字全屏，免本机）；上下键微调"
    echo "  ./cast.sh apkstop            退出接收端，回到电视桌面"
    echo "  ./cast.sh build              重新编译并安装接收端 APK"
    echo ""
    echo "【AI 增强】只看文字，字号更大、消息自动累积（本机需常开）"
    echo "  ./cast.sh wall               OCR 弹幕 → 超大字看板（本机需常开，Ctrl+C 自动切回）"
    echo ""
    echo "【可选】Mac 中继 / 浏览器方案"
    echo "  ./cast.sh test               自检电视链路（测试图案，20 秒）"
    echo "  ./cast.sh phone IP:端口      投手机整屏（本机需常开）"
    echo "  ./cast.sh url '流地址'       把直播流/视频推到电视"
    echo "  ./cast.sh open '网页地址'    电视浏览器打开任意页面"
    echo "  ./cast.sh stop               停止投屏"
    exit 1
    ;;
esac
