# RTSP test lab

A reproducible stand-in for the Tapo C320WS cameras: MediaMTX serves C320WS-shaped streams
(2560×1440 and 640×360, H.264 Main, 15 fps, G.711 A-law) with Digest authentication, plus a live
clock stream and two paths limited to two viewers like a Tapo camera.

Quick start (full instructions in [docs/TEST_LAB.md](../docs/TEST_LAB.md)):

```bash
cd testlab
docker compose up -d                 # or run mediamtx.exe mediamtx.yml (MediaMTX v1.21.0)
./scripts/make-clips.sh              # Windows: .\scripts\make-clips.ps1
./scripts/publish.sh                 # Windows: .\scripts\publish.ps1   (keep it running)
```

Then, from any PC on the network:

```bash
./gradlew :tools:camcheck:installDist
RTSP_PASSWORD=viewer-lab-pass tools/camcheck/build/install/camcheck/bin/camcheck --user viewer rtsp://<lab-pc>:8554/lab/stream1
```

| Path | What it is | Readers |
| --- | --- | --- |
| `lab/stream1` | 2560×1440, 15 fps, Main L5.0, GOP 30, ~3 Mbit/s, PCMA | unlimited |
| `lab/stream2` | 640×360, 15 fps, Main L2.2, GOP 30, ~0.5 Mbit/s, PCMA | unlimited |
| `lab/clock` | 1280×720 live clock of the lab PC, for delay checks | unlimited |
| `tapo/stream1`, `tapo/stream2` | same as `lab/*` | 2, like a Tapo camera |

Accounts (lab only, change them in `mediamtx.yml` if the PC is on a shared network):
`publisher` / `publisher-lab-pass` publishes, `viewer` / `viewer-lab-pass` reads.

Limits: x264 always writes a reorder limit in the SPS, which the C320WS doesn't, so decoder
buffering delay must be measured on the real camera. `scripts/netem.sh` adds loss and jitter on
Linux; on Windows use [clumsy](https://jagt.github.io/clumsy/).
