package io.github.rhlshlt.tvrtsp.core.rtsp

import java.util.Locale

/** The steps of a camera check, in order. */
enum class ProbeStep(val label: String) {
    DNS("DNS lookup"),
    CONNECT("TCP connect"),
    OPTIONS("OPTIONS"),
    DESCRIBE("DESCRIBE"),
    SETUP("SETUP"),
    PLAY("PLAY"),
    STREAM("streaming"),
}

/** How a check ended. [code] is short and stable, for logs and error overlays (for example E-401). */
sealed class ProbeOutcome(val code: String, val title: String, val retryable: Boolean) {
    data object Ok : ProbeOutcome("OK", "Stream is reachable", false)
    class DnsFailure(val host: String, val timedOut: Boolean) :
        ProbeOutcome("E-DNS", if (timedOut) "DNS lookup for \"$host\" timed out" else "Can't find host \"$host\"", true)
    class Refused(val port: Int) : ProbeOutcome("E-REFUSED", "Connection refused on port $port", true)
    class Unreachable(val detail: String) : ProbeOutcome("E-UNREACHABLE", "No route to the camera ($detail)", true)
    class Timeout(val step: ProbeStep) : ProbeOutcome("E-TIMEOUT", "Camera isn't responding (${step.label})", true)
    class ConnectionClosed(val step: ProbeStep) :
        ProbeOutcome("E-CLOSED", "Camera closed the connection during ${step.label}", true)
    class TlsFailure(val detail: String) : ProbeOutcome("E-TLS", "TLS handshake failed: $detail", false)
    data object AuthRequired : ProbeOutcome("E-401", "Camera asks for a user name and password", false)
    data object AuthFailed : ProbeOutcome("E-401", "Wrong user name or password", false)
    data object NotFound : ProbeOutcome("E-404", "Stream path not found", false)
    class CameraBusy(val step: ProbeStep, val status: Int) :
        ProbeOutcome("E-BUSY", "Camera is busy: $status at ${step.label} (too many viewers?)", true)
    class ServerError(val step: ProbeStep, val status: Int, val reason: String) :
        ProbeOutcome("E-$status", "Camera answered $status $reason to ${step.label}", true)
    class ProtocolError(val step: ProbeStep, val detail: String) :
        ProbeOutcome("E-PROTOCOL", "Unexpected answer to ${step.label}: $detail", false)
    data object NoVideo : ProbeOutcome("E-NOVIDEO", "The stream has no video track", false)

    val isOk: Boolean get() = this === Ok
}

enum class Severity(val tag: String) { OK("ok"), INFO("info"), WARNING("warn"), ERROR("error") }

/** One observation in a report. [engine] is set when it applies to one engine only. */
data class Finding(val severity: Severity, val code: String, val message: String, val engine: PlayerEngine? = null)

/** A media track from the SDP. */
class TrackInfo(
    val type: String,
    val encoding: String?,
    val payloadType: Int?,
    val clockRate: Int?,
    val channels: Int?,
    val control: String?,
    val controlUrl: String,
    val fmtp: Map<String, String>,
)

/** What the stream looked like over the analysis window. */
class StreamAnalysis(
    val requestedMs: Long,
    val receivedMs: Long,
    val setupMs: Long,
    val playMs: Long,
    val firstPacketMs: Long?,
    val firstKeyframeMs: Long?,
    val videoPackets: Int,
    val audioPackets: Int,
    val rtcpPackets: Int,
    val sequenceGaps: Int,
    val frames: Int,
    val keyframes: Int,
    val keyframeIntervalsSec: List<Double>,
    val measuredFps: Double?,
    val videoKbps: Double?,
    val audioKbps: Double?,
    val sliceTypeCounts: Map<H264SliceType, Int>,
    val maxFrameBytes: Int,
    val averageFrameBytes: Int,
    val averageKeyframeBytes: Int,
    val maxArrivalJitterMs: Long?,
    val inBandSps: Boolean,
    val inBandSpsMatchesSdp: Boolean?,
    val unsupportedPackets: Int,
    val stallsOver1s: Int,
    val sessionTimeoutSec: Int?,
) {
    val bFrames: Boolean get() = (sliceTypeCounts[H264SliceType.B] ?: 0) > 0
    val keyframeIntervalAvg: Double? get() = keyframeIntervalsSec.takeIf { it.isNotEmpty() }?.average()
    val keyframeIntervalMin: Double? get() = keyframeIntervalsSec.minOrNull()
    val keyframeIntervalMax: Double? get() = keyframeIntervalsSec.maxOrNull()
}

