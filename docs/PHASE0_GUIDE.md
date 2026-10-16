# Phase 0 guide: foundations and camera spike

Phase 0 answers one question before feature work starts: **does every C320WS stream play on every
reference TV, and how many can each TV decode at once?** That is Gate A. This guide takes you from a
fresh checkout to filled-in results in about two weeks of part-time testing; write the answers in
[PHASE0_RESULTS.md](PHASE0_RESULTS.md).

| What Phase 0 measures | Tool | Plan section it settles |
| --- | --- | --- |
| Handshake, sign-in with both engines' rules, SDP/SPS, keyframe interval, B-frames, bitrate | `camcheck` on your PC | Decision 1 (engine), requirements 2 and 3 |
| Decoders, levels and instance limits per TV; a suggested tier | App → Device profile | Requirement 10 tiers |
| First-frame time and switching time per engine | App → Play full screen | Requirement 2 |
| Glass-to-glass delay, with low-latency decoding on and off | App → Play full screen + a photo | Requirements 5 and 7 |
| How many tiles each TV plays smoothly, on sub- and main streams | App → Multi-view capacity test + test lab | Requirement 9, tier table |
| What happens at Tapo's viewer limit, on Wi-Fi loss, and with special characters in passwords | `camcheck --parallel`, the player, a second account | Requirements 1, 3, 4 |

## 1. Set up the project

You need:

- **Android Studio Quail 4 (2026.1.4) or newer** (Rabbit 1 also works). AGP 9.4 needs at least
  Quail 4; Studio brings JDK 21, which is all the build needs.
- **Android SDK Platform 37**: Studio offers to install it on first sync.
- **Git**, and for the test lab **FFmpeg** (`winget install Gyan.FFmpeg`) plus **Docker Desktop** or
  the MediaMTX executable ([TEST_LAB.md](TEST_LAB.md)).

Put the project at `D:\Projects\TvRtspPlayer`:

```powershell
cd D:\Projects
git clone https://github.com/rhlshlt/AndroidTvRtspPlayer.git TvRtspPlayer
```

If you received this work as a zip, extract it into `D:\Projects` so that the folder is
`D:\Projects\TvRtspPlayer` (it contains the `.git` folder, so `git push` works from there).

Open it with **File → Open → D:\Projects\TvRtspPlayer** and let Gradle sync. The wrapper downloads
Gradle 9.7.1 and checks its SHA-256 against the value pinned in
`gradle/wrapper/gradle-wrapper.properties`. Then, from the project folder:

```powershell
.\gradlew.bat :core:rtsp:test           # 76 unit tests, about 10 s
.\gradlew.bat :app:assemblePerf         # app\build\outputs\apk\perf\app-perf.apk
```

Build variants (Build → Select Build Variant):

| Variant | Use it for | Notes |
| --- | --- | --- |
| `debug` | Development | Debuggable, StrictMode on, adb camera hook |
| `perf` | **All Phase 0 measurements** | Not debuggable (debug builds run slower), signed with the debug key, adb camera hook |
| `release` | Not used in Phase 0 | Unsigned until Phase 7 |

If sync can't resolve `com.github.alexeyvasilyev:rtsp-client-android:6.1.1`, open
<https://jitpack.io/#alexeyvasilyev/rtsp-client-android/6.1.1> in a browser so JitPack builds it,
then sync again.

## 2. Prepare the cameras

1. In the Tapo app, open each camera → Device Settings → Advanced Settings → **Camera Account** and
   create a user name and password of 6–32 characters. Use letters and digits first; special
   characters are a separate test (step 9).
2. Set the video quality to **Best Quality**, so stream1 is 2560 × 1440.
3. Reserve each camera's IP address in your router.
4. Note each camera's hardware version (V1 or V2.20) and firmware version from the Tapo app's device
   info, for the results file.
5. Close other RTSP viewers (NVR, Home Assistant, VLC): a Tapo camera serves only about two.

## 3. Check each camera from your PC

`camcheck` connects the way the TV app will: OPTIONS and DESCRIBE with Digest, then the SDP and SPS
checks, then it receives the stream and measures it. Credentials never appear in its output.

```powershell
.\gradlew.bat :tools:camcheck:installDist
.\tools\camcheck\build\install\camcheck\bin\camcheck.bat --tapo 192.168.1.20 --user tapoview --ask-password --seconds 60 --sdp --json results\front-door-day.json
```

`--ask-password` prompts without echo. For scripts, put the password in the `RTSP_PASSWORD`
environment variable instead; never put it in the URL, where it ends up in your shell history.

Read the output top to bottom:

