package io.github.rhlshlt.tvrtsp.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import io.github.rhlshlt.tvrtsp.core.perf.MetricsLogger
import io.github.rhlshlt.tvrtsp.core.perf.ProcessSample
import io.github.rhlshlt.tvrtsp.core.perf.ProcessSampler
import io.github.rhlshlt.tvrtsp.core.player.BufferProfile
import io.github.rhlshlt.tvrtsp.core.player.EngineKind
import io.github.rhlshlt.tvrtsp.core.player.EngineState
import io.github.rhlshlt.tvrtsp.core.player.EngineStats
import io.github.rhlshlt.tvrtsp.core.player.StreamEngine
import io.github.rhlshlt.tvrtsp.core.player.StreamRequest
import io.github.rhlshlt.tvrtsp.core.player.Transport
import io.github.rhlshlt.tvrtsp.data.AppVisibility
import io.github.rhlshlt.tvrtsp.data.CameraStore
import io.github.rhlshlt.tvrtsp.data.PlayerSettings
import io.github.rhlshlt.tvrtsp.ui.components.ButtonRow
import io.github.rhlshlt.tvrtsp.ui.components.LatencyClock
import io.github.rhlshlt.tvrtsp.ui.components.OptionButton
import io.github.rhlshlt.tvrtsp.ui.components.focusWhenReady
import io.github.rhlshlt.tvrtsp.ui.theme.TvColors
import kotlinx.coroutines.delay
import java.util.Locale

private enum class Overlay { FULL, CLOCK, NONE }

private val PLAYER_COLUMNS = listOf(
    "time_ms", "event", "engine", "camera", "stream", "transport", "buffer_profile", "low_latency_requested",
    "sps_rewrite", "state", "first_frame_ms", "width", "height", "fps", "frames_dropped", "buffered_ms",
    "network_latency_ms", "decoder_latency_ms", "decoder", "low_latency_applied", "app_cpu_pct_one_core",
    "threads", "java_heap_mb", "native_heap_mb", "pss_mb", "thermal",
)

/**
 * Full-screen test player. Left/Right switch camera, Down switches main/sub stream, Up cycles the
 * overlay (metrics + clock, clock only, nothing), OK opens the test controls. Every second a row
 * goes to a CSV in the metrics folder.
 */
