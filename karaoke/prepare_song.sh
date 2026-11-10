#!/bin/bash
# prepare_song.sh — 给「歌名.mp3 + 歌名.lrc」补一份「歌名.伴奏.mp3」
#
# 用法：
#   ./prepare_song.sh 朋友.mp3 [更多.mp3 ...]        # 单曲 / 多曲
#   ./prepare_song.sh --dir /Volumes/USB/karaoke     # 整库批量（递归 2 层）
#   ./prepare_song.sh --tv 朋友.mp3                  # 生成后直接推到电视 U 盘
#   ./prepare_song.sh --force --dir ...              # 重做已有伴奏
#   DEMUCS_BIN=/path/to/demucs ./prepare_song.sh 朋友.mp3
#
# 产物（与源 MP3 同目录，三个文件放一起拷进 U 盘即可）：
#   歌名.mp3          原唱（保留）
#   歌名.lrc          歌词（保留）
#   歌名.伴奏.mp3     无人声伴奏（本脚本唯一新增的文件）
#
# 电视端曲库严格只认这三种文件名；旧版 .人声.mp3 不再生成，也不会进歌单。
# 伴奏用 Demucs --two-stems=vocals --other-method minus 生成，统一 CBR：
# Android 4.0.4 的 MediaPlayer 遇到 VBR 时 seekTo 会被静默忽略，切音轨就保不住位置。
#
# 电视端操作：按 1 在「原唱 / 伴奏」两个文件间切换（保持进度），详见 README。

set -e

DEMUCS="${DEMUCS_BIN:-$HOME/.venvs/demucs/bin/demucs}"
ADB="${ADB_BIN:-/opt/homebrew/bin/adb}"
TV="${TV_HOST:-192.168.2.98:5555}"
ENCODE="-ar 44100 -ac 2 -c:a libmp3lame -b:a 128k -abr 0 -write_xing 0"
FORCE=0
PUSH_TV=0
SRCDIR=""
FILES=()

usage() {
  sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
}

for cmd in ffmpeg ffprobe; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "缺少 ${cmd}，先装：brew install ffmpeg"; exit 1; }
done
[ -x "$DEMUCS" ] || {
  echo "找不到 demucs：${DEMUCS}"
  echo "准备环境（一次即可）："
  echo "  /opt/homebrew/bin/python3 -m venv ~/.venvs/demucs"
  echo "  ~/.venvs/demucs/bin/pip install demucs numpy soundfile"
  echo "或显式指定：DEMUCS_BIN=/path/to/demucs ./prepare_song.sh 朋友.mp3"
  exit 1
}