/** The result of [CameraCheck.run]: everything learned about one stream URL. */
class CameraReport(
    /** The URL checked, without credentials. */
    val target: String,
    val outcome: ProbeOutcome,
    val connectedAddress: String?,
    val dnsMs: Long?,
    val connectMs: Long?,
    val optionsMs: Long?,
    val describeMs: Long?,
    val server: String?,
    val publicMethods: List<String>,
    val authTrace: List<String>,
    val challengeHeaders: List<String>,
    val acceptedLegacyDigest: Boolean?,
    val engineAuth: List<EngineAuthVerdict>,
    val contentBase: String?,
    val sdp: String?,
    val video: TrackInfo?,
    val audio: TrackInfo?,
    val h264: H264SdpInfo?,
    val sps: H264Sps?,
    val spsError: String?,
    val pps: H264Pps?,
    val analysis: StreamAnalysis?,
    val findings: List<Finding>,
) {
    val ok: Boolean get() = outcome.isOk

    /** The worst finding for [engine] (or for all engines). */
    fun worstFor(engine: PlayerEngine): Severity =
        findings.filter { it.engine == null || it.engine == engine }.maxOfOrNull { it.severity } ?: Severity.OK

    /**
     * Human-readable report. [ascii] replaces typographic characters (—, ×, →) for consoles that
     * aren't UTF-8, such as the Windows console; [includeSdp] appends the raw SDP.
     */
    fun toText(ascii: Boolean = false, includeSdp: Boolean = false): String {
        val text = ReportText.render(this, includeSdp)
        return if (ascii) ReportText.toAscii(text) else text
    }

    fun toJson(): String = Json.stringify(toMap())

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "target" to target,
        "outcome" to linkedMapOf("code" to outcome.code, "title" to outcome.title, "retryable" to outcome.retryable),
        "connectedAddress" to connectedAddress,
        "timingsMs" to linkedMapOf("dns" to dnsMs, "connect" to connectMs, "options" to optionsMs, "describe" to describeMs),
        "server" to server,
        "publicMethods" to publicMethods,
        "authentication" to linkedMapOf(
            "trace" to authTrace,
            "challenges" to challengeHeaders,
            "acceptedEngineStyleDigest" to acceptedLegacyDigest,
            "engines" to engineAuth.map { linkedMapOf("engine" to it.engine.label, "level" to it.level, "scheme" to it.scheme, "detail" to it.detail) },
        ),
        "contentBase" to contentBase,
        "video" to video?.let(::trackMap),
        "audio" to audio?.let(::trackMap),
        "h264" to h264?.let {
            linkedMapOf(
                "packetizationMode" to it.packetizationMode,
                "profileLevelId" to it.profileLevelId,
                "spropParameterSets" to it.spropText,
                "hasSpsAndPps" to it.hasSpsAndPps,
            )
        },
        "sps" to sps?.let(::spsMap),
        "spsError" to spsError,
        "pps" to pps?.let {
            linkedMapOf("ppsId" to it.ppsId, "spsId" to it.spsId, "cabac" to it.cabac, "sliceGroups" to it.numSliceGroups,
                "refIdxL0" to it.numRefIdxL0DefaultActive, "refIdxL1" to it.numRefIdxL1DefaultActive,
                "weightedPred" to it.weightedPred, "weightedBipredIdc" to it.weightedBipredIdc, "picInitQp" to it.picInitQp)
        },
        "analysis" to analysis?.let(::analysisMap),
        "findings" to findings.map {
            linkedMapOf("severity" to it.severity, "code" to it.code, "engine" to it.engine?.label, "message" to it.message)
        },
        "sdp" to sdp,
    )

    private fun trackMap(t: TrackInfo) = linkedMapOf(
        "type" to t.type, "encoding" to t.encoding, "payloadType" to t.payloadType, "clockRate" to t.clockRate,
        "channels" to t.channels, "control" to t.control, "controlUrl" to t.controlUrl, "fmtp" to t.fmtp,
    )

    private fun spsMap(s: H264Sps) = linkedMapOf(
        "profile" to s.profileName, "profileIdc" to s.profileIdc, "constraintFlags" to s.constraintFlags,
        "level" to s.levelName, "levelIdc" to s.levelIdc, "width" to s.width, "height" to s.height,
        "codedWidth" to s.codedWidth, "codedHeight" to s.codedHeight, "frameRate" to s.frameRate,
        "chromaFormatIdc" to s.chromaFormatIdc, "bitDepth" to s.bitDepthLuma, "maxNumRefFrames" to s.maxNumRefFrames,
        "frameMbsOnly" to s.frameMbsOnly, "picOrderCntType" to s.picOrderCntType, "log2MaxFrameNum" to s.log2MaxFrameNum,
        "fullRange" to s.vui?.fullRange, "videoFormat" to s.vui?.videoFormat,
        "colourPrimaries" to s.vui?.colourPrimaries, "transferCharacteristics" to s.vui?.transferCharacteristics,
        "matrixCoefficients" to s.vui?.matrixCoefficients, "fixedFrameRate" to s.vui?.fixedFrameRate,
        "hrd" to (s.vui?.let { it.nalHrdPresent || it.vclHrdPresent }),
        "bitstreamRestriction" to s.hasReorderLimit, "maxNumReorderFrames" to s.maxNumReorderFrames,
        "maxDecFrameBuffering" to s.maxDecFrameBuffering, "maxDpbFrames" to s.maxDpbFrames,
        "mbsPerFrame" to s.mbsPerFrame,
    )

    private fun analysisMap(a: StreamAnalysis) = linkedMapOf(
        "requestedMs" to a.requestedMs, "receivedMs" to a.receivedMs, "setupMs" to a.setupMs, "playMs" to a.playMs,
        "firstPacketMs" to a.firstPacketMs, "firstKeyframeMs" to a.firstKeyframeMs,
        "videoPackets" to a.videoPackets, "audioPackets" to a.audioPackets, "rtcpPackets" to a.rtcpPackets,
        "sequenceGaps" to a.sequenceGaps, "frames" to a.frames, "keyframes" to a.keyframes,
        "keyframeIntervalSec" to linkedMapOf("avg" to a.keyframeIntervalAvg, "min" to a.keyframeIntervalMin, "max" to a.keyframeIntervalMax),
        "measuredFps" to a.measuredFps, "videoKbps" to a.videoKbps, "audioKbps" to a.audioKbps,
        "sliceTypes" to a.sliceTypeCounts.mapKeys { it.key.name }, "bFrames" to a.bFrames,
        "maxFrameBytes" to a.maxFrameBytes, "averageFrameBytes" to a.averageFrameBytes,
        "averageKeyframeBytes" to a.averageKeyframeBytes, "maxArrivalJitterMs" to a.maxArrivalJitterMs,
        "inBandSps" to a.inBandSps, "inBandSpsMatchesSdp" to a.inBandSpsMatchesSdp,
        "unsupportedPackets" to a.unsupportedPackets, "stallsOver1s" to a.stallsOver1s,
        "sessionTimeoutSec" to a.sessionTimeoutSec,
    )
}

