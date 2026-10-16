#!/usr/bin/env bash
# Publishes the lab streams to MediaMTX and keeps them running until Ctrl+C:
#   lab/stream1, lab/stream2   C320WS-shaped clips, looped (any number of readers)
#   tapo/stream1, tapo/stream2 the same clips on paths limited to 2 readers, like a Tapo camera
#   lab/clock                  a live clock showing this PC's time with milliseconds
# Usage: ./publish.sh   (LAB_HOST=127.0.0.1 LAB_PORT=8554 LAB_PUBLISH_USER=... LAB_PUBLISH_PASS=...)
set -euo pipefail
cd "$(dirname "$0")/.."

HOST=${LAB_HOST:-127.0.0.1}
PORT=${LAB_PORT:-8554}
USER_NAME=${LAB_PUBLISH_USER:-publisher}
PASS=${LAB_PUBLISH_PASS:-publisher-lab-pass}
BASE="rtsp://$USER_NAME:$PASS@$HOST:$PORT"
FONT=${FONT:-}
if [ -z "$FONT" ]; then
  for f in /usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf /usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf /System/Library/Fonts/Supplemental/Courier\ New\ Bold.ttf; do
    if [ -f "$f" ]; then FONT=$f; break; fi
  done
fi

for clip in lab-stream1 lab-stream2; do
  [ -f "clips/$clip.mkv" ] || { echo "clips/$clip.mkv is missing; run ./make-clips.sh first" >&2; exit 1; }
done

trap 'echo "Stopping publishers"; kill 0' INT TERM EXIT

loop_clip() { # clip path
  while true; do
    ffmpeg -hide_banner -loglevel warning -re -stream_loop -1 -i "clips/$1.mkv" -c copy \
      -f rtsp -rtsp_transport tcp "$BASE/$2" || true
    echo "Publisher for $2 stopped; restarting in 2 s" >&2
    sleep 2
  done
}

clock() {
  while true; do
    # Seconds since local midnight, so %{pts:hms} shows the wall clock with milliseconds.
    local offset
    offset=$(date +'%H %M %S %N' | awk '{printf "%.3f", $1*3600 + $2*60 + $3 + substr($4,1,3)/1000}')
    ffmpeg -hide_banner -loglevel warning -re -f lavfi -i "testsrc2=size=1280x720:rate=15" \
      -vf "drawtext=fontfile='$FONT':text='%{pts\:hms\:$offset}':x=(w-tw)/2:y=(h-th)/2:fontsize=96:fontcolor=white:box=1:boxcolor=black@0.7:boxborderw=20,drawtext=fontfile='$FONT':text='lab clock (PC time)':x=40:y=40:fontsize=36:fontcolor=white:box=1:boxcolor=black@0.6" \
      -c:v libx264 -preset veryfast -tune zerolatency -profile:v main -bf 0 -g 15 -pix_fmt yuv420p \
      -f rtsp -rtsp_transport tcp "$BASE/lab/clock" || true
    echo "Clock publisher stopped; restarting in 2 s" >&2
    sleep 2
  done
}

loop_clip lab-stream1 lab/stream1 &
loop_clip lab-stream2 lab/stream2 &
loop_clip lab-stream1 tapo/stream1 &
loop_clip lab-stream2 tapo/stream2 &
clock &
echo "Publishing to rtsp://$HOST:$PORT/{lab/stream1,lab/stream2,lab/clock,tapo/stream1,tapo/stream2}. Ctrl+C stops."
wait
