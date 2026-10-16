package io.github.rhlshlt.tvrtsp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import io.github.rhlshlt.tvrtsp.core.perf.MetricsLogger
import io.github.rhlshlt.tvrtsp.ui.components.MenuItem
import io.github.rhlshlt.tvrtsp.ui.components.SafeAreaPadding
import io.github.rhlshlt.tvrtsp.ui.components.ScreenTitle
import io.github.rhlshlt.tvrtsp.ui.components.ScrollableText
import io.github.rhlshlt.tvrtsp.ui.components.focusWhenReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Lists the metrics folder and shows how to pull it to a computer. */
@Composable
fun ResultsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var preview by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val dir = remember { MetricsLogger.directory(context) }
    val pull = "adb pull ${dir.absolutePath.replace("/storage/emulated/0", "/sdcard")}"

    suspend fun reload() {
        files = withContext(Dispatchers.IO) { dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty() }
    }
    LaunchedEffect(Unit) {
        reload()
        first.focusWhenReady()
    }

    Column(Modifier.fillMaxSize().then(SafeAreaPadding)) {
        ScreenTitle("Results", "${files.size} file(s). On your computer: $pull")
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(420.dp).fillMaxHeight()) {
                items(files, key = { it.name }) { f ->
                    val modifier = if (f == files.first()) Modifier.focusRequester(first) else Modifier
                    MenuItem(f.name, onClick = {
                        scope.launch {
                            preview = withContext(Dispatchers.IO) {
                                val text = f.readText()
                                if (text.length > 60_000) text.take(60_000) + "\n… (truncated; pull the file to see all of it)" else text
                            }
                        }
                    }, modifier = modifier, subtitle = "${f.length() / 1024} KB")
                }
                item {
                    // Deleting needs a second press, so a stray OK can't lose unpulled measurements.
                    Button(onClick = {
                        if (!confirmDelete) {
                            confirmDelete = true
                        } else {
                            confirmDelete = false
                            scope.launch {
                                withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
                                preview = ""
                                reload()
                            }
                        }
                    }, enabled = files.isNotEmpty(), modifier = Modifier.padding(top = 8.dp)) {
                        Text(if (confirmDelete) "Press again to delete ${files.size} file(s)" else "Delete all results")
                    }
                }
            }
            ScrollableText(
                preview.ifEmpty { "OK on a file shows it here." },
                Modifier.padding(start = 16.dp).fillMaxSize().then(if (files.isEmpty()) Modifier.focusRequester(first) else Modifier),
            )
        }
    }
}
