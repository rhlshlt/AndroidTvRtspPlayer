package io.github.rhlshlt.tvrtsp.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rhlshlt.tvrtsp.core.player.BufferProfile
import io.github.rhlshlt.tvrtsp.core.player.EngineKind
import io.github.rhlshlt.tvrtsp.core.player.Transport
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

    // Multi-view capacity test
    var tiles by mutableIntStateOf(4)
    var tileEngine by mutableStateOf(EngineKind.MEDIA3)
    var tilesUseSub by mutableStateOf(true)
    var waveSize by mutableIntStateOf(4)
}

/** Whether the activity is visible; screens stop every stream when it isn't. */
object AppVisibility {
    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground

    fun set(visible: Boolean) {
        _foreground.value = visible
    }
}