/** Turns what a check saw into findings. Kept apart from the network code so it can be unit tested. */
object Findings {
    fun build(
        outcome: ProbeOutcome,
        authSchemeUsed: String?,
        acceptedLegacyDigest: Boolean?,
        engineAuth: List<EngineAuthVerdict>,
        sdp: SessionDescription?,
        video: TrackInfo?,
        audio: TrackInfo?,
        h264: H264SdpInfo?,
        sps: H264Sps?,
        spsError: String?,
        analysis: StreamAnalysis?,
    ): List<Finding> {
        val f = mutableListOf<Finding>()
        if (!outcome.isOk) f += Finding(Severity.ERROR, outcome.code, "${outcome.title}. ${hint(outcome)}".trim())

        // Authentication
        if (authSchemeUsed == "Basic") {
            f += Finding(Severity.WARNING, "AUTH-BASIC", "Camera uses Basic authentication, which sends the password base64-encoded with every request; keep it on a trusted network")
        }
        if (acceptedLegacyDigest == true) {
            f += Finding(Severity.OK, "AUTH-DIGEST", "Camera accepted the Digest answer both engines send (MD5, no qop)")
        } else if (acceptedLegacyDigest == false && outcome.isOk) {
            f += Finding(Severity.ERROR, "AUTH-QOP", "Camera refused the Digest answer both engines send and accepted only a qop=auth answer; both engines will fail to sign in")
        }
        for (v in engineAuth) {
            when (v.level) {
                EngineAuthVerdict.Level.FAILS -> f += Finding(Severity.ERROR, "AUTH-ENGINE", v.detail, v.engine)
                EngineAuthVerdict.Level.RISK -> f += Finding(Severity.WARNING, "AUTH-ENGINE", v.detail, v.engine)
                EngineAuthVerdict.Level.OK -> Unit
            }
        }
        if (sdp == null) return f

        // Tracks
        if (video == null) {
            if (outcome.isOk) f += Finding(Severity.ERROR, "NO-VIDEO", "SDP has no video track")
        } else {
            when (video.encoding) {
                "H264" -> f += Finding(Severity.OK, "CODEC", "Video is H.264, which both engines decode in hardware")
                "H265" -> f += Finding(Severity.INFO, "CODEC", "Video is H.265; both engines support it, but this plan was sized for H.264")
                else -> f += Finding(Severity.ERROR, "CODEC", "Video codec ${video.encoding ?: "unknown"} isn't supported by the planned engines")
            }
            if (video.control == null) {
                f += Finding(Severity.ERROR, "SDP-CONTROL", "Video track has no a=control attribute; Media3 rejects such tracks", PlayerEngine.MEDIA3)
            }
        }
        if (audio != null && audio.control == null) {
            f += Finding(Severity.WARNING, "SDP-CONTROL", "Audio track has no a=control attribute; Media3 rejects such tracks", PlayerEngine.MEDIA3)
        }

        // H.264 SDP parameters (RFC 6184) and Media3's rules
        if (h264 != null) {
            val sprop = h264.spropText
            if (sprop == null) {
                f += Finding(Severity.ERROR, "SPROP", "No sprop-parameter-sets in the SDP; Media3 needs SPS/PPS there", PlayerEngine.MEDIA3)
                f += Finding(Severity.INFO, "SPROP", "rtsp-client-android can start from in-band SPS/PPS instead", PlayerEngine.RTSP_CLIENT_ANDROID)
            } else {
                val parts = sprop.split(',')
                when {
                    parts.size != 2 -> f += Finding(Severity.ERROR, "SPROP",
                        "sprop-parameter-sets has ${parts.size} entries; Media3 1.11.1 needs exactly one SPS and one PPS", PlayerEngine.MEDIA3)
                    h264.parameterSets.size != 2 || H264.nalType(h264.parameterSets[0].firstOrNull() ?: 0.toByte()) != H264.NAL_SPS ->
                        f += Finding(Severity.ERROR, "SPROP", "sprop-parameter-sets must list the SPS first, then the PPS (Media3 reads them by position)", PlayerEngine.MEDIA3)
                    else -> f += Finding(Severity.OK, "SPROP", "SDP carries SPS and PPS (sprop-parameter-sets), as Media3 requires")
                }
            }
            when (h264.packetizationMode) {
                1 -> Unit
                0 -> f += Finding(Severity.INFO, "PACKETIZATION", "packetization-mode=0 (single NAL units)")
                else -> f += Finding(Severity.WARNING, "PACKETIZATION", "packetization-mode=${h264.packetizationMode} (interleaved) isn't supported by the planned engines")
            }
            if (sps != null && h264.profileIdc != null && h264.levelIdc != null &&
                (h264.profileIdc != sps.profileIdc || h264.levelIdc != sps.levelIdc)
            ) {
                f += Finding(Severity.INFO, "PROFILE-LEVEL", "profile-level-id ${h264.profileLevelId} differs from the SPS (${sps.profileName}, Level ${sps.levelName}); decoders use the SPS")
            }
        }

        // SPS
        if (spsError != null) f += Finding(Severity.WARNING, "SPS", "Couldn't read the SPS: $spsError")
        if (sps != null) {
            val fps = sps.frameRate
            val fpsText = fps?.let { " at ${fmt(it)} fps" } ?: ""
            f += Finding(Severity.INFO, "SPS", "${sps.profileName} profile, Level ${sps.levelName}, ${sps.width}×${sps.height}$fpsText, " +
                "${sps.maxNumRefFrames} reference frame(s), ${if (sps.frameMbsOnly) "progressive" else "interlaced"}")
            if (!sps.frameMbsOnly) f += Finding(Severity.WARNING, "INTERLACED", "Stream is interlaced; TV decoders handle it, but expect deinterlacing artefacts")
            if (sps.levelIdc >= 50) {
                f += Finding(Severity.WARNING, "LEVEL", "Needs an H.264 decoder that supports Level ${sps.levelName}; 1080p-only TV boxes often stop at Level 4.2. Check the device profile")
            }
            val needed = H264Level.minimumFor(sps.mbsPerFrame, sps.mbsPerFrame * (fps ?: 30.0))
            if (needed != null && sps.level != null && needed.levelIdc < sps.levelIdc) {
                f += Finding(Severity.INFO, "LEVEL", "Declared Level ${sps.levelName} is higher than the ${needed.name} this size and frame rate need; some decoders size buffers from the declared level")
            }
            if (sps.hasReorderLimit) {
                if (sps.maxNumReorderFrames == 0) f += Finding(Severity.OK, "REORDER", "SPS declares no frame reordering, so decoders can output each frame at once")
                else f += Finding(Severity.INFO, "REORDER", "SPS allows ${sps.maxNumReorderFrames} reordered frame(s)")
            } else {
                val frames = sps.maxNumReorderFrames ?: 0
                val delay = if (fps != null && fps > 0) " (about ${fmt(frames / fps, 2)} s at ${fmt(fps)} fps)" else ""
                f += Finding(Severity.WARNING, "REORDER",
                    "SPS sets no reorder limit, so a decoder may hold up to $frames frame(s)$delay before showing them. " +
                        "Low-latency decoding mode, or rtsp-client-android's SPS rewrite, can remove this; the rewrite is safe only without B-frames")
            }
            if (sps.fullRange) f += Finding(Severity.INFO, "RANGE", "SPS marks the video as full range (0–255); check that contrast matches the Tapo app")
        }

        // Audio
        if (audio == null) {
            f += Finding(Severity.INFO, "AUDIO", "No audio track")
        } else {
            val ch = audio.channels ?: 1
            val desc = "${audio.encoding} ${audio.clockRate ?: "?"} Hz ${if (ch == 1) "mono" else "$ch channels"}"
            when (audio.encoding) {
                "PCMA", "PCMU" -> f += Finding(Severity.OK, "AUDIO", "Audio is $desc (G.711), supported by both engines")
                "MPEG4-GENERIC", "MP4A-LATM", "OPUS" -> f += Finding(Severity.OK, "AUDIO", "Audio is $desc")
                else -> f += Finding(Severity.WARNING, "AUDIO", "Audio is $desc; check engine support or play video only")
            }
        }

        // Live stream analysis
        if (analysis != null) analysisFindings(f, analysis, sps)
        return f
    }