| Section | What to check |
| --- | --- |
| Result | `OK`; otherwise the code (E-401, E-404, E-REFUSED, E-TIMEOUT, E-BUSY) and its hint |
| Authentication | `accepted the Digest answer both engines send`; and `Media3 1.11.1: OK`, `rtsp-client-android 6.1.1: OK`. `RISK` or `FAILS` means that engine may not sign in |
| Video / SPS | Main, Level 5 (stream1) or 2.2 (stream2), 15 fps, `reorder not set (inferred 7)` |
| Stream | `GOP` = keyframe interval in seconds; `frames with I/P/B slices` must show `B 0`; bitrates |
| Findings | Each `WARN` or `ERROR` line, with the engine it applies to |
| SDP | Keep it: it is the first real capture of your camera's full SDP |

Repeat at night (infrared mode) for the night bitrate, and once per camera.

To see what Tapo's viewer limit looks like, stop every other viewer and open three sessions at once:

```powershell
.\tools\camcheck\build\install\camcheck\bin\camcheck.bat --user tapoview --ask-password --seconds 10 --parallel 3 rtsp://192.168.1.20:554/stream1
```

The third session's code (E-BUSY with 453 or 503, E-CLOSED, or E-REFUSED) is what the app will
have to recognise as "camera busy".

## 4. Install the app on each TV

1. On the TV: Settings → Device Preferences → About → **Build**, press OK seven times. Then in
   Developer options turn on **USB debugging** (and **Network debugging** where offered).
2. Connect over the network and accept the prompt on the TV:

   ```powershell
   adb connect 192.168.1.50:5555
   ```

   TVs that offer **Wireless debugging** instead: choose "Pair device with pairing code", run
   `adb pair <ip>:<pairing-port>`, then `adb connect <ip>:<port>`.
3. Install and start the perf build:

   ```powershell
   .\gradlew.bat :app:installPerf
   adb shell am start -n io.github.rhlshlt.tvrtsp/.MainActivity
   ```

The app appears in the TV's Apps row with the "TV RTSP Player" banner.

## 5. Add cameras without typing on the TV

Cameras live in memory only in Phase 0 (encrypted storage arrives in Phase 1), so add them after
each app start. The helper reads the password without echo and keeps it out of your shell history:

```powershell
.\tools\scripts\add-camera.ps1 -Name "Front door" -CameraHost 192.168.1.20 -User tapoview
.\tools\scripts\add-camera.ps1 -Name "Garden" -CameraHost 192.168.1.21 -User tapoview -Device 192.168.1.50:5555
```

On Linux or macOS: `tools/scripts/add-camera.sh "Front door" 192.168.1.20 tapoview`.

The test lab's streams (16 identical cameras for the capacity test), and clearing the list:

```powershell
adb shell am broadcast -a io.github.rhlshlt.tvrtsp.ADD_LAB -n io.github.rhlshlt.tvrtsp/.debug.AdbCameraReceiver --es host 192.168.1.10 --ei port 8554 --es user viewer --es pass viewer-lab-pass --ei count 16
adb shell am broadcast -a io.github.rhlshlt.tvrtsp.CLEAR -n io.github.rhlshlt.tvrtsp/.debug.AdbCameraReceiver
```

Each command answers with `Broadcast completed: result=0, data="added ..."`. The receiver only
accepts broadcasts from the adb shell (it requires `android.permission.DUMP`) and is enabled only in
the debug and perf builds. The **Cameras → Add camera** screen does the same with the remote.

## 6. Run the measurements

Run each test on every reference TV, with the perf build. The player writes one CSV per engine to the
metrics folder; the multi-view test writes one per run.

### Device profile

Home → **Device profile**. It lists every H.264 and H.265 decoder with its highest level, instance
limit, low-latency support and performance points, then answers the C320WS questions (can it decode
stream1, 2-view, 4-view, 1 + 7, 16-view) and suggests a tier. It saves `device-profile.json`.

### First-frame time and switching

Home → **Play full screen**. Keys: **Left/Right** switch camera, **Down** switches main/sub,
**Up** cycles the overlay, **OK** opens the test controls.

For each engine (OK → Engine) and each stream:

1. Wait until the overlay shows `State playing`, then press OK → **Restart** ten times, waiting for
   the picture each time.
2. Switch between two cameras with Left/Right ten times.

Each start writes a `state` row with `first_frame_ms`. To summarise one CSV in PowerShell:

```powershell
$rows = Import-Csv (Get-ChildItem results\living-room\*_player_media3.csv | Select-Object -Last 1)
$rows | Where-Object { $_.event -eq "state" -and $_.state -eq "playing" } |
  ForEach-Object { [int]$_.first_frame_ms } | Measure-Object -Average -Minimum -Maximum
```

The keyframe interval from `camcheck` is the floor: no engine shows a picture before the next
keyframe arrives.

### Glass-to-glass delay

The player shows a clock with milliseconds in the top-right corner.

1. Point a camera at the TV so the clock is in view, and play that camera full screen.
2. Photograph the TV with your phone. The photo shows the live clock and, inside the video, an
   older copy of it. The difference is the delay from camera lens to TV screen.
3. Take five photos per configuration and keep the median.

