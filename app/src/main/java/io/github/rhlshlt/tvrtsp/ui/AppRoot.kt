package io.github.rhlshlt.tvrtsp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

/** Screens of the Phase 0 app. */
sealed interface Screen {
    data object Home : Screen
    data object Cameras : Screen
    data object AddCamera : Screen
    data class Player(val cameraIndex: Int) : Screen
    data object MultiView : Screen
    data object CameraCheck : Screen
    data object DeviceProfile : Screen
    data object Results : Screen
}

/** A simple back stack: Back pops one screen; screens with overlays handle Back first. */
@Composable
fun AppRoot() {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val open: (Screen) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { back() }

    val current = stack.last()
    key(current) {
        when (current) {
            Screen.Home -> HomeScreen(open)
            Screen.Cameras -> CamerasScreen(open, back)
            Screen.AddCamera -> AddCameraScreen(back)
            is Screen.Player -> PlayerScreen(current.cameraIndex, back)
            Screen.MultiView -> MultiViewScreen(back)
            Screen.CameraCheck -> CameraCheckScreen()
            Screen.DeviceProfile -> DeviceProfileScreen()
            Screen.Results -> ResultsScreen()
        }
    }
}
