# AndroidTvRtspPlayer

Android TV RTSP Player: a secure, remote-friendly player for TP-Link Tapo C320WS cameras on Android
TV and Google TV (Android 11 or newer), with stream switching, auto-reconnect and multi-view of up to
16 cameras.

**Status: Phase 0 (foundations and camera spike).** This phase measures whether the cameras play on
each TV, with which engine, at what delay, and how many streams each TV decodes at once. The feature
work of phases 1–7 follows the [implementation plan](docs/IMPLEMENTATION_PLAN.md).

## What's in the repository

| Path | What it is |
| --- | --- |
| [`app`](app) | Phase 0 TV app: full-screen player with metrics and a millisecond clock, 1–16 tile capacity test, camera check, device profile, CSV results |
| [`core/rtsp`](core/rtsp) | Pure Kotlin: RTSP URLs with safe credential handling, Digest and Basic auth, SDP and H.264 SPS/PPS parsing, camera check with live stream analysis; 76 unit tests |
| [`core/player`](core/player) | `StreamEngine` interface with two engines: Media3 ExoPlayer RTSP (with low-latency decoding) and rtsp-client-android |
| [`core/perf`](core/perf) | Device profile (decoders, levels, instances, tier), process sampling, CSV metrics |
| [`tools/camcheck`](tools/camcheck) | Desktop command-line camera check, built on `core/rtsp` |
| [`tools/scripts`](tools/scripts) | Adds a camera to the app over adb without typing the password on the TV |
| [`testlab`](testlab) | MediaMTX test lab with C320WS-shaped streams, a live clock and Tapo's two-viewer limit |
| [`docs`](docs) | Plan, Phase 0 guide, camera profile, test lab, decisions, results template |

## Quick start

Requirements: Android Studio Quail 4 (2026.1.4) or newer, Android SDK Platform 37, a TV with
Android 11 or newer and developer options enabled.

```powershell
git clone https://github.com/rhlshlt/AndroidTvRtspPlayer.git D:\Projects\TvRtspPlayer
cd D:\Projects\TvRtspPlayer
.\gradlew.bat :core:rtsp:test            # unit tests
adb connect <tv-ip>:5555
.\gradlew.bat :app:installPerf           # measurement build on the TV
.\tools\scripts\add-camera.ps1 -Name "Front door" -CameraHost <camera-ip> -User <camera account>
```

Check a camera from your PC first:

```powershell
.\gradlew.bat :tools:camcheck:installDist
.\tools\camcheck\build\install\camcheck\bin\camcheck.bat --tapo <camera-ip> --user <camera account> --ask-password --sdp
```

On Linux or macOS use `./gradlew` and `tools/scripts/add-camera.sh`. The full procedure is in the
[Phase 0 guide](docs/PHASE0_GUIDE.md).

## Documents

| Document | Read it for |
| --- | --- |
| [Implementation plan](docs/IMPLEMENTATION_PLAN.md) | Architecture, the ten requirements, roadmap, risks ([live version](https://claude.ai/code/artifact/b64189f7-8fc3-49f6-8f9a-e5f4b7d19196)) |
| [Phase 0 guide](docs/PHASE0_GUIDE.md) | Setting up, installing on a TV, and every Phase 0 measurement |
| [Tapo C320WS profile](docs/CAMERA_C320WS.md) | The camera's verified stream parameters and what they mean for decoding |
| [Test lab](docs/TEST_LAB.md) | Reproducible RTSP streams and fault injection |
| [Decisions](docs/DECISIONS.md) | Plan decisions and Phase 0 implementation choices |
| [Phase 0 results](docs/PHASE0_RESULTS.md) | Template for the measurements and the Gate A review |
| [Notices](NOTICE.md) | Third-party licenses |

## Security

- Camera passwords are never written to storage in Phase 0; cameras are kept in memory until the
  Keystore-encrypted store of Phase 1.
- `RtspUrl.toString()`, reports, logs and CSV files never contain credentials; engine error messages
  are scrubbed of passwords before display.
- The adb camera hook is enabled only in debug and perf builds and only accepts broadcasts from the
  adb shell.
- The test lab's accounts are lab-only; change them if the lab PC is reachable from other networks.
- Reach cameras over the LAN or a VPN; never port-forward RTSP.

## Building in CI

Every push runs [`.github/workflows/build.yml`](.github/workflows/build.yml): unit tests, the debug
and perf APKs and `camcheck`, kept as a downloadable artifact for 14 days.

## License

No license has been chosen yet, so all rights are reserved. Third-party components and their
licenses are listed in [NOTICE.md](NOTICE.md).
