# Test lab

The test lab is one PC that serves RTSP streams shaped like the C320WS, so every Phase 0 test and
every later regression test runs against the same reproducible input. It also does what real
cameras can't: feed 16 tiles at once, show a millisecond clock for delay checks, and break on demand.
It runs [MediaMTX](https://github.com/bluenviron/mediamtx) v1.21 with Digest authentication, like the
Tapo cameras, and FFmpeg publishers. Files are in [`testlab/`](../testlab).

## Streams and accounts

| Path | Content | Readers |
| --- | --- | --- |
| `lab/stream1` | 2560 × 1440, 15 fps, H.264 Main Level 5.0, 1 reference frame, no B-frames, keyframe every 2 s, about 3 Mbit/s, G.711 A-law 8 kHz mono | Unlimited |
| `lab/stream2` | 640 × 360, 15 fps, H.264 Main Level 2.2, same structure, about 0.5 Mbit/s, G.711 A-law | Unlimited |
| `lab/clock` | 1280 × 720, 15 fps, the lab PC's clock with milliseconds | Unlimited |
| `tapo/stream1`, `tapo/stream2` | The same as `lab/stream1` and `lab/stream2` | 2, like a Tapo camera |

| Account | Password | Can |
| --- | --- | --- |
| `publisher` | `publisher-lab-pass` | Publish (the FFmpeg scripts) |
| `viewer` | `viewer-lab-pass` | Read (the app and `camcheck`) |

These are lab-only accounts. If the lab PC can be reached from other networks, change both
passwords in `testlab/mediamtx.yml`, pass the publisher's to the publish script, and use the
viewer's in the app.

## Set it up

### Windows

1. Install FFmpeg: `winget install Gyan.FFmpeg`, then open a new terminal so `ffmpeg` is on PATH.
2. Start MediaMTX, either with Docker Desktop:

   ```powershell
   cd D:\Projects\AndroidTvRtspPlayer\testlab
   docker compose up -d
   ```

   or without Docker: download `mediamtx_v1.21.0_windows_amd64.zip` from the
   [MediaMTX releases](https://github.com/bluenviron/mediamtx/releases), extract it, and run
   `mediamtx.exe D:\Projects\AndroidTvRtspPlayer\testlab\mediamtx.yml`. Allow it through Windows Firewall
   on private networks (TCP 8554, UDP 8000–8001).
3. Make the clips once (about a minute):

   ```powershell
   .\scripts\make-clips.ps1
   ```

4. Start publishing and leave the window open:

   ```powershell
   .\scripts\publish.ps1
   ```

### Linux or macOS

```bash
cd testlab
docker compose up -d          # or: ./mediamtx mediamtx.yml
./scripts/make-clips.sh
./scripts/publish.sh          # Ctrl+C stops all publishers
```

### Check it

From any PC, with the lab PC's address:

```powershell
$env:RTSP_PASSWORD = "viewer-lab-pass"
.\tools\camcheck\build\install\camcheck\bin\camcheck.bat --user viewer --seconds 10 rtsp://192.168.1.10:8554/lab/stream1
```

Expect `Result: OK`, Main Level 5, 2560×1440, `GOP 2.00 s` and `B 0`. VLC also plays
`rtsp://viewer:viewer-lab-pass@192.168.1.10:8554/lab/stream1`.

### Add it to the app

```powershell
adb shell am broadcast -a io.github.rhlshlt.tvrtsp.ADD_LAB -n io.github.rhlshlt.tvrtsp/.debug.AdbCameraReceiver --es host 192.168.1.10 --ei port 8554 --es user viewer --es pass viewer-lab-pass --ei count 16
```

This adds "Lab 01" to "Lab 16", all reading `lab/stream1` (main) and `lab/stream2` (sub). Use
`--es path tapo` to point them at the 2-viewer paths instead. The app's **Add camera → Test lab**
form does the same.

## Break it on purpose

| Fault | How | What the app should show (Phase 2 onwards) |
| --- | --- | --- |
| Camera offline | Stop `publish` (Ctrl+C), or `docker compose stop` | Stream ends or times out; reconnect when it's back |
| Camera unreachable | Block the port: `New-NetFirewallRule -DisplayName "Lab RTSP block" -Direction Inbound -Protocol TCP -LocalPort 8554 -Action Block` (remove with `Remove-NetFirewallRule -DisplayName "Lab RTSP block"`) | Timeout |
| Loss and jitter | Linux: `sudo ./scripts/netem.sh start eth0 2 40 20` (2% loss, 40 ± 20 ms); Windows: [clumsy](https://jagt.github.io/clumsy/) | Broken frames on UDP; delay on TCP |
| Wrong credentials | Use `viewer` with another password | E-401, no retry loop |
| Wrong path | `rtsp://<lab>:8554/lab/stream9` | E-404 |
| Viewer limit | Open three sessions to `tapo/stream1` (`camcheck --parallel 3`) | The third is busy |
| Publisher restart | Stop and start `publish` while playing | Stream resumes after a reconnect |

## Measure delay with the lab clock

Play `lab/clock` on the TV with the player's clock overlay on. Photograph the screen: the TV's clock
(top right) minus the lab clock in the video is the delay from the lab PC to the screen. Both
clocks must be on network time; the result is accurate to their offset, usually under 50 ms. This
delay excludes a camera's own encoder, so it isolates what the TV and the engine add.

## Limits

- x264 always writes a reorder limit (`max_num_reorder_frames = 0`) into the SPS. The C320WS writes
  none, which can make a decoder hold frames. Measure decoder delay on a real camera.
- Test patterns compress differently from camera video: the bitrates are set, not natural.
- The publishers loop a two-minute clip. FFmpeg keeps the timestamps running across loops, but a
  brief glitch at each loop point is possible; a real camera has none, so ignore one every two
  minutes.
- MediaMTX serves many readers from one path, so 16 tiles read the same stream. Each tile still has
  its own RTSP session and decoder, which is what the capacity test measures.
