package io.github.rhlshlt.tvrtsp.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import io.github.rhlshlt.tvrtsp.data.Camera
import io.github.rhlshlt.tvrtsp.data.CameraStore
import io.github.rhlshlt.tvrtsp.data.PlayerSettings
import io.github.rhlshlt.tvrtsp.ui.components.ButtonRow
import io.github.rhlshlt.tvrtsp.ui.components.LatencyClock
import io.github.rhlshlt.tvrtsp.ui.components.OptionButton
import io.github.rhlshlt.tvrtsp.ui.components.SafeAreaPadding
import io.github.rhlshlt.tvrtsp.ui.components.ScreenTitle
import io.github.rhlshlt.tvrtsp.ui.components.focusWhenReady
import io.github.rhlshlt.tvrtsp.ui.theme.TvColors
import kotlinx.coroutines.delay

private val TILE_COUNTS = listOf(1, 2, 4, 9, 16)

private val MULTI_COLUMNS = listOf(
    "time_ms", "tiles", "engine", "stream", "tile", "camera", "state", "first_frame_ms", "width", "height",
    "fps", "frames_dropped", "decoder", "low_latency_applied", "playing_tiles", "total_fps",
    "app_cpu_pct_one_core", "threads", "java_heap_mb", "native_heap_mb", "pss_mb", "thermal",
)

/**
 * Decoder-capacity test: N tiles, each its own engine and decoder, started in waves. Tiles take
 * cameras round-robin, so with fewer cameras than tiles a camera appears several times; for 9 or
 * 16 tiles use the test lab, because a Tapo camera serves only about two RTSP viewers.
 */
@Composable
fun MultiViewScreen(back: () -> Unit) {
    var running by remember { mutableStateOf(false) }
    if (running) {
        BackHandler { running = false }
        MultiViewRun()
    } else {
        MultiViewSetup(onStart = { running = true }, back = back)
    }
}

@Composable
private fun MultiViewSetup(onStart: () -> Unit, back: () -> Unit) {
    val s = PlayerSettings
    val cameras by CameraStore.cameras.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.focusWhenReady() }
    Column(Modifier.fillMaxSize().then(SafeAreaPadding)) {
        ScreenTitle(
            "Multi-view capacity test",
            "${cameras.size} camera(s) available. Each tile opens its own RTSP session and decoder. " +
                "Back stops the test; a CSV row per tile is written every second.",
        )
        ButtonRow {
            OptionButton("Tiles", "${s.tiles}", {
                s.tiles = TILE_COUNTS[(TILE_COUNTS.indexOf(s.tiles) + 1) % TILE_COUNTS.size]
            }, Modifier.focusRequester(first))
            OptionButton("Engine", s.tileEngine.label, {
                s.tileEngine = if (s.tileEngine == EngineKind.MEDIA3) EngineKind.RTSP_CLIENT else EngineKind.MEDIA3
            })
            OptionButton("Stream", if (s.tilesUseSub) "sub (stream2)" else "main (stream1)", { s.tilesUseSub = !s.tilesUseSub })
            OptionButton("Start in waves of", "${s.waveSize}", { s.waveSize = if (s.waveSize == 4) 1 else if (s.waveSize == 1) 16 else 4 })
        }
        ButtonRow {
            Button(onClick = onStart, enabled = cameras.isNotEmpty()) { Text("Start") }
            Button(onClick = back) { Text("Back") }
        }
        val distinct = cameras.map { it.main.requestUri }.distinct().size
        if (cameras.any { it.kind == Camera.Kind.TAPO } && s.tiles > 2 * distinct) {
            Text(
                "Note: ${s.tiles} tiles on $distinct camera(s) exceeds Tapo's limit of about two RTSP viewers per camera; " +
                    "expect busy errors. Use the test lab for capacity tests.",
                color = TvColors.Warn,
            )
        }
    }
}