while [ $# -gt 0 ]; do
  case "$1" in
    --tv) PUSH_TV=1; shift ;;
    --force) FORCE=1; shift ;;
    --dir) SRCDIR="$2"; shift 2 ;;
    -h|--help) usage ;;
    -*) echo "未知参数：${1}"; usage ;;
    *) FILES[${#FILES[@]}]="$1"; shift ;;
  esac
done

if [ -n "$SRCDIR" ]; then
  [ -d "$SRCDIR" ] || { echo "目录不存在：${SRCDIR}"; exit 1; }
  TMPLIST="$(mktemp -t kslist)"
  trap 'rm -f "$TMPLIST"' EXIT
  find "$SRCDIR" -maxdepth 2 -type f -iname '*.mp3' -print0 > "$TMPLIST"
  while IFS= read -r -d '' f; do
    case "$(basename "$f")" in
      *人声*|*伴奏*|*-inst*|*_inst*|*-vocal*|*_vocal*) continue ;;
    esac
    FILES[${#FILES[@]}]="$f"
  done < "$TMPLIST"
fi

[ ${#FILES[@]} -gt 0 ] || usage

# 把生成的两条分轨推到电视 U 盘：中文文件名通过「名字文件」传递，
# 避开老电视 busybox shell 的编码问题；找不到同名主歌时落到 /mnt/sdcard/karaoke。
push_to_tv() {
  local src="$1" ins="$2"
  local main_name lrc_name ins_name lrc
  main_name="$(basename "$src")"
  local stem="${main_name%.*}"
  lrc_name="${stem}.lrc"
  ins_name="${stem}.伴奏.mp3"
  lrc="$(dirname "$src")/${lrc_name}"

  local stage
  stage="$(mktemp -d -t ksstage)"
  printf '%s' "$main_name" > "$stage/main.name"
  printf '%s' "$lrc_name"  > "$stage/lrc.name"
  printf '%s' "$ins_name"  > "$stage/ins.name"
  cp "$src" "$stage/main.mp3"
  cp "$ins" "$stage/b.mp3"
  if [ -f "$lrc" ]; then cp "$lrc" "$stage/main.lrc"; else : > "$stage/main.lrc"; fi

  "$ADB" -s "$TV" shell 'rm -rf /data/local/tmp/ksong; mkdir -p /data/local/tmp/ksong' >/dev/null 2>&1
  for n in main.name lrc.name ins.name main.mp3 main.lrc b.mp3; do
    "$ADB" -s "$TV" push "$stage/$n" "/data/local/tmp/ksong/$n" >/dev/null 2>&1
  done
  "$ADB" -s "$TV" shell '
    S=/data/local/tmp/ksong
    M=$(cat $S/main.name); L=$(cat $S/lrc.name); B=$(cat $S/ins.name)
    D=""
    for f in /mnt/sdcard/*/*.mp3; do
      [ -f "$f" ] || continue
      case "$f" in *伴奏*|*人声*) continue ;; esac
      if [ "${f##*/}" = "$M" ]; then D="${f%/*}"; break; fi
    done
    if [ -z "$D" ]; then
      D=/mnt/sdcard/karaoke
      mkdir -p "$D" 2>/dev/null || D=/mnt/sdcard
      cp "$S/main.mp3" "$D/$M" 2>/dev/null
      S2=$(wc -c < "$S/main.lrc")
      [ "$S2" -gt 0 ] && cp "$S/main.lrc" "$D/$L" 2>/dev/null
      echo "TV_NEWDIR $D"
    fi
    cp "$S/b.mp3" "$D/$B" && echo "TV_OK $D/$B"
    rm -rf "$S"
  ' 2>&1 | grep -E 'TV_OK|TV_NEWDIR'
  rm -rf "$stage"
}

OK=0
SKIP=0
FAIL=0
for SRC in "${FILES[@]}"; do
  if [ ! -f "$SRC" ]; then
    echo "跳过（不是文件）：${SRC}"
    FAIL=$((FAIL + 1))
    continue
  fi
  MAIN="$(basename "$SRC")"
  STEM="${MAIN%.*}"
  DIR="$(dirname "$SRC")"
  INS="${DIR}/${STEM}.伴奏.mp3"
  LRC="${DIR}/${STEM}.lrc"

  if [ -f "$INS" ] && [ "$FORCE" -eq 0 ]; then
    echo "已有伴奏，跳过生成：${STEM}（重做加 --force）"
    # 换 U 盘 / 重插电视时，--tv 仍要能把现成伴奏推过去
    if [ "$PUSH_TV" -eq 1 ]; then push_to_tv "$SRC" "$INS"; fi
    SKIP=$((SKIP + 1))
    continue
  fi
  [ -f "$LRC" ] || echo "  提示：没找到 ${STEM}.lrc，仍会生成伴奏，但电视上无歌词"

  echo "分离：${STEM}"
  WORK="$(mktemp -d -t ksdemucs)"
  if ! "$DEMUCS" --two-stems=vocals --other-method minus -o "$WORK" "$SRC" >/dev/null 2>&1; then
    echo "  Demucs 分离失败：${SRC}"
    rm -rf "$WORK"
    FAIL=$((FAIL + 1))
    continue
  fi
  VWAV="$(find "$WORK" -type f -name 'vocals.wav' | head -1)"
  BWAV="$(find "$WORK" -type f \( -name 'minus_vocals.wav' -o -name 'no_vocals.wav' \) | head -1)"
  if [ -z "$VWAV" ] || [ -z "$BWAV" ]; then
    echo "  没找到分轨产物（vocals.wav / minus_vocals.wav）"
    rm -rf "$WORK"
    FAIL=$((FAIL + 1))
    continue
  fi

  TMPV="$(mktemp -t ksvoc).mp3"
  TMPB="$(mktemp -t ksinst).mp3"
  if ! ffmpeg -y -v error -i "$VWAV" $ENCODE "$TMPV" \
     || ! ffmpeg -y -v error -i "$BWAV" $ENCODE "$TMPB"; then
    echo "  转码失败：${STEM}"
    rm -rf "$WORK" "$TMPV" "$TMPB"
    FAIL=$((FAIL + 1))
    continue
  fi
  rm -rf "$WORK"

  D0="$(ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$SRC")"
  D1="$(ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$TMPV")"
  D2="$(ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$TMPB")"
  BAD="$(awk -v a="$D0" -v b="$D1" -v c="$D2" 'BEGIN {
      d1=a-b; if(d1<0)d1=-d1; d2=a-c; if(d2<0)d2=-d2; d3=b-c; if(d3<0)d3=-d3;
      print (d1>0.5||d2>0.5||d3>0.5) ? 1 : 0 }')"
  if [ "$BAD" = "1" ]; then
    echo "  时长不齐（母版 ${D0}s / 人声 ${D1}s / 伴奏 ${D2}s），已丢弃：${STEM}"
    rm -f "$TMPV" "$TMPB"
    FAIL=$((FAIL + 1))
    continue
  fi

  rm -f "$TMPV"          # 人声分轨只用于校验时长，曲库不再需要它
  # 目标常在外置 U 盘，与 /tmp 不同卷；用 cp+rm 而不是 mv：
  # 某些托管环境里的 mv shim 只做 rename(2)，跨卷会报 EXDEV 直接中断整批。
  if ! /bin/cp "$TMPB" "$INS"; then
    echo "  写入 U 盘失败：${INS}"
    /bin/rm -f "$TMPB"
    FAIL=$((FAIL + 1))
    continue
  fi
  /bin/rm -f "$TMPB"
  echo "  完成：${STEM}.伴奏.mp3  (${D2}s, CBR 128k)"
  OK=$((OK + 1))

  if [ "$PUSH_TV" -eq 1 ]; then
    push_to_tv "$SRC" "$INS"
  fi
done

echo "----"
echo "生成 ${OK} 首，跳过 ${SKIP} 首，失败 ${FAIL} 首。"
if [ "$PUSH_TV" -eq 0 ]; then
  echo "把「歌名.mp3」「歌名.lrc」「歌名.伴奏.mp3」放同一目录拷进 U 盘。"
else
  echo "已推送到电视；打开谢姐K歌台即可，遥控器 1 切原唱 / 伴奏。"
fi
