#!/bin/bash
# make_inst.sh — 给 K 歌机生成「歌名.伴奏.mp3」，放 U 盘后按遥控器 0 键切换原唱/伴奏
#
# 用法：
#   ./make_inst.sh 朋友.mp3 [更多歌曲.mp3 ...]        # ffmpeg 相位抵消，几秒一首，效果一般
#   ./make_inst.sh --ai 朋友.mp3                      # Demucs AI 分离，慢，效果好
#   ./make_inst.sh --ai --dir /Volumes/USB/karaoke    # 批量处理整个曲库
#   ./make_inst.sh --force --ai 朋友.mp3              # 覆盖已有伴奏
#   DEMUCS_BIN=/path/to/demucs ./make_inst.sh --ai 朋友.mp3
#
# 产物固定为 CBR 128k / 44.1kHz / 立体声：这台 Android 4.0.4 的 MediaPlayer
# 遇到 VBR 时 seekTo 会被静默忽略，切换音轨就会跳位。
# 伴奏与原唱必须等长，脚本会校验，不等长直接报错不落地。

set -e

MODE="phase"
FORCE=0
OUTDIR=""
SRCDIR=""
DEMUCS="${DEMUCS_BIN:-demucs}"
FILES=()
ENCODE="-ar 44100 -ac 2 -c:a libmp3lame -b:a 128k -abr 0 -write_xing 0"

usage() {
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
}

for cmd in ffmpeg ffprobe; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "缺少 ${cmd}，先装：brew install ffmpeg"; exit 1; }
done

while [ $# -gt 0 ]; do
  case "$1" in
    --ai) MODE="ai"; shift ;;
    --force) FORCE=1; shift ;;
    --dir) SRCDIR="$2"; shift 2 ;;
    --out) OUTDIR="$2"; shift 2 ;;
    -h|--help) usage ;;
    -*) echo "未知参数：${1}"; usage ;;
    *) FILES[${#FILES[@]}]="$1"; shift ;;
  esac
done

if [ "$MODE" = "ai" ]; then
  command -v "$DEMUCS" >/dev/null 2>&1 || {
    echo "找不到 demucs。先装："
    echo "  /opt/homebrew/bin/python3 -m venv ~/.venvs/demucs"
    echo "  ~/.venvs/demucs/bin/pip install demucs"
    echo "  然后：DEMUCS_BIN=~/.venvs/demucs/bin/demucs ./make_inst.sh --ai 朋友.mp3"
    exit 1
  }
fi

TMPLIST="$(mktemp -t instlist)"
trap 'rm -f "$TMPLIST"' EXIT
if [ -n "$SRCDIR" ]; then
  [ -d "$SRCDIR" ] || { echo "目录不存在：${SRCDIR}"; exit 1; }
  find "$SRCDIR" -maxdepth 2 -type f -iname '*.mp3' -print0 > "$TMPLIST"
  while IFS= read -r -d '' f; do
    case "$(basename "$f")" in
      *伴奏*|*-inst*|*_inst*|*inst.*) continue ;;
    esac
    FILES[${#FILES[@]}]="$f"
  done < "$TMPLIST"
fi

[ ${#FILES[@]} -gt 0 ] || usage

FILTER='[0:a]asplit=2[lo][hi];[lo]lowpass=f=200,pan=mono|c0=0.5*c0+0.5*c1,pan=stereo|c0=c0|c1=c0[low];[hi]highpass=f=200,pan=stereo|c0=c0-c1|c1=c1-c0,volume=1.8[him];[low][him]amix=inputs=2:duration=first[out]'

OK=0
FAIL=0
for SRC in "${FILES[@]}"; do
  if [ ! -f "$SRC" ]; then
    echo "跳过（不是文件）：${SRC}"
    FAIL=$((FAIL + 1))
    continue
  fi
  STEM="$(basename "$SRC")"
  STEM="${STEM%.*}"
  DIR="$(dirname "$SRC")"
  if [ -n "$OUTDIR" ]; then
    DIR="$OUTDIR"
    mkdir -p "$DIR"
  fi
  DST="${DIR}/${STEM}.伴奏.mp3"
  if [ -f "$DST" ] && [ "$FORCE" -eq 0 ]; then
    echo "已有伴奏，跳过：${DST}（覆盖加 --force）"
    continue
  fi

  TMP="$(mktemp -t inst).mp3"
  echo "生成：${STEM}  [${MODE}]"
  if [ "$MODE" = "ai" ]; then
    WORK="$(mktemp -d -t demucs)"
    if ! "$DEMUCS" --two-stems=vocals --mp3 -o "$WORK" "$SRC" >/dev/null 2>&1; then
      echo "  AI 分离失败：${SRC}"
      rm -rf "$WORK" "$TMP"
      FAIL=$((FAIL + 1))
      continue
    fi
    NOVOCAL="$(find "$WORK" -type f -name 'no_vocals.*' | head -1)"
    if [ -z "$NOVOCAL" ] || ! ffmpeg -y -v error -i "$NOVOCAL" $ENCODE "$TMP"; then
      echo "  转码失败：${SRC}"
      rm -rf "$WORK" "$TMP"
      FAIL=$((FAIL + 1))
      continue
    fi
    rm -rf "$WORK"
  else
    if ! ffmpeg -y -v error -i "$SRC" -filter_complex "$FILTER" -map '[out]' $ENCODE "$TMP"; then
      echo "  转码失败：${SRC}"
      rm -f "$TMP"
      FAIL=$((FAIL + 1))
      continue
    fi
  fi

  D1="$(ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$SRC")"
  D2="$(ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$TMP")"
  DIFF="$(awk -v a="$D1" -v b="$D2" 'BEGIN { d=a-b; if(d<0) d=-d; printf "%.2f", d }')"
  if awk -v d="$DIFF" 'BEGIN { exit !(d>0.5) }'; then
    echo "  时长不一致（原唱 ${D1}s / 伴奏 ${D2}s），已丢弃：${SRC}"
    rm -f "$TMP"
    FAIL=$((FAIL + 1))
    continue
  fi

  mv "$TMP" "$DST"
  echo "  完成：${DST}  (${D2}s, CBR 128k, 时长差 ${DIFF}s)"
  OK=$((OK + 1))
done

echo "----"
echo "成功 ${OK} 首，跳过/失败 ${FAIL} 首。"
echo "把「歌名.mp3」「歌名.伴奏.mp3」「歌名.lrc」三个放同一目录拷进 U 盘，按 0 切换。"
