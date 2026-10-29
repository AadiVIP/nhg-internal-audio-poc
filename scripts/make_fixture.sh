#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
OUT="${1:-/sdcard/Download/NHG-Fixture-440L-1000R-30s.wav}"
mkdir -p "$(dirname "$OUT")"
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "aevalsrc=0.5*sin(2*PI*440*t)|0.5*sin(2*PI*1000*t):s=48000:d=30" \
  -c:a pcm_s16le -ar 48000 -ac 2 "$OUT"
ls -l "$OUT"
sha256sum "$OUT"
ffprobe -v error -show_entries format=duration,size,bit_rate -show_entries stream=codec_name,sample_rate,channels,channel_layout,sample_fmt -of default=noprint_wrappers=1 "$OUT"
