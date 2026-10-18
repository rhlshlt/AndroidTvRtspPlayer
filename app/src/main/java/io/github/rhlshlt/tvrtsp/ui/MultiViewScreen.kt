package io.github.rhlshlt.tvrtsp.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.draw.clipToBounds
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
import io.github.rhlshlt.tvrtsp.core.player.VideoView
import io.github.rhlshlt.tvrtsp.data.AppVisibility
import io.github.rhlshlt.tvrtsp.data.Camera
import io.github.rhlshlt.tvrtsp.data.CameraStore
import io.github.rhlshlt.tvrtsp.data.PlayerSettings
import io.github.rhlshlt.tvrtsp.data.TileDecoders
import io.github.rhlshlt.tvrtsp.ui.components.ButtonRow
import io.github.rhlshlt.tvrtsp.ui.components.LatencyClock
import io.github.rhlshlt.tvrtsp.ui.components.OptionButton
import io.github.rhlshlt.tvrtsp.ui.components.SafeAreaPadding
import io.github.rhlshlt.tvrtsp.ui.components.ScreenTitle
import io.github.rhlshlt.tvrtsp.ui.components.focusWhenReady
import io.github.rhlshlt.tvrtsp.ui.theme.TvColors
import kotlinx.coroutines.delay

private val TILE_COUNTS = listOf(1, 2, 4, 9, 16)

// New columns go at the end so older CSVs keep their column positions.
private val MULTI_COLUMNS = listOf(
    "time_ms", "tiles", "engine", "stream", "tile", "camera", "state", "first_frame_ms", "width", "height",
    "fps", "frames_dropped", "decoder", "low_latency_applied", "playing_tiles", "total_fps",
    "app_cpu_pct_one_core", "threads", "java_heap_mb", "native_heap_mb", "pss_mb", "thermal",
    "view", "clock", "decoder_low_latency_supported", "buffered_ms", "guard_restarts", "decoder_preference",
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
            OptionButton("Video view", tileView(s.tileEngine, s.tileView).label, {
                s.tileView = if (s.tileView == VideoView.SURFACE_VIEW) VideoView.TEXTURE_VIEW else VideoView.SURFACE_VIEW
            }, enabled = s.tileEngine.textureView)
            OptionButton("Decoders", s.tileDecoders.label, {
                s.tileDecoders = TileDecoders.entries[(s.tileDecoders.ordinal + 1) % TileDecoders.entries.size]
            })
            OptionButton("Clock", if (s.tileClock) "on" else "off", { s.tileClock = !s.tileClock })
        }
        ButtonRow {
            Button(onClick = onStart, enabled = cameras.isNotEmpty()) { Text("Start") }
            Button(onClick = back) { Text("Back") }
        }
        Text(
            "SurfaceView hands video to the display hardware; TextureView draws it with the GPU (Media3 only). " +
                "If a tile stays black while its label says playing, compare the two, or keep hardware decoding for one tile only.",
            color = TvColors.Muted,
        )
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

/** The view the tiles will really use: engines without TextureView support use a SurfaceView. */
private fun tileView(kind: EngineKind, wanted: VideoView): VideoView = if (kind.textureView) wanted else VideoView.SURFACE_VIEW

