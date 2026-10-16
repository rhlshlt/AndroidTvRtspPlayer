# Phase 0 results

Fill this in while you follow [PHASE0_GUIDE.md](PHASE0_GUIDE.md); the Gate A review at the end
decides the engine and the device tiers. Keep raw files in `results/` (ignored by git) and copy
the numbers here.

| Item | Value |
| --- | --- |
| Tested by | |
| Dates | |
| App version and variant | 0.1.0-phase0, `perf` |
| Lab PC | |

## Cameras

| Camera | Location | Hardware version | Firmware | Connection | Camera Account has symbols? |
| --- | --- | --- | --- | --- | --- |
| | | V1 / V2.20 | | Wi-Fi / Ethernet | |

## Camera checks (`camcheck`)

One row per camera and stream; "Engines" is the Authentication section's verdicts.

| Camera | Stream | Result | Engines (Media3 / rtsp-client) | Keyframe interval (s) | B-frames | fps | Video kbit/s day | Video kbit/s night | First keyframe after PLAY (ms) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| | stream1 | | | | | | | | |
| | stream2 | | | | | | | | |

- Full SDP captured and added to `core/rtsp` tests: yes / no
- Third viewer (`--parallel 3`) gets: error code, and whether the first two keep playing:
- Special characters in the password: work / fail (which characters):

## Reference TVs (device profile)

| TV | Model | Android | SoC | RAM (MB) | Best H.264 decoder | Max level | Instances | Low latency | Suggested tier |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Budget | | | | | | | | | |
| Mainstream | | | | | | | | | |
| High-end | | | | | | | | | |

## First-frame time

Median and slowest of 10 restarts, in ms.

| TV | Engine | stream1 median | stream1 max | stream2 median | stream2 max | Camera switch median |
| --- | --- | --- | --- | --- | --- | --- |
| | Media3 | | | | | |
| | rtsp-client-android | | | | | |

## Glass-to-glass delay

Median of 5 photos, in ms (camera filming the TV's clock).

| TV | Media3 Balanced | Media3 Low latency | Media3, low-latency decoding off | rtsp-client-android | rtsp-client-android + SPS rewrite |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

## Multi-view capacity (test lab)

"Smooth" means every tile plays at 15 fps with under 1% dropped frames for two minutes.

| TV | Engine | Stream | Tiles tried | Tiles playing | Total fps (target 15 × tiles) | Dropped/s | App CPU % | Smooth? |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| | Media3 | sub | 16 | | | | | |
| | Media3 | main | 4 | | | | | |

Largest smooth layout per TV:

| TV | Sub-streams (max tiles) | Main streams (max tiles) | Tier to use |
| --- | --- | --- | --- |
| | | | |

## Other checks

| Check | Result |
| --- | --- |
| Camera unplugged 10 s: what each engine shows, time to error | |
| Camera unplugged 2 min | |
| TCP vs UDP on Wi-Fi | |
| Audio (G.711) on both engines | |
| Privacy Mode | |
| Contrast versus the Tapo app | |

## Gate A review

- [ ] Both streams of every camera play on every reference TV with at least one engine
- [ ] Both engines sign in to the cameras, or the failing one is ruled out
- [ ] Keyframe interval, B-frames and bitrates are known for every camera
- [ ] First-frame time and delay are measured per TV and engine
- [ ] Tile limits on stream1 and stream2 are measured per TV and the tier table is updated
- [ ] LibVLC fallback: needed / not needed

Decisions taken (copy them to [DECISIONS.md](DECISIONS.md) and the plan):

| Decision | Outcome | Evidence |
| --- | --- | --- |
| 1. Player engine | | |
| Tier values (threads, tiles, probe parallelism) | | |
| Low-latency decoding and buffer profile defaults | | |