@Composable
private fun MultiViewRun() {
    val context = LocalContext.current
    val s = PlayerSettings
    val cameras by CameraStore.cameras.collectAsState()
    val foreground by AppVisibility.foreground.collectAsState()
    val tiles = s.tiles
    val kind = s.tileEngine
    val useSub = s.tilesUseSub
    val engines = remember(tiles, kind) { List(tiles) { i -> StreamEngine.create(kind, context, "tile-$i") } }
    DisposableEffect(engines) { onDispose { engines.forEach { it.release() } } }
    val logger = remember(engines) { MetricsLogger(MetricsLogger.directory(context), "multiview_${tiles}_${kind.name.lowercase()}", MULTI_COLUMNS) }
    DisposableEffect(logger) { onDispose { logger.close() } }
    val sampler = remember { ProcessSampler(context) }
    // Tiles take cameras round-robin; the list can change (or empty) while the test runs.
    fun cameraName(tile: Int): String = cameras.let { list -> if (list.isEmpty()) "" else list[tile % list.size].name }
    var stats by remember { mutableStateOf<List<EngineStats>>(emptyList()) }
    var fps by remember { mutableStateOf<List<Double?>>(emptyList()) }
    var sample by remember { mutableStateOf<ProcessSample?>(null) }
    var playingTiles by remember { mutableIntStateOf(0) }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Staggered start: a wave of tiles, then a pause, so a 16-tile start doesn't flood the network.
    LaunchedEffect(engines, useSub, foreground) {
        val list = cameras // one snapshot for the whole staggered start
        if (!foreground || list.isEmpty()) {
            engines.forEach { it.stop() }
            return@LaunchedEffect
        }
        engines.forEachIndexed { i, engine ->
            val camera = list[i % list.size]
            engine.start(
                StreamRequest(
                    url = camera.url(useSub),
                    label = camera.name,
                    transport = Transport.TCP,
                    audio = false,
                    lowLatencyDecoding = true,
                    bufferProfile = BufferProfile.BALANCED,
                ),
            )
            if ((i + 1) % s.waveSize == 0 && i + 1 < engines.size) delay(1_000)
        }
    }
    LaunchedEffect(engines) {
        var last = List(engines.size) { -1L }
        var lastAt = SystemClock.elapsedRealtime()
        while (true) {
            delay(1_000)
            val now = SystemClock.elapsedRealtime()
            val st = engines.map { it.stats() }
            val frames = st.map { it.framesRendered ?: it.framesReceived ?: -1L }
            fps = frames.mapIndexed { i, f -> if (f >= 0 && last[i] >= 0 && now > lastAt) (f - last[i]) * 1000.0 / (now - lastAt) else null }
            last = frames
            lastAt = now
            stats = st
            val ps = sampler.sample()
            sample = ps
            val playing = engines.count { it.state.value is EngineState.Playing }
            playingTiles = playing
            val total = fps.sumOf { it ?: 0.0 }
            st.forEachIndexed { i, t ->
                logger.log(listOf(
                    System.currentTimeMillis(), tiles, kind.name, if (useSub) "sub" else "main", i,
                    cameraName(i), stateName(engines[i].state.value), t.firstFrameMs,
                    t.width, t.height, fps.getOrNull(i), t.framesDropped, t.decoderName, t.lowLatencyDecoding,
                    playing, total, ps.cpuPercentOneCore, ps.threads, ps.javaHeapMb, ps.nativeHeapMb, ps.pssMb, ps.thermalStatus,
                ))
            }
        }
    }

    val columns = when (tiles) {
        1 -> 1
        2 -> 2
        4 -> 2
        9 -> 3
        else -> 4
    }
    val rows = (tiles + columns - 1) / columns
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    for (c in 0 until columns) {
                        val i = r * columns + c
                        Box(Modifier.weight(1f).fillMaxHeight().border(1.dp, Color(0xFF202124)), contentAlignment = Alignment.Center) {
                            if (i < engines.size) Tile(engines[i], stats.getOrNull(i), fps.getOrNull(i), cameraName(i))
                        }
                    }
                }
            }
        }
        val ps = sample
        Text(
            "$tiles tiles · ${kind.label} · ${if (useSub) "sub" else "main"} · playing $playingTiles/$tiles · total ${fmt(fps.sumOf { it ?: 0.0 })} fps · " +
                "dropped ${stats.sumOf { it.framesDropped ?: 0L }} · app CPU ${ps?.cpuPercentOneCore?.let { fmt(it) } ?: "–"}% · " +
                "threads ${ps?.threads ?: "–"} · Back stops",
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).background(TvColors.Panel, RoundedCornerShape(6.dp)).padding(8.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = Color.White,
        )
        LatencyClock(Modifier.align(Alignment.BottomEnd).padding(12.dp), fontSize = 24.sp)
    }
}

@Composable
private fun Tile(engine: StreamEngine, stats: EngineStats?, fps: Double?, name: String) {
    val state by engine.state.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        key(engine) {
            AndroidView(factory = { ctx -> engine.createView(ctx) }, modifier = Modifier.aspectRatio(16f / 9f))
        }
        Text(
            "$name · ${stateName(state)}" + (fps?.let { " · ${fmt(it)} fps" } ?: "") +
                (stats?.firstFrameMs?.let { " · ${it} ms" } ?: ""),
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp).background(TvColors.Panel).padding(horizontal = 4.dp),
            color = stateColor(state),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