@Composable
private fun MultiViewRun() {
    val context = LocalContext.current
    val s = PlayerSettings
    val cameras by CameraStore.cameras.collectAsState()
    val foreground by AppVisibility.foreground.collectAsState()
    val tiles = s.tiles
    val kind = s.tileEngine
    val useSub = s.tilesUseSub
    val videoView = tileView(kind, s.tileView)
    val clock = s.tileClock
    val decoders = s.tileDecoders
    val engines = remember(tiles, kind, videoView, decoders) { List(tiles) { i -> StreamEngine.create(kind, context, "tile-$i", videoView) } }
    DisposableEffect(engines) { onDispose { engines.forEach { it.release() } } }
    val logger = remember(engines) {
        val name = "multiview_${tiles}_${kind.name.lowercase()}" +
            (if (videoView == VideoView.TEXTURE_VIEW) "_textureview" else "") +
            when (decoders) {
                TileDecoders.HARDWARE_FIRST -> ""
                TileDecoders.SOFTWARE -> "_software"
                TileDecoders.ONE_HARDWARE -> "_1hardware"
            }
        MetricsLogger(MetricsLogger.directory(context), name, MULTI_COLUMNS)
    }
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
                    preferSoftwareDecoder = decoders.preferSoftware(i),
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
            // Counters restart from zero when the latency guard rebuilds a tile's player.
            fps = frames.mapIndexed { i, f -> if (f >= 0 && last[i] in 0..f && now > lastAt) (f - last[i]) * 1000.0 / (now - lastAt) else null }
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
                    videoView.label, if (clock) "on" else "off", t.decoderLowLatencySupported, t.bufferedMs, t.guardRestarts,
                    if (decoders.preferSoftware(i)) "software" else "hardware",
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
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // All videos first, then everything drawn on top of them. On the first test TV, a label
        // drawn before a later tile's SurfaceView disappeared; only things drawn after the last
        // video (the last label, this bar and the clock) stayed visible.
        TileGrid(tiles, columns) { i -> VideoTile(engines[i]) }
        TileGrid(tiles, columns, Modifier.border(1.dp, Color(0xFF202124))) { i ->
            TileLabel(engines[i], stats.getOrNull(i), fps.getOrNull(i), cameraName(i))
        }
        val ps = sample
        // Two lines, so the clock in the bottom-right corner doesn't cover the end.
        Text(
            "$tiles tiles · ${kind.label} · ${videoView.label} · ${if (useSub) "sub" else "main"} · decoders ${decoders.label}\n" +
                "playing $playingTiles/$tiles · total ${fmt(fps.sumOf { it ?: 0.0 })} fps · dropped ${stats.sumOf { it.framesDropped ?: 0L }} · " +
                "app CPU ${ps?.cpuPercentOneCore?.let { fmt(it) } ?: "–"}% · threads ${ps?.threads ?: "–"} · Back stops",
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).background(TvColors.Panel, RoundedCornerShape(6.dp)).padding(8.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = Color.White,
        )
        if (clock) LatencyClock(Modifier.align(Alignment.BottomEnd).padding(12.dp), fontSize = 24.sp)
    }
}

/** Lays [tiles] cells out in a grid of [columns]; [cellModifier] applies to every cell. */
@Composable
private fun TileGrid(tiles: Int, columns: Int, cellModifier: Modifier = Modifier, cell: @Composable BoxScope.(Int) -> Unit) {
    val rows = (tiles + columns - 1) / columns
    Column(Modifier.fillMaxSize()) {
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (c in 0 until columns) {
                    val i = r * columns + c
                    Box(Modifier.weight(1f).fillMaxHeight().then(cellModifier), contentAlignment = Alignment.Center) {
                        if (i < tiles) cell(i)
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoTile(engine: StreamEngine) {
    key(engine) {
        // clipToBounds keeps a SurfaceView's see-through hole inside its own tile.
        AndroidView(factory = { ctx -> engine.createView(ctx) }, modifier = Modifier.aspectRatio(16f / 9f).clipToBounds())
    }
}

@Composable
private fun TileLabel(engine: StreamEngine, stats: EngineStats?, fps: Double?, name: String) {
    val state by engine.state.collectAsState()
    // Same size and place as the video, so the label sits on the video's top-left corner.
    Box(Modifier.aspectRatio(16f / 9f)) {
        Text(
            "$name · ${stateName(state)}" + (fps?.let { " · ${fmt(it)} fps" } ?: "") +
                (stats?.firstFrameMs?.let { " · $it ms" } ?: "") +
                (stats?.guardRestarts?.takeIf { it > 0 }?.let { " · guard restarts $it" } ?: ""),
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp).background(TvColors.Panel).padding(horizontal = 4.dp),
            color = stateColor(state),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
