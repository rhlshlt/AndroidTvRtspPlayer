package io.github.rhlshlt.tvrtsp.core.perf

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import io.github.rhlshlt.tvrtsp.core.rtsp.Json
import java.util.Locale

/** A video format to plan for, for example the C320WS main stream. */
data class VideoTarget(val label: String, val width: Int, val height: Int, val fps: Int) {
    override fun toString(): String = "$label ($width×$height at $fps fps)"

    companion object {
        val C320WS_MAIN = VideoTarget("stream1", 2560, 1440, 15)
        val C320WS_SUB = VideoTarget("stream2", 640, 360, 15)
    }
}

/** What one hardware or software decoder says it can do. */
data class DecoderInfo(
    val name: String,
    val mime: String,
    val hardware: Boolean,
    val softwareOnly: Boolean,
    val vendor: Boolean,
    val alias: Boolean,
    val maxInstances: Int,
    val maxWidth: Int,
    val maxHeight: Int,
    /** Highest level listed for Main or High profile (H.264) or Main profile (H.265), as text. */
    val maxLevel: String?,
    /** H.264 level_idc of [maxLevel] (for example 51), null for H.265. */
    val maxLevelIdc: Int?,
    val lowLatency: Boolean,
    val tunneled: Boolean,
    val adaptive: Boolean,
    /** Published performance points, or null when the codec publishes none. */
    val performancePoints: List<String>?,
    val supports: Map<String, Boolean>,
    /** Achievable frame-rate range per target from the vendor's measurements, if published. */
    val achievableFps: Map<String, String>,
    /** Estimated number of streams of each target this decoder can run at once. */
    val streamEstimate: Map<String, Int?>,
)

enum class DeviceTier { LOW, MID, HIGH }

data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val securityPatch: String,
    val soc: String?,
    val isTv: Boolean,
    val cpuCores: Int,
    val totalRamMb: Long,
    val lowRamDevice: Boolean,
    val memoryClassMb: Int,
    val largeMemoryClassMb: Int,
    val display: String,
    val displayModes: List<String>,
    val hdrTypes: List<String>,
    val decoders: List<DecoderInfo>,
    /** Plain-language answers for the C320WS layouts. */
    val c320ws: Map<String, String>,
    val tier: DeviceTier,
    val tierReasons: List<String>,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "device" to linkedMapOf(
            "manufacturer" to manufacturer, "model" to model, "device" to device,
            "android" to androidRelease, "sdkInt" to sdkInt, "securityPatch" to securityPatch,
            "soc" to soc, "isTv" to isTv, "cpuCores" to cpuCores, "totalRamMb" to totalRamMb,
            "lowRamDevice" to lowRamDevice, "memoryClassMb" to memoryClassMb, "largeMemoryClassMb" to largeMemoryClassMb,
            "display" to display, "displayModes" to displayModes, "hdrTypes" to hdrTypes,
        ),
        "tier" to linkedMapOf("suggested" to tier, "reasons" to tierReasons, "note" to "Provisional; Phase 0 calibrates tiers on the reference devices"),
        "c320ws" to c320ws,
        "decoders" to decoders.map { d ->
            linkedMapOf(
                "name" to d.name, "mime" to d.mime, "hardware" to d.hardware, "softwareOnly" to d.softwareOnly,
                "vendor" to d.vendor, "alias" to d.alias, "maxInstances" to d.maxInstances,
                "maxSize" to "${d.maxWidth}x${d.maxHeight}", "maxLevel" to d.maxLevel,
                "lowLatency" to d.lowLatency, "tunneled" to d.tunneled, "adaptive" to d.adaptive,
                "performancePoints" to d.performancePoints, "supports" to d.supports,
                "achievableFps" to d.achievableFps, "streamEstimate" to d.streamEstimate,
            )
        },
    )

    fun toJson(): String = Json.stringify(toMap())

    /** The hardware H.264 decoder Media3 would most likely pick (highest level, most instances). */
    val bestAvcDecoder: DecoderInfo?
        get() = decoders.filter { it.mime == MediaFormat.MIMETYPE_VIDEO_AVC && it.hardware && !it.alias }
            .maxWithOrNull(compareBy<DecoderInfo>({ it.maxLevelIdc ?: 0 }, { it.maxInstances }))
}

