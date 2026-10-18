# Phase 0 results

Fill this in while you follow [PHASE0_GUIDE.md](PHASE0_GUIDE.md); the Gate A review at the end
decides the engine and the device tiers. Keep raw files in `results/` (ignored by git) and copy
the numbers here.

So far one camera has been checked from a PC and one TV has been measured; the test lab, the other
reference TVs and the night-time checks are still to do. Cells marked "to fill" need your input;
"–" means not measured yet.

| Item | Value |
| --- | --- |
| Tested by | Project owner |
| Dates | Oct 16, 2026 (camcheck); Oct 17–18, 2026 (first TV) |
| App version and variant | 0.1.0-phase0, `perf`, built by GitHub Actions from `main` |
| Lab PC | Not set up yet |

## Cameras

| Camera | Location | Hardware version | Firmware | Connection | Camera Account has symbols? |
| --- | --- | --- | --- | --- | --- |
| Front door | Home LAN | to fill | to fill | to fill | to fill |
| Outdoor door | Another site, reached through a router port forward (move it to a VPN) | to fill | to fill | to fill | to fill |
| C320WS at 192.168.1.101 (checked with camcheck) | Home LAN | to fill | to fill | Wi-Fi | to fill |

## Camera checks (`camcheck`)

One row per camera and stream; "Engines" is the Authentication section's verdicts. Checked in
daylight from a PC, 60 s per stream.

| Camera | Stream | Result | Engines (Media3 / rtsp-client) | Keyframe interval (s) | B-frames | fps | Video kbit/s day | Video kbit/s night | First keyframe after PLAY (ms) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 192.168.1.101 | stream1 | OK | OK / OK | 2.00 | 0 in 899 frames | 15.0 | 1,481 | – | 62 |
| 192.168.1.101 | stream2 | OK | OK / OK | 2.00 | 0 in 900 frames | 15.0 | 125 | – | 55 |

- Sign-in: Digest with MD5, realm "TP-Link IP-Camera", no qop.
- Largest arrival jitter: 496 ms on stream1 and 552 ms on stream2, which points to Wi-Fi rather
  than bandwidth.
- Full SDP captured (`--sdp`): yes; an `a=control` line per track and two `sprop-parameter-sets`
  entries, as Media3 needs. Not yet added to the `core/rtsp` tests.
- Third viewer (`--parallel 3`) gets: – (four tiles on two cameras, two sessions each, all played)
- Special characters in the password: –

## Reference TVs (device profile)

| TV | Model | Android | SoC | RAM (MB) | Best H.264 decoder | Max level | Instances | Low latency | Suggested tier |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Budget | Panasonic 32-inch, 1280 × 720 panel (build SCBC ATV R1) | 11 (API 30), patch 2023-11-05 | Not reported (Realtek decoder) | 1,536 | OMX.realtek.video.decoder, up to 1920 × 1088 | 5.1 | 2 | No | LOW |
| Mainstream | – | | | | | | | | |
| High-end | – | | | | | | | | |

The Realtek decoder lists Level 5.1 but stops at 1920 pixels wide, so it can't decode stream1
(2560 × 1440); both engines fall back to Android's software decoder `c2.android.avc.decoder`.

## First-frame time

Median and slowest of every start in the logs (restarts, camera and stream switches), in ms, on
the Panasonic TV with the Front door camera.

| TV | Engine | stream1 median | stream1 max | stream2 median | stream2 max | Camera switch median |
| --- | --- | --- | --- | --- | --- | --- |
| Panasonic | Media3 | 1,502 (23 starts) | 3,803 | 1,077 (18 starts) | 2,051 | – |
| Panasonic | rtsp-client-android | 3,239 (8 starts) | 3,568 | 950 (4 starts) | 979 | – |
| Panasonic | rtsp-client-android + SPS rewrite | 3,024 (3 starts) | 3,495 | 664 (2 starts) | 744 | – |

rtsp-client-android's stream1 starts include a failed attempt with the hardware decoder before it
falls back to software, which costs about 2 s.

## Glass-to-glass delay

Read from photos against the camera's own time stamp, which shows whole seconds, so each value is a
range; stream2 on the Panasonic TV.