Configurations: Media3 with Balanced buffering; Media3 with Low latency buffering; Media3 with
low-latency decoding off; rtsp-client-android; rtsp-client-android with SPS rewrite (only if
`camcheck` found no B-frames). The plan's target is under 2 s; the comparison between
configurations matters more than the absolute value, which includes the camera's own encoder delay.

Without a camera pointed at the TV, play the lab's `lab/clock` path instead: it shows the lab PC's
clock, so the TV's clock minus the video's clock is the delay, accurate to how well both clocks are
synchronised (usually within 50 ms on network time).

### Multi-view capacity

Start the test lab ([TEST_LAB.md](TEST_LAB.md)) and add 16 lab cameras (step 5). Then Home →
**Multi-view capacity test**:

1. Stream **sub**, engine **Media3**: run 1, 2, 4, 9 and 16 tiles for two minutes each.
2. Stream **main**: run 1, 2 and 4 tiles, then 9 if 4 was smooth.
3. Repeat the largest smooth setting with rtsp-client-android.

The bottom bar shows `playing n/N`, total frames per second (smooth = 15 × tiles) and dropped
frames. A tile in error usually means the TV ran out of decoder instances. The app's CPU figure
excludes decoding, which runs in the media service; for the whole device run:

```powershell
adb shell top -b -n 1 -m 15
```

Finish with your real cameras: 2-view and, if you have four, 4-view on stream1.

### Reconnect behaviour

Play a camera for 30 minutes, then unplug its power for 10 seconds and for 2 minutes. Note what each
engine shows and how long it takes to report the failure; press OK → **Mark event in log** when you
pull the plug. Phase 0 has no automatic reconnect: use OK → Restart once the camera is back.

### Other checks

| Check | How |
| --- | --- |
| TCP vs UDP | OK → Transport on a Wi-Fi camera; watch for broken frames and drops |
| Audio | OK → Audio on; G.711 should play on both engines |
| Special characters | A second Camera Account with symbols such as `@`, `:` and `#`; run `camcheck` and the player |
| Privacy Mode, firmware update | Play, switch Privacy Mode on in the Tapo app, note the error code |
| Contrast | Photograph the same scene on the TV and in the Tapo app |

## 7. Collect the results

```powershell
adb pull /sdcard/Android/data/io.github.rhlshlt.tvrtsp/files/metrics results\living-room
```

The `results` folder is ignored by git, because files name your cameras. If `adb pull` is refused
on your TV, open Home → **Results** to read the files on screen.

| File | Written by | One row per |
| --- | --- | --- |
| `*_player_media3.csv`, `*_player_rtsp_client.csv` | Player | Start, state change and second; `event` says which |
| `*_multiview_<tiles>_<engine>.csv` | Multi-view test | Tile per second |
| `*_check_<camera>.txt` and `.json` | Camera check on the TV | Camera |
| `*_device-profile.json` | Device profile | TV |

Player columns: `first_frame_ms` (from start to first rendered frame), `fps` (rendered, or received
for rtsp-client-android), `frames_dropped`, `buffered_ms` (Media3), `network_latency_ms` and
`decoder_latency_ms` (rtsp-client-android), `decoder`, `low_latency_applied`,
`app_cpu_pct_one_core` (this app only, 100 = one full core), `threads`, heap sizes and `thermal`
(0 = normal).

## 8. Gate A

Gate A passes when:

- [ ] Both streams of every camera play on every reference TV with at least one engine.
- [ ] Both engines' sign-in works with your cameras, or the failing one is ruled out (decision 1).
- [ ] Keyframe interval, B-frames and bitrates are known for each camera.
- [ ] First-frame time and delay are measured per TV and engine, and the engine is chosen.
- [ ] Each TV's tile limits on stream1 and stream2 are measured, and the tier table is updated.
- [ ] It is decided whether the LibVLC fallback is needed (it is if a camera won't play on either engine).

Record the decisions in [DECISIONS.md](DECISIONS.md) and in the plan, then start Phase 1.

## Troubleshooting

| Symptom | Likely cause and fix |
| --- | --- |
| Sync fails on `rtsp-client-android` | JitPack hasn't built 6.1.1 yet: open its JitPack page (step 1) and sync again |
| `Verification of Gradle distribution failed` | The download doesn't match the pinned SHA-256; compare with <https://gradle.org/release-checksums/> |
| E-401 in the app but `camcheck` is OK | One engine signs in differently; read the engine lines in `camcheck`'s Authentication section |
| E-BUSY or E-CLOSED | Too many viewers: close VLC, NVR or Home Assistant streams; Tapo Care uses one slot |
| Black picture while the state says playing | The decoder may not handle Level 5.0: check the device profile, try stream2 |
| Picture freezes, state stays playing | A stall the Phase 0 player doesn't detect yet; restart and note it for Phase 2 |
| The app isn't in the Apps row | It installed on another device: `adb devices`, then `-s <device>` |
