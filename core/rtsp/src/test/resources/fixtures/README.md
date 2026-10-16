# Test fixtures

Real RTP packets from FFmpeg's RTP muxer (an RFC 6184 packetizer independent of this project),
captured over UDP on 127.0.0.1 and stored in RTSP interleaved framing: `'$'`, channel (1 byte),
length (2 bytes, big-endian), packet. Channels: 0 = video RTP, 1 = video RTCP, 2 = audio RTP,
3 = audio RTCP. A test RTSP server replays them after PLAY.

| File | Content | Expected (checked with a separate Python script) |
| --- | --- | --- |
| `main360-pcma.rtp` | H.264 Main, Level 2.2, 640×360, 15 fps, GOP 30, no B-frames, SPS/PPS before every IDR; G.711 A-law 8 kHz mono | 63 frames, 3 keyframes 2.0 s apart, 15.0 fps, 60 P / 3 I frames, 33 audio packets |
| `high360-bframes.rtp` | H.264 High, Level 3.0, 640×360, 25 fps, GOP 25, 2 B-frames | 80 frames, 4 keyframes 1.0 s apart, 25.0 fps, 46 frames with B slices |

How they were made (FFmpeg 6.1.1 with libx264):

```bash
# main360-pcma.rtp (a UDP listener on ports 5004–5007 wrote the interleaved file)
ffmpeg -re -t 4.2 -f lavfi -i testsrc2=size=640x360:rate=15 -re -t 4.2 -f lavfi -i sine=frequency=1000:sample_rate=8000 \
  -map 0:v -c:v libx264 -profile:v main -level:v 2.2 -pix_fmt yuv420p -crf 40 -bf 0 -refs 1 -g 30 -keyint_min 30 \
  -sc_threshold 0 -x264-params repeat-headers=1 -f rtp rtp://127.0.0.1:5004?rtcpport=5005 \
  -map 1:a -c:a pcm_alaw -ar 8000 -ac 1 -f rtp rtp://127.0.0.1:5006?rtcpport=5007

# high360-bframes.rtp
ffmpeg -re -t 3.2 -f lavfi -i testsrc2=size=640x360:rate=25 -c:v libx264 -profile:v high -pix_fmt yuv420p -crf 40 \
  -bf 2 -g 25 -keyint_min 25 -sc_threshold 0 -x264-params repeat-headers=1 -f rtp rtp://127.0.0.1:5004?rtcpport=5005
```

The C320WS parameter sets used in `H264Test` and `SdpTest` are the `sprop-parameter-sets` values
read from the user's camera. The rest of the Tapo-like SDP in `SdpTest` is NOT a capture; replace it
with the real SDP that `camcheck --sdp` prints during Phase 0.
