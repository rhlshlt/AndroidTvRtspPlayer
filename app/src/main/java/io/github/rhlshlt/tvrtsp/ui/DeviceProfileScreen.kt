package io.github.rhlshlt.tvrtsp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import io.github.rhlshlt.tvrtsp.core.perf.DeviceProfile
import io.github.rhlshlt.tvrtsp.core.perf.DeviceProfiler
import io.github.rhlshlt.tvrtsp.core.perf.MetricsLogger
import io.github.rhlshlt.tvrtsp.ui.components.SafeAreaPadding
import io.github.rhlshlt.tvrtsp.ui.components.ScreenTitle
import io.github.rhlshlt.tvrtsp.ui.components.ScrollableText
import io.github.rhlshlt.tvrtsp.ui.components.focusWhenReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shows the decoder and device profile and saves it as JSON in the metrics folder. */
@Composable
fun DeviceProfileScreen() {
    val context = LocalContext.current
    var text by remember { mutableStateOf("Reading the decoder list…") }
    var saved by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.focusWhenReady()
        val profile = withContext(Dispatchers.Default) { DeviceProfiler.profile(context) }
        text = describe(profile)
        saved = withContext(Dispatchers.IO) {
            MetricsLogger.writeText(context, "device-profile.json", profile.toJson()).name
        }
    }
    Column(Modifier.fillMaxSize().then(SafeAreaPadding)) {
        ScreenTitle("Device profile", saved?.let { "Saved as $it in the metrics folder. Up/Down scroll." } ?: "Up/Down scroll.")
        ScrollableText(text, Modifier.fillMaxWidth().weight(1f).focusRequester(focus))
    }
}

private fun describe(p: DeviceProfile): String = buildString {
    appendLine("${p.manufacturer} ${p.model} (${p.device}) · Android ${p.androidRelease} (API ${p.sdkInt}) · patch ${p.securityPatch}")
    appendLine("SoC ${p.soc ?: "unknown"} · ${p.cpuCores} CPU cores · RAM ${p.totalRamMb} MB · low-RAM ${if (p.lowRamDevice) "yes" else "no"} · " +
        "heap class ${p.memoryClassMb} MB (large ${p.largeMemoryClassMb} MB) · TV ${if (p.isTv) "yes" else "no"}")
    appendLine("Display ${p.display} · HDR ${p.hdrTypes.ifEmpty { listOf("none") }.joinToString(", ")}")
    appendLine("Modes ${p.displayModes.joinToString(", ")}")
    appendLine()
    appendLine("Suggested tier: ${p.tier} (provisional; Phase 0 calibrates it)")
    p.tierReasons.forEach { appendLine("  · $it") }
    appendLine()
    appendLine("Tapo C320WS on this TV")
    p.c320ws.forEach { (k, v) -> appendLine("  $k: $v") }
    appendLine()
    for (mime in listOf("video/avc", "video/hevc")) {
        appendLine(if (mime == "video/avc") "H.264 decoders" else "H.265 decoders")
        p.decoders.filter { it.mime == mime }.forEach { d ->
            val kind = when {
                d.hardware -> "hardware"
                d.softwareOnly -> "software"
                else -> "unknown"
            } + if (d.alias) ", alias" else ""
            appendLine("  ${d.name} ($kind) · max ${d.maxWidth}×${d.maxHeight} · Level ${d.maxLevel ?: "?"} · " +
                "${d.maxInstances} instances · low-latency ${if (d.lowLatency) "yes" else "no"}")
            d.supports.forEach { (target, ok) ->
                val fps = d.achievableFps[target]?.let { " · achievable $it fps" } ?: ""
                val est = d.streamEstimate[target]?.let { " · about $it at once" } ?: if (ok) " · capacity unknown" else ""
                appendLine("      $target: ${if (ok) "yes" else "no"}$fps$est")
            }
            appendLine("      performance points: ${d.performancePoints?.joinToString(", ")?.ifEmpty { "none" } ?: "not published"}")
        }
        appendLine()
    }
}
