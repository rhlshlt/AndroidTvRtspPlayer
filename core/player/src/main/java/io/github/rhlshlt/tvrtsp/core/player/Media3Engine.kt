package io.github.rhlshlt.tvrtsp.core.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Media3 ExoPlayer with its RTSP module. A new player is built on every [start] so that buffer and
 * decoder options apply cleanly; Phase 2 replaces this with pooled players.
 *
 * The latency guard (on unless [StreamRequest.latencyGuard] is false) checks once a second how much
 * video is buffered. RTSP has no back-pressure, so a decoder that can't keep up, or a player stuck
 * buffering, lets video pile up in memory without limit; past [BufferProfile.guardMs] for
 * [LATENCY_GUARD_GRACE_MS] (or at three times the limit), the guard rebuilds the player, which drops
 * the backlog and rejoins live.
 */
@OptIn(UnstableApi::class)
class Media3Engine(
    context: Context,
    private val name: String,
    private val videoView: VideoView = VideoView.SURFACE_VIEW,
) : StreamEngine {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state
    override val kind = EngineKind.MEDIA3

    private var player: ExoPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var textureView: TextureView? = null
    private var request: StreamRequest? = null
    private var startedAt = 0L
    private var firstFrameMs: Long? = null
    private var width = 0
    private var height = 0
    private var errors = 0
    private var droppedFrames = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var guardRestarts = 0
    private var overLimitSince = 0L

    @Volatile private var decoderName: String? = null

    @Volatile private var lowLatency: Boolean? = null

    @Volatile private var lowLatencySupported: Boolean? = null

    override fun createView(context: Context): View = when (videoView) {
        VideoView.SURFACE_VIEW -> SurfaceView(context).also { view ->
            surfaceView = view
            player?.setVideoSurfaceView(view)
        }
        VideoView.TEXTURE_VIEW -> TextureView(context).also { view ->
            textureView = view
            player?.setVideoTextureView(view)
        }
    }

    override fun start(request: StreamRequest) {
        guardRestarts = 0
        launch(request)
    }

    /** Builds a fresh player for [request]; the latency guard calls this to rejoin live. */
    private fun launch(request: StreamRequest) {
        handler.removeCallbacks(guardTick)
        releasePlayer()
        this.request = request
        firstFrameMs = null
        width = 0
        height = 0
        droppedFrames = 0
        decoderName = null
        lowLatency = null
        lowLatencySupported = null
        overLimitSince = 0L

        val renderers = LowLatencyRenderersFactory(appContext, request.lowLatencyDecoding) { codec, supported, applied ->
            decoderName = codec
            lowLatencySupported = supported
            lowLatency = applied
        }.setEnableDecoderFallback(true)
        val profile = request.bufferProfile
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(profile.minBufferMs, profile.maxBufferMs, profile.startMs, profile.afterRebufferMs)
            .setBackBuffer(0, false)
            .build()
        val p = ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderers)
            .setLoadControl(loadControl)
            .setName(name)
            .build()
        p.addListener(listener)
        p.addAnalyticsListener(analytics)
        if (!request.audio) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
        surfaceView?.let { p.setVideoSurfaceView(it) }
        textureView?.let { p.setVideoTextureView(it) }
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(request.transport == Transport.TCP)
            .setTimeoutMs(request.rtspTimeoutMs)
            .setUserAgent(StreamEngine.USER_AGENT)
            .createMediaSource(MediaItem.fromUri(request.url.toUriWithCredentials()))
        p.setMediaSource(source)
        player = p
        startedAt = SystemClock.elapsedRealtime()
        _state.value = EngineState.Connecting
        p.playWhenReady = true
        p.prepare()
        if (request.latencyGuard) handler.postDelayed(guardTick, GUARD_INTERVAL_MS)
    }

    override fun stop() {
        handler.removeCallbacks(guardTick)
        releasePlayer()
        _state.value = EngineState.Idle
    }

    override fun release() {
        handler.removeCallbacks(guardTick)
        releasePlayer()
        surfaceView = null
        textureView = null
        _state.value = EngineState.Idle
    }

    private fun releasePlayer() {
        player?.let {
            it.removeListener(listener)
            it.removeAnalyticsListener(analytics)
            it.release()
        }
        player = null
    }

    override fun stats(): EngineStats {
        val counters = player?.videoDecoderCounters
        counters?.ensureUpdated()
        return EngineStats(
            engine = kind,
            width = width,
            height = height,
            decoderName = decoderName,
            lowLatencyDecoding = lowLatency,
            decoderLowLatencySupported = lowLatencySupported,
            framesRendered = counters?.renderedOutputBufferCount?.toLong(),
            framesDropped = counters?.let { (it.droppedBufferCount + it.skippedOutputBufferCount).toLong() } ?: droppedFrames,
            bufferedMs = player?.totalBufferedDuration,
            firstFrameMs = firstFrameMs,
            errors = errors,
            guardLimitMs = request?.takeIf { it.latencyGuard }?.bufferProfile?.guardMs,
            guardRestarts = guardRestarts,
        )
    }

    private fun sinceStart(): Long = SystemClock.elapsedRealtime() - startedAt

    private val guardTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val req = request ?: return
            val buffered = p.totalBufferedDuration
            val limit = req.bufferProfile.guardMs
            val now = SystemClock.elapsedRealtime()
            val failed = _state.value.let { it is EngineState.Error || it == EngineState.Ended }
            if (failed || buffered <= limit) {
                // Errors and ended streams stay visible: recovering from them is Phase 2's reconnect.
                overLimitSince = 0L
            } else {
                if (overLimitSince == 0L) overLimitSince = now
                if (now - overLimitSince >= LATENCY_GUARD_GRACE_MS || buffered >= 3 * limit) {
                    guardRestarts++
                    Log.w(TAG, "$name: $buffered ms buffered, over the $limit ms limit; restarting (guard restart $guardRestarts)")
                    launch(req) // posts the next check itself
                    return
                }
            }
            handler.postDelayed(this, GUARD_INTERVAL_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            val ms = firstFrameMs ?: sinceStart().also { firstFrameMs = it }
            _state.value = EngineState.Playing(ms)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val first = firstFrameMs
            when (playbackState) {
                Player.STATE_BUFFERING -> if (first != null) _state.value = EngineState.Buffering(first)
                Player.STATE_READY -> if (first != null) _state.value = EngineState.Playing(first)
                Player.STATE_ENDED -> _state.value = EngineState.Ended
                else -> Unit
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            width = videoSize.width
            height = videoSize.height
        }

        override fun onPlayerError(error: PlaybackException) {
            errors++
            val req = request
            val detail = buildString {
                append(error.message ?: "")
                error.cause?.message?.let { append(": ").append(it) }
            }.let { if (req != null) it.withoutCredentials(req.url) else it }
            _state.value = EngineState.Error(error.errorCodeName, detail, sinceStart())
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            this@Media3Engine.droppedFrames += droppedFrames
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            this@Media3Engine.decoderName = decoderName
        }
    }

    private companion object {
        const val TAG = "Media3Engine"
        const val GUARD_INTERVAL_MS = 1_000L
    }
}
