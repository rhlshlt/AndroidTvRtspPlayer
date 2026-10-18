package io.github.rhlshlt.tvrtsp.core.player

import android.content.Context
import android.view.View
import io.github.rhlshlt.tvrtsp.core.rtsp.RtspUrl
import kotlinx.coroutines.flow.StateFlow

/** The engines Phase 0 compares on the C320WS. */
enum class EngineKind(
    val label: String,
    /** Whether the engine can render into a TextureView ([VideoView.TEXTURE_VIEW]). */
    val textureView: Boolean,
) {
    MEDIA3("Media3", textureView = true),
    RTSP_CLIENT("rtsp-client-android", textureView = false),
}

enum class Transport { TCP, UDP }

/**
 * How an engine shows video. A SurfaceView hands decoded frames to the display hardware; a
 * TextureView draws them with the GPU inside the app's window. On the first test TV, one of two
 * hardware-decoded SurfaceView tiles stayed black in a 4-tile grid; TextureView tests whether the
 * GPU path shows them all, and at what CPU cost.
 */
enum class VideoView(val label: String) {
    SURFACE_VIEW("SurfaceView"),
    TEXTURE_VIEW("TextureView"),
}

/**
 * Media3 buffer thresholds (DefaultLoadControl). RTSP is pushed by the camera, so these mostly set
 * how long playback waits before starting, which adds directly to the delay behind live.
 *
 * [guardMs] is the latency guard's limit. RTSP gives the camera no signal to slow down, so when
 * decoding falls behind, video piles up in memory: on the first test TV, Media3 stalled on stream1
 * and stored 617 s of video, growing its Java heap by about 10 MB a minute. The guard restarts the
 * player when more than [guardMs] stays buffered for [LATENCY_GUARD_GRACE_MS].
 */
enum class BufferProfile(
    val label: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val startMs: Int,
    val afterRebufferMs: Int,
    val guardMs: Long,
) {
    LOW_LATENCY("Low latency", 500, 1_000, 100, 300, 3_000),
    BALANCED("Balanced", 1_500, 3_000, 500, 1_000, 4_000),
    SMOOTH("Smooth", 3_000, 6_000, 1_500, 2_500, 8_000),
}

/** How long the buffer may stay above [BufferProfile.guardMs] before the guard restarts the player. */
const val LATENCY_GUARD_GRACE_MS = 5_000L

/** Everything an engine needs to play one stream. */
data class StreamRequest(
    /** Stream URL including credentials; never log it, use [RtspUrl.redacted]. */
    val url: RtspUrl,
    /** Shown in overlays and logs, for example "Front door · main". */
    val label: String,
    val transport: Transport = Transport.TCP,
    val audio: Boolean = false,
    /** Media3: ask the decoder for KEY_LOW_LATENCY when it supports it. */
    val lowLatencyDecoding: Boolean = true,
    val bufferProfile: BufferProfile = BufferProfile.BALANCED,
    /** Media3 RtspMediaSource.Factory.setTimeoutMs; also how long until a silent stream "ends". */
    val rtspTimeoutMs: Long = 8_000,
    /** rtsp-client-android: rewrite the SPS to declare no frame reordering (only safe without B-frames). */
    val spsRewrite: Boolean = false,
    /** Media3: restart the player when more than [BufferProfile.guardMs] of video piles up. */
    val latencyGuard: Boolean = true,
    /**
     * Try Android's software decoder before the hardware one. On the first test TV only one
     * hardware-decoded video showed at a time in SurfaceViews, so multi-view can give every tile but
     * one a software decoder; stream2 costs about 13% of one core that way.
     */
    val preferSoftwareDecoder: Boolean = false,
)

sealed interface EngineState {
    data object Idle : EngineState

    data object Connecting : EngineState

    /** First frame shown [firstFrameMs] after start(). */
    data class Playing(val firstFrameMs: Long) : EngineState

    /** Media3 only: playing stalled while the buffer refills. */
    data class Buffering(val firstFrameMs: Long) : EngineState

    /** Media3 reports ENDED when nothing arrives within the RTSP timeout. */
    data object Ended : EngineState

    data class Error(val code: String, val message: String, val afterMs: Long) : EngineState
}

/** A snapshot of an engine's counters. Null means the engine doesn't report that value. */
data class EngineStats(
    val engine: EngineKind,
    val width: Int = 0,
    val height: Int = 0,
    val decoderName: String? = null,
    /**
     * Whether Android's low-latency decoding mode is on: MediaFormat.KEY_LOW_LATENCY was set, which
     * only works when the decoder advertises it ([decoderLowLatencySupported]). Null when unknown.
     */
    val lowLatencyDecoding: Boolean? = null,
    /** Whether the decoder advertises the low-latency mode (FEATURE_LowLatency); null when unknown. */
    val decoderLowLatencySupported: Boolean? = null,
    val framesRendered: Long? = null,
    val framesDropped: Long? = null,
    val framesReceived: Long? = null,
    val bufferedMs: Long? = null,
    val networkLatencyMs: Int? = null,
    val decoderLatencyMs: Int? = null,
    val firstFrameMs: Long? = null,
    val errors: Int = 0,
    /** Media3: the latency guard's limit while the guard is on, else null. */
    val guardLimitMs: Long? = null,
    /** Media3: how often the latency guard restarted the player since the last start(). */
    val guardRestarts: Int = 0,
)

/**
 * One way of playing an RTSP stream into a view. Call everything on the main thread.
 *
 * Lifecycle: [createView] once, then [start]/[stop] any number of times, then [release].
 */
interface StreamEngine {
    val kind: EngineKind
    val state: StateFlow<EngineState>

    /** Creates the view this engine renders into: a SurfaceView, or a TextureView if created with one. */
    fun createView(context: Context): View

    fun start(request: StreamRequest)

    fun stop()

    fun release()

    fun stats(): EngineStats

    companion object {
        const val USER_AGENT = "TvRtspPlayer/0.1 (Phase 0)"

        /** [view] applies where [EngineKind.textureView] allows it; other engines use a SurfaceView. */
        fun create(
            kind: EngineKind,
            context: Context,
            name: String = "player",
            view: VideoView = VideoView.SURFACE_VIEW,
        ): StreamEngine = when (kind) {
            EngineKind.MEDIA3 -> Media3Engine(context, name, view)
            EngineKind.RTSP_CLIENT -> RtspClientEngine()
        }
    }
}

/** Removes credentials from engine messages before they reach the screen or a log. */
internal fun String.withoutCredentials(url: RtspUrl): String {
    var s = this
    s = s.replace(url.toUriWithCredentials(), url.redacted())
    url.password?.takeIf { it.length >= 3 }?.let { pw ->
        s = s.replace(pw, "***").replace(io.github.rhlshlt.tvrtsp.core.rtsp.PercentCodec.encode(pw), "***")
    }
    return s
}