@Composable
fun PlayerScreen(startIndex: Int, back: () -> Unit) {
    val context = LocalContext.current
    val cameras by CameraStore.cameras.collectAsState()
    if (cameras.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(48.dp)) {
            Text("No cameras. Add one first.")
            Button(onClick = back) { Text("Back") }
        }
        return
    }
    val s = PlayerSettings
    var index by remember { mutableIntStateOf(startIndex) }
    val camera = cameras[index.mod(cameras.size)]
    var panel by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(Overlay.FULL) }
    var restart by remember { mutableIntStateOf(0) }
    val foreground by AppVisibility.foreground.collectAsState()

    val request = remember(camera, s.useSub, s.transport, s.audio, s.lowLatencyDecoding, s.bufferProfile, s.spsRewrite) {
        StreamRequest(
            url = camera.url(s.useSub),
            label = "${camera.name} · ${if (s.useSub && camera.sub != null) "sub" else "main"}",
            transport = s.transport,
            audio = s.audio,
            lowLatencyDecoding = s.lowLatencyDecoding,
            bufferProfile = s.bufferProfile,
            spsRewrite = s.spsRewrite,
        )
    }
    val engine = remember(s.engine) { StreamEngine.create(s.engine, context, "full-a") }
    DisposableEffect(engine) { onDispose { engine.release() } }
    val state by engine.state.collectAsState()

    val logger = remember(engine) { MetricsLogger(MetricsLogger.directory(context), "player_${engine.kind.name.lowercase()}", PLAYER_COLUMNS) }
    DisposableEffect(logger) { onDispose { logger.close() } }
    val sampler = remember { ProcessSampler(context) }
    var stats by remember { mutableStateOf<EngineStats?>(null) }
    var sample by remember { mutableStateOf<ProcessSample?>(null) }
    var fps by remember { mutableStateOf<Double?>(null) }

    fun logRow(event: String) {
        val st = stats
        val ps = sample
        logger.log(listOf(
            System.currentTimeMillis(), event, engine.kind.name, camera.name, if (request.label.endsWith("sub")) "sub" else "main",
            request.transport.name, request.bufferProfile.name, request.lowLatencyDecoding, request.spsRewrite, stateName(state),
            st?.firstFrameMs, st?.width, st?.height, fps, st?.framesDropped, st?.bufferedMs, st?.networkLatencyMs,
            st?.decoderLatencyMs, st?.decoderName, st?.lowLatencyDecoding, ps?.cpuPercentOneCore, ps?.threads,
            ps?.javaHeapMb, ps?.nativeHeapMb, ps?.pssMb, ps?.thermalStatus,
        ))
    }

    LaunchedEffect(engine, request, restart, foreground) {
        if (foreground) {
            stats = null
            fps = null
            engine.start(request)
            logRow("start")
        } else {
            engine.stop()
            logRow("background")
        }
    }
    LaunchedEffect(state) {
        stats = engine.stats() // fresh numbers, so a "playing" row carries its first-frame time
        logRow("state")
    }
    LaunchedEffect(engine, request, restart) {
        var lastFrames = -1L
        var lastAt = SystemClock.elapsedRealtime()
        while (true) {
            delay(1_000)
            val st = engine.stats()
            val frames = st.framesRendered ?: st.framesReceived
            val now = SystemClock.elapsedRealtime()
            fps = if (frames != null && lastFrames >= 0 && now > lastAt) (frames - lastFrames) * 1000.0 / (now - lastAt) else null
            lastFrames = frames ?: -1L
            lastAt = now
            stats = st
            sample = sampler.sample()
            logRow("sample")
        }
    }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(panel) { if (!panel) rootFocus.focusWhenReady() }
    BackHandler(enabled = panel) { panel = false }

    val ratio = stats?.let { if (it.width > 0 && it.height > 0) it.width.toFloat() / it.height else null } ?: (16f / 9f)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (panel || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionRight, Key.ChannelUp, Key.MediaNext -> {
                        index = (index + 1).mod(cameras.size)
                        true
                    }
                    Key.DirectionLeft, Key.ChannelDown, Key.MediaPrevious -> {
                        index = (index - 1).mod(cameras.size)
                        true
                    }
                    Key.DirectionUp -> {
                        overlay = Overlay.entries[(overlay.ordinal + 1) % Overlay.entries.size]
                        true
                    }
                    Key.DirectionDown -> {
                        s.useSub = !s.useSub
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu -> {
                        panel = true
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        key(engine) {
            AndroidView(
                factory = { ctx -> engine.createView(ctx) },
                modifier = Modifier.align(Alignment.Center).aspectRatio(ratio),
            )
        }
        if (overlay != Overlay.NONE) LatencyClock(Modifier.align(Alignment.TopEnd).padding(24.dp))
        if (overlay == Overlay.FULL) {
            MetricsOverlay(request, engine.kind, state, stats, fps, sample, index, cameras.size, Modifier.align(Alignment.TopStart).padding(24.dp))
        }
        StatusMessage(state, request, engine.kind, Modifier.align(Alignment.Center))
        if (panel) {
            ControlPanel(
                onRestart = {
                    restart++
                    panel = false
                },
                onMark = { logger.note("marked by user at ${System.currentTimeMillis()}") },
                onClose = { panel = false },
                onExit = back,
                modifier = Modifier.align(Alignment.BottomStart).padding(24.dp),
            )
        }
    }
}

@Composable
private fun MetricsOverlay(
    request: StreamRequest,
    kind: EngineKind,
    state: EngineState,
    stats: EngineStats?,
    fps: Double?,
    sample: ProcessSample?,
    index: Int,
    count: Int,
    modifier: Modifier,
) {
    val lines = buildList {
        add("${index + 1}/$count  ${request.label}  ·  ${kind.label}  ·  ${request.transport}")
        add("State ${stateName(state)}  ·  first frame ${stats?.firstFrameMs?.let { "$it ms" } ?: "–"}")
        if (stats != null) {
            add("${stats.width}×${stats.height}  ·  ${fps?.let { fmt(it) } ?: "–"} fps  ·  dropped ${stats.framesDropped ?: "–"}" +
                (stats.bufferedMs?.let { "  ·  buffered $it ms" } ?: ""))
            add("Decoder ${stats.decoderName ?: "–"}  ·  low latency ${stats.lowLatencyDecoding?.let { if (it) "on" else "off" } ?: "–"}")
            if (stats.networkLatencyMs != null || stats.decoderLatencyMs != null) {
                add("Network ${stats.networkLatencyMs ?: "–"} ms  ·  decoder ${stats.decoderLatencyMs ?: "–"} ms")
            }
        }
        if (kind == EngineKind.MEDIA3) add("Buffer ${request.bufferProfile.label} (starts after ${request.bufferProfile.startMs} ms)")
        if (sample != null) {
            add("App CPU ${sample.cpuPercentOneCore?.let { fmt(it) } ?: "–"}%  ·  threads ${sample.threads}  ·  " +
                "heap ${fmt(sample.javaHeapMb)} MB  ·  native ${fmt(sample.nativeHeapMb)} MB  ·  thermal ${sample.thermalStatus}")
        }
        add("←/→ camera  ·  ↓ main/sub  ·  ↑ overlay  ·  OK controls")
    }
    Column(
        modifier
            .widthIn(max = 760.dp)
            .background(TvColors.Panel, RoundedCornerShape(8.dp))
            .padding(14.dp),
    ) {
        lines.forEachIndexed { i, line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = if (i == 0) 17.sp else 14.sp, color = if (i == 1) stateColor(state) else Color.White)
        }
    }
}

