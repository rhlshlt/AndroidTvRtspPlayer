# Third-party notices

Components the app and tools use, with their licenses. Keep this list current when dependencies
change; Apache-2.0 notices and MIT copyright lines must ship with any distributed build.

## Shipped in the app

| Component | Version | License | Notes |
| --- | --- | --- | --- |
| [AndroidX Media3](https://github.com/androidx/media) (ExoPlayer, RTSP) | 1.11.1 | Apache-2.0 | |
| [Jetpack Compose](https://developer.android.com/jetpack/androidx/releases/compose), [Compose for TV](https://developer.android.com/jetpack/androidx/releases/tv) (tv-material) | BOM 2026.09.00, 1.1.0 | Apache-2.0 | |
| AndroidX Activity, Annotation | 1.13.0, 1.10.0 | Apache-2.0 | |
| [Kotlin standard library](https://github.com/JetBrains/kotlin), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 2.4.20, 1.10.2+ | Apache-2.0 | |
| [rtsp-client-android](https://github.com/alexeyvasilyev/rtsp-client-android) | 6.1.1 | Apache-2.0 | Contains `MediaCodecHelper`, marked as based on [Moonlight for Android](https://github.com/moonlight-stream/moonlight-android) (GPL-3.0); review before distributing beyond personal use |
| AndroidX CameraX core (dependency of rtsp-client-android) | 1.6.2 | Apache-2.0 | |
| [JCodec](https://github.com/jcodec/jcodec) (dependency of rtsp-client-android) | 0.2.5 | BSD 2-Clause (FreeBSD) | |

## Used only for building and testing

| Component | License | Notes |
| --- | --- | --- |
| [Gradle](https://gradle.org) wrapper | Apache-2.0 | `gradle/wrapper/gradle-wrapper.jar` |
| [JUnit 4](https://junit.org/junit4/) | EPL-1.0 | Unit tests |
| [MediaMTX](https://github.com/bluenviron/mediamtx) | MIT | Test lab server, not distributed with the app |
| [FFmpeg](https://ffmpeg.org) with libx264 | LGPL-2.1+ / GPL-2.0+ | Test lab clips and publishing; also produced the RTP test fixtures; not distributed with the app |
