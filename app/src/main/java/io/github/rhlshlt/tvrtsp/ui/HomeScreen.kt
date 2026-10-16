package io.github.rhlshlt.tvrtsp.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.rhlshlt.tvrtsp.BuildConfig
import io.github.rhlshlt.tvrtsp.data.CameraStore
import io.github.rhlshlt.tvrtsp.ui.components.MenuItem
import io.github.rhlshlt.tvrtsp.ui.components.SafeAreaPadding
import io.github.rhlshlt.tvrtsp.ui.components.ScreenTitle
import io.github.rhlshlt.tvrtsp.ui.components.focusWhenReady
import io.github.rhlshlt.tvrtsp.ui.theme.TvColors

@Composable
fun HomeScreen(open: (Screen) -> Unit) {
    val cameras by CameraStore.cameras.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.focusWhenReady() }
    val none = cameras.isEmpty()

    Column(Modifier.fillMaxSize().then(SafeAreaPadding)) {
        ScreenTitle(
            "TV RTSP Player · Phase 0",
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}) on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
        )
        LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
            item {
                MenuItem(
                    "Cameras (${cameras.size})",
                    { open(Screen.Cameras) },
                    Modifier.focusRequester(first),
                    subtitle = "Add your C320WS or the test-lab streams; kept in memory only",
                )
            }
            item {
                MenuItem(
                    "Play full screen",
                    { if (!none) open(Screen.Player(0)) else open(Screen.AddCamera) },
                    subtitle = if (none) "Add a camera first" else "Compare engines, measure first-frame time and delay",
                )
            }
            item {
                MenuItem(
                    "Multi-view capacity test",
                    { if (!none) open(Screen.MultiView) else open(Screen.AddCamera) },
                    subtitle = "1, 2, 4, 9 or 16 tiles: how many streams this TV decodes smoothly",
                )
            }
            item {
                MenuItem(
                    "Camera check",
                    { if (!none) open(Screen.CameraCheck) else open(Screen.AddCamera) },
                    subtitle = "RTSP handshake, SDP and SPS, keyframe interval, B-frames, bitrate",
                )
            }
            item { MenuItem("Device profile", { open(Screen.DeviceProfile) }, subtitle = "Decoders, levels, instances, suggested tier") }
            item { MenuItem("Results", { open(Screen.Results) }, subtitle = "CSV and report files to collect with adb pull") }
        }
        Text(
            "Measurements are written to the app's metrics folder. Nothing leaves the TV.",
            style = MaterialTheme.typography.bodySmall,
            color = TvColors.Muted,
        )
    }
}
