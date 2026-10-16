package io.github.rhlshlt.tvrtsp.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.darkColorScheme

object TvColors {
    val Background = Color(0xFF0F1115)
    val Panel = Color(0xE6181B21)
    val Live = Color(0xFFEA4335)
    val Ok = Color(0xFF81C995)
    val Warn = Color(0xFFFDD663)
    val Error = Color(0xFFF28B82)
    val Muted = Color(0xFF9AA0A6)
}

private val Scheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B1E3A),
    background = TvColors.Background,
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF1B1E24),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF2A2F37),
    onSurfaceVariant = Color(0xFFC4C7C5),
    border = Color(0xFF8AB4F8),
    error = TvColors.Error,
)

/** Dark, high-contrast theme for viewing from across a room. */
@Composable
fun TvRtspTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        ) {
            content()
        }
    }
}
