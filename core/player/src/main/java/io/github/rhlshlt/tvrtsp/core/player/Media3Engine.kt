package io.github.rhlshlt.tvrtsp.core.player

import android.content.Context
import android.os.SystemClock
import android.view.SurfaceView
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
 */
@OptIn(UnstableApi::class)
class Media3Engine(context: Context, private val name: String) : StreamEngine {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state
    override val kind = EngineKind.MEDIA3

    private var player: ExoPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var request: StreamRequest? = null
    private var startedAt = 0L
    private var firstFrameMs: Long? = null
    private var width = 0
    private var height = 0
    private var errors = 0
    private var droppedFrames = 0L

    @Volatile private var decoderName: String? = null

    @Volatile private var lowLatency: Boolean? = null

    override fun createView(context: Context): View =
        SurfaceView(context).also { view ->
            surfaceView = view
            player?.setVideoSurfaceView(view)
        }

    override fun start(request: StreamRequest) {
        releasePlayer()
        this.request = request
        firstFrameMs = null
        width = 0
        height = 0
        droppedFrames = 0
        decoderName = null
        lowLatency = null

        val renderers = LowLatencyRenderersFactory(appContext, request.lowLatencyDecoding) { codec, applied ->
            decoderName = codec
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
    }

    override fun stop() {
        releasePlayer()
        _state.value = EngineState.Idle
    }

    override fun release() {
        releasePlayer()
        surfaceView = null
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
            framesRendered = counters?.renderedOutputBufferCount?.toLong(),
            framesDropped = counters?.let { (it.droppedBufferCount + it.skippedOutputBufferCount).toLong() } ?: droppedFrames,
            bufferedMs = player?.totalBufferedDuration,
            firstFrameMs = firstFrameMs,
            errors = errors,
        )
    }

    private fun sinceStart(): Long = SystemClock.elapsedRealtime() - startedAt

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
}
