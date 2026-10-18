# Android TV RTSP Player — Implementation Plan

> Exported from the plan's Claude Doc on 2026-10-18 (revision 130). The [live document](https://claude.ai/code/artifact/b64189f7-8fc3-49f6-8f9a-e5f4b7d19196) is the source of truth; diagrams are redrawn here as Mermaid and text.

Oct 15, 2026 · @rohit

## Summary

Build a Kotlin and Jetpack Compose for TV app on Media3 ExoPlayer 1.11.1, where each stream is a `StreamSession` that owns its connection check, reconnects, LIVE state and buffer limits. It's planned for one experienced Android developer and takes about 15 weeks. Your C320WS streams already match what Media3 supports, so the biggest remaining risks are decoder capacity and delay on the TV, which Phase 0 measures before any feature work. Tapo's two-viewer RTSP limit and fixed stream1/stream2 URLs also shape the add-camera and multi-view design.

| Area | Decision | Why |
| --- | --- | --- |
| Player engine | [Media3 ExoPlayer 1.11.1](https://developer.android.com/jetpack/androidx/releases/media3) with the RTSP module, behind a `StreamEngine` interface | Hardware decoding through MediaCodec; recent releases fixed RTP timestamp wrap and H.264/H.265 packet parsing |
| Fallback engine | LibVLC, added only if Phase 0 finds cameras Media3 can't play | The [Media3 RTSP guide](https://developer.android.com/media/media3/exoplayer/rtsp) lists H.264 (with SPS/PPS in the SDP), AAC and AC-3 |
| UI | Compose + [Compose for TV](https://developer.android.com/jetpack/androidx/releases/tv) (tv-material 1.1.0) + `media3-ui-compose` surfaces | Current TV toolkit; a SurfaceView is the cheapest surface per tile |
| Secure storage | AES-256-GCM key in Android Keystore; the whole stream list encrypted in one DataStore file | [EncryptedSharedPreferences is deprecated](https://developer.android.com/reference/kotlin/androidx/security/crypto/package-summary) |
| Multi-view | Fixed grid of SurfaceView tiles fed by camera sub-streams | Sixteen HD decodes exceed what most TV chips can do |
| Threads | Shared playback threads sized per device tier; capped coroutine dispatchers | Thread count stays bounded at 16 streams |
| SDK levels | minSdk 30, targetSdk 36 | Android 11+ guarantees the low-latency decoding API (decision 3); a current targetSdk keeps platform behaviour up to date, even for a sideloaded app |

## Phase 0 status

Phase 0's code is merged into main and builds on GitHub Actions, and its RTSP core passes 76 unit tests; the first TV and camera are measured, and the test-lab capacity test and other Gate A checks are next. Everything is on the main branch of the [AndroidTvRtspPlayer repository](https://github.com/rhlshlt/AndroidTvRtspPlayer), with the project documents under `docs/`.

| Deliverable | Where in the repo | Status |
| --- | --- | --- |
| Project setup: Gradle 9.7.1, AGP 9.4.1, Kotlin 2.4.20, five modules, CI | `settings.gradle.kts`, `gradle/`, `.github/workflows/` | Builds on GitHub Actions: unit tests, debug and perf APKs, camcheck; open it in Android Studio Quail 4 (2026.1.4) or newer |
| RTSP core: URL parsing, Digest and Basic auth, SDP and SPS parsing, camera check with stream analysis | `core/rtsp` | 76 unit tests pass: RFC 2617/7616 vectors, your C320WS SPS/PPS, real FFmpeg RTP captures |
| `camcheck` desktop tool: handshake, both engines' auth rules, keyframe interval, B-frames, bitrate, viewer limit | `tools/camcheck` | Run end to end against a scripted camera |
| Engines behind `StreamEngine`: Media3 with low-latency decoding, rtsp-client-android | `core/player` | Builds in CI; needs a TV |
| TV app: player with metrics and a millisecond clock, 1–16 tile capacity test, camera check, device profile, CSV logs | `app`, `core/perf` | Builds in CI; needs a TV |
| Test lab: MediaMTX with Digest, C320WS-shaped clips, live clock, paths limited to 2 viewers | `testlab` | Clip and publish commands verified with FFmpeg 6.1 |

Found while building:

- Both engines answer Digest with MD5 and no qop, and Media3 needs `Digest realm="…", nonce="…"` in that order. `camcheck` tests your camera's challenge against both engines' rules.
- Media3 needs exactly two `sprop-parameter-sets` entries, SPS first, and an `a=control` line per track. Your C320WS fmtp lines meet the first rule.
- Media3 gets low-latency decoding through a wrapped codec adapter factory; requirement 7 now says so.
- Android 17 makes local-network access a runtime permission for apps targeting API 37. The app stays on targetSdk 36, where access is granted implicitly, until that prompt is built.
- rtsp-client-android ships a class based on Moonlight's `MediaCodecHelper`, and Moonlight is GPL-3.0. That's fine for private installs; review it before any wider distribution.
- Each GitHub Actions run signed the APKs with a new random debug key, so a new build couldn't update the installed app. Debug and perf builds now share a public test key in the repository; Phase 1 must switch to a private key before it saves camera passwords, because an app signed with the public key could replace this one and read its data.

First camera check, Oct 16 (one C320WS in daylight, `camcheck` from a PC, 60 s per stream):

| Measured | stream1 | stream2 |
| --- | --- | --- |
| Sign-in | Digest MD5, realm "TP-Link IP-Camera", no qop; both engines OK | Same |
| Video | H.264 Main, Level 5.0, 2560 × 1440, 15.0 fps | H.264 Main, Level 2.2, 640 × 360, 15.0 fps |
| Keyframe interval | 2.00 s, steady | 2.00 s, steady |
| B-frames | None in 899 frames | None in 900 frames |
| Video bitrate (day) | 1,481 kbit/s; keyframes about 176 KB | 125 kbit/s; keyframes about 28 KB |
| First keyframe after PLAY | 62 ms | 55 ms |
| Largest arrival jitter | 496 ms | 552 ms |

- Both engines can sign in, so decision 1 rests on playback quality alone.
- With no B-frames, both low-latency options are safe: Media3's low-latency decoding and rtsp-client-android's SPS rewrite.
- The camera opens each session with a keyframe within about 60 ms, so switching cameras need not wait out the 2 s keyframe interval. Repeated runs and the TV's first-frame times will confirm it.
- About 0.5 s of arrival jitter on both streams, even at 125 kbit/s, points to Wi-Fi rather than bandwidth. An unbuffered engine may look jerky, while Media3's 0.5 s start buffer should absorb it.
- The full SDP has an `a=control` line per track and two `sprop-parameter-sets` entries, as Media3 needs. Still to measure: the night bitrate, the viewer limit, and jitter with a wired connection.

First TV, Oct 18: Panasonic 32-inch (model SCBC ATV R1), Android 11, Realtek hardware decoder `OMX.realtek.video.decoder`. One pass per setting with SPS rewrite off; delay read from the camera's on-screen time, which has 1 s resolution:

| Engine | Stream | Decoder | Frame rate | First frame | Behind live |
| --- | --- | --- | --- | --- | --- |
| Media3 | stream1 | Android software decoder | 3.9 fps, 2,094 dropped | 3.8 s | About 4–5 s |
| Media3 | stream2 | Realtek hardware, low latency off | 15.0 fps, 0 dropped | 0.89 s | 1.9–2.9 s (989 ms buffered) |
| rtsp-client-android | stream1 | Software, after the hardware decoder failed | 14.9 fps | 3.2 s | 1.5–2.5 s (decoder 918 ms) |
| rtsp-client-android | stream2 | Realtek hardware | 14.9 fps | 0.98 s | 0.5–1.5 s (decoder 589 ms) |

- This TV's hardware decoder can't decode stream1 (2560 × 1440, Level 5.0); both engines fall back to Android's software decoder. Full screen here means stream2, or stream1 decoded in software by rtsp-client-android at a high CPU cost.
- The Realtek decoder has no low-latency mode, so it holds about 0.5 s of frames because of the missing reorder limit. rtsp-client-android's SPS rewrite should remove that; Media3 has no equivalent yet.
- rtsp-client-android gave the lowest delay, and Media3 the steadiest playback, with no dropped frames on stream2.

- Left running, Media3 on stream1 fell 1 min 45 s behind live: 97.6 s of video piled up while the software decoder showed 0 fps, and memory kept growing. RTSP gives the camera no signal to slow down, so requirement 7's latency guard must cap the backlog, and devices that can't decode stream1 in hardware must get stream2.

- Device profile: 4 CPU cores, 1.5 GB RAM, a 1280 × 720 panel. The Realtek H.264 decoder goes up to 1920 pixels wide at Level 5.1, runs 2 instances and has no low-latency mode. Provisional tier: LOW.
- With 2 hardware instances, only 2 tiles decode in hardware; further tiles fall back to the software decoder, rated at about 4 stream2 tiles. The multi-view test measures the real limit.
- On a 720p panel, stream1's detail can't be shown anyway. A main stream of 1920 pixels wide or less would decode in hardware here.
- SPS rewrite on: rtsp-client-android's software decoding of stream1 dropped from 918 to 658 ms of decoder delay, at 12.8 fps.
- Media3 with the Low latency buffer on stream2: 15 fps, no dropped frames, about 1–2 s behind live.
- Outdoor door, over the internet through a port forward: Media3 on stream2 showed its first frame in 1.2 s, at 15 fps with no dropped frames, 0.8–1.8 s behind live.

SPS rewrite and multi-view on the same TV, Oct 18 (stream2 unless noted):

- rtsp-client-android with SPS rewrite: the Realtek decoder's delay fell from 589 to 259–273 ms, the first frame came in 584 ms, and the picture was under 1 s behind live, the lowest so far. Outdoor door over the internet: first frame 686 ms, decoder 274 ms.
- 2 tiles with Media3: both on the Realtek decoder at 15 fps each, with no dropped frames in runs of up to 161 s, but only one of the two showed on screen. An evening run started only one of the two tiles; the label bug hid the other tile's error.
- 4 tiles: 2 Realtek and 2 software decoders, 15 fps each, no dropped frames and no thermal throttling. One Realtek tile stayed black while decoding 15 fps, as in the 2-tile run: this TV shows only one hardware-decoded SurfaceView video at a time. The next build tests TextureView tiles, and a software decoder for every tile but one.
- Tile labels drawn before the last video disappeared; the next build draws labels after all videos.
- App CPU covers only the app's own process, as a share of one of the 4 cores; decoding runs in Android's media service. It was about 48% with nothing playing, mostly the clock redrawn every frame, plus about 15% per stream and 10% per extra tile. The total load needs `adb shell top`.
- Left for 12 minutes, Media3 on stream1 stalled at 0 fps and stored 617 s of video. Its Java heap grew from 10 to 129 MB, about 10 MB a minute, against a 192 MB limit, so the latency guard must restart the player long before memory runs out.
- The next build accepts camera addresses with a port, reports low-latency decoding truthfully, redraws the clock 10 times a second, adds Media3's latency guard, fixes the multi-view labels, and adds TextureView and software-decoder switches.

Long run and evening checks, Oct 18:

- rtsp-client-android with SPS rewrite played the Front door's stream2 for about 11 hours, with one short break to switch streams: 14.9 fps in every hour (53 of 39,858 one-second samples under 10 fps), no errors or reconnects, Java heap flat at about 7 MB, PSS 34–52 MB, no thermal throttling.
- The camera check on the TV at night (Front door, 20:00): stream1 1,085 kbit/s and stream2 144 kbit/s, keyframes every 2.00 s, no B-frames, arrival jitter 168 and 62 ms on the TV's network. The real SDP has only a video and an audio track.
- Evening 2-tile runs: only one of the two hardware decoder instances was free, so the right tile played on the software decoder. The left tile got the hardware decoder but rendered no frame in 12 minutes, while the same camera played on a software decoder in a 4-tile run. Something outside the test held the other instance, possibly a decoder left over from the long player session. Reinstalling the app clears anything the app held, and `adb shell dumpsys media.resource_manager` names the holder if it happens again.

To reach Gate A:

1. Open the project in Android Studio, build, and install the `perf` variant on each reference TV.
2. Run `camcheck --tapo <ip> --user <camera account>` for each camera: keyframe interval, B-frames, bitrates by day and night, and engine sign-in.
3. On each TV, save the device profile, then compare both engines on stream1 and stream2: first-frame time, delay against the on-screen clock, and low-latency decoding on and off.
4. Run the 1–16 tile capacity test against the test lab, then the 2-viewer limit against a real camera.
5. Record everything in `docs/PHASE0_RESULTS.md` and hold the Gate A review.

## Assumptions and scope

The plan assumes TP-Link Tapo C320WS outdoor cameras on the same LAN or a VPN, streaming 2K H.264 over RTSP to Android TV or Google TV devices running Android 11 (API 30) or newer.

- **Cameras:** `rtsp://` URLs on port 554: stream1 (main) and stream2 (sub-stream), signed in with a per-camera Camera Account (see the Tapo camera profile below).
- **Reference devices:** a 2 GB-RAM budget box, a mainstream Google TV streamer and a high-end box, all on Android 11 or newer; every performance target is checked on all three.
- **Distribution:** private installs by ADB or APK file (decision 4); the plan still follows Play's rules, so a store listing stays possible later.
- **No backend:** no accounts, cloud relay or analytics, so URLs never leave the TV.
- **Out of scope for v1:** recording, PTZ control, two-way audio, motion alerts, ONVIF discovery and casting.

Effort figures assume one experienced Android developer with some media background; treat them as estimates.

## TP-Link Tapo camera profile

Your cameras are TP-Link Tapo, which fixes the stream URLs, adds a per-camera account step, and caps RTSP viewers at about two per camera. Each row shows what a Tapo fact changes in this plan.

| Tapo behaviour | What it changes in the plan |
| --- | --- |
| Streams are `rtsp://<ip>:554/stream1` (high quality) and `/stream2` (standard); dual-lens models use `/stream6` and `/stream7` for the telephoto lens | Adding a camera needs only its IP and camera account; the app fills main = stream1 and sub = stream2 (requirement 1) |
| RTSP signs in with a separate **Camera Account**, created per camera in the Tapo app (Device Settings → Advanced Settings → Camera Account), 6–32 characters | A first-run checklist explains it; the form checks the length; special characters are tested in Phase 0, since TP-Link's troubleshooting suggests removing them |
| TP-Link support says a camera serves 2 RTSP streams plus 2 app live views at once, and Tapo Care takes one stream | The TV opens at most one session per camera; maximize falls back to break-before-make when no second slot is free; a CameraBusy error (requirements 3, 4, 9) |
| Tapo Care, microSD/Hub recording and NVR/NAS/ONVIF recording can't all run at once; with all three on, NVR/NAS/ONVIF recording is switched off | Phase 0 checks whether plain RTSP viewing is affected too; the busy and offline help text names this setting |
| Observed on C210/C310: H.264 video, stream1 1920 × 1080 at 15–20 fps, stream2 640 × 360, G.711 A-law (PCMA) audio at 64 kbps | Media3's RTSP module has H.264, H.265 and PCMA payload readers; stream2 is close to the 16-view's 480 × 270 tiles, so little decode is wasted |
| On some models, stream1 resolution follows the Tapo app's video quality setting | The checklist asks for "Best Quality" so full screen gets full resolution |
| Battery and solar models (for example C425, C460, C660, C645D, D230) generally don't support RTSP; D235, D225 and TD25 do only when hardwired in always-on mode | Support wired models only; the Refused and Timeout messages mention this |
| ONVIF Profile S on port 2020, discoverable on the same subnet | Optional for v1.1: find cameras by ONVIF discovery instead of typing IP addresses |
| IP addresses come from the router's DHCP | Recommend a DHCP reservation per camera; a changed IP shows as "Camera isn't responding" with an Edit shortcut |
| TP-Link recommends RTSP only on trusted, encrypted local networks, and a VPN for remote viewing | Matches the security checklist: no port forwarding |

Still to measure on your cameras in Phase 0:

- The keyframe interval (TP-Link documents no setting for it).
- How long each TV's decoder holds frames before showing them.
- Whether either stream uses B-frames (none expected); without them, declaring no reordering is safe.
- Whether contrast matches the Tapo app, since the SPS marks the video as full range.
- How RTSP behaves in Privacy Mode and during firmware updates.
- Both streams' bitrates, by day and at night.

Sources: TP-Link's [RTSP/ONVIF setup guide](https://www.tp-link.com/us/support/faq/2680/) and [RTSP/ONVIF FAQ](https://www.tp-link.com/en/support/faq/4465/), a [TP-Link support reply on stream limits](https://community.tp-link.com/en/business/forum/topic/643532), [ffprobe output for C210/C310](https://github.com/blakeblackshear/frigate/issues/9492) in a Frigate issue, and Media3's [RtpPayloadFormat](https://github.com/androidx/media/blob/release/libraries/exoplayer_rtsp/src/main/java/androidx/media3/exoplayer/rtsp/RtpPayloadFormat.java).

### Your camera: Tapo C320WS

The C320WS streams 2560 × 1440 H.264 at 15 fps over 2.4 GHz Wi-Fi or 10/100 Ethernet. With it, the heaviest layout is the full-resolution 4-view, not the 16-view.

| Spec | Value | Plan impact |
| --- | --- | --- |
| stream1 | 2560 × 1440 at 15 fps, H.264 (confirmed on your camera) | About 0.9 of a 1080p30 decode per camera |
| stream2 | 640 × 360 at 15 fps, H.264 (confirmed on your camera) | Matches the loads below; close in size to the 16-view's 480 × 270 tiles |
| SDP and SPS | Both streams: H.264 Main profile, progressive, 1 reference frame, SPS/PPS sent in the SDP (sprop-parameter-sets), packetization mode 1; stream1 is Level 5.0, stream2 Level 2.2; checked on your camera | Meets Media3's SPS/PPS requirement on both streams, so neither needs a fallback engine |
| Decoder level | 2560 × 1440 needs an H.264 decoder that supports Level 5.0; 1080p-only TV boxes often stop at Level 4.2. stream2 needs only Level 2.2, which any decoder handles | The profiler checks this at launch; if unsupported, full screen uses stream2 and says why. Prefer 4K-capable boxes, which usually reach Level 5.0 or higher |
| Frame buffering | Neither stream's SPS sets a reorder limit, so some decoders hold frames before showing them: up to 7 on stream1 (about 0.47 s at 15 fps) and 8 on stream2 (about 0.53 s) | Phase 0 measures the delay on both engines. With minSdk 30, both can request Android's low-latency decoding mode; if delay remains, rtsp-client-android can rewrite the SPS to declare no reordering, which is safe only if the streams have no B-frames |
| Network | 802.11b/g/n Wi-Fi on 2.4 GHz, or a 10/100 Mbps Ethernet port; 9 V DC power, no PoE | Prefer Ethernet; RTSP over TCP by default on Wi-Fi |
| Audio | Built-in microphone and speaker; RTSP audio is G.711 A-law (PCMA), mono, 8 kHz, confirmed on your camera | Media3 reads PCMA; audio on the maximized stream; no two-way audio, as planned |
| Hardware versions | V1 and V2.20 | Record version and firmware for every test run |

| Layout | Streams used | Decode load | In 1080p30 decodes |
| --- | --- | --- | --- |
| 1-view | 1 × stream1 | 55 Mpx/s | 0.9 |
| 2-view | 2 × stream1 | 111 Mpx/s | 1.8 |
| 4-view, mid and high tier | 4 × stream1 | 221 Mpx/s | 3.6 |
| 4-view, low tier | 4 × stream2 | 14 Mpx/s | 0.2 |
| 8-view (1 + 7) | 1 × stream1 + 7 × stream2 | 79 Mpx/s | 1.3 |
| 16-view | 16 × stream2 | 55 Mpx/s | 0.9 |

A 4K30 decoder handles about 249 Mpx/s, so the full-resolution 4-view uses roughly 90% of one. The 16-view is light on throughput; its real limit is how many decoder instances the TV allows at once.

Sources: TP-Link's [C320WS specifications](https://www.tp-link.com/us/home-networking/cloud-camera/tapo-c320ws/) and Synology's [C320WS compatibility entry](https://www.synology.com/en-global/compatibility/camera/36935), which lists H.264, 2560 × 1440 and two streams.

## Architecture

The UI only observes state: a `PlaybackOrchestrator` creates and limits `StreamSession`s, and each session owns one player plus its probe, reconnect loop and watchdog.

```mermaid
flowchart TB
    UI["Screens + ViewModels<br/>stream list, add/edit, full-screen player, multi-view grid"]
    Repo["StreamRepository<br/>saved streams as a Flow"]
    Orch["PlaybackOrchestrator<br/>pool, switching, layouts"]
    Gov["Profiler + Governor<br/>tier, degrade, recover"]
    Store["Encrypted DataStore<br/>streams.bin, AES-256-GCM"]
    Session["StreamSession × N<br/>states, watchdog, reconnect"]
    Probe["RtspProbe<br/>OPTIONS, DESCRIBE, SDP"]
    Keystore["Android Keystore<br/>non-exportable AES key"]
    Engine["StreamEngine<br/>Media3 ExoPlayer RTSP, or LibVLC"]
    Factory["PlayerFactory<br/>LoadControl, thread pool"]
    Codec["MediaCodec + SurfaceView<br/>hardware decode, one per tile"]
    UI -- actions --> Orch
    Orch -- state --> UI
    UI --> Repo
    Repo --> Orch
    Gov --> Orch
    Repo --> Store
    Store --> Keystore
    Orch --> Session
    Session --> Probe
    Session --> Engine
    Factory --> Engine
    Engine --> Codec
```

Screens send actions down and read state back; only `StreamSession` touches a player, and only `StreamRepository` touches the encrypted store.

### Tech stack

| Layer | Choice | Note |
| --- | --- | --- |
| Language | Kotlin 2.4.20, built into AGP 9.4.1 | Media3 1.11 is built with Kotlin 2.2.0 |
| UI | Jetpack Compose + `androidx.tv:tv-material` 1.1.0 | TV lazy layouts were removed; use standard Compose lazy lists for menus |
| Video surface | `media3-ui-compose` `PlayerSurface` or `ContentFrame`, SurfaceView type | Media3 1.9.0 worked around an API 34 SurfaceView sizing bug in Compose |
| Playback | `media3-exoplayer` + `media3-exoplayer-rtsp` 1.11.1 | Hardware decoding through MediaCodec |
| Storage | Jetpack DataStore + Android Keystore (AES-256-GCM) | One encrypted file |
| DI and async | Hilt, Kotlin coroutines and Flow | — |
| Debug builds | LeakCanary, StrictMode, Perfetto | Leak, thread and jank checks |

### Modules

- `:app` — activity, navigation, dependency wiring
- `:core:security` — Keystore key, AES-GCM cipher, log redaction
- `:core:data` — `StreamRepository`, encrypted DataStore, settings
- `:core:player` — `StreamEngine`, Media3 engine, `StreamSession`, `RtspProbe`, `ReconnectPolicy`
- `:core:perf` — `DeviceProfiler`, `PerformanceGovernor`, playback-thread pool
- `:core:ui` — TV theme, focus helpers, `LiveBadge`, overlays
- `:feature:streams` — stream list, add/edit, reorder
- `:feature:player` — full-screen player, multi-view grid

### Core components

| Component | Responsibility | Requirements |
| --- | --- | --- |
| `StreamRepository` | Encrypted create/read/update/delete; exposes `Flow<List<StreamConfig>>` | 1 |
| `RtspProbe` | OPTIONS/DESCRIBE handshake, authentication, SDP and decoder checks | 3 |
| `StreamSession` | One player, its state machine, watchdog and reconnect loop | 4, 5, 6 |
| `PlaybackOrchestrator` | Session pool, switching, layouts, maximize, app lifecycle | 2, 9 |
| `PlayerFactory` | Builds ExoPlayer with a shared LoadControl and playback looper | 7, 10 |
| `DeviceProfiler`, `PerformanceGovernor` | Device tier at launch; degrade and recover at runtime | 7, 9, 10 |

### Session states

Every stream moves through the same states; the LIVE badge, overlays and reconnects in later sections all key off them.

```mermaid
stateDiagram-v2
    [*] --> Probing
    Probing --> Connecting: probe ok
    Probing --> Reconnecting: probe failed
    Probing --> Error: auth, path or codec error
    Connecting --> Live: first frame
    Live --> Stalled: no frame for 2 s, error, or stream end
    Stalled --> Live: frames resume
    Stalled --> Reconnecting: at 4 s or on error
    Reconnecting --> Probing: retry after backoff (1 s to 30 s)
    note right of Live: LIVE badge blinks
    note right of Stalled: badge hidden
    note right of Error: waits for Retry or Edit
```

A user pause (not drawn) hides the badge, and resuming re-enters at Probing, because a paused live stream has fallen behind the live edge.

## Open-source building blocks

No open-source project covers all ten requirements on Android TV, so build the planned app from open-source parts rather than forking one app. Each row says what a project gives this plan and how to use it.

| Project | License | What it gives this plan | How to use it |
| --- | --- | --- | --- |
| [Media3 ExoPlayer](https://github.com/androidx/media) | Apache-2.0 | RTSP playback with hardware decoding, including H.264 video and PCMA audio | Main `StreamEngine`; its demo apps show the wiring |
| [rtsp-client-android](https://github.com/alexeyvasilyev/rtsp-client-android) | Apache-2.0 | Unbuffered, low-latency RTSP over TCP or UDP; H.264/H.265, G.711 A-law, Digest auth; minimum API 24 | Second `StreamEngine`, tested against the C320WS in Phase 0; reference for packet handling (requirement 7) |
| [LibVLC for Android](https://code.videolan.org/videolan/vlc-android) | LGPL for the engine; the VLC app itself is GPL | Broad camera compatibility | Fallback engine only if Phase 0 needs it; never fork the VLC app |
| [Cams](https://github.com/vladpen/cams) | MIT per its repository; the [IzzyOnDroid listing](https://apt.izzysoft.de/fdroid/index/apk/com.vladpen.cams?repo=main) says GPL-2.0 or later | Working multi-camera RTSP viewer: camera groups, main/sub-stream switching, TCP/UDP choice; built on LibVLC with Android Views | Reference for multi-view behaviour; confirm the license with the author before copying code |
| [go2rtc](https://github.com/AlexxIT/go2rtc) | MIT | Server with a Tapo source that re-serves each camera over RTSP | Optional, with an always-on box: the TV connects to go2rtc, so Tapo's two-viewer limit no longer applies |

### Not suitable

- **ScottiBYTE MultiView TV client:** its repository has no license file, so the code can't be reused, and it only plays HLS from its own server.
- **VXG-based "RTSP Player" repositories:** built on a trial SDK that stops playback after 2 minutes.
- **damianhoppe/RTSP-Player** (GPL-3.0): Android TV and multi-view support are still on its to-do list.

### How it fits the plan

1. Phase 0 runs Media3 and rtsp-client-android side by side on the C320WS, behind the same `StreamEngine` interface. Compare time to first frame, latency, reconnect behaviour and CPU load, then choose per camera or globally.
2. LibVLC stays in reserve as the fallback engine.
3. Multi-view screens borrow Cams' patterns: groups on sub-streams, and pausing tiles that are off screen. Copy its code only once the license is confirmed.
4. rtsp-client-android needs API 24, which the plan's minSdk of 30 already covers.
5. A NOTICE file lists every dependency's license. Apache-2.0 notices and MIT copyright lines are kept, and LibVLC, if used, stays a separately linked library with its LGPL notice and source offer.

```kotlin
// One interface, so Phase 0 can swap engines per camera
interface StreamEngine {
    val state: StateFlow<EngineState>          // Connecting, Playing, Stalled, Error
    fun start(stream: StreamConfig, useSubStream: Boolean)
    fun stop()
    fun release()
    @Composable fun Video(modifier: Modifier)  // Media3: PlayerSurface; rtsp-client-android: RtspSurfaceView in AndroidView
}
```

## Delivery roadmap

Build in eight phases over about 15 weeks for one developer. Three gates can stop or re-plan the work early if cameras, single-stream stability or 16-tile performance fall short.

| Weeks | Phase | Scope |
| --- | --- | --- |
| 1–2 | **Phase 0 · Foundations and camera spike** | Project setup, RTSP test lab, real-camera compatibility, decoder limits per device |
| | **Gate A** | Every target camera plays and device tiers are measured |
| 3–4 | **Phase 1 · Secure storage and stream list** (req. 1) | Encrypted store, add/edit/reorder screens, URL parsing, Test connection |
| 5–7 | **Phase 2 · Single-stream core** (req. 3, 4, 5, 6) | Probe, session state machine, LIVE badge, retry overlays, auto-reconnect |
| | **Gate B** | One stream survives 12 h with injected faults |
| 8 | **Phase 3 · Stream switching** (req. 2) | Left/Right zapping, make-before-break swap, pre-probe of neighbouring streams |
| 9–10 | **Phase 4 · Multi-view and maximize** (req. 9) | 2/4/8/16 layouts, sub-streams, capacity check, maximize and return |
| 11–12 | **Phase 5 · Memory and threads** (req. 7, 10) | Buffer budgets, latency guard, playback-thread pool, performance governor |
| | **Gate C** | 16 tiles pass a 12 h soak on the mid-tier device |
| 13 | **Phase 6 · TV experience polish** (req. 8) | Focus paths, key map, safe areas, first-run screen, accessibility |
| 14–15 | **Phase 7 · Hardening and release** | Device matrix, 24 h soak, signed APK, install guide and update path |

Each gate is a go/no-go review: missing Gate A adds the LibVLC fallback to the plan, and missing Gate C lowers the tile limits per device tier.

With two developers, the UI track (phases 1 and 6, plus the screens of phase 4) runs beside the engine track after Phase 0, for about 9–10 weeks in total.

## 1. Secure URL management

The whole stream list lives in one AES-256-GCM-encrypted DataStore file whose key never leaves the Android Keystore; screens pass only stream IDs, never URLs. EncryptedSharedPreferences is not used: the androidx `security-crypto` library was [deprecated at 1.1.0-alpha07 in April 2025](https://github.com/ed-george/encrypted-shared-preferences), and its [reference](https://developer.android.com/reference/kotlin/androidx/security/crypto/package-summary) now points to `KeyGenerator` with the AndroidKeyStore.

### Data model

```kotlin
@Serializable
data class StreamConfig(
    val id: String,                     // UUID; the only value passed between screens
    val name: String,
    val mainUrl: String,                // rtsp://host:554/path, credentials stored separately
    val subUrl: String? = null,         // low-resolution stream for multi-view tiles
    val username: String? = null,
    val password: String? = null,
    val transport: Transport = Transport.AUTO,    // AUTO, TCP, UDP
    val engine: EngineChoice = EngineChoice.AUTO, // AUTO, MEDIA3, VLC
    val order: Int,
)
```

### Encryption

```kotlin
class KeystoreCipher(private val alias: String = "stream_store_v1") {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey =
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()   // no user-auth binding: most TVs have no lock screen
                )
                generateKey()
            }

    // Layout: [version][12-byte IV][ciphertext + 16-byte GCM tag]
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        c.updateAAD(aad)
        return byteArrayOf(VERSION) + c.iv + c.doFinal(plain)
    }

    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        require(blob.size > 13 && blob[0] == VERSION) { "Unknown store format" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 1, 12))
        c.updateAAD(aad)
        return c.doFinal(blob, 13, blob.size - 13)
    }

    private companion object { const val VERSION: Byte = 1 }
}
```

A DataStore `Serializer<List<StreamConfig>>` calls `encrypt` in `writeTo` and `decrypt` in `readFrom`, wrapping any decryption failure in `CorruptionException`. A `ReplaceFileCorruptionHandler` then resets the store and shows a one-time notice; there is never a plaintext fallback.

### Steps

1. Define `StreamConfig`, `Transport` and validation: scheme `rtsp` or `rtsps`, non-empty host, port 1–65535, no whitespace, at most 2,048 characters.
2. Implement `KeystoreCipher` with unit tests for round-trip and tamper detection.
3. Implement the encrypted DataStore serializer (file `streams.bin`, AAD `streams.v1`) and the corruption handler.
4. Build `StreamRepository`: `streams: Flow<List<StreamConfig>>`, `add`, `update`, `delete`, `move`.
5. Parse pasted URLs: split `rtsp://user:pass@host:554/path` into URL, username and password (percent-decoded). Rebuild the credentialed URI with `Uri.encode` only when a session starts, because Media3 reads RTSP credentials from the URI.
6. Build the Streams screen (list with status dot, reorder mode, delete confirmation) and the Add/Edit form: Name, URL, Username, Password (masked), and under Advanced the sub-stream URL, transport and engine. Add a **Test connection** button that runs the probe from requirement 3.
7. Harden: exclude `streams.bin` from backup (`dataExtractionRules` and `fullBackupContent`), redact credentials in every log, pass only IDs in navigation, enable R8, and sign every build with a private key kept out of the repository, since the Phase 0 test key is public.

### Tapo quick add

The Add form opens on a **Tapo camera** preset: name, IP address, camera-account username and password, and lens (standard or telephoto). The app builds both URLs, so nobody types an RTSP path with a remote.

```kotlin
fun tapoUrls(ip: String, telephoto: Boolean = false): Pair<String, String> {
    val (main, sub) = if (telephoto) "stream6" to "stream7" else "stream1" to "stream2"
    return "rtsp://$ip:554/$main" to "rtsp://$ip:554/$sub"   // credentials are added at session start
}
```

Check the camera account is 6–32 characters, as the Tapo app requires. Test passwords with special characters in Phase 0, because TP-Link's troubleshooting suggests removing them when a stream won't open. Transport defaults to TCP, which copes better with lossy 2.4 GHz Wi-Fi; UDP stays available per camera. A generic RTSP form stays available under Advanced.

### Edge cases

- Passwords containing `@`, `:` or `/` are percent-encoded when the URI is rebuilt.
- A store restored onto another TV cannot be decrypted, because Keystore keys never leave the device; it takes the reset path.
- Duplicate URLs get a warning, not a block.

### Done when

- [ ] `streams.bin` shows no readable host, path or password when dumped from a debug build
- [ ] Add, edit, delete and reorder work with D-pad, OK and Back only
- [ ] Malformed URLs are rejected with a specific message
- [ ] Flipping one byte of the file is detected and handled without a crash
- [ ] No credential appears in logcat during a full test pass

## 2. Stream switching (Left/Right)

In full screen, Left and Right move to the previous and next saved stream. The current picture stays on screen until the new stream renders its first frame, so a switch never shows black.

### How it works

- **Two player slots.** Slot A keeps playing while slot B connects to the target. On B's `onRenderedFirstFrame`, B becomes visible and A's session stops and returns to the pool.
- **Zapping debounce.** Each press updates a channel banner at once (name, "3 / 12", status dot). Connecting starts only after 350 ms without another press, and a new press cancels the attempt in flight.
- **Pre-warming.** On high-tier devices, after 10 s of stable playback the app probes the next and previous streams and caches the result for 30 s, taking the RTSP handshake out of the switch. Pre-buffering a neighbour would hold one of that Tapo camera's two RTSP slots, so it stays off by default.
- **Keyframes set the floor.** The decoder shows nothing before the next IDR frame. TP-Link documents no I-frame setting for Tapo, so Phase 0 measures each model's interval; it is the floor for switch time.
- **Worth evaluating.** Media3 1.11 published `PlayerPool` and `rememberPooledPlayer` for sliding-window preloading; check in Phase 3 whether they can replace hand-written A/B slots.

```kotlin
// Full-screen player: Left/Right, CH+/CH− and media next/previous all zap
Modifier
    .focusRequester(playerFocus)
    .focusable()
    .onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown || overlayVisible) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionRight, Key.ChannelUp, Key.MediaNext -> { vm.zap(+1); true }
            Key.DirectionLeft, Key.ChannelDown, Key.MediaPrevious -> { vm.zap(-1); true }
            else -> false
        }
    }

// ViewModel
fun zap(delta: Int) {
    val n = streams.size
    if (n < 2) return showHint(R.string.only_one_stream)
    target = (target + delta).mod(n)                    // wraps around in both directions
    banner.value = BannerUi(streams[target].name, target + 1, n)
    zapJob?.cancel()
    zapJob = viewModelScope.launch {
        delay(350)                                      // let rapid presses settle
        orchestrator.switchTo(streams[target].id)       // make-before-break
    }
}
```

### Steps

1. `StreamNavigator`: ordered list from the repository, current index, wrap-around in both directions.
2. Key handling on the player screen; while an overlay has focus, Left/Right move between its buttons instead.
3. Channel banner that hides after 3 s, plus the 350 ms debounce.
4. `PlaybackOrchestrator.switchTo(id)`: take the standby slot, probe, prepare, wait for the first frame (10 s timeout), swap visibility, release the old session.
5. Audio: `mute()` the old player at the key press and `unmute()` the new one at its first frame.
6. If the target fails, show its error overlay (requirement 6) with **Next stream** available; a setting can skip offline streams automatically.
7. Save the last-watched stream ID so the app can resume it on launch.

### Edge cases

- One saved stream: show a hint instead of reconnecting.
- A held key sends repeat events: move the banner only, and connect after release.
- The list changes during playback (a stream deleted or moved): re-find the current stream by ID.

### Done when

- [ ] No black frame between streams
- [ ] Key release to first frame ≤ the camera's measured keyframe interval + 0.5 s on the LAN, logged per switch
- [ ] Ten rapid presses open at most one new connection
- [ ] Never more than two players exist in full-screen mode

## 3. Connectivity check before playback

Before a player is created, a lightweight RTSP probe — TCP connect, `OPTIONS`, `DESCRIBE` with authentication, then an SDP check — answers "can this stream play here?" in under a second on a LAN, and names the exact failure.

The probe is cheaper than preparing a player, separates network faults from decoder faults, and doubles as the health check inside the reconnect loop. It also catches a known Media3 limit: H.264 streams must carry SPS/PPS in the SDP `fmtp` line ([RTSP guide](https://developer.android.com/media/media3/exoplayer/rtsp)), so streams without it are routed to the fallback engine or flagged.

```kotlin
suspend fun probe(cfg: StreamConfig, timeoutMs: Int = 3_000): ProbeResult =
    withContext(probeDispatcher) {                              // IO.limitedParallelism(4)
        if (!network.isConnected.value) return@withContext ProbeResult.NoNetwork
        val uri = URI(cfg.mainUrl)
        // Blocking DNS can't be cancelled, so it runs detached and is awaited with a timeout
        val host = resolveWithTimeout(uri.host, timeoutMs)
            ?: return@withContext ProbeResult.DnsFailure
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, uri.port.takeIf { it > 0 } ?: 554), timeoutMs)
                socket.soTimeout = timeoutMs                        // bounds every read
                val rtsp = RtspIo(socket)                           // small request/response helper
                rtsp.send("OPTIONS", cfg.mainUrl).requireOk()
                var resp = rtsp.send("DESCRIBE", cfg.mainUrl, "Accept" to "application/sdp")
                if (resp.code == 401) resp = rtsp.send(
                    "DESCRIBE", cfg.mainUrl, "Accept" to "application/sdp",
                    "Authorization" to Auth.header(resp.header("WWW-Authenticate"), cfg, "DESCRIBE"),
                )
                classify(resp)                                      // 200 + SDP → codec and decoder checks
            }
        }.getOrElse(::mapException)                                 // refused, timeout, protocol errors
    }
```

| Result | Typical cause | Message on screen | Default action |
| --- | --- | --- | --- |
| NoNetwork | TV offline | TV isn't connected to a network | Wait for the network, then retry automatically |
| DnsFailure | Wrong host name | Can't find host "cam1.local" | Edit |
| Refused | Wrong port, RTSP disabled | Connection refused on port 554 | Edit |
| Timeout | Camera off, firewall | Camera isn't responding | Retry |
| AuthFailed | Wrong credentials (401 after auth) | Wrong username or password | Edit |
| NotFound | Wrong path (404) | Stream path not found | Edit |
| UnsupportedCodec | No decoder for the codec, profile or size | This TV can't decode this stream | Use the sub-stream or fallback engine |
| MissingSpsPps | H.264 SDP without `sprop-parameter-sets` | This stream needs the fallback engine | Switch engine |
| CameraBusy | Tapo viewer limit reached by other RTSP clients or app viewers | Camera is busy — close other viewers | Retry with slower backoff |
| Ok | — | — | Start playback |

### Steps

1. `RtspIo`: request writer (CSeq, User-Agent) and response reader (status line, headers, `Content-Length` body) with 3 s timeouts.
2. Authentication: Basic, and Digest (MD5, `realm`, `nonce`, `qop=auth` with `cnonce` and `nc` when offered).
3. SDP parser: media sections, `a=rtpmap`, `a=fmtp` (SPS/PPS, plus VPS for H.265), `a=control`.
4. Decoder check: map `H264` to `video/avc` and `H265` to `video/hevc`, then ask `MediaCodecList(REGULAR_CODECS).findDecoderForFormat()`, using the resolution from the SPS when available.
5. `ConnectivityMonitor`: a network callback (`registerDefaultNetworkCallback`) feeding a `StateFlow<Boolean>`; the probe returns NoNetwork without opening sockets.
6. Cache Ok results for 30 s per stream so switching back skips the probe.
7. Cap concurrency at four probes so a 16-tile start doesn't flood the network. Each Tapo camera takes only about two RTSP viewers, so the probe disconnects right after DESCRIBE. A session that probes Ok but is refused at SETUP or PLAY maps to CameraBusy.
8. UI: a "Connecting…" overlay naming the current step, and on failure the overlay from requirement 6.

### Done when

- [ ] Each failure in the test matrix (wrong host, port, path and password; camera unplugged; TV offline) shows its own message
- [ ] A reachable LAN stream probes in under 1 s; the worst case stays within the sum of the step timeouts, with the current step on screen
- [ ] StrictMode shows no network access on the main thread and no leaked sockets
- [ ] Unit tests cover Digest auth with the RFC 2617 example and SDP samples captured from your cameras

## 4. Auto-reconnect

Every session runs a reconnect loop with capped exponential backoff (1 s doubling to 30 s, ±20% jitter). It never gives up on network faults, waits while the TV is offline, and resets after 30 s of stable playback.

| Trigger | How it is detected | Action |
| --- | --- | --- |
| Network error | `onPlayerError` with `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`, `ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT`, `ERROR_CODE_IO_UNSPECIFIED` or an RTSP exception | Reconnect now |
| Stream "ended" | `STATE_ENDED`: Media3 ends an RTSP stream when nothing arrives within `setTimeoutMs` | Reconnect (live cameras never end) |
| Frozen picture | No rendered frame for 2 s → Stalled; still none at 4 s | Hide LIVE, then reconnect |
| Long buffering | `STATE_BUFFERING` for more than 6 s | Reconnect |
| Network lost or back | `NetworkCallback.onLost` / `onAvailable` | Pause the loop / retry at once with backoff reset |
| App in background | `onStop` / `onStart` | Stop every session / restart them |
| Camera busy | Probe Ok, but SETUP or PLAY refused (Tapo viewer limit) | Retry with slower backoff starting at 10 s; the overlay names the viewer limit |
| Not retryable | 401, no decoder, missing SPS/PPS | Error state; user action only |

Media3 1.9 added its own stuck-player detection with long defaults (10 minutes buffering, 10 s ready without progress). Keep it as a backstop behind the faster watchdog above.

```kotlin
class ReconnectPolicy(private val baseMs: Long = 1_000, private val maxMs: Long = 30_000) {
    fun delayFor(attempt: Int): Long {
        val exp = (baseMs shl attempt.coerceAtMost(5)).coerceAtMost(maxMs)
        return (exp * Random.nextDouble(0.8, 1.2)).toLong()        // ±20% jitter
    }
}

// StreamSession runs on Main.immediate because ExoPlayer is bound to the main looper
private fun scheduleReconnect(reason: StallReason) {
    reconnectJob?.cancel()
    reconnectJob = scope.launch {
        player.stop()                                      // drops all buffered data
        while (isActive) {
            state.value = SessionState.Reconnecting(attempt, reason)
            network.awaitConnected()                       // suspends while the TV is offline
            delay(policy.delayFor(attempt++))
            when (val probe = prober.probe(cfg)) {
                is ProbeResult.Ok -> {
                    // Shared Semaphore(4): a router reboot never causes 16 parallel reconnects
                    val started = connectGate.withPermit { startPlayback(probe); awaitFirstFrame(10_000) }
                    if (started) return@launch
                }
                is ProbeResult.Fatal -> { state.value = SessionState.Error(probe); return@launch }
                else -> Unit                               // transient: loop and back off
            }
        }
    }
}
```

### Steps

1. Implement `SessionState` and its transitions exactly as in the session-state diagram.
2. Classify errors as retryable or fatal (table above).
3. Run one 500 ms ticker in the orchestrator that checks every session's last-frame time (from `VideoFrameMetadataListener`) and buffering time, rather than a timer per session.
4. Implement the loop: backoff with jitter, network gate, shared connect semaphore, attempt reset after 30 s of stable playback.
5. Set `RtspMediaSource.Factory.setTimeoutMs()` deliberately, starting at 6–8 s. The RTSP guide notes it also drives end-of-stream detection, so a small value causes false "ended" events on weak Wi-Fi.
6. Keep the last frame on screen, dimmed, with "Reconnecting… attempt 3, next try in 4 s" instead of a black shutter.
7. Record reconnect count, last error and uptime per session for the info overlay.

### Done when

- [ ] Camera unplugged for 10 s, 2 min and 30 min: playback resumes within one backoff step after it returns
- [ ] TV Wi-Fi off and on: every stream resumes, and no attempts are counted while offline
- [ ] Router reboot with 16 tiles: never more than four sessions connect at once
- [ ] 200 forced reconnects: no growth in threads, heap or open sockets

## 5. LIVE indicator

The LIVE badge follows session state, not raw player flags: it appears once frames have flowed for 300 ms and disappears at once on pause, error or a frozen picture.

| Session state | Badge |
| --- | --- |
| Live (playing, last frame under 2 s old) for 300 ms | Blinking red LIVE |
| Buffering inside a live session | Stays 1 s, then hides (rides out micro-rebuffers) |
| Paused by the user | Hidden; grey PAUSED chip |
| Stalled or reconnecting | Hidden; amber RECONNECTING chip |
| Error or offline | Hidden; grey OFFLINE chip |

```kotlin
// ViewModel: debounce so the badge never flickers
val showLive: StateFlow<Boolean> = session.state
    .transformLatest { s ->
        when (s) {
            is SessionState.Live -> { delay(300); emit(true) }
            is SessionState.Buffering -> { delay(1_000); emit(false) }
            else -> emit(false)                            // paused, stalled, error: hide now
        }
    }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

@Composable
fun LiveBadge(visible: Boolean, blinkAlpha: () -> Float, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.live_content_description)
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Row(
            Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .semantics { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).graphicsLayer { alpha = blinkAlpha() }.background(Color.Red, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.live), color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// Hoisted once per screen, so all tiles blink in sync from one clock
@Composable
fun rememberBlinkAlpha(): State<Float> =
    rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
```

Pass `{ blink.value }` as `blinkAlpha`: the value is read inside `graphicsLayer`, so the blink redraws without recomposing the tile.

### Steps

1. Derive `showLive` from `SessionState` with the debounce above.
2. Build `LiveBadge`, the shared blink clock and the three status chips.
3. Place the badge top-left inside the safe area in full screen, and top-left of each tile in multi-view, scaled down for 16 tiles.
4. If the system animation scale is 0 ("Remove animations"), show a steady badge.
5. On OLED TVs, nudge persistent overlays a few pixels every few minutes to limit burn-in.

### Done when

- [ ] The badge appears within 0.5 s of the first frame and disappears within 2.5 s of a camera unplug
- [ ] It is never visible while paused, reconnecting, in error, or after 1 s of buffering
- [ ] Layout Inspector shows no recompositions caused by the blink
- [ ] All tiles blink in sync

## 6. Manual retry

Every failure shows an overlay whose first focused button fixes the most likely cause — **Retry now** for network faults, **Edit stream** for wrong credentials or paths — so recovery is always one OK press away.

| Where | Shows | Buttons, in focus order |
| --- | --- | --- |
| Full screen | Stream name, plain-language reason, auto-retry countdown, small error code | Retry now · Try TCP/UDP · Edit stream · Next stream |
| Multi-view tile | Icon and "Offline — press OK to retry" | OK retries; Menu or long-press opens tile options |
| Multi-view menu | Count of offline tiles | Retry all offline |

```kotlin
@Composable
fun ErrorOverlay(err: UiError, actions: ErrorActions) {
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(err.id) { firstButton.requestFocus() }      // focus lands on the fix
    ErrorCard(title = err.title, detail = err.detail, countdownSec = err.autoRetryInSec) {
        if (err.needsEdit) {
            Button(actions.edit, Modifier.focusRequester(firstButton)) { Text(stringResource(R.string.edit_stream)) }
            Button(actions.retry) { Text(stringResource(R.string.retry_now)) }
        } else {
            Button(actions.retry, Modifier.focusRequester(firstButton), enabled = !err.attemptRunning) {
                Text(stringResource(R.string.retry_now))
            }
            Button(actions.toggleTransport) { Text(stringResource(R.string.try_other_transport)) }
            Button(actions.edit) { Text(stringResource(R.string.edit_stream)) }
        }
        Button(actions.next) { Text(stringResource(R.string.next_stream)) }
    }
}
```

### Steps

1. Map every `ProbeResult` and `PlaybackException` to a `UiError`: title, detail, `needsEdit`, retryable, and a short code such as E-401.
2. Build the full-screen and tile overlays with tv-material buttons; Back closes the overlay and returns to the grid or home.
3. `StreamSession.retryNow()`: cancel the pending backoff, reset the attempt counter, probe immediately.
4. While an attempt runs, show progress on the button and ignore further presses.
5. **Try TCP/UDP** flips the transport for this attempt (`RtspMediaSource.Factory.setForceUseRtpTcp`) and offers to save it if it works.
6. Add **Retry all offline** to the multi-view menu.

### Done when

- [ ] From any error state, the fix is reachable with at most one key press
- [ ] Five quick presses of Retry start exactly one attempt
- [ ] Retry during an auto-retry countdown starts at once, and a failure restarts the countdown

## 7. Memory and buffer optimization

Every buffer is bounded and small: shared `DefaultLoadControl`s give each player a byte budget, nothing is kept behind the playhead, late frames are dropped before decoding, and a latency guard pulls any stream that drifts more than 2 s behind live back to the live edge.

RTSP is push-based: the camera sends at its own rate whether or not the player wants data. LoadControl therefore sets start-up thresholds, rebuffer thresholds and the memory target, while the latency guard is the real protection against backlog. Phase 0 verifies how Media3's RTSP loader behaves when LoadControl stops loading.

| Profile | Min / max buffer | Start after | Restart after rebuffer | Byte budget per player |
| --- | --- | --- | --- | --- |
| Grid tile (sub-stream) | 1.0 s / 2.0 s | 0.3 s | 0.7 s | 1 MB |
| Full screen (main stream) | 1.5 s / 3.0 s | 0.5 s | 1.0 s | 6 MB |
| Smooth (setting for weak Wi-Fi) | 3.0 s / 6.0 s | 1.5 s | 2.5 s | 12 MB |

```kotlin
// One LoadControl shared by all tile players (and another for the two full-screen slots).
// Since Media3 1.9 it budgets each player separately, matched by ExoPlayer.Builder.setName().
val gridLoadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(1_000, 2_000, 300, 700)        // min, max, start, after rebuffer
    .setBackBuffer(0, false)                             // keep nothing behind the playhead
    .setPrioritizeTimeOverSizeThresholds(false)          // the byte budget wins
    .apply { repeat(16) { setPlayerTargetBufferBytes("tile-$it", 1 shl 20) } }
    .build()

// Latency guard, run by the orchestrator's 500 ms ticker for each live session
val aheadMs = player.totalBufferedDuration
when {
    aheadMs > profile.resyncMs -> resyncToLiveEdge()            // > 5 s: stop + prepare drops the backlog
    aheadMs > profile.maxLatencyMs -> player.setPlaybackSpeed(1.08f)  // > 2 s: gentle catch-up
    aheadMs < profile.targetLatencyMs -> player.setPlaybackSpeed(1f)  // < 1 s: back to normal
}
```

### Packet handling

- Media3 drops frames that arrive late, and since 1.9.0 it drops late input before decoding by default (15 ms threshold, for eligible streams), so this path needs no extra code.
- If a fallback or custom pipeline is used, give it the same rules: a bounded ring of pooled `ByteBuffer`s per stream (about 2 s), whole GOPs dropped on overflow so the decoder always restarts at a keyframe, and late output buffers released with `render = false`.
- Never allocate per packet; reuse buffers to keep garbage-collection pauses out of playback.
- Ask every decoder for Android's low-latency mode (`MediaFormat.KEY_LOW_LATENCY`), an API every supported TV has now that minSdk is 30. rtsp-client-android does this by default. Media3 has no switch for it, so wrap the codec adapter factory (`DefaultRenderersFactory.getCodecAdapterFactory()`) and set the key when the decoder reports `FEATURE_LowLatency`, as Phase 0's `LowLatencyRenderersFactory` does. It only helps on decoders that support the mode, so Phase 0 measures the result.

### Steps

1. Compute a media-buffer budget at launch (about a quarter of `ActivityManager.memoryClass`, capped at 64 MB) and split it across the tier's maximum number of players.
2. Create the grid and full-screen `DefaultLoadControl`s with named per-player budgets; name every player after its slot (`tile-0`…`tile-15`, `full-a`, `full-b`).
3. Add the latency guard with hysteresis: catch up above 2 s, return to normal speed below 1 s, resync above 5 s.
4. Pool players: at most the visible tiles plus one standby. Release everything in `onStop`, when leaving a screen, and on `onTrimMemory` (UI hidden or background).
5. Leak hygiene: no Activity context in sessions, listeners removed in `release()`, surfaces detached in `DisposableEffect`; LeakCanary and StrictMode in debug builds.
6. Debug overlay per stream: resolution, fps, buffered ms, dropped frames, decoder name, reconnects; app-wide Java heap, native heap and thread count.

### Done when

- [ ] 12 h with 16 tiles: Java and native heap flat (±10%) after warm-up, and thread count constant
- [ ] The full-screen stream stays within 2 s of live except while recovering
- [ ] 200 switch and maximize cycles return the player count to baseline; LeakCanary reports nothing

## 8. TV-friendly interface

Everything works with a five-key remote (D-pad, OK, Back), focus is always visible, and resuming the last view takes one press from launch.

### Screens

1. **Home** — Continue (last single stream or multi-view), Multi-view (2, 4, 8, 16), Streams (cards with an online dot), Settings.
2. **Player** — full-screen video with channel banner, controls bar, info panel and error overlay.
3. **Multi-view** — the grid from requirement 9.
4. **Streams** — list, reorder mode, add/edit form.
5. **Settings** — default layout, transport, buffer profile (Low latency, Balanced, Smooth), start-up behaviour, skip offline streams, audio follows focus, show stream info.

### Remote keys

| Key | Full screen | Multi-view | Lists and forms |
| --- | --- | --- | --- |
| Left / Right | Previous / next stream | Move focus | Move focus |
| Up / Down | Info panel / controls bar | Move focus | Move focus |
| OK | Show or hide controls | Maximize tile (retry if offline) | Select |
| Back | Close overlay, then back to grid or home | Home | Back |
| Menu or long-press OK | Stream options | Tile options | Item options |
| CH+ / CH− | Next / previous stream | Next / previous page | — |
| Number keys | Jump to stream N | — | — |
| Play/Pause | Freeze; resume jumps to live | Freeze all | — |

### Design rules

- Keep text and controls inside overscan-safe margins of 48 dp left and right and 27 dp top and bottom; video may run to the edges.
- Use tv-material `Surface`, `Card` and `Button` focus styles for menus. Give video tiles a focus border instead of scaling, because SurfaceViews don't scale reliably.
- Restore focus to the last item when returning to a screen, give each screen an initial focus target, and test for focus traps.
- Hide overlays after 5 s without input.
- Typing URLs: a `KeyboardType.Uri` field, separate credential fields and paste parsing (requirement 1); an optional QR "add from phone" flow can follow later.
- Keep the screen on only while a stream plays (`FLAG_KEEP_SCREEN_ON`), and clear the flag when playback stops.
- Accessibility: content descriptions such as "Front door, live"; status is never shown by colour alone.
- Manifest: `LEANBACK_LAUNCHER` intent category, a 320 × 180 banner, `android.hardware.touchscreen` not required, `android.software.leanback` required for a TV-only listing.

### Steps

1. Navigation graph and screen scaffolds, with focus handling from day one.
2. TV theme (dark, high contrast) and shared components: banner, overlays, status chips, `LiveBadge`.
3. Key handling per screen (table above) with Compose `onPreviewKeyEvent`.
4. First-run empty state with a large **Add your first stream** button, a one-time hint about Left/Right switching, and a Tapo checklist: camera account created, Best Quality set, DHCP reservation made.
5. A remote-only test pass on each reference device.

### Done when

- [ ] The full script (add stream → watch → switch → multi-view → maximize → retry → edit) works with D-pad, OK and Back only
- [ ] Focus is visible from 3 m on every screen, with no focus traps
- [ ] All text stays inside the safe margins on 720p and 4K TVs
- [ ] The screen never sleeps during playback and does sleep after it stops

## 9. Multi-stream playback

Multi-view is a fixed grid of SurfaceView tiles fed by camera sub-streams and sized by a decoder-capacity check. Selecting a tile enlarges the same session instantly, then upgrades it to the main stream behind the scenes.

Every layout keeps tiles at 16:9; the 8-view is one large tile plus seven small.

```text
 2 streams        4 streams        8 streams (1 + 7)     16 streams
+-------+-------+ +-------+-------+ +-----------+---+  +---+---+---+---+
|       |       | |   1   |   2   | |           | 2 |  | 1 | 2 | 3 | 4 |
|   1   |   2   | +-------+-------+ |     1     +---+  +---+---+---+---+
|       |       | |   3   |   4   | |           | 3 |  | 5 | 6 | 7 | 8 |
+-------+-------+ +-------+-------+ |           +---+  +---+---+---+---+
 side by side      2 × 2            |           | 4 |  | 9 |10 |11 |12 |
                                    +---+---+---+---+  +---+---+---+---+
                                    | 5 | 6 | 7 | 8 |  |13 |14 |15 |16 |
                                    +---+---+---+---+  +---+---+---+---+
                                    large tile on a     4 × 4, sub-streams
                                    4 × 4 grid
```

The 1 + 7 layout keeps every tile at 16:9, while a uniform 4 × 2 grid would letterbox all eight.

| Layout | Tile size at 1080p | Note |
| --- | --- | --- |
| 2 | 960 × 540 | Letterboxed top and bottom |
| 4 | 960 × 540 | Main streams often fine |
| 8 (1 + 7) | 1440 × 810 and 480 × 270 | Every tile stays 16:9 |
| 16 | 480 × 270 | Sub-streams required on most devices |

### Decoder capacity

- At launch, read each H.264/H.265 decoder's `getMaxSupportedInstances()` and its performance points, to estimate how many streams of a given size and frame rate it can decode.
- Per stream, take resolution and frame rate from the probe's SDP/SPS, or from the first `onVideoSizeChanged`.
- If a layout's total load exceeds 80% of capacity, switch tiles to sub-streams. If it still does, say so on screen and suggest a smaller layout instead of failing silently.
- Optional calibration: decode a bundled 10 s clip as fast as possible on first launch and cache the result per build fingerprint, because reported capabilities are sometimes optimistic.

With your C320WS cameras, the 1- and 2-view use stream1 (2560 × 1440 at 15 fps). The 4-view uses stream1 on mid- and high-tier devices and stream2 on low-tier ones. The 8- and 16-view use stream2, except the large 8-view tile, which uses stream1.

```kotlin
@Composable
fun StreamTile(session: StreamSession, blink: () -> Float, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val state by session.state.collectAsStateWithLifecycle()
    Surface(                                                       // androidx.tv.material3
        onClick = onOpen,                                          // OK: maximize, or retry when offline
        modifier = modifier.aspectRatio(16f / 9f),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),   // never scale a video surface
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.border)),
        ),
    ) {
        PlayerSurface(player = session.player, modifier = Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_SURFACE_VIEW)
        LiveBadge(state is SessionState.Live, blink, Modifier.align(Alignment.TopStart).padding(6.dp))
        TileStatus(state)                                          // connecting, reconnecting, offline
    }
}
```

Build the grid from plain `Row`s and `Column`s with weights, not `LazyVerticalGrid`: recycling would destroy surfaces and decoders while tiles are on screen.

### Maximize and return

1. OK on a tile calls `orchestrator.maximize(id)`; the chosen session's player moves to the full-screen surface without reconnecting.
2. If the tile was on a sub-stream, the main stream starts in the standby slot and takes over at its first frame (make-before-break, as in requirement 2). A Tapo camera serves only about two RTSP viewers. If stream1 is refused as busy, the app stops stream2 first and holds its enlarged last frame until stream1 starts.
3. The maximized stream gets audio. Tiles stay muted with the audio track disabled, which also saves decoding.
4. Other tiles keep running for a 15 s grace period so a quick Back is instant, then stop; high-tier devices may keep them running.
5. Left/Right in maximized view switches among the grid's streams.
6. Back restores the grid: tiles restart in waves of four, the stream returns to its sub-stream, and focus lands on the same tile.

### Steps

1. Layout engine for 2, 4, 1 + 7 and 16, with paging (CH+/CH−) when more streams are saved than tiles.
2. `StreamTile` with status overlays, LIVE badge and focus border.
3. Staggered start: the focused tile first, then waves of four, each wave after first frames or 3 s.
4. `DecoderBudget` from the device profile, plus the over-capacity message.
5. Maximize-and-return flow with the grace period and main-stream upgrade.
6. Audio policy: muted tiles, audio on the maximized stream, optional "audio follows focus".

### Done when

- [ ] On the mid-tier device, 16 sub-streams (up to 640 × 360 at 15 fps) and the full-resolution 4-view each play for 1 h with under 1% dropped frames
- [ ] Maximize shows the tile full screen in under 300 ms, and the main stream within 2 s
- [ ] Back restores the grid with focus on the same tile
- [ ] Over capacity, the app explains what it did and suggests sub-streams or a smaller layout

## 10. Thread management

Thread count is bounded by design: players share a small pool of playback threads sized from the device profile, probes and reconnects run on capped dispatchers, and a governor lowers quality before the UI starts dropping frames.

| Work | Runs on | Threads at 16 tiles |
| --- | --- | --- |
| UI, player calls, player callbacks | Main thread (the [ExoPlayer threading model](https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer)) | 1 |
| ExoPlayer playback loops | Shared `HandlerThread`s via `ExoPlayer.Builder.setPlaybackLooper` | 1–4 by tier, not 16 |
| RTSP control and RTP receive | Media3 loader threads per session | Grows with sessions; mostly idle on sockets |
| Video decoding | MediaCodec hardware, outside the app process | One codec instance per stream |
| Probes, DNS, sockets | `Dispatchers.IO.limitedParallelism(4)` | Up to 4 |
| Crypto, SDP parsing, JSON | `Dispatchers.Default` | Shared, one per core |
| Watchdog and latency guard | One coroutine ticker in the orchestrator | None extra (main) |

Hardware decoders already run in parallel. Media3 queues MediaCodec buffers asynchronously on API 31+, and since 1.11 it schedules its playback loop dynamically to cut CPU wake-ups. The app's job is to keep the main thread and the shared playback threads from becoming the bottleneck.

```kotlin
class PlaybackThreadPool(size: Int) {
    private val threads = List(size) { i ->
        HandlerThread("rtsp-playback-$i", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    }
    private val load = IntArray(size)

    @MainThread
    fun acquire(): Int = load.indices.minBy { load[it] }.also { load[it]++ }   // least-loaded thread
    fun looper(slot: Int): Looper = threads[slot].looper
    @MainThread fun release(slot: Int) { load[slot]-- }
    fun shutdown() = threads.forEach { it.quitSafely() }
}

fun buildPlayer(ctx: Context, name: String, looper: Looper, loadControl: LoadControl): ExoPlayer =
    ExoPlayer.Builder(ctx)
        .setName(name)                         // matches setPlayerTargetBufferBytes(name, …)
        .setPlaybackLooper(looper)             // several players share one playback thread
        .setLoadControl(loadControl)
        .setRenderersFactory(DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
        .build()
```

A player's playback looper is fixed when it is built, so rebalancing happens when a session gets a new player (switch or reconnect), never mid-playback.

### Device tiers

Starting values, calibrated on the reference devices in Phase 0:

| Setting | Low | Mid | High |
| --- | --- | --- | --- |
| Playback threads | 1 | 2 | 4 |
| Max tiles on sub-streams | 4 | 16 | 16 |
| Max tiles on main streams | 1 | 4 | 8 |
| Probe parallelism | 2 | 4 | 6 |
| Switching standby | Off | Pre-probe | Pre-probe; pre-buffer off for Tapo |
| Grid while maximized | Stop after 15 s | Stop after 15 s | Keep running |

The tier comes from CPU cores (`availableProcessors`), RAM and the low-RAM flag (`ActivityManager`), decoder limits (instances and performance points) and an optional decode benchmark.

### Runtime governor

- Every 5 s it reads the dropped-frame rate per stream (`AnalyticsListener.onDroppedVideoFrames`), decoder errors, playback-thread lag, UI jank (JankStats), thermal status (`PowerManager`) and memory pressure. Lag is measured by posting a timestamped message to each playback looper; more than 20 ms late means saturated.
- It degrades one step per 10 s: tiles to sub-streams → standby off → an extra playback thread if one is saturated and cores are free → pause the lowest-priority tiles with a visible note.
- It recovers one step after 60 s without trouble.

### Steps

1. `DeviceProfiler` at launch; log the chosen tier and its inputs.
2. `PlaybackThreadPool` and `PlayerFactory`.
3. Dispatchers: IO limited to the tier's probe parallelism; nothing blocking on main (StrictMode in debug).
4. `PerformanceGovernor` with the degrade/recover ladder and hysteresis.
5. Perfetto traces of 1, 4 and 16 tiles on each reference device to set the final tier values.

### Done when

- [ ] Thread count with 16 tiles is stable over 12 h and within the Phase 0 budget
- [ ] App code never blocks the main thread for more than 16 ms (StrictMode, Perfetto)
- [ ] Under forced overload (16 main streams), the governor steps down within 10 s and the UI stays responsive
- [ ] Each reference device logs the expected tier at launch

## Testing and QA

A local RTSP test lab with scripted failures is ready in Phase 0, so every requirement is tested against the same reproducible streams before real cameras and the three reference devices confirm it.

### Test lab

- [MediaMTX](https://github.com/bluenviron/mediamtx) v1.21 as the RTSP server, in Docker or as a plain executable, with Digest users like the Tapo cameras (`testlab/mediamtx.yml`).
- FFmpeg publishing looped clips shaped like the C320WS: `lab/stream1` at 2560 × 1440 and `lab/stream2` at 640 × 360, both 15 fps H.264 Main with no B-frames and G.711 A-law audio. Any number of tiles can read the same path, so one PC feeds the 16-tile test. A `lab/clock` stream shows the PC's time with milliseconds for delay checks. H.265 variants follow if a camera needs them.
- Failure injection: stop and start publishers (camera offline), firewall drops (timeouts), `tc netem` loss and jitter, wrong credentials, and a per-path reader limit (`maxReaders`) set to 2 to imitate Tapo's viewer limit.

Real cameras: your C320WS units on Wi-Fi and on Ethernet, by day and at night (full-color and infrared modes), with hardware and firmware versions recorded for each test run.

```bash
ffmpeg -re -f lavfi -i testsrc2=size=2560x1440:rate=15 \
  -vf "drawtext=text='%{localtime}':fontsize=48:x=40:y=40:fontcolor=white" \
  -c:v libx264 -preset veryfast -tune zerolatency -g 30 -bf 0 \
  -f rtsp rtsp://localhost:8554/cam01
```

| Level | What it covers | Tools |
| --- | --- | --- |
| Unit | URL validation, crypto round-trip and tamper, Digest vectors, SDP parsing, backoff, state machine, governor decisions | JUnit, kotlinx-coroutines-test virtual time |
| Component | Probe against a scripted fake RTSP server; sessions against the test lab | Instrumented tests on a device |
| UI | Focus paths, key handling, overlays, LIVE visibility | Compose UI tests with key input |
| Device | Real cameras and the test lab on the low, mid and high devices | Manual script with logs |
| Soak | 12–24 h, 16 tiles, a failure injected every 5–10 min; heap, native heap, threads, dropped frames and reconnects logged each minute | adb script and an in-app CSV logger |

Track per build: time to first frame, switch time, glass-to-glass latency, dropped-frame rate, memory and thread count.

## Security checklist

Encryption at rest protects saved URLs if the storage is copied; it does not stop someone holding the remote, so an optional PIN and network hygiene complete the picture.

- [ ] AES-256-GCM key generated in Android Keystore, with no user-authentication binding, and StrongBox only when the device has it
- [ ] The whole stream list encrypted as one file: version byte, AAD, fresh IV on every write
- [ ] Stream store excluded from cloud backup and device-to-device transfer
- [ ] Credentials never in logs, crash reports, analytics, navigation arguments or Intent extras
- [ ] Passwords masked; URLs displayed without credentials
- [ ] A decryption failure resets the store with a notice, never a plaintext fallback
- [ ] Digest preferred over Basic authentication, since Basic sends the password base64-encoded with every request
- [ ] Remote cameras reached over a VPN, never by port-forwarding RTSP
- [ ] If cameras require RTSPS, engine support confirmed in Phase 0 (the Media3 RTSP guide doesn't list it)
- [ ] Optional PIN before stream management, or before the whole app
- [ ] Release builds minified and not debuggable; Media3 and other dependencies kept current
- [ ] Optional QR import: one-time token, LAN only, 5-minute expiry, single submission, confirmed on the TV
- [ ] Release APK signed with a private key that is backed up and never shared; Android only installs updates signed with the same key

## Risks and mitigations

Camera compatibility and decoder capacity are the two risks that can change the design, and both are tested in Phase 0 before feature work starts.

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Cameras Media3 can't play: H.264 without SPS/PPS in the SDP, H.265 quirks, RTSPS, multicast | Low for the C320WS: both streams' SDPs carry SPS/PPS (checked) | Phase 0 test on every camera model; the probe flags SDP problems; per-stream LibVLC fallback behind `StreamEngine` |
| 16 simultaneous decodes exceed the TV's hardware | High on low-end devices | Sub-streams in multi-view, capacity check, tier limits, a clear on-screen message |
| Tapo serves only about two RTSP viewers per camera, one fewer with Tapo Care | High: extra viewers are refused | One session per camera from the TV; break-before-make fallback on maximize; a clear CameraBusy message |
| Slow memory growth over days | Medium | Bounded buffers, latency guard, a 12 h soak as a release gate |
| RTP timestamps wrap about every 13 h at 90 kHz | Medium | Media3 fixed wrap handling in 1.9.1; stay on 1.11.1 or newer and soak for 24 h |
| Typing URLs with a remote | Medium (usability) | Separate fields, paste parsing, optional QR import |
| Keystore problems on some vendor firmware | Low | Reset-with-notice path, tested on all reference devices |
| LibVLC adds app size and LGPL obligations | Low to medium | Add it only if Phase 0 needs it; per-ABI App Bundle; licence review |
| Tapo firmware updates change RTSP behaviour | Medium | Record model, hardware and firmware versions; rerun the Phase 0 RTSP suite after each update |
| Tapo Care, microSD and NVR/NAS use together switch off third-party recording | Medium | Phase 0 checks the effect on live viewing; help text tells users to turn one of the three off |
| 2.4 GHz Wi-Fi outdoors drops packets (the C320WS has no 5 GHz radio) | Medium | Ethernet where possible; RTSP over TCP by default; compare TCP and UDP in Phase 0 |
| Stream1 needs an H.264 Level 5.0 decoder; 1080p-only TV boxes may not play it | Medium | Profiler check at launch; full screen falls back to stream2 with a note; prefer 4K-capable boxes |
| Decoders buffer frames because the SPS sets no reorder limit, adding up to about 0.5 s of delay | Medium | Measure in Phase 0; low-latency decoding mode, or rtsp-client-android's SPS rewrite |
| Sideloaded installs don't update themselves | Low | Keep the signing key safe and backed up; reinstall each new APK with ADB; optional in-app check for a newer version |
| Google's developer verification later covers TVs and APK installs | Low | ADB installs stay exempt, per Google's FAQ; a free limited-distribution account covers APK installs on up to 20 devices |
| An engine can't sign in to the camera: Digest header order, qop required, or a non-MD5 algorithm | Unknown until checked: both engines answer only MD5 Digest without qop | camcheck tests the C320WS challenge against both engines' rules in Phase 0; Basic is the fallback the camera may offer |
| Raising targetSdk to 37 turns on Android 17's local-network permission | Low now: targetSdk 36 keeps the implicit grant | Add the Nearby devices permission prompt before raising targetSdk; never request it earlier |
| rtsp-client-android includes a class based on GPL-3.0 code (Moonlight's MediaCodecHelper) | Low for private installs | Review before any wider distribution; prefer Media3 if it meets the Phase 0 targets |

## Decisions to make before development

Most decisions have a recommended default, so Phase 0 can start while they are settled; the camera list is the one input Phase 0 cannot start without.

| # | Decision | Options | Recommended | Status |
| --- | --- | --- | --- | --- |
| 1 | Player engine | Media3 only; Media3 + rtsp-client-android; Media3 + LibVLC fallback; LibVLC only; custom FFmpeg + MediaCodec pipeline | Media3 first, compared with rtsp-client-android on the C320WS in Phase 0; LibVLC only if needed | Open |
| 2 | Cameras to support | TP-Link Tapo C320WS (2560 × 1440, 15 fps, H.264; Wi-Fi or Ethernet) | Tapo C320WS for all cameras; note each unit's hardware version (V1 or V2.20) | Decided |
| 3 | Minimum Android version | API 23 (Media3's floor); API 24 (needed for rtsp-client-android); API 30 (guarantees low-latency decoding) | Decided: API 30 (Android 11). Guarantees the low-latency decoding API; older boxes are out of scope | Decided |
| 4 | Distribution | Google Play TV listing; sideload or device management | Decided: private installs. ADB installs need no developer verification; a free limited-distribution account covers APK installs on up to 20 devices | Decided |
| 5 | 8-stream layout | 1 + 7 (all tiles 16:9); 4 × 2 (letterboxed) | 1 + 7 | Open |
| 6 | Adding URLs | Remote typing; plus QR import from a phone | Remote typing in v1, QR in v1.1 | Open |
| 7 | Grid while maximized | Stop after a grace period; keep running | Stop after 15 s; keep running on high-tier devices | Open |
| 8 | Audio | Always muted; maximized stream only; follows focus | Maximized stream only | Open |
| 9 | App lock | None; PIN for stream management; PIN for the app | PIN for stream management | Open |
| 10 | Team size | 1 developer (about 15 weeks); 2 developers (about 9–10 weeks) | Decided: 1 developer, about 15 weeks | Decided |

## Sources

- [Media3 release notes](https://developer.android.com/jetpack/androidx/releases/media3) — 1.11.1 (Sep 10, 2026); minSdk 23 from 1.9.0; per-player LoadControl budgets; stuck-player detection; late-frame dropping; dynamic scheduling; RTSP fixes
- [Media3 RTSP guide](https://developer.android.com/media/media3/exoplayer/rtsp) — supported formats, UDP and TCP transport, authentication, `setTimeoutMs`, `setForceUseRtpTcp`, `setSocketFactory`
- [ExoPlayer API reference](https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer) — threading model
- [DefaultLoadControl.Builder reference](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl.Builder) — `setPlayerTargetBufferBytes` with player names set by `setName`
- [Compose for TV releases](https://developer.android.com/jetpack/androidx/releases/tv) — tv-material 1.1.0, tv-foundation 1.0.0, removal of TV lazy layouts
- [androidx.security.crypto reference](https://developer.android.com/reference/kotlin/androidx/security/crypto/package-summary) — deprecated classes, Keystore `KeyGenerator` guidance
- [encrypted-shared-preferences README](https://github.com/ed-george/encrypted-shared-preferences) — security-crypto deprecated at 1.1.0-alpha07 in April 2025
- [Google Play target API level requirements](https://support.google.com/googleplay/android-developer/answer/11926878) — new Android TV apps must target API 34 or higher
- [Android developer verification FAQ](https://developer.android.com/developer-verification/guides/faq) — ADB installs need no verification; enforcement starts with phones and tablets in four countries; limited-distribution accounts cover up to 20 devices

* [TP-Link: view Tapo cameras via RTSP/ONVIF](https://www.tp-link.com/us/support/faq/2680/) — stream URLs, camera account, battery models, storage limits, dual-lens paths
* [TP-Link: Tapo ONVIF and RTSP FAQ](https://www.tp-link.com/en/support/faq/4465/) — supported models, ports, remote access
* [TP-Link community reply on Tapo stream limits](https://community.tp-link.com/en/business/forum/topic/643532) — 2 RTSP plus 2 app streams; Tapo Care uses one
* [Frigate issue #9492](https://github.com/blakeblackshear/frigate/issues/9492) — ffprobe of Tapo C210/C310 streams
* [Media3 RtpPayloadFormat source](https://github.com/androidx/media/blob/release/libraries/exoplayer_rtsp/src/main/java/androidx/media3/exoplayer/rtsp/RtpPayloadFormat.java) — RTP payloads Media3 accepts, including PCMA

- [Tapo C320WS specifications](https://www.tp-link.com/us/home-networking/cloud-camera/tapo-c320ws/) — 2560 × 1440, 15 fps, H.264, 2.4 GHz Wi-Fi, 10/100 Ethernet, RTSP and ONVIF
- [Synology compatibility entry for the C320WS](https://www.synology.com/en-global/compatibility/camera/36935) — H.264, 2560 × 1440, two streams, firmware 1.1.20

* [Android local network permission](https://developer.android.com/privacy-and-security/local-network-permission) — enforced on Android 17 for apps targeting API 37; earlier targets keep an implicit grant
* [Android Gradle plugin compatibility](https://developer.android.com/build/releases/about-agp) — AGP 9.4 needs Android Studio Quail 4 (2026.1.4) or newer and Gradle 9.6.0 or newer
* [Gradle release checksums](https://gradle.org/release-checksums/) — SHA-256 of the Gradle 9.7.1 distribution pinned in the wrapper
* [MediaMTX releases](https://github.com/bluenviron/mediamtx/releases) — v1.21.0, the version the test lab pins
* [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android) — GPL-3.0; source of the class rtsp-client-android's `MediaCodecHelper` is based on