    private fun analysisFindings(f: MutableList<Finding>, a: StreamAnalysis, sps: H264Sps?) {
        if (a.frames == 0) {
            f += Finding(Severity.ERROR, "STREAM", "No video frames arrived in ${a.receivedMs} ms after PLAY")
            return
        }
        a.firstKeyframeMs?.let {
            f += Finding(Severity.INFO, "FIRST-KEYFRAME", "First keyframe arrived $it ms after PLAY; no engine can show a picture before it")
        }
        val avg = a.keyframeIntervalAvg
        if (avg != null) {
            val sev = if (avg > 4.0) Severity.WARNING else Severity.INFO
            f += Finding(sev, "GOP", "Keyframe every ${fmt(avg, 2)} s (min ${fmt(a.keyframeIntervalMin!!, 2)}, max ${fmt(a.keyframeIntervalMax!!, 2)}); this is the floor for stream switching time")
        } else if (a.keyframes <= 1) {
            f += Finding(Severity.INFO, "GOP", "Fewer than two keyframes in ${a.receivedMs / 1000} s; run a longer check to measure the keyframe interval")
        }
        if (a.bFrames) {
            f += Finding(Severity.WARNING, "B-FRAMES", "B-frames present (${a.sliceTypeCounts[H264SliceType.B]} slices): don't use the SPS rewrite; expect reorder delay")
        } else {
            f += Finding(Severity.OK, "B-FRAMES", "No B-frames in ${a.frames} frames, so declaring no reordering is safe")
        }
        val measured = a.measuredFps
        val declared = sps?.frameRate
        if (measured != null) {
            val text = "Measured ${fmt(measured)} fps from RTP timestamps"
            if (declared != null && kotlin.math.abs(measured - declared) / declared > 0.1) {
                f += Finding(Severity.INFO, "FPS", "$text; the SPS says ${fmt(declared)} fps")
            } else {
                f += Finding(Severity.INFO, "FPS", text)
            }
        }
        a.videoKbps?.let { v ->
            val audio = a.audioKbps?.let { ", audio ${fmt(it, 0)} kbit/s" } ?: ""
            f += Finding(Severity.INFO, "BITRATE", "Video ${fmt(v, 0)} kbit/s$audio over ${fmt(a.receivedMs / 1000.0, 1)} s")
        }
        a.maxArrivalJitterMs?.let {
            if (it > 250) f += Finding(Severity.WARNING, "JITTER", "Frames arrived up to $it ms off their timestamps; delivery is bursty (weak Wi-Fi?)")
        }
        if (a.sequenceGaps > 0) f += Finding(Severity.WARNING, "LOSS", "${a.sequenceGaps} gap(s) in RTP sequence numbers")
        if (a.stallsOver1s > 0) f += Finding(Severity.WARNING, "STALL", "${a.stallsOver1s} pause(s) longer than 1 s with no data")
        if (a.inBandSpsMatchesSdp == false) f += Finding(Severity.WARNING, "SPS-INBAND", "In-band SPS differs from the SDP's SPS")
        if (a.unsupportedPackets > 0) f += Finding(Severity.WARNING, "RTP", "${a.unsupportedPackets} packet(s) used STAP-B/MTAP/FU-B, which the planned engines don't expect")
    }