/**
 * Reads what this TV's decoders, memory and display can do, and answers the questions Phase 0
 * needs: can it decode the C320WS main stream (2560×1440, Level 5.0), and how many streams at once.
 *
 * Capability numbers come from the vendor and are sometimes optimistic, so every answer here is
 * checked by the multi-view test in the app.
 */
object DeviceProfiler {
    val TARGETS = listOf(VideoTarget.C320WS_MAIN, VideoTarget.C320WS_SUB, VideoTarget("1080p30", 1920, 1080, 30))

    fun profile(context: Context): DeviceProfile {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val decoders = decoders()
        val avc = decoders.filter { it.mime == MediaFormat.MIMETYPE_VIDEO_AVC && it.hardware && !it.alias }
            .maxWithOrNull(compareBy<DecoderInfo>({ it.maxLevelIdc ?: 0 }, { it.maxInstances }))
        val c320 = c320wsVerdicts(avc)
        val cores = Runtime.getRuntime().availableProcessors()
        val ramMb = mem.totalMem / (1024 * 1024)
        val (tier, reasons) = suggestTier(am.isLowRamDevice, ramMb, cores, avc)

        @Suppress("DEPRECATION")
        val hdr = display?.hdrCapabilities?.supportedHdrTypes?.map { hdrName(it) }.orEmpty()
        return DeviceProfile(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH,
            soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else null,
            isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
            cpuCores = cores,
            totalRamMb = ramMb,
            lowRamDevice = am.isLowRamDevice,
            memoryClassMb = am.memoryClass,
            largeMemoryClassMb = am.largeMemoryClass,
            display = mode?.let { "${it.physicalWidth}x${it.physicalHeight} @ ${fmt(it.refreshRate.toDouble())} Hz" } ?: "unknown",
            displayModes = display?.supportedModes?.map { "${it.physicalWidth}x${it.physicalHeight}@${fmt(it.refreshRate.toDouble())}" }.orEmpty().distinct(),
            hdrTypes = hdr,
            decoders = decoders,
            c320ws = c320,
            tier = tier,
            tierReasons = reasons,
        )
    }

