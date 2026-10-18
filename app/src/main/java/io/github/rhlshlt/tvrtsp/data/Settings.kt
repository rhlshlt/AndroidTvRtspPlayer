package io.github.rhlshlt.tvrtsp.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rhlshlt.tvrtsp.core.player.BufferProfile
import io.github.rhlshlt.tvrtsp.core.player.EngineKind
import io.github.rhlshlt.tvrtsp.core.player.Transport
import io.github.rhlshlt.tvrtsp.core.player.VideoView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Test settings shared by the player and multi-view screens for this app session. */
object PlayerSettings {
    var engine by mutableStateOf(EngineKind.MEDIA3)
    var useSub by mutableStateOf(false)
    var transport by mutableStateOf(Transport.TCP)
    var bufferProfile by mutableStateOf(BufferProfile.BALANCED)
    var lowLatencyDecoding by mutableStateOf(true)
    var spsRewrite by mutableStateOf(false)
    var audio by mutableStateOf(false)
    /** Media3: restart the player when video piles up (BufferProfile.guardMs). */
    var latencyGuard by mutableStateOf(true)
    var preferSoftwareDecoder by mutableStateOf(false)

    // Multi-view capacity test
    var tiles by mutableIntStateOf(4)
    var tileEngine by mutableStateOf(EngineKind.MEDIA3)
    var tilesUseSub by mutableStateOf(true)
    var waveSize by mutableIntStateOf(4)
    var tileView by mutableStateOf(VideoView.SURFACE_VIEW)
    var tileClock by mutableStateOf(true)
    var tileDecoders by mutableStateOf(TileDecoders.HARDWARE_FIRST)
}

/**
 * Which decoder each multi-view tile tries first. Android picks hardware first and falls back to
 * software when the hardware instances run out; on the first test TV only one hardware-decoded
 * SurfaceView tile was visible, so [ONE_HARDWARE] keeps hardware for the first tile only.
 */
enum class TileDecoders(val label: String) {
    HARDWARE_FIRST("hardware first"),
    SOFTWARE("software"),
    ONE_HARDWARE("1 hardware, rest software");

    /** Whether [tile] (0-based) should try the software decoder first. */
    fun preferSoftware(tile: Int): Boolean = when (this) {
        HARDWARE_FIRST -> false
        SOFTWARE -> true
        ONE_HARDWARE -> tile > 0
    }
}

/** Whether the activity is visible; screens stop every stream when it isn't. */
object AppVisibility {
    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground

    fun set(visible: Boolean) {
        _foreground.value = visible
    }
}