    private fun hint(outcome: ProbeOutcome): String = when (outcome) {
        is ProbeOutcome.DnsFailure -> "Check the host name, or use the camera's IP address."
        is ProbeOutcome.Refused -> "Check the port (Tapo: 554) and that the camera account exists; battery Tapo models don't serve RTSP."
        is ProbeOutcome.Unreachable -> "The TV and camera may be on different networks."
        is ProbeOutcome.Timeout -> "Camera off, wrong IP address, or a firewall in between."
        is ProbeOutcome.ConnectionClosed -> "The camera may be restarting or limiting viewers."
        is ProbeOutcome.AuthRequired -> "Enter the camera account (Tapo app → Device Settings → Advanced Settings → Camera Account)."
        is ProbeOutcome.AuthFailed -> "Use the Camera Account from the Tapo app, not your TP-Link ID."
        is ProbeOutcome.NotFound -> "Tapo paths are /stream1 (main) and /stream2 (sub)."
        is ProbeOutcome.CameraBusy -> "Tapo serves about two RTSP viewers; close other viewers (NVR, VLC, Home Assistant)."
        else -> ""
    }

    internal fun fmt(v: Double, decimals: Int = 1): String = String.format(Locale.ROOT, "%.${decimals}f", v)
}

/** Plain-text rendering of a [CameraReport]. */
internal object ReportText {
    private val ASCII = mapOf('—' to "-", '–' to "-", '·' to "|", '×' to "x", '→' to "->", '…' to "...", '“' to "\"", '”' to "\"", '’' to "'", '≈' to "~")