| TV | Media3 Balanced | Media3 Low latency | Media3, low-latency decoding off | rtsp-client-android | rtsp-client-android + SPS rewrite |
| --- | --- | --- | --- | --- | --- |
| Panasonic | 1.9–2.9 s (about 1 s buffered) | 1–2 s (about 0.9 s buffered) | Same as on: the decoder has no low-latency mode | 0.5–1.5 s (decoder holds 589 ms) | 0–1 s (decoder holds 259–273 ms) |

Over the internet, the Outdoor door camera gave 0.8–1.8 s with Media3 Low latency, and a 686 ms
first frame with a 274 ms decoder hold with rtsp-client-android and the SPS rewrite.

On stream1, decoded in software: rtsp-client-android played 13–15 fps with a 513–970 ms decoder
hold; Media3 managed 4–7 fps and fell behind without limit (see Other checks).

## Multi-view capacity (test lab)

"Smooth" means every tile plays at 15 fps with under 1% dropped frames for two minutes. The test
lab isn't set up yet; these rows are from the two real cameras, two sessions each.

| TV | Engine | Stream | Tiles tried | Tiles playing | Total fps (target 15 × tiles) | Dropped/s | App CPU % | Smooth? |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Panasonic | Media3 | sub | 2 | 2, both hardware | 29.9 | 0 | 70–75 | Yes (runs up to 161 s) |
| Panasonic | Media3 | sub | 4 | 4: 2 hardware, 2 software | 59.6 | 0 | 94 | Frame rate yes, but one hardware tile showed black |
| | Media3 | sub | 16 | | | | | |
| | Media3 | main | 4 | | | | | |

App CPU counts the app's own process only, as a share of one of the TV's 4 cores; decoding runs in
Android's media processes, so the total load needs `adb shell top`. With nothing playing, the app
still used about 48%, most likely for the clock it redrew every frame; the next build redraws the
clock 10 times a second and can hide it.

Largest smooth layout per TV:

| TV | Sub-streams (max tiles) | Main streams (max tiles) | Tier to use |
| --- | --- | --- | --- |
| Panasonic | 2 in hardware; 4 pending the black-tile test | None in hardware | LOW |

## Other checks

| Check | Result |
| --- | --- |
| Media3 when the decoder can't keep up (stream1 in software) | Stalled at 0 fps and stored 617 s of video in 12 minutes; Java heap grew from 10 to 129 MB, against a 192 MB limit. The latency guard now restarts the player |
| Camera over the internet (port forward) | Plays on both engines; first frame 1.0–1.3 s with Media3 |
| Camera unplugged 10 s: what each engine shows, time to error | – |
| Camera unplugged 2 min | – |
| TCP vs UDP on Wi-Fi | – |
| Audio (G.711) on both engines | – |
| Privacy Mode | – |
| Contrast versus the Tapo app | – |

## Gate A review

- [ ] Both streams of every camera play on every reference TV with at least one engine (first TV:
      stream2 in hardware with both engines; stream1 only in software, smoothly with
      rtsp-client-android)
- [x] Both engines sign in to the cameras, or the failing one is ruled out
- [ ] Keyframe interval, B-frames and bitrates are known for every camera (daytime, one camera)
- [ ] First-frame time and delay are measured per TV and engine (first TV done)
- [ ] Tile limits on stream1 and stream2 are measured per TV and the tier table is updated
- [ ] LibVLC fallback: needed / not needed (not needed so far: every stream plays on at least one engine)

Decisions taken (copy them to [DECISIONS.md](DECISIONS.md) and the plan):

| Decision | Outcome | Evidence |
| --- | --- | --- |
| 1. Player engine | Open | rtsp-client-android with the SPS rewrite has the lowest delay (under 1 s) and decodes stream1 in software; Media3 plays most steadily on stream2 but adds about 1 s of buffer |
| Tier values (threads, tiles, probe parallelism) | Open | The Panasonic TV: 2 hardware decoder instances, no stream1 in hardware |
| Low-latency decoding and buffer profile defaults | Open | The Realtek decoder has no low-latency mode; the SPS rewrite cut its hold from about 600 to about 270 ms |
