package io.github.rhlshlt.tvrtsp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.rhlshlt.tvrtsp.data.AppVisibility
import io.github.rhlshlt.tvrtsp.ui.AppRoot
import io.github.rhlshlt.tvrtsp.ui.theme.TvRtspTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvRtspTheme {
                AppRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.set(true)
    }

    override fun onStop() {
        // Live streams must not keep running (or holding a Tapo viewer slot) in the background.
        AppVisibility.set(false)
        super.onStop()
    }
}