@Composable
private fun StatusMessage(state: EngineState, request: StreamRequest, kind: EngineKind, modifier: Modifier) {
    val text = when (state) {
        is EngineState.Connecting -> "Connecting to ${request.label} with ${kind.label}…"
        is EngineState.Error -> "${state.code}: ${state.message}\nOK: options  ·  ←/→: other camera"
        is EngineState.Ended -> "Stream ended: nothing arrived for ${request.rtspTimeoutMs / 1000} s. OK → Restart"
        is EngineState.Buffering -> "Buffering…"
        else -> return
    }
    Text(
        text,
        modifier = modifier.background(TvColors.Panel, RoundedCornerShape(8.dp)).padding(18.dp),
        color = if (state is EngineState.Error) TvColors.Error else Color.White,
        fontSize = 20.sp,
    )
}

@Composable
private fun ControlPanel(onRestart: () -> Unit, onMark: () -> Unit, onClose: () -> Unit, onExit: () -> Unit, modifier: Modifier) {
    val s = PlayerSettings
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.focusWhenReady() }
    val media3 = s.engine == EngineKind.MEDIA3
    Column(modifier.background(TvColors.Panel, RoundedCornerShape(8.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Test controls (Back closes)", fontSize = 16.sp)
        ButtonRow {
            OptionButton("Engine", s.engine.label, {
                s.engine = if (media3) EngineKind.RTSP_CLIENT else EngineKind.MEDIA3
            }, Modifier.focusRequester(first))
            OptionButton("Stream", if (s.useSub) "sub" else "main", { s.useSub = !s.useSub })
            OptionButton("Transport", s.transport.name, { s.transport = if (s.transport == Transport.TCP) Transport.UDP else Transport.TCP })
            OptionButton("Audio", if (s.audio) "on" else "off", { s.audio = !s.audio })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OptionButton("Buffer", s.bufferProfile.label, {
                s.bufferProfile = BufferProfile.entries[(s.bufferProfile.ordinal + 1) % BufferProfile.entries.size]
            }, enabled = media3)
            OptionButton("Low-latency decoding", if (s.lowLatencyDecoding) "on" else "off", { s.lowLatencyDecoding = !s.lowLatencyDecoding }, enabled = media3)
            OptionButton("SPS rewrite", if (s.spsRewrite) "on" else "off", { s.spsRewrite = !s.spsRewrite }, enabled = !media3)
        }
        ButtonRow {
            Button(onClick = onRestart) { Text("Restart (new first-frame time)") }
            Button(onClick = onMark) { Text("Mark event in log") }
            Button(onClick = onClose) { Text("Close") }
            Button(onClick = onExit) { Text("Exit player") }
        }
    }
}

internal fun stateName(state: EngineState): String = when (state) {
    EngineState.Idle -> "idle"
    EngineState.Connecting -> "connecting"
    is EngineState.Playing -> "playing"
    is EngineState.Buffering -> "buffering"
    EngineState.Ended -> "ended"
    is EngineState.Error -> "error ${state.code}"
}

internal fun stateColor(state: EngineState): Color = when (state) {
    is EngineState.Playing -> TvColors.Ok
    is EngineState.Error, EngineState.Ended -> TvColors.Error
    else -> TvColors.Warn
}

internal fun fmt(v: Double): String = String.format(Locale.ROOT, "%.1f", v)
