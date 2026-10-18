package io.github.rhlshlt.tvrtsp.core.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.View
import com.alexvas.rtsp.RtspClient
import com.alexvas.rtsp.codec.VideoDecodeThread.DecoderType
import com.alexvas.rtsp.widget.RtspDataListener
import com.alexvas.rtsp.widget.RtspStatusListener
import com.alexvas.rtsp.widget.RtspSurfaceView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * rtsp-client-android: unbuffered playback straight into MediaCodec. Its decoder setup (6.1.1) adds
 * vendor hints such as `vdec-lowlatency` and a high operating rate, but not Android's
 * KEY_LOW_LATENCY, so [stats] reports low-latency decoding as off and says whether the decoder would
 * support it. Its real latency lever is the SPS rewrite ([StreamRequest.spsRewrite]).
 *
 * Connection callbacks arrive on the main thread; first-frame and frame-size callbacks arrive on its
 * decoder thread, so state is kept in thread-safe holders.
 *
 * The library stops a session asynchronously, and the old session's thread stops "the" decoders
 * when it ends, which would hit a new session's decoders on a quick restart. So a restart waits
 * [RESTART_GAP_MS] after a stop before connecting; first-frame time is counted from the connect.
 */
class RtspClientEngine : StreamEngine {
    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    override val state: StateFlow<EngineState> = _state
    override val kind = EngineKind.RTSP_CLIENT

    private var view: RtspSurfaceView? = null
    private var surfaceReady = false
    private var pending: StreamRequest? = null
    private var request: StreamRequest? = null
    private var stopping = false
    private val handler = Handler(Looper.getMainLooper())
    private var connectTask: Runnable? = null
    private var lastStopAt = 0L

    @Volatile private var startedAt = 0L

    @Volatile private var firstFrameMs: Long? = null

    @Volatile private var width = 0

    @Volatile private var height = 0

    @Volatile private var errors = 0
    private val framesReceived = AtomicLong()

    @Volatile private var lastTimestamp = Long.MIN_VALUE

    override fun createView(context: Context): View {
        val v = RtspSurfaceView(context)
        v.setStatusListener(statusListener)
        v.setDataListener(dataListener)
        // Added after RtspSurfaceView's own callback, so it runs second. The library creates the
        // decoder from holder.surface when the camera answers, so only start once the surface exists.
        v.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                pending?.let {
                    pending = null
                    scheduleConnect(it)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                cancelConnect()
                if (v.isStarted()) {
                    stopSession(v)
                    request?.let { pending = it } // resume when the surface comes back
                }
            }
        })
        view = v
        return v
    }

    override fun start(request: StreamRequest) {
        cancelConnect()
        view?.let { if (it.isStarted()) stopSession(it) }
        this.request = request
        firstFrameMs = null
        width = 0
        height = 0
        framesReceived.set(0)
        lastTimestamp = Long.MIN_VALUE
        startedAt = SystemClock.elapsedRealtime()
        _state.value = EngineState.Connecting
        if (surfaceReady) scheduleConnect(request) else pending = request
    }

    private fun stopSession(v: RtspSurfaceView) {
        stopping = true
        v.stop()
        lastStopAt = SystemClock.elapsedRealtime()
    }

    private fun scheduleConnect(r: StreamRequest) {
        val wait = RESTART_GAP_MS - (SystemClock.elapsedRealtime() - lastStopAt)
        if (wait <= 0) {
            connect(r)
            return
        }
        val task = Runnable {
            connectTask = null
            connect(r)
        }
        connectTask = task
        handler.postDelayed(task, wait)
    }

    private fun cancelConnect() {
        connectTask?.let { handler.removeCallbacks(it) }
        connectTask = null
    }

    private fun connect(r: StreamRequest) {
        val v = view ?: return
        stopping = false
        startedAt = SystemClock.elapsedRealtime()
        v.experimentalUpdateSpsFrameWithLowLatencyParams = r.spsRewrite
        v.videoDecoderType = if (r.preferSoftwareDecoder) DecoderType.SOFTWARE else DecoderType.HARDWARE
        v.init(
            uri = Uri.parse(r.url.requestUri),
            username = r.url.username,
            password = r.url.password,
            userAgent = StreamEngine.USER_AGENT,
            socketTimeout = 5_000,
            transport = if (r.transport == Transport.TCP) RtspClient.Transport.TCP else RtspClient.Transport.UDP,
        )
        v.start(requestVideo = true, requestAudio = r.audio, requestApplication = false)
    }

    override fun stop() {
        pending = null
        cancelConnect()
        stopping = true
        view?.let { if (it.isStarted()) stopSession(it) }
        _state.value = EngineState.Idle
    }

    override fun release() {
        stop()
        view?.setStatusListener(null)
        view?.setDataListener(null)
        view = null
    }

    override fun stats(): EngineStats {
        val s = view?.statistics
        val decoder = s?.videoDecoderName
        return EngineStats(
            engine = kind,
            width = width,
            height = height,
            decoderName = decoder,
            lowLatencyDecoding = if (decoder != null) false else null,
            decoderLowLatencySupported = DecoderFeatures.lowLatencySupported(decoder),
            framesReceived = framesReceived.get(),
            networkLatencyMs = s?.networkLatencyMsec?.takeIf { it >= 0 },
            decoderLatencyMs = s?.videoDecoderLatencyMsec?.takeIf { it >= 0 },
            firstFrameMs = firstFrameMs,
            errors = errors,
        )
    }

    private fun sinceStart() = SystemClock.elapsedRealtime() - startedAt

    private fun fail(code: String, message: String) {
        errors++
        val clean = request?.let { message.withoutCredentials(it.url) } ?: message
        _state.value = EngineState.Error(code, clean, sinceStart())
    }

    private val statusListener = object : RtspStatusListener {
        override fun onRtspStatusConnecting() {
            if (firstFrameMs == null) _state.value = EngineState.Connecting
        }

        override fun onRtspFirstFrameRendered() { // decoder thread
            val ms = firstFrameMs ?: sinceStart().also { firstFrameMs = it }
            _state.value = EngineState.Playing(ms)
        }

        override fun onRtspFrameSizeChanged(width: Int, height: Int) { // decoder thread
            this@RtspClientEngine.width = width
            this@RtspClientEngine.height = height
        }

        override fun onRtspStatusFailedUnauthorized() = fail("E-401", "Wrong user name or password")

        override fun onRtspStatusFailed(message: String?) = fail("E-RTSP", message ?: "Connection failed")

        override fun onRtspStatusDisconnected() {
            // Only while playing: a late callback from a session stopped for a restart arrives
            // while the new one is still connecting, and must not count as an error.
            if (!stopping && _state.value is EngineState.Playing) fail("E-DISCONNECTED", "Camera closed the stream")
        }
    }

    private companion object {
        const val RESTART_GAP_MS = 500L
    }

    private val dataListener = object : RtspDataListener {
        override fun onRtspDataVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
            if (timestamp != lastTimestamp) { // RTSP thread; one frame per timestamp
                lastTimestamp = timestamp
                framesReceived.incrementAndGet()
            }
        }
    }
}