    fun toAscii(text: String): String = buildString(text.length) {
        for (c in text) {
            val r = ASCII[c]
            when {
                r != null -> append(r)
                c.code < 0x80 -> append(c)
                else -> append('?')
            }
        }
    }

    fun render(r: CameraReport, includeSdp: Boolean): String = buildString {
        appendLine("Camera check: ${r.target}")
        appendLine("Result: ${r.outcome.code} — ${r.outcome.title}")
        val timings = listOfNotNull(
            r.dnsMs?.let { "DNS $it ms" }, r.connectMs?.let { "connect $it ms" },
            r.optionsMs?.let { "OPTIONS $it ms" }, r.describeMs?.let { "DESCRIBE $it ms" },
        )
        if (timings.isNotEmpty()) appendLine("Timings: ${timings.joinToString(" · ")}")
        r.connectedAddress?.let { appendLine("Address: $it") }
        r.server?.let { appendLine("Server: $it") }
        if (r.publicMethods.isNotEmpty()) appendLine("Methods: ${r.publicMethods.joinToString(", ")}")
        if (r.authTrace.isNotEmpty()) {
            appendLine("Authentication:")
            r.authTrace.forEach { appendLine("  $it") }
            r.engineAuth.forEach { appendLine("  ${it.engine.label}: ${it.level} — ${it.detail}") }
        }
        r.video?.let { appendLine(trackLine("Video", it)) }
        r.h264?.let { h ->
            appendLine("  packetization-mode=${h.packetizationMode}, profile-level-id=${h.profileLevelId ?: "none"}, SPS/PPS in SDP: ${if (h.hasSpsAndPps) "yes" else "no"}")
        }
        r.sps?.let { s ->
            val fps = s.frameRate?.let { Findings.fmt(it) + " fps" } ?: "no timing info"
            appendLine("  SPS: ${s.profileName}, Level ${s.levelName}, ${s.width}×${s.height} (coded ${s.codedWidth}×${s.codedHeight}), $fps, " +
                "refs ${s.maxNumRefFrames}, ${if (s.fullRange) "full range" else "limited range"}, " +
                "reorder ${if (s.hasReorderLimit) s.maxNumReorderFrames.toString() else "not set (inferred ${s.maxNumReorderFrames})"}")
        }
        r.audio?.let { appendLine(trackLine("Audio", it)) }
        r.analysis?.let { a ->
            appendLine("Stream (${Findings.fmt(a.receivedMs / 1000.0)} s of ${Findings.fmt(a.requestedMs / 1000.0)} s): SETUP ${a.setupMs} ms, PLAY ${a.playMs} ms, " +
                "first packet ${a.firstPacketMs ?: "-"} ms, first keyframe ${a.firstKeyframeMs ?: "-"} ms")
            appendLine("  ${a.frames} frames, ${a.keyframes} keyframes, " +
                "fps ${a.measuredFps?.let { Findings.fmt(it) } ?: "-"}, " +
                "GOP ${a.keyframeIntervalAvg?.let { Findings.fmt(it, 2) + " s" } ?: "-"}, " +
                "frames with I/P/B slices ${a.sliceTypeCounts[H264SliceType.I] ?: 0}/${a.sliceTypeCounts[H264SliceType.P] ?: 0}/${a.sliceTypeCounts[H264SliceType.B] ?: 0}")
            appendLine("  video ${a.videoKbps?.let { Findings.fmt(it, 0) } ?: "-"} kbit/s, audio ${a.audioKbps?.let { Findings.fmt(it, 0) } ?: "-"} kbit/s, " +
                "largest frame ${a.maxFrameBytes} B, average keyframe ${a.averageKeyframeBytes} B, jitter ${a.maxArrivalJitterMs ?: "-"} ms")
        }
        if (r.findings.isNotEmpty()) {
            appendLine("Findings:")
            for (f in r.findings) {
                val who = f.engine?.let { " [${it.label}]" } ?: ""
                appendLine("  ${f.severity.tag.uppercase().padEnd(5)} ${f.code}$who: ${f.message}")
            }
        }
        if (includeSdp && r.sdp != null) {
            appendLine("SDP:")
            r.sdp.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.forEach { appendLine("  $it") }
        }
    }

    private fun trackLine(label: String, t: TrackInfo): String {
        val ch = t.channels?.let { ", $it ch" } ?: ""
        return "$label: ${t.encoding ?: "?"}, payload ${t.payloadType ?: "?"}, ${t.clockRate ?: "?"} Hz$ch, control ${t.control ?: "none"} → ${t.controlUrl}"
    }
}