    fun decoders(): List<DecoderInfo> {
        val result = mutableListOf<DecoderInfo>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder) continue
            for (mime in info.supportedTypes) {
                if (mime != MediaFormat.MIMETYPE_VIDEO_AVC && mime != MediaFormat.MIMETYPE_VIDEO_HEVC) continue
                val caps = try {
                    info.getCapabilitiesForType(mime)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                result += describe(info, mime, caps)
            }
        }
        return result
    }

    private fun describe(info: MediaCodecInfo, mime: String, caps: MediaCodecInfo.CodecCapabilities): DecoderInfo {
        val video = caps.videoCapabilities
        val points = video?.supportedPerformancePoints
        val isAvc = mime == MediaFormat.MIMETYPE_VIDEO_AVC
        val level = if (isAvc) maxAvcLevel(caps.profileLevels) else maxHevcLevel(caps.profileLevels)
        val supports = linkedMapOf<String, Boolean>()
        val achievable = linkedMapOf<String, String>()
        val estimate = linkedMapOf<String, Int?>()
        for (t in TARGETS) {
            val ok = video != null && video.isSizeSupported(t.width, t.height) &&
                video.areSizeAndRateSupported(t.width, t.height, t.fps.toDouble())
            supports[t.label] = ok
            if (ok) {
                try {
                    video?.getAchievableFrameRatesFor(t.width, t.height)?.let { r ->
                        achievable[t.label] = "${fmt(r.lower)}–${fmt(r.upper)}"
                    }
                } catch (_: IllegalArgumentException) {
                }
            }
            estimate[t.label] = if (ok) estimateStreams(points, caps.maxSupportedInstances, video, t) else 0
        }
        return DecoderInfo(
            name = info.name,
            mime = mime,
            hardware = info.isHardwareAccelerated,
            softwareOnly = info.isSoftwareOnly,
            vendor = info.isVendor,
            alias = info.isAlias,
            maxInstances = caps.maxSupportedInstances,
            maxWidth = video?.supportedWidths?.upper ?: 0,
            maxHeight = video?.supportedHeights?.upper ?: 0,
            maxLevel = level?.second,
            maxLevelIdc = if (isAvc) level?.first else null,
            lowLatency = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency),
            tunneled = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback),
            adaptive = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback),
            performancePoints = points?.map { it.toString() },
            supports = supports,
            achievableFps = achievable,
            streamEstimate = estimate,
        )
    }

    /**
     * How many [target] streams the decoder can run at once. Android's guidance for several active
     * codecs: take the largest picture size and add up the frame rates, then check the performance
     * points. Without performance points, fall back to the vendor's achievable frame rate.
     */
    private fun estimateStreams(
        points: List<PerformancePoint>?,
        maxInstances: Int,
        video: MediaCodecInfo.VideoCapabilities?,
        target: VideoTarget,
    ): Int? {
        val byPoints = if (!points.isNullOrEmpty()) {
            var n = 0
            while (n < 64 && points.any { it.covers(PerformancePoint(target.width, target.height, target.fps * (n + 1))) }) n++
            n
        } else {
            try {
                video?.getAchievableFrameRatesFor(target.width, target.height)?.upper?.let { (it / target.fps).toInt() }
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return byPoints?.let { minOf(it, maxInstances) }
    }

    /** Can [points] carry one main stream plus [subs] sub-streams (largest size, summed frame rates)? */
    private fun coversMix(points: List<PerformancePoint>?, mains: Int, subs: Int): Boolean? {
        if (points.isNullOrEmpty()) return null
        val main = VideoTarget.C320WS_MAIN
        val largest = if (mains > 0) main else VideoTarget.C320WS_SUB
        val fps = (mains + subs) * main.fps
        return points.any { it.covers(PerformancePoint(largest.width, largest.height, fps)) }
    }

    private fun c320wsVerdicts(avc: DecoderInfo?): Map<String, String> {
        val v = linkedMapOf<String, String>()
        if (avc == null) {
            v["stream1 full screen"] = "No hardware H.264 decoder found"
            return v
        }
        val levelOk = (avc.maxLevelIdc ?: 0) >= 50
        val sizeOk = avc.supports[VideoTarget.C320WS_MAIN.label] == true
        v["stream1 full screen"] = when {
            levelOk && sizeOk -> "Yes: ${avc.name} lists Level ${avc.maxLevel} and supports 2560×1440 at 15 fps"
            sizeOk -> "Maybe: ${avc.name} supports 2560×1440 but lists only Level ${avc.maxLevel ?: "?"}; play stream1 to confirm"
            else -> "No: ${avc.name} can't decode 2560×1440; full screen would use stream2"
        }
        val points = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { it.name == avc.name }
            ?.let { info ->
                try {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities?.supportedPerformancePoints
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        fun answer(mains: Int, subs: Int): String {
            val needed = mains + subs
            if (avc.maxInstances < needed) return "No: at most ${avc.maxInstances} decoder instances"
            return when (coversMix(points, mains, subs)) {
                true -> "Likely: performance points cover it; confirm with the multi-view test"
                false -> "Unlikely: beyond the published performance points; the multi-view test decides"
                null -> "Unknown: no performance points published; the multi-view test decides"
            }
        }
        v["2-view stream1"] = answer(2, 0)
        v["4-view stream1"] = answer(4, 0)
        v["8-view 1 + 7"] = answer(1, 7)
        v["16-view stream2"] = answer(0, 16)
        return v
    }

    private fun suggestTier(lowRam: Boolean, ramMb: Long, cores: Int, avc: DecoderInfo?): Pair<DeviceTier, List<String>> {
        val reasons = mutableListOf<String>()
        val main = VideoTarget.C320WS_MAIN.label
        val sub = VideoTarget.C320WS_SUB.label
        val subStreams = avc?.streamEstimate?.get(sub)
        val mainStreams = avc?.streamEstimate?.get(main)
        if (lowRam) reasons += "Android marks this as a low-RAM device"
        if (ramMb < 2_500) reasons += "$ramMb MB RAM (< 2.5 GB)"
        if (cores < 4) reasons += "$cores CPU cores"
        if (avc == null) reasons += "no hardware H.264 decoder"
        if (avc != null && avc.maxInstances < 4) reasons += "only ${avc.maxInstances} decoder instances"
        if (avc != null && avc.supports[main] != true) reasons += "can't decode 2560×1440"
        if (reasons.isNotEmpty()) return DeviceTier.LOW to reasons

        val highReasons = mutableListOf<String>()
        if (ramMb >= 3_500) highReasons += "$ramMb MB RAM"
        if ((avc?.maxInstances ?: 0) >= 16) highReasons += "${avc?.maxInstances} decoder instances"
        if ((mainStreams ?: 0) >= 4) highReasons += "about $mainStreams × 2560×1440 streams"
        if ((subStreams ?: 0) >= 16) highReasons += "16 sub-streams within published performance"
        return if (highReasons.size == 4) {
            DeviceTier.HIGH to highReasons
        } else {
            DeviceTier.MID to listOf("Meets the low-tier minimum; not every high-tier check passed (" +
                "RAM $ramMb MB, instances ${avc?.maxInstances}, stream1 × ${mainStreams ?: "?"}, stream2 × ${subStreams ?: "?"})")
        }
    }

    private val AVC_LEVELS = listOf(
        CodecProfileLevel.AVCLevel1 to (10 to "1"),
        CodecProfileLevel.AVCLevel1b to (9 to "1b"),
        CodecProfileLevel.AVCLevel11 to (11 to "1.1"),
        CodecProfileLevel.AVCLevel12 to (12 to "1.2"),
        CodecProfileLevel.AVCLevel13 to (13 to "1.3"),
        CodecProfileLevel.AVCLevel2 to (20 to "2"),
        CodecProfileLevel.AVCLevel21 to (21 to "2.1"),
        CodecProfileLevel.AVCLevel22 to (22 to "2.2"),
        CodecProfileLevel.AVCLevel3 to (30 to "3"),
        CodecProfileLevel.AVCLevel31 to (31 to "3.1"),
        CodecProfileLevel.AVCLevel32 to (32 to "3.2"),
        CodecProfileLevel.AVCLevel4 to (40 to "4"),
        CodecProfileLevel.AVCLevel41 to (41 to "4.1"),
        CodecProfileLevel.AVCLevel42 to (42 to "4.2"),
        CodecProfileLevel.AVCLevel5 to (50 to "5"),
        CodecProfileLevel.AVCLevel51 to (51 to "5.1"),
        CodecProfileLevel.AVCLevel52 to (52 to "5.2"),
        CodecProfileLevel.AVCLevel6 to (60 to "6"),
        CodecProfileLevel.AVCLevel61 to (61 to "6.1"),
        CodecProfileLevel.AVCLevel62 to (62 to "6.2"),
    )

    private val HEVC_MAIN_TIER_LEVELS = listOf(
        CodecProfileLevel.HEVCMainTierLevel1 to "1", CodecProfileLevel.HEVCMainTierLevel2 to "2",
        CodecProfileLevel.HEVCMainTierLevel21 to "2.1", CodecProfileLevel.HEVCMainTierLevel3 to "3",
        CodecProfileLevel.HEVCMainTierLevel31 to "3.1", CodecProfileLevel.HEVCMainTierLevel4 to "4",
        CodecProfileLevel.HEVCMainTierLevel41 to "4.1", CodecProfileLevel.HEVCMainTierLevel5 to "5",
        CodecProfileLevel.HEVCMainTierLevel51 to "5.1", CodecProfileLevel.HEVCMainTierLevel52 to "5.2",
        CodecProfileLevel.HEVCMainTierLevel6 to "6", CodecProfileLevel.HEVCMainTierLevel61 to "6.1",
        CodecProfileLevel.HEVCMainTierLevel62 to "6.2",
    )

    /** Highest level listed for Main or High profile, as (level_idc, name). */
    private fun maxAvcLevel(levels: Array<CodecProfileLevel>): Pair<Int, String>? {
        val profiles = setOf(CodecProfileLevel.AVCProfileMain, CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCProfileConstrainedHigh)
        val max = levels.filter { it.profile in profiles }.maxOfOrNull { it.level } ?: return null
        return AVC_LEVELS.lastOrNull { it.first <= max }?.second
    }

    private fun maxHevcLevel(levels: Array<CodecProfileLevel>): Pair<Int, String>? {
        val max = levels.filter { it.profile == CodecProfileLevel.HEVCProfileMain }.maxOfOrNull { it.level } ?: return null
        // High-tier flags sit between main-tier ones; report the main-tier level at or below max.
        return HEVC_MAIN_TIER_LEVELS.lastOrNull { it.first <= max }?.let { 0 to it.second }
    }

    private fun hdrName(type: Int): String = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        else -> "type $type"
    }

    private fun fmt(v: Double): String = String.format(Locale.ROOT, if (v % 1.0 == 0.0) "%.0f" else "%.2f", v)
}
