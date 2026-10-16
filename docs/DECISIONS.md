# Decisions

Four of the plan's ten decisions are made; the player engine (decision 1) is the one Phase 0 must
settle. Below the plan's table are the implementation choices made while building Phase 0, each with
its reason, so later phases can revisit them deliberately.

## Plan decisions

| # | Decision | Options | Recommended | Status |
| --- | --- | --- | --- | --- |
| 1 | Player engine | Media3 only; Media3 + rtsp-client-android; Media3 + LibVLC fallback; LibVLC only; custom FFmpeg + MediaCodec pipeline | Media3 first, compared with rtsp-client-android on the C320WS in Phase 0; LibVLC only if needed | Open: Gate A decides |
| 2 | Cameras to support | TP-Link Tapo C320WS (2560 × 1440, 15 fps, H.264; Wi-Fi or Ethernet) | Tapo C320WS for all cameras; note each unit's hardware version (V1 or V2.20) | Decided |
| 3 | Minimum Android version | API 23 (Media3's floor); API 24 (rtsp-client-android); API 30 (guarantees low-latency decoding) | API 30 (Android 11) | Decided |
| 4 | Distribution | Google Play TV listing; sideload or device management | Private installs: ADB installs need no developer verification; a free limited-distribution account covers APK installs on up to 20 devices | Decided |
| 5 | 8-stream layout | 1 + 7 (all tiles 16:9); 4 × 2 (letterboxed) | 1 + 7 | Open |
| 6 | Adding URLs | Remote typing; plus QR import from a phone | Remote typing in v1, QR in v1.1 | Open |
| 7 | Grid while maximized | Stop after a grace period; keep running | Stop after 15 s; keep running on high-tier devices | Open |
| 8 | Audio | Always muted; maximized stream only; follows focus | Maximized stream only | Open |
| 9 | App lock | None; PIN for stream management; PIN for the app | PIN for stream management | Open |
| 10 | Team size | 1 developer (about 15 weeks); 2 developers (about 9–10 weeks) | 1 developer, about 15 weeks | Decided |

## Phase 0 implementation decisions

| ID | Decision | Why | Revisit |
| --- | --- | --- | --- |
| P0-1 | RTSP handling (URLs, auth, SDP, SPS, camera check) lives in a pure Kotlin module, `core/rtsp`, shared by the app and the `camcheck` tool | Runs and is unit-tested on any JVM without an Android device; the same code checks cameras from a PC and from the TV | — |
| P0-2 | Cameras are kept in memory only; nothing is written to storage until Phase 1's encrypted store | Camera passwords must never be stored in plaintext, even in a test build | Phase 1 replaces it with the Keystore-encrypted DataStore |
| P0-3 | A broadcast receiver lets adb add cameras, only from the adb shell (`android.permission.DUMP`). It is declared in the main manifest and enabled only in debug and perf builds through the `adbHookEnabled` manifest placeholder | Typing passwords with a remote for every test run is slow and error-prone; other apps on the TV can't use the hook. A placeholder needs no shared source sets, which AGP 9's built-in Kotlin restricts | Removed in Phase 1, when cameras are saved |
| P0-4 | `targetSdk` 36, `minSdk` 30, `compileSdk` 37 | Targeting 37 turns on Android 17's local-network runtime permission, which would need a "Nearby devices" prompt before any camera works; at 36 access is granted implicitly | Add the prompt, then raise `targetSdk` (Play requires a recent target for any store listing) |
| P0-5 | A `perf` build type: not debuggable, signed with the debug key, with the adb hook | Debuggable builds run slower, so CPU and delay must be measured without debugging | Phase 7 adds the real signing key |
| P0-6 | No Hilt, DataStore or navigation library in Phase 0; a small back stack and singletons instead | Fewer moving parts while the goal is measurement | Phase 1 adds Hilt and DataStore as planned |
| P0-7 | Media3's low-latency decoding is added by wrapping `DefaultRenderersFactory.getCodecAdapterFactory()` and setting `KEY_LOW_LATENCY` when the decoder reports `FEATURE_LowLatency`; a test switch turns it off | Media3 has no option for it, and the C320WS SPS lets decoders buffer up to 7–8 frames | Keep if Phase 0 shows a delay gain |
| P0-8 | Video renders into a plain `SurfaceView` hosted with `AndroidView`, sized to the video's aspect ratio | The cheapest surface per tile, without `media3-ui-compose`, and the same for both engines | Phase 4 may adopt `PlayerSurface` |
| P0-9 | Each engine builds a new player on every start; no pooling | Every start is a clean, comparable measurement | Phases 2 and 5 add pooled players and shared playback threads |
| P0-10 | The camera check behaves like Media3 where it matters: OPTIONS then DESCRIBE, control URLs resolved as Media3 does, PLAY sent to the request URI, Digest answered in RFC 2069 style first | A check that passes should mean the engine will connect | — |
| P0-11 | The check re-creates each engine's `WWW-Authenticate` handling (Media3's regular expressions; rtsp-client-android's search) and reports whether each can sign in | Both engines answer only MD5 Digest without qop, and Media3 needs `realm` before `nonce`; a camera that differs fails silently otherwise | Update when either library changes |
| P0-12 | The test lab uses MediaMTX with Digest only, one path per stream type read by many tiles, and two paths limited to two viewers | Mirrors Tapo's sign-in and viewer limit; one PC can feed 16 tiles | — |
| P0-13 | The Gradle wrapper pins the distribution's SHA-256, and JitPack is limited to the `com.github.alexeyvasilyev` groups | A tampered Gradle download fails the build; no other dependency can come from JitPack | Update the checksum with each Gradle upgrade |
| P0-14 | CI on GitHub Actions runs the unit tests and builds the debug and perf APKs and `camcheck` | Every push proves the project still builds, and the APKs can be downloaded | — |

## License notes

- rtsp-client-android (Apache-2.0) contains `com.limelight.binding.video.MediaCodecHelper`, which its
  source marks as based on Moonlight for Android, a GPL-3.0 project. For private installs this
  doesn't matter; before distributing the app beyond personal use with that engine included, review
  it or ask the library's author. If Media3 meets the Phase 0 targets, the question disappears.
- No license has been chosen for this project yet; until one is, all rights are reserved.
  Third-party licenses are listed in [NOTICE.md](../NOTICE.md).
