package io.github.rhlshlt.tvrtsp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import io.github.rhlshlt.tvrtsp.data.CameraStore
import io.github.rhlshlt.tvrtsp.ui.components.ButtonRow
import io.github.rhlshlt.tvrtsp.ui.components.MenuItem
import io.github.rhlshlt.tvrtsp.ui.components.SafeAreaPadding
import io.github.rhlshlt.tvrtsp.ui.components.ScreenTitle
import io.github.rhlshlt.tvrtsp.ui.components.focusWhenReady

@Composable
fun CamerasScreen(open: (Screen) -> Unit, back: () -> Unit) {
    val cameras by CameraStore.cameras.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.focusWhenReady() }

    Column(Modifier.fillMaxSize().then(SafeAreaPadding)) {
        ScreenTitle("Cameras", "OK plays a camera. The list lives in memory until the app closes.")
        ButtonRow {
            Button(onClick = { open(Screen.AddCamera) }, modifier = Modifier.focusRequester(first)) { Text("Add camera") }
            Button(onClick = { CameraStore.clear() }, enabled = cameras.isNotEmpty()) { Text("Remove all") }
            Button(onClick = back) { Text("Back") }
        }
        LazyColumn(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
            itemsIndexed(cameras, key = { _, c -> c.id }) { index, camera ->
                MenuItem(
                    title = camera.name,
                    subtitle = "${camera.kind.name.lowercase()} · ${camera.address} · main ${camera.main.pathAndQuery}" +
                        (camera.sub?.let { " · sub ${it.pathAndQuery}" } ?: ""),
                    onClick = { open(Screen.Player(index)) },
                )
            }
        }
    }
}
