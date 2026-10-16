package io.github.rhlshlt.tvrtsp.core.perf

import android.content.Context
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * One sample of this app's process. CPU is this process only: hardware decoding runs in the
 * media codec service, so use `adb shell top` for the whole picture (see docs/PHASE0_GUIDE.md).
 */
data class ProcessSample(
    /** CPU time used since the previous sample, as a percentage of one core (top's %CPU). */
    val cpuPercentOneCore: Double?,
    /** The same, as a share of all cores. */
    val cpuPercentAllCores: Double?,
    val threads: Int,
    val javaHeapMb: Double,
    val nativeHeapMb: Double,
    /** Proportional set size in MB; read every few samples because it is slower. */
    val pssMb: Double?,
    /** PowerManager thermal status: 0 none … 6 shutdown. */
    val thermalStatus: Int,
)

/** Samples CPU, threads, memory and thermal state. Call [sample] about once a second. */
class ProcessSampler(context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val ticksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: 100L
    private val cores = Runtime.getRuntime().availableProcessors()
    private var lastTicks = -1L
    private var lastWall = 0L
    private var count = 0
    private var lastPss: Double? = null

    fun sample(): ProcessSample {
        val ticks = cpuTicks()
        val now = SystemClock.elapsedRealtime()
        var oneCore: Double? = null
        if (lastTicks >= 0 && ticks >= 0 && now > lastWall) {
            val cpuSec = (ticks - lastTicks).toDouble() / ticksPerSecond
            oneCore = cpuSec / ((now - lastWall) / 1000.0) * 100.0
        }
        lastTicks = ticks
        lastWall = now
        if (count++ % 5 == 0) lastPss = Debug.getPss() / 1024.0
        val rt = Runtime.getRuntime()
        return ProcessSample(
            cpuPercentOneCore = oneCore,
            cpuPercentAllCores = oneCore?.div(cores),
            threads = File("/proc/self/task").list()?.size ?: Thread.activeCount(),
            javaHeapMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576.0,
            nativeHeapMb = Debug.getNativeHeapAllocatedSize() / 1_048_576.0,
            pssMb = lastPss,
            thermalStatus = power.currentThermalStatus,
        )
    }

    /** utime + stime from /proc/self/stat (fields 14 and 15), in clock ticks; -1 if unreadable. */
    private fun cpuTicks(): Long = try {
        val stat = File("/proc/self/stat").readText()
        // The command name (field 2) is in parentheses and may contain spaces.
        val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
        fields[11].toLong() + fields[12].toLong()
    } catch (_: Exception) {
        -1L
    }
}
