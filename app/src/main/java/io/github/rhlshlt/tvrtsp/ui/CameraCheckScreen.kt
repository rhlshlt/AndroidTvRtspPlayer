package io.github.rhlshlt.tvrtsp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.rhlshlt.tvrtsp.core.perf.DecoderCheck
import io.github.rhlshlt.tvrtsp.core.perf.MetricsLogger
import io.github.rhlshlt.tvrtsp.core.rtsp.CameraCheck
import io.github.rhlshlt.tvrtsp.core.rtsp.CameraCheckOptions
import io.github.rhlshlt.tvrtsp.data.CameraStore
import io.github.rhlshlt.tvrtsp.ui.components.MenuItem
import io.github.rhlshlt.tvrtsp.ui.components.OptionButton
import io.github.rhlshlt.tvrtsp.ui.components.SafeAreaPadding
import io.github.rhlshlt.tvrtsp.ui.components.ScreenTitle
import io.github.rhlshlt.tvrtsp.ui.components.ScrollableText
import io.github.rhlshlt.tvrtsp.ui.components.focusWhenReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the same camera check as the desktop camcheck tool, from the TV's network position, plus
 * a decoder check against this TV's codecs. Results are saved as text and JSON.
 */
@Composable
fun CameraCheckScreen() {
    val context = LocalContext.current
    val cameras by CameraStore.cameras.collectAsState()
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(15) }
    var report by remember { mutableStateOf("Pick a camera. The check connects to its main and sub stream in turn and receives each for the chosen time.") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.focusWhenReady() }

    Column(Modifier.fillMaxSize().then(SafeAreaPadding)) {
        ScreenTitle("Camera check", "Close other viewers first: each check uses one of the camera's RTSP slots.")
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(320.dp).fillMaxHeight()) {
                item {
                    OptionButton("Receive for", "$seconds s", {
                        seconds = when (seconds) {
                            15 -> 30
                            30 -> 60
                            60 -> 0
                            else -> 15
                        }
                    }, Modifier.focusRequester(first).padding(bottom = 8.dp))
                }
                items(cameras, key = { it.id }) { camera ->
                    MenuItem(camera.name, onClick = {
                        if (running) return@MenuItem
                        running = true
                        report = "Checking ${camera.name}…"
                        scope.launch {
                            val text = withContext(Dispatchers.IO) {
                                val sb = StringBuilder()
                                val targets = listOfNotNull("main" to camera.main, camera.sub?.let { "sub" to it })
                                for ((label, url) in targets) {
                                    val r = CameraCheck().run(url, CameraCheckOptions(analyzeMs = seconds * 1000L, userAgent = "TvRtspPlayer-check/0.1"))
                                    sb.append("== ").append(camera.name).append(" · ").append(label).append('\n')
                                    sb.append(r.toText(includeSdp = true)).append(DecoderCheck.verdict(r)).append("\n\n")
                                    MetricsLogger.writeText(context, "check_${safeName(camera.name)}_$label.json", r.toJson())
                                }
                                val file = MetricsLogger.writeText(context, "check_${safeName(camera.name)}.txt", sb.toString())
                                sb.append("Saved as ").append(file.name).append(" in the metrics folder.")
                                sb.toString()
                            }
                            report = text
                            running = false
                        }
                    }, subtitle = camera.address)
                }
            }
            ScrollableText(report, Modifier.padding(start = 16.dp).fillMaxSize())
        }
    }
}

internal fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9_-]"), "_")
