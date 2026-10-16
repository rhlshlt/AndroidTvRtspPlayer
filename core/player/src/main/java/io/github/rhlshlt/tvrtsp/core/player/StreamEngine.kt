package io.github.rhlshlt.tvrtsp.core.player

import android.content.Context
import android.view.View
import io.github.rhlshlt.tvrtsp.core.rtsp.RtspUrl
import kotlinx.coroutines.flow.StateFlow

/** The engines Phase 0 compares on the C320WS. */
enum class EngineKind(val label: String) {
    MEDIA3("Media3"),
    RTSP_CLIENT("rtsp-client-android"),
}

enum class Transport { TCP, UDP }

/**
 * Media3 buffer thresholds (DefaultLoadControl). RTSP is pushed by the camera, so these mostly set
 * how long playback waits before starting, which adds directly to the delay behind live.
 */
enum class BufferProfile(
    val label: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val startMs: Int,
    val afterRebufferMs: Int,
) {
    LOW_LATENCY("Low latency", 500, 1_000, 100, 300),
    BALANCED("Balanced", 1_500, 3_000, 500, 1_000),
    SMOOTH("Smooth", 3_000, 6_000, 1_500, 2_500),
}

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
    /** Whether low-latency decoding was requested from the codec (null when unknown). */
    val lowLatencyDecoding: Boolean? = null,
    val framesRendered: Long? = null,
    val framesDropped: Long? = null,
    val framesReceived: Long? = null,
    val bufferedMs: Long? = null,
    val networkLatencyMs: Int? = null,
    val decoderLatencyMs: Int? = null,
    val firstFrameMs: Long? = null,
    val errors: Int = 0,
)

/**
 * One way of playing an RTSP stream into a view. Call everything on the main thread.
 *
 * Lifecycle: [createView] once, then [start]/[stop] any number of times, then [release].
 */
interface StreamEngine {
    val kind: EngineKind
    val state: StateFlow<EngineState>

    /** Creates the SurfaceView this engine renders into. */
    fun createView(context: Context): View

    fun start(request: StreamRequest)

    fun stop()

    fun release()

    fun stats(): EngineStats

    companion object {
        const val USER_AGENT = "TvRtspPlayer/0.1 (Phase 0)"

        fun create(kind: EngineKind, context: Context, name: String = "player"): StreamEngine = when (kind) {
            EngineKind.MEDIA3 -> Media3Engine(context, name)
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
