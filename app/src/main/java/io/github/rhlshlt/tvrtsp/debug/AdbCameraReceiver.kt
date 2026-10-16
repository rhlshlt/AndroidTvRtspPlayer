package io.github.rhlshlt.tvrtsp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.rhlshlt.tvrtsp.core.rtsp.RtspUrlException
import io.github.rhlshlt.tvrtsp.core.rtsp.Tapo
import io.github.rhlshlt.tvrtsp.core.rtsp.TapoLens
import io.github.rhlshlt.tvrtsp.data.CameraStore

/**
 * Adds cameras from adb so nobody has to type passwords with a remote. The manifest enables it only
 * in debug and perf builds; cameras stay in memory, so start the app first. Examples
 * (docs/PHASE0_GUIDE.md):
 *
 * ```
 * adb shell am broadcast -a io.github.rhlshlt.tvrtsp.ADD_TAPO -n io.github.rhlshlt.tvrtsp/.debug.AdbCameraReceiver \
 *     --es name "Front door" --es host 192.168.1.20 --es user "<camera account>" --es pass "<password>"
 * adb shell am broadcast -a io.github.rhlshlt.tvrtsp.ADD_LAB -n io.github.rhlshlt.tvrtsp/.debug.AdbCameraReceiver \
 *     --es host 192.168.1.50 --ei port 8554 --es user viewer --es pass "<lab password>" --ei count 16
 * ```
 *
 * Only camera names are logged, never addresses with credentials.
 */
class AdbCameraReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = try {
            when (intent.action) {
                ACTION_ADD_TAPO -> {
                    val user = intent.getStringExtra("user").orEmpty()
                    val pass = intent.getStringExtra("pass").orEmpty()
                    val warnings = Tapo.validateAccount(user, pass).joinToString(" ") { it.message }
                    val lens = if (intent.getStringExtra("lens") == "telephoto") TapoLens.TELEPHOTO else TapoLens.STANDARD
                    val camera = CameraStore.addTapo(
                        intent.getStringExtra("name").orEmpty(),
                        intent.getStringExtra("host") ?: error("host is required"),
                        user,
                        pass,
                        lens,
                    )
                    "added ${camera.name}" + if (warnings.isNotEmpty()) " ($warnings)" else ""
                }
                ACTION_ADD_URL -> {
                    val camera = CameraStore.addUrl(
                        intent.getStringExtra("name").orEmpty(),
                        intent.getStringExtra("main") ?: error("main is required"),
                        intent.getStringExtra("sub"),
                        intent.getStringExtra("user"),
                        intent.getStringExtra("pass"),
                    )
                    "added ${camera.name}"
                }
                ACTION_ADD_LAB -> {
                    val added = CameraStore.addLab(
                        intent.getStringExtra("host") ?: error("host is required"),
                        intent.getIntExtra("port", 8554),
                        intent.getStringExtra("user"),
                        intent.getStringExtra("pass"),
                        intent.getIntExtra("count", 16),
                        intent.getStringExtra("path") ?: "lab",
                    )
                    "added ${added.size} lab cameras"
                }
                ACTION_CLEAR -> {
                    CameraStore.clear()
                    "cleared"
                }
                else -> "unknown action"
            }
        } catch (e: RtspUrlException) {
            "error: ${e.message}"
        } catch (e: IllegalStateException) {
            "error: ${e.message}"
        }
        Log.i(TAG, result)
        resultData = result
    }

    private companion object {
        const val TAG = "AdbCameraReceiver"
        const val ACTION_ADD_TAPO = "io.github.rhlshlt.tvrtsp.ADD_TAPO"
        const val ACTION_ADD_URL = "io.github.rhlshlt.tvrtsp.ADD_URL"
        const val ACTION_ADD_LAB = "io.github.rhlshlt.tvrtsp.ADD_LAB"
        const val ACTION_CLEAR = "io.github.rhlshlt.tvrtsp.CLEAR"
    }
}
