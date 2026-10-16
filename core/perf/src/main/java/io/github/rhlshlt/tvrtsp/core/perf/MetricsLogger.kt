package io.github.rhlshlt.tvrtsp.core.perf

import android.content.Context
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Writes Phase 0 measurements as CSV on a background thread. Files go to
 * `Android/data/<package>/files/metrics/` so `adb pull` can collect them without root.
 *
 * Never put URLs or credentials in a row; use camera names.
 */
class MetricsLogger(directory: File, testName: String, private val columns: List<String>) : Closeable {
    val file: File = File(directory, "${stamp()}_${testName.replace(Regex("[^A-Za-z0-9_-]"), "_")}.csv")
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "metrics-writer").apply { isDaemon = true } }
    private var writer: BufferedWriter? = null
    private var rows = 0

    init {
        executor.execute {
            directory.mkdirs()
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8)).also {
                it.write(columns.joinToString(",") { c -> csv(c) })
                it.newLine()
            }
        }
    }

    /** Adds one row; values are matched to [columns] by position. */
    fun log(values: List<Any?>) {
        val line = values.joinToString(",") { csv(it) }
        executor.execute {
            writer?.let {
                it.write(line)
                it.newLine()
                if (++rows % 10 == 0) it.flush()
            }
        }
    }

    /** Writes a free-text note row (for example "camera unplugged") with the same column count. */
    fun note(text: String) = log(listOf(System.currentTimeMillis(), "note", text) + List((columns.size - 3).coerceAtLeast(0)) { null })

    /** Flushes and closes the file; queued rows are still written. Doesn't block the caller. */
    override fun close() {
        executor.execute {
            writer?.flush()
            writer?.close()
            writer = null
        }
        executor.shutdown()
    }

    companion object {
        fun directory(context: Context): File = context.getExternalFilesDir("metrics") ?: File(context.filesDir, "metrics")

        fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())

        /** Writes a whole text file (for example a camera report) on the caller's thread. */
        fun writeText(context: Context, name: String, text: String): File {
            val dir = directory(context).also { it.mkdirs() }
            return File(dir, "${stamp()}_$name").also { it.writeText(text, Charsets.UTF_8) }
        }

        private fun csv(v: Any?): String {
            val s = when (v) {
                null -> ""
                is Double -> if (v.isNaN()) "" else String.format(Locale.ROOT, "%.2f", v)
                is Float -> String.format(Locale.ROOT, "%.2f", v)
                else -> v.toString()
            }
            return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }
    }
}
