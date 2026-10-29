#!/data/data/com.termux/files/usr/bin/bash
# Copy one NHG POC capture session from Downloads into a unique evidence directory
# and run independent validation. Never overwrites: refuses an existing directory.
# Usage: collect_session.sh <label> [session-substring]
set -eu
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
SRC="/sdcard/Download/NHG-InternalAudioPOC"
LABEL="${1:?usage: collect_session.sh <label> [session-substring]}"
MATCH="${2:-}"
case "$LABEL" in *[!A-Za-z0-9._-]*) echo "label must be [A-Za-z0-9._-]" >&2; exit 2;; esac
[ -d "$SRC" ] || { echo "no capture directory: $SRC" >&2; exit 3; }
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$PROJ/evidence/${TS}_${LABEL}_capture"
[ -e "$OUT" ] && { echo "refusing to overwrite $OUT" >&2; exit 4; }
mkdir -p "$OUT"
if [ -n "$MATCH" ]; then
  find "$SRC" -maxdepth 1 -type f -name "*${MATCH}*" -exec cp -p {} "$OUT"/ \;
else
  find "$SRC" -maxdepth 1 -type f -newermt '-30 minutes' -exec cp -p {} "$OUT"/ \;
fi
COUNT=$(find "$OUT" -maxdepth 1 -type f | wc -l)
[ "$COUNT" -gt 0 ] || { echo "no matching files in $SRC" >&2; rmdir "$OUT"; exit 5; }
{
  echo "collected_at=$(date -Ins)"
  echo "source_dir=$SRC"
  echo "label=$LABEL"
  echo "match=$MATCH"
  echo "file_count=$COUNT"
} > "$OUT/COLLECTION.txt"
ls -la "$OUT" > "$OUT/listing.txt"
( cd "$OUT" && sha256sum * > SHA256SUMS.txt 2>/dev/null || true )
WAV="$(find "$OUT" -maxdepth 1 -name '*.wav' | head -1 || true)"
if [ -n "$WAV" ]; then
  ffprobe -v error -show_format -show_streams -of json "$WAV" > "$OUT/ffprobe.json" 2> "$OUT/ffprobe.err" || true
  ffprobe -v error -show_format -show_streams "$WAV" > "$OUT/ffprobe.txt" 2>> "$OUT/ffprobe.err" || true
  ffmpeg -hide_banner -nostats -i "$WAV" -af astats=metadata=1:reset=0 -f null - 2> "$OUT/astats.txt" || true
  ffmpeg -hide_banner -nostats -i "$WAV" -af volumedetect -f null - 2> "$OUT/volumedetect.txt" || true
  python "$PROJ/tools/analyze_wav.py" "$WAV" > "$OUT/analyze.json" 2> "$OUT/analyze.err" || true
fi
echo "EVIDENCE_DIR=$OUT"
