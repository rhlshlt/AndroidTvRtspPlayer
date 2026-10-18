# Camera profile: TP-Link Tapo C320WS

The C320WS streams 2560 × 1440 (stream1) and 640 × 360 (stream2) H.264 Main at 15 fps with G.711
A-law audio over RTSP, and its SDP carries SPS and PPS, which is what Media3 needs. Two things decide
how it plays on a TV: stream1 needs an H.264 **Level 5.0** decoder that accepts 2560-pixel-wide
pictures, and neither stream's SPS limits frame reordering, so a decoder may hold up to 7–8 frames
(about 0.5 s) unless low-latency decoding or rtsp-client-android's SPS rewrite is used. The first
measurements are under [Measured so far](#measured-so-far).

## Connecting

| Item | Value |
| --- | --- |
| Main stream | `rtsp://<camera-ip>:554/stream1` (2560 × 1440; follows the Tapo app's video quality, so pick "Best Quality") |
| Sub-stream | `rtsp://<camera-ip>:554/stream2` (640 × 360) |
| Telephoto lens (dual-lens models only) | `/stream6` and `/stream7` |
| Sign-in | A per-camera **Camera Account**: Tapo app → camera → Device Settings → Advanced Settings → Camera Account; user name and password 6–32 characters |
| Viewers | About 2 RTSP streams plus 2 app live views at once; Tapo Care uses one (TP-Link support) |
| Network | 2.4 GHz Wi-Fi (802.11b/g/n) or 10/100 Ethernet; no PoE |
| Hardware versions | V1 and V2.20; record yours with the firmware version for every test run |

Give each camera a DHCP reservation in the router, so its address doesn't change.

## Stream parameters (from your camera's SDP)

You read these `a=fmtp` lines from the camera with VLC:

```text
stream1: a=fmtp:96 packetization-mode=1; profile-level-id=4D0032; sprop-parameter-sets=Z00AMoqKUBQAWtNkAAAPoAAB1MAQ,aO48gA==
stream2: a=fmtp:96 packetization-mode=1; profile-level-id=4D0016; sprop-parameter-sets=Z00AFoqKUFAX/LNkAAAPoAAB1MAQ,aO48gA==
```

Decoded with the project's SPS parser (`core/rtsp`, unit-tested against these exact bytes) and an
independent Python parser:

| Field | stream1 | stream2 |
| --- | --- | --- |
| Profile | Main (77), no constraint flags | Main (77) |
| Level | 5.0 (`level_idc` 50) | 2.2 (`level_idc` 22) |
| Picture size | 2560 × 1440 (160 × 90 macroblocks, no cropping) | 640 × 360 (coded 640 × 368, 8 rows cropped) |
| Frame rate in the SPS | 15 fps (`time_scale` 30000 / 2 × `num_units_in_tick` 1000), not fixed | 15 fps, not fixed |
| Reference frames | 1 | 1 |
| Picture order count | type 0, `log2_max_pic_order_cnt_lsb` 13 | same |
| Frame numbers | `log2_max_frame_num` 13, gaps allowed | same |
| Scan | progressive (`frame_mbs_only_flag` 1) | progressive |
| Video signal | `video_format` 5, **full range** (0–255), no colour description | same |
| HRD parameters | none | none |
| Reorder limit (`bitstream_restriction_flag`) | **absent** | **absent** |
| MaxDpbFrames (H.264 Table A-1) | 7 (110,400 / 14,400 macroblocks) | 8 (8,100 / 920) |
| Frames a decoder may hold before output | up to 7, about 0.47 s at 15 fps | up to 8, about 0.53 s |
| PPS (`aO48gA==`, same on both) | id 0, CABAC, 1 slice group, 1 reference index per list, no weighted prediction, initial QP 26, deblocking control present | same |
| Audio | G.711 A-law (PCMA), 8 kHz, mono (static payload type 8) | same |
| `packetization-mode` | 1 (single NAL units, STAP-A, FU-A) | 1 |

What follows from this:

- **Decoder level and size.** 2560 × 1440 is 14,400 macroblocks per frame, more than Level 4.2
  allows (8,704), so a decoder must support Level 5.0. Many 1080p-only TV boxes stop at 4.2. Level
  alone isn't enough: the first test TV's Realtek decoder lists Level 5.1 but stops at 1920 pixels
  wide, so it can't decode stream1 at all. The app's device profile checks both; where either falls
  short, full screen uses stream2.
- **Decoder delay.** With no reorder limit, H.264 lets a decoder hold up to MaxDpbFrames frames.
  Media3 can ask for Android's low-latency decoding mode (`KEY_LOW_LATENCY`, API 30+) where the
  decoder offers it; rtsp-client-android 6.1.1 sends only vendor hints, but can rewrite the SPS to
  declare no reordering, which is safe only if the camera sends no B-frames. `camcheck` found none.
- **Media3 compatibility.** Media3 1.11.1 needs `sprop-parameter-sets` with exactly two entries,
  SPS first, and an `a=control` line per track. Both lines meet the first rule, and the full SDP
  from `camcheck --sdp` meets the second.
- **Contrast.** The full-range flag means black is 0, not 16. Compare a frame on the TV with the
  Tapo app; a washed-out or crushed picture means a decoder or display ignores the flag.

## Decode load

| Layout | Streams | Decode load | In 1080p30 decodes |
| --- | --- | --- | --- |
| 1-view | 1 × stream1 | 55 Mpx/s | 0.9 |
| 2-view | 2 × stream1 | 111 Mpx/s | 1.8 |
| 4-view, mid and high tier | 4 × stream1 | 221 Mpx/s | 3.6 |
| 4-view, low tier | 4 × stream2 | 14 Mpx/s | 0.2 |
| 8-view (1 + 7) | 1 × stream1 + 7 × stream2 | 79 Mpx/s | 1.3 |
| 16-view | 16 × stream2 | 55 Mpx/s | 0.9 |

A 4K30 decoder handles about 249 Mpx/s, so the full-resolution 4-view needs roughly 90% of one. The
16-view is light on throughput; its limit is how many decoder instances the TV allows.

## Measured so far

One camera checked with `camcheck` in daylight on Oct 16, 2026, and the first TV (a Panasonic with a
Realtek decoder) on Oct 17–18; full tables in [PHASE0_RESULTS.md](PHASE0_RESULTS.md).

| Question | Answer |
| --- | --- |
| Sign-in | Digest with MD5, realm "TP-Link IP-Camera", no qop; both engines sign in |
| Keyframe interval | 2.00 s on both streams, steady |
| B-frames | None (899 and 900 frames checked), so the SPS rewrite is safe |
| Daytime bitrate | stream1 1,481 kbit/s (keyframes about 176 KB); stream2 125 kbit/s (about 28 KB) |
| First keyframe after PLAY | 62 ms (stream1) and 55 ms (stream2): switching needn't wait for the next keyframe |
| Arrival jitter on Wi-Fi | Up to 496 ms (stream1) and 552 ms (stream2) |
| Decoder hold on the Realtek TV, stream2 | About 600 ms as sent; 259–273 ms with rtsp-client-android's SPS rewrite |
| stream1 on the Realtek TV | Software decoding only: 13–15 fps with rtsp-client-android, 4–7 fps with Media3 |
| Two viewers per camera | Worked: four tiles on two cameras, two sessions each |

## Still to measure in Phase 0

Each item has its command in [PHASE0_GUIDE.md](PHASE0_GUIDE.md).

| Question | How | Recorded in |
| --- | --- | --- |
| Bitrate of both streams at night (infrared) | `camcheck --tapo <ip> --user <account> --seconds 60` at night | Camera checks |
| The other cameras' keyframe interval, B-frames and sign-in | same, once per camera | Camera checks |
| Full SDP in the unit tests | paste the `camcheck ... --sdp` output into `SdpTest` | Camera checks |
| What a third RTSP viewer gets (error code or refused connection) | `camcheck ... --parallel 3` while nothing else watches | Viewer limit |
| Whether special characters in the Camera Account password work | a second camera account with symbols | Camera checks |
| How long the other TVs' decoders hold frames | the player's delay test, low-latency decoding on and off | Delay |
| Whether contrast matches the Tapo app | side-by-side photo | Notes |
| Behaviour in Privacy Mode and during a firmware update | play, switch Privacy Mode on, watch the error | Notes |

## Sources

- TP-Link: [View Tapo cameras over RTSP/ONVIF](https://www.tp-link.com/us/support/faq/2680/), [Tapo RTSP/ONVIF FAQ](https://www.tp-link.com/en/support/faq/4465/), [Tapo C320WS specifications](https://www.tp-link.com/us/home-networking/cloud-camera/tapo-c320ws/)
- [TP-Link community reply on Tapo stream limits](https://community.tp-link.com/en/business/forum/topic/643532)
- ITU-T H.264 Table A-1 (level limits) and Annex E (VUI semantics, inferred `max_num_reorder_frames`)
- [Media3 RTSP guide](https://developer.android.com/media/media3/exoplayer/rtsp) and the Media3 1.11.1 source of `RtspMediaTrack` (two parameter sets, `a=control`)
