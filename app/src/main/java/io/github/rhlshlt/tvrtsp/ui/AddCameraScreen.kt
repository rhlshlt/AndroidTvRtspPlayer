package io.github.rhlshlt.tvrtsp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.rhlshlt.tvrtsp.core.rtsp.RtspUrlException
import io.github.rhlshlt.tvrtsp.core.rtsp.Tapo
import io.github.rhlshlt.tvrtsp.core.rtsp.TapoAccountIssue
import io.github.rhlshlt.tvrtsp.core.rtsp.TapoLens
import io.github.rhlshlt.tvrtsp.data.CameraStore
import io.github.rhlshlt.tvrtsp.ui.components.ButtonRow
import io.github.rhlshlt.tvrtsp.ui.components.OptionButton
import io.github.rhlshlt.tvrtsp.ui.components.SafeAreaPadding
import io.github.rhlshlt.tvrtsp.ui.components.ScreenTitle
import io.github.rhlshlt.tvrtsp.ui.components.TvTextField
import io.github.rhlshlt.tvrtsp.ui.components.focusWhenReady
import io.github.rhlshlt.tvrtsp.ui.theme.TvColors

/**
 * Three ways to add cameras: a Tapo camera by IP address, the test lab's streams, or any RTSP URL.
 * Typing with a remote is slow; in debug and perf builds adb can add cameras instead
 * (docs/PHASE0_GUIDE.md).
 */
@Composable
fun AddCameraScreen(back: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.focusWhenReady() }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) } // (isError, text)

    // Tapo
    var tapoName by remember { mutableStateOf("") }
    var tapoHost by remember { mutableStateOf("") }
    var tapoUser by remember { mutableStateOf("") }
    var tapoPass by remember { mutableStateOf("") }
    var telephoto by remember { mutableStateOf(false) }
    // Test lab
    var labHost by remember { mutableStateOf("") }
    var labPort by remember { mutableStateOf("8554") }
    var labUser by remember { mutableStateOf("viewer") }
    var labPass by remember { mutableStateOf("") }
    var labCount by remember { mutableStateOf("16") }
    // Any URL
    var urlName by remember { mutableStateOf("") }
    var urlMain by remember { mutableStateOf("rtsp://") }
    var urlSub by remember { mutableStateOf("") }
    var urlUser by remember { mutableStateOf("") }
    var urlPass by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().then(SafeAreaPadding)) {
        ScreenTitle("Add camera", "Values stay in memory only; nothing is saved to the TV's storage in Phase 0.")
        message?.let { (isError, text) ->
            Text(text, color = if (isError) TvColors.Error else TvColors.Ok, modifier = Modifier.padding(bottom = 8.dp))
        }
        LazyColumn(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
            item { Section("Tapo camera (C320WS)") }
            item { TvTextField("Name", tapoName, { tapoName = it }, Modifier.focusRequester(first), hint = "For example Front door") }
            item {
                TvTextField("IP address", tapoHost, { tapoHost = it }, keyboardType = KeyboardType.Uri,
                    hint = "For example 192.168.1.20, or 192.168.1.20:8554 when RTSP isn't on port 554. Reserve it in your router so it doesn't change")
            }
            item { TvTextField("Camera Account user name", tapoUser, { tapoUser = it }, hint = "Tapo app → camera → Device Settings → Advanced Settings → Camera Account") }
            item { TvTextField("Camera Account password", tapoPass, { tapoPass = it }, password = true) }
            item {
                ButtonRow {
                    OptionButton("Lens", if (telephoto) "telephoto (stream6/7)" else "standard (stream1/2)", { telephoto = !telephoto })
                    Button(onClick = {
                        val issues = Tapo.validateAccount(tapoUser, tapoPass)
                        val errors = issues.filter { it.severity == TapoAccountIssue.Severity.ERROR }
                        message = when {
                            tapoHost.isBlank() -> true to "Enter the camera's IP address"
                            errors.isNotEmpty() -> true to errors.joinToString(" ") { it.message }
                            else -> try {
                                val cam = CameraStore.addTapo(tapoName, tapoHost.trim(), tapoUser, tapoPass,
                                    if (telephoto) TapoLens.TELEPHOTO else TapoLens.STANDARD)
                                tapoPass = ""
                                false to "Added ${cam.name}. " + issues.joinToString(" ") { it.message }
                            } catch (e: RtspUrlException) {
                                true to (e.message ?: "Invalid address")
                            }
                        }
                    }) { Text("Add Tapo camera") }
                }
            }

            item { Section("Test lab (MediaMTX, docs/TEST_LAB.md)") }
            item { TvTextField("Lab PC address", labHost, { labHost = it }, keyboardType = KeyboardType.Uri) }
            item { TvTextField("Port", labPort, { labPort = it }, keyboardType = KeyboardType.Number) }
            item { TvTextField("Viewer user name", labUser, { labUser = it }) }
            item { TvTextField("Viewer password", labPass, { labPass = it }, password = true) }
            item { TvTextField("How many tiles to create", labCount, { labCount = it }, keyboardType = KeyboardType.Number, hint = "All read the same lab streams") }
            item {
                ButtonRow {
                    Button(onClick = {
                        val port = labPort.toIntOrNull()
                        val count = labCount.toIntOrNull()
                        message = when {
                            labHost.isBlank() -> true to "Enter the lab PC's address"
                            port == null || port !in 1..65535 -> true to "Port must be 1–65535"
                            count == null || count !in 1..32 -> true to "Create 1–32 lab cameras"
                            else -> try {
                                CameraStore.addLab(labHost.trim(), port, labUser.ifBlank { null }, labPass.ifBlank { null }, count)
                                false to "Added $count lab cameras"
                            } catch (e: RtspUrlException) {
                                true to (e.message ?: "Invalid address")
                            }
                        }
                    }) { Text("Add lab cameras") }
                }
            }

            item { Section("Any RTSP URL") }
            item { TvTextField("Name", urlName, { urlName = it }) }
            item { TvTextField("Main stream URL", urlMain, { urlMain = it }, keyboardType = KeyboardType.Uri) }
            item { TvTextField("Sub-stream URL (optional)", urlSub, { urlSub = it }, keyboardType = KeyboardType.Uri) }
            item { TvTextField("User name (optional)", urlUser, { urlUser = it }) }
            item { TvTextField("Password (optional)", urlPass, { urlPass = it }, password = true) }
            item {
                ButtonRow {
                    Button(onClick = {
                        message = try {
                            val cam = CameraStore.addUrl(urlName, urlMain, urlSub, urlUser.ifBlank { null }, urlPass.ifBlank { null })
                            urlPass = ""
                            false to "Added ${cam.name}"
                        } catch (e: RtspUrlException) {
                            true to (e.message ?: "Invalid URL")
                        }
                    }) { Text("Add URL") }
                    Button(onClick = back) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
}
