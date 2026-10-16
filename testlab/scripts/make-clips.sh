#!/usr/bin/env bash
# Makes looping test clips shaped like the Tapo C320WS streams (docs/TEST_LAB.md):
#   clips/lab-stream1.mkv  2560x1440, 15 fps, H.264 Main Level 5.0, 1 reference frame, no B-frames
#   clips/lab-stream2.mkv  640x360,  15 fps, H.264 Main Level 2.2, 1 reference frame, no B-frames
# Both: keyframe every 2 s (GOP 30, adjust KEYINT after measuring your camera), SPS/PPS before
# every keyframe, G.711 A-law 8 kHz mono audio.
# Needs FFmpeg with libx264. Usage: ./make-clips.sh   (DURATION=120 KEYINT=30 FONT=/path/to.ttf)
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p clips

DURATION=${DURATION:-120}
KEYINT=${KEYINT:-30}
FONT=${FONT:-}
if [ -z "$FONT" ]; then
  for f in /usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf /System/Library/Fonts/Supplemental/Arial\ Bold.ttf /Library/Fonts/Arial\ Bold.ttf; do
    if [ -f "$f" ]; then FONT=$f; break; fi
  done
fi
if [ -z "$FONT" ]; then echo "Set FONT to a .ttf file for the on-screen labels" >&2; exit 1; fi

make_clip() { # name size level bitrate fontsize
  local name=$1 size=$2 level=$3 rate=$4 fs=$5
  echo "Encoding clips/$name.mkv ($size, Level $level, ${DURATION}s)..."
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc2=size=$size:rate=15" \
    -f lavfi -i "sine=frequency=1000:sample_rate=8000" \
    -t "$DURATION" \
    -vf "drawtext=fontfile='$FONT':text='$name $size 15 fps  frame %{frame_num}':x=20:y=20:fontsize=$fs:fontcolor=white:box=1:boxcolor=black@0.6" \
    -c:v libx264 -preset veryfast -profile:v main -level:v "$level" -pix_fmt yuv420p \
    -bf 0 -refs 1 -g "$KEYINT" -keyint_min "$KEYINT" -sc_threshold 0 \
    -b:v "$rate" -maxrate "$rate" -bufsize "$rate" -x264-params repeat-headers=1 \
    -c:a pcm_alaw -ar 8000 -ac 1 \
    "clips/$name.mkv"
}

make_clip lab-stream1 2560x1440 5.0 3M 48
make_clip lab-stream2 640x360 2.2 512k 18
for clip in lab-stream1 lab-stream2; do
  ffprobe -v error -show_entries stream=codec_name,profile,level,width,height,r_frame_rate,has_b_frames,refs,sample_rate \
    -of compact "clips/$clip.mkv"
done
echo "Done. Next: ./publish.sh"
