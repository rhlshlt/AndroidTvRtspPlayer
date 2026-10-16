package io.github.rhlshlt.tvrtsp.core.rtsp

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumMap
import javax.net.SocketFactory
import javax.net.ssl.SSLException

data class CameraCheckOptions(
    val dnsTimeoutMs: Long = 3_000,
    val connectTimeoutMs: Int = 3_000,
    val readTimeoutMs: Int = 3_000,
    /** How long to receive the stream after PLAY; 0 stops after DESCRIBE, like a pre-playback probe. */
    val analyzeMs: Long = 0,
    /** Also SETUP the audio track during analysis. */
    val includeAudio: Boolean = true,
    val userAgent: String = RtspConnection.DEFAULT_USER_AGENT,
    /** Try a qop=auth answer when the engine-style Digest answer is refused (diagnosis only). */
    val allowQopFallback: Boolean = true,
)

/**
 * Checks one RTSP URL the way the planned engines will use it: DNS, TCP connect, OPTIONS and
 * DESCRIBE with authentication, then SDP and SPS checks. With [CameraCheckOptions.analyzeMs] set,
 * it also runs SETUP (RTP over TCP) and PLAY and measures the live stream: keyframe interval,
 * frame rate, B-frames, bitrate and delivery jitter.
 *
 * Blocking; call it from a background thread. Credentials never appear in the report.
 */
class CameraCheck(
    private val resolver: HostResolver = HostResolver.DEFAULT,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun run(url: RtspUrl, options: CameraCheckOptions = CameraCheckOptions()): CameraReport {
        val s = State(url)
        try {
            runSteps(s, options)
        } catch (e: SocketTimeoutException) {
            s.outcome = ProbeOutcome.Timeout(s.step)
        } catch (e: EOFException) {
            s.outcome = ProbeOutcome.ConnectionClosed(s.step)
        } catch (e: SSLException) {
            s.outcome = ProbeOutcome.TlsFailure(e.message ?: e.javaClass.simpleName)
        } catch (e: ProtocolException) {
            s.outcome = ProbeOutcome.ProtocolError(s.step, e.message ?: "malformed message")
        } catch (e: SocketException) {
            s.outcome = ProbeOutcome.ConnectionClosed(s.step)
        } catch (e: IOException) {
            s.outcome = ProbeOutcome.ConnectionClosed(s.step)
        }
        return s.toReport()
    }

    private fun runSteps(s: State, o: CameraCheckOptions) {
        s.step = ProbeStep.DNS
        var t = nanoTime()
        val addresses = try {
            resolver.resolve(s.url.host, o.dnsTimeoutMs)
        } catch (e: UnknownHostException) {
            s.outcome = ProbeOutcome.DnsFailure(s.url.host, e is DnsTimeoutException)
            return
        }
        s.dnsMs = msSince(t)

        s.step = ProbeStep.CONNECT
        t = nanoTime()
        val socket = try {
            val (sock, address) = RtspConnection.connect(s.url, addresses, o.connectTimeoutMs, o.readTimeoutMs, socketFactory)
            s.address = address.hostAddress
            sock
        } catch (e: Exception) {
            s.outcome = connectFailure(e, s.url)
            return
        }
        s.connectMs = msSince(t)

        RtspConnection(socket, s.url, nanoTime).use { conn ->
            conn.userAgent = o.userAgent
            conn.allowQopFallback = o.allowQopFallback

            s.step = ProbeStep.OPTIONS
            t = nanoTime()
            val options = conn.request("OPTIONS", s.url.requestUri)
            s.optionsMs = msSince(t)
            s.server = options.headers["Server"]
            s.publicMethods = options.headers["Public"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            s.captureAuth(conn)
            if (!options.isSuccess) {
                s.outcome = statusOutcome(ProbeStep.OPTIONS, options, conn)
                return
            }

            s.step = ProbeStep.DESCRIBE
            t = nanoTime()
            val describe = conn.request("DESCRIBE", s.url.requestUri, listOf("Accept" to "application/sdp"))
            s.describeMs = msSince(t)
            if (s.server == null) s.server = describe.headers["Server"]
            s.captureAuth(conn)
            if (!describe.isSuccess) {
                s.outcome = statusOutcome(ProbeStep.DESCRIBE, describe, conn)
                return
            }
            s.contentBase = describe.headers["Content-Base"]
            s.contentLocation = describe.headers["Content-Location"]
            val sdp = try {
                SessionDescription.parse(describe.bodyText)
            } catch (e: IllegalArgumentException) {
                s.outcome = ProbeOutcome.ProtocolError(ProbeStep.DESCRIBE, "the answer has no SDP")
                return
            }
            s.sdp = sdp
            s.video = sdp.video?.let { trackInfo(it, s) }
            s.audio = sdp.audio?.let { trackInfo(it, s) }
            s.h264 = sdp.video?.let { H264SdpInfo.from(it) }
            s.h264?.sps?.let { nal ->
                try {
                    s.sps = H264Sps.parse(nal)
                } catch (e: BitstreamException) {
                    s.spsError = e.message
                }
            }
            s.h264?.ppsList?.firstOrNull()?.let { nal ->
                try {
                    s.pps = H264Pps.parse(nal)
                } catch (_: BitstreamException) {
                }
            }
            if (sdp.video == null) {
                s.outcome = ProbeOutcome.NoVideo
                return
            }
            s.outcome = ProbeOutcome.Ok
            if (o.analyzeMs > 0) analyze(s, conn, o)
        }
    }

    private fun analyze(s: State, conn: RtspConnection, o: CameraCheckOptions) {
        val video = s.video ?: return
        s.step = ProbeStep.SETUP
        var t = nanoTime()
        val setup = conn.request("SETUP", video.controlUrl, listOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"))
        if (!setup.isSuccess) {
            s.outcome = statusOutcome(ProbeStep.SETUP, setup, conn)
            return
        }
        val sessionHeader = setup.headers["Session"]
        if (sessionHeader != null) conn.sessionId = RtspConnection.sessionIdOf(sessionHeader)
        val sessionTimeout = sessionHeader?.let { RtspConnection.sessionTimeoutOf(it) }
        val videoChannel = interleavedChannel(setup.headers["Transport"]) ?: 0

        var audioChannel = -1
        val audio = s.audio
        if (o.includeAudio && audio != null && audio.control != null) {
            val a = conn.request("SETUP", audio.controlUrl, listOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=2-3"))
            if (a.isSuccess) audioChannel = interleavedChannel(a.headers["Transport"]) ?: 2
        }
        val setupMs = msSince(t)

        val collector = StreamCollector(
            videoChannel = videoChannel,
            audioChannel = audioChannel,
            videoClock = video.clockRate ?: 90_000,
            audioClock = audio?.clockRate ?: 8_000,
            sdpSps = s.h264?.sps,
            nanoTime = nanoTime,
        )
        conn.interleavedSink = collector::onFrame

        // Media3 sends PLAY to the request URI, not to Content-Base; do the same.
        val playUri = s.url.requestUri
        s.step = ProbeStep.PLAY
        t = nanoTime()
        collector.playSentNanos = t
        val play = conn.request("PLAY", playUri, listOf("Range" to "npt=0.000-"))
        val playMs = msSince(t)
        if (!play.isSuccess) {
            s.outcome = statusOutcome(ProbeStep.PLAY, play, conn)
            return
        }

        s.step = ProbeStep.STREAM
        conn.readTimeoutMs = 1_000
        val endAt = t + o.analyzeMs * 1_000_000
        val keepAliveMethod = if (s.publicMethods.any { it.equals("GET_PARAMETER", ignoreCase = true) }) "GET_PARAMETER" else "OPTIONS"
        val keepAliveEvery = ((sessionTimeout ?: 60).coerceAtLeast(10) * 1_000_000_000L / 2).coerceAtMost(20_000_000_000L)
        var lastKeepAlive = nanoTime()
        var stalls = 0
        var stalled = false
        while (nanoTime() < endAt) {
            if (nanoTime() - lastKeepAlive > keepAliveEvery) {
                conn.sendWithoutWaiting(keepAliveMethod, playUri)
                lastKeepAlive = nanoTime()
            }
            val frame = try {
                conn.readFrame()
            } catch (e: SocketTimeoutException) {
                if (!stalled) stalls++
                stalled = true
                if (collector.videoPackets == 0 && nanoTime() - t > 5_000_000_000L) {
                    s.outcome = ProbeOutcome.Timeout(ProbeStep.STREAM)
                    break
                }
                continue
            }
            if (frame == null) { // the camera ended the stream
                if (collector.videoPackets == 0) s.outcome = ProbeOutcome.ConnectionClosed(ProbeStep.STREAM)
                break
            }
            stalled = false
        }
        val receivedMs = msSince(t)
        try {
            conn.sendWithoutWaiting("TEARDOWN", playUri)
        } catch (_: IOException) {
        }
        s.analysis = collector.finish(o.analyzeMs, receivedMs, setupMs, playMs, stalls, sessionTimeout)
    }

    private fun trackInfo(m: MediaDescription, s: State): TrackInfo {
        val map = m.rtpMap
        return TrackInfo(
            type = m.type,
            encoding = m.encoding,
            payloadType = m.payloadType,
            clockRate = map?.clockRate,
            channels = map?.channels,
            control = m.control,
            controlUrl = ControlUrl.resolve(m.control, s.url.requestUri, s.contentBase, s.contentLocation),
            fmtp = m.fmtp(),
        )
    }

    private fun msSince(start: Long): Long = (nanoTime() - start) / 1_000_000

    private class State(val url: RtspUrl) {
        var step = ProbeStep.DNS
        var outcome: ProbeOutcome = ProbeOutcome.Ok
        var address: String? = null
        var dnsMs: Long? = null
        var connectMs: Long? = null
        var optionsMs: Long? = null
        var describeMs: Long? = null
        var server: String? = null
        var publicMethods: List<String> = emptyList()
        var authTrace: List<String> = emptyList()
        var challengeHeaders: List<String> = emptyList()
        var acceptedLegacyDigest: Boolean? = null
        var authScheme: String? = null
        var contentBase: String? = null
        var contentLocation: String? = null
        var sdp: SessionDescription? = null
        var video: TrackInfo? = null
        var audio: TrackInfo? = null
        var h264: H264SdpInfo? = null
        var sps: H264Sps? = null
        var spsError: String? = null
        var pps: H264Pps? = null
        var analysis: StreamAnalysis? = null

        fun captureAuth(conn: RtspConnection) {
            authTrace = conn.authTrace.toList()
            challengeHeaders = conn.firstChallengeHeaders
            acceptedLegacyDigest = conn.acceptedLegacyDigest
            authScheme = conn.authSession?.schemeName
        }

        fun toReport(): CameraReport {
            val engineAuth = if (challengeHeaders.isEmpty()) emptyList() else EngineAuthCompat.evaluate(challengeHeaders)
            val findings = Findings.build(outcome, authScheme, acceptedLegacyDigest, engineAuth, sdp, video, audio, h264, sps, spsError, analysis)
            return CameraReport(
                target = url.redacted(), outcome = outcome, connectedAddress = address,
                dnsMs = dnsMs, connectMs = connectMs, optionsMs = optionsMs, describeMs = describeMs,
                server = server, publicMethods = publicMethods, authTrace = authTrace,
                challengeHeaders = challengeHeaders, acceptedLegacyDigest = acceptedLegacyDigest,
                engineAuth = engineAuth, contentBase = contentBase, sdp = sdp?.text, video = video, audio = audio,
                h264 = h264, sps = sps, spsError = spsError, pps = pps, analysis = analysis, findings = findings,
            )
        }
    }

    companion object {
        fun statusOutcome(step: ProbeStep, response: RtspResponse, conn: RtspConnection): ProbeOutcome =
            when (response.statusCode) {
                401 -> if (conn.credentials == null) ProbeOutcome.AuthRequired else ProbeOutcome.AuthFailed
                404 -> ProbeOutcome.NotFound
                453, 503 -> ProbeOutcome.CameraBusy(step, response.statusCode)
                else -> ProbeOutcome.ServerError(step, response.statusCode, response.reason)
            }

        fun connectFailure(e: Exception, url: RtspUrl): ProbeOutcome = when (e) {
            is SocketTimeoutException -> ProbeOutcome.Timeout(ProbeStep.CONNECT)
            is SSLException -> ProbeOutcome.TlsFailure(e.message ?: "handshake failed")
            is NoRouteToHostException -> ProbeOutcome.Unreachable(e.message ?: "no route to host")
            is ConnectException ->
                if (e.message?.contains("refused", ignoreCase = true) == true) ProbeOutcome.Refused(url.port)
                else ProbeOutcome.Unreachable(e.message ?: "connect failed")
            else -> ProbeOutcome.Unreachable(e.message ?: e.javaClass.simpleName)
        }

        /** The RTP channel from `Transport: RTP/AVP/TCP;unicast;interleaved=0-1`. */
        fun interleavedChannel(transport: String?): Int? =
            transport?.split(';')?.map { it.trim() }
                ?.firstOrNull { it.startsWith("interleaved=", ignoreCase = true) }
                ?.substringAfter('=')?.substringBefore('-')?.trim()?.toIntOrNull()
    }
}

/** Collects statistics from interleaved RTP/RTCP frames during analysis. */
internal class StreamCollector(
    private val videoChannel: Int,
    private val audioChannel: Int,
    private val videoClock: Int,
    private val audioClock: Int,
    private val sdpSps: ByteArray?,
    private val nanoTime: () -> Long,
) {
    var playSentNanos = 0L
    var videoPackets = 0
        private set
    private var audioPackets = 0
    private var rtcpPackets = 0
    private var firstPacketNanos = 0L
    private var firstKeyframeNanos = 0L
    private var lastSeq = -1
    private var gaps = 0
    private val analyzer = H264RtpAnalyzer()
    private var videoBytes = 0L
    private var audioBytes = 0L
    private val videoTs = TimestampUnwrapper()
    private val audioTs = TimestampUnwrapper()
    private var lastVideoRtpTs = -1L
    private var auArrival = 0L
    private var firstAuTs = Long.MIN_VALUE
    private var minAuTs = Long.MAX_VALUE
    private var maxAuTs = Long.MIN_VALUE
    private var firstAuArrival = 0L
    private var minTransitNs = Long.MAX_VALUE
    private var maxJitterNs = 0L
    private var frames = 0
    private var keyframes = 0
    private val keyframeTs = ArrayList<Long>()
    private val sliceCounts = EnumMap<H264SliceType, Int>(H264SliceType::class.java)
    private var maxFrameBytes = 0
    private var frameBytes = 0L
    private var keyframeBytes = 0L
    private var inBandSps = false
    private var inBandMatches: Boolean? = null
    private var firstAudioTs = Long.MIN_VALUE
    private var lastAudioTs = Long.MIN_VALUE

    fun onFrame(frame: InterleavedFrame) {
        val now = nanoTime()
        when (frame.channel) {
            videoChannel -> onVideo(frame.data, now)
            audioChannel -> onAudio(frame.data)
            videoChannel + 1 -> rtcpPackets++
            else -> if (audioChannel >= 0 && frame.channel == audioChannel + 1) rtcpPackets++
        }
    }

    private fun onVideo(data: ByteArray, now: Long) {
        val p = RtpPacket.parse(data) ?: return
        videoPackets++
        if (firstPacketNanos == 0L) firstPacketNanos = now
        if (lastSeq >= 0 && p.sequenceNumber != ((lastSeq + 1) and 0xFFFF)) gaps++
        lastSeq = p.sequenceNumber
        videoBytes += p.payloadLength
        if (firstKeyframeNanos == 0L && startsIdr(p)) firstKeyframeNanos = now
        val newTimestamp = p.timestamp != lastVideoRtpTs
        lastVideoRtpTs = p.timestamp
        analyzer.push(p)?.let { onAccessUnit(it, auArrival) }
        if (newTimestamp) auArrival = now
    }

    private fun onAudio(data: ByteArray) {
        val p = RtpPacket.parse(data) ?: return
        audioPackets++
        audioBytes += p.payloadLength
        val ts = audioTs.unwrap(p.timestamp)
        if (firstAudioTs == Long.MIN_VALUE) firstAudioTs = ts
        lastAudioTs = ts
    }

    private fun onAccessUnit(au: H264AccessUnit, arrival: Long) {
        frames++
        val ts = videoTs.unwrap(au.rtpTimestamp)
        if (firstAuTs == Long.MIN_VALUE) {
            firstAuTs = ts
            firstAuArrival = arrival
        }
        minAuTs = minOf(minAuTs, ts)
        maxAuTs = maxOf(maxAuTs, ts)
        val expectedNs = (ts - firstAuTs) * 1_000_000_000L / videoClock
        val transit = (arrival - firstAuArrival) - expectedNs
        if (transit < minTransitNs) minTransitNs = transit
        maxJitterNs = maxOf(maxJitterNs, transit - minTransitNs)
        if (au.isKeyframe) {
            keyframes++
            keyframeTs += ts
            keyframeBytes += au.bytes
        }
        for (type in au.sliceTypes) sliceCounts[type] = (sliceCounts[type] ?: 0) + 1
        maxFrameBytes = maxOf(maxFrameBytes, au.bytes)
        frameBytes += au.bytes
        for (sps in au.spsUnits) {
            inBandSps = true
            if (sdpSps != null) inBandMatches = (inBandMatches ?: true) && trimZeros(sps).contentEquals(trimZeros(sdpSps))
        }
    }

    fun finish(requestedMs: Long, receivedMs: Long, setupMs: Long, playMs: Long, stalls: Int, sessionTimeout: Int?): StreamAnalysis {
        analyzer.flush()?.let { onAccessUnit(it, auArrival) }
        // With B-frames, timestamps arrive out of order, so use the full range.
        val spanTicks = if (frames >= 2) maxAuTs - minAuTs else 0L
        val fps = if (spanTicks > 0) (frames - 1) * videoClock.toDouble() / spanTicks else null
        val spanSec = if (spanTicks > 0) spanTicks.toDouble() / videoClock * frames / (frames - 1) else 0.0
        val audioSpan = if (audioPackets >= 2 && lastAudioTs > firstAudioTs) {
            (lastAudioTs - firstAudioTs).toDouble() / audioClock * audioPackets / (audioPackets - 1)
        } else 0.0
        return StreamAnalysis(
            requestedMs = requestedMs,
            receivedMs = receivedMs,
            setupMs = setupMs,
            playMs = playMs,
            firstPacketMs = if (firstPacketNanos > 0) (firstPacketNanos - playSentNanos) / 1_000_000 else null,
            firstKeyframeMs = if (firstKeyframeNanos > 0) (firstKeyframeNanos - playSentNanos) / 1_000_000 else null,
            videoPackets = videoPackets,
            audioPackets = audioPackets,
            rtcpPackets = rtcpPackets,
            sequenceGaps = gaps,
            frames = frames,
            keyframes = keyframes,
            keyframeIntervalsSec = keyframeTs.zipWithNext { a, b -> (b - a).toDouble() / videoClock },
            measuredFps = fps,
            videoKbps = if (spanSec > 0) videoBytes * 8 / 1000.0 / spanSec else null,
            audioKbps = if (audioSpan > 0) audioBytes * 8 / 1000.0 / audioSpan else null,
            sliceTypeCounts = sliceCounts.toMap(),
            maxFrameBytes = maxFrameBytes,
            averageFrameBytes = if (frames > 0) (frameBytes / frames).toInt() else 0,
            averageKeyframeBytes = if (keyframes > 0) (keyframeBytes / keyframes).toInt() else 0,
            maxArrivalJitterMs = if (frames >= 2) maxJitterNs / 1_000_000 else null,
            inBandSps = inBandSps,
            inBandSpsMatchesSdp = inBandMatches,
            unsupportedPackets = analyzer.unsupportedPackets,
            stallsOver1s = stalls,
            sessionTimeoutSec = sessionTimeout,
        )
    }

    private fun startsIdr(p: RtpPacket): Boolean {
        if (p.payloadLength < 1) return false
        return when (p.payloadByte(0) and 0x1F) {
            H264.NAL_IDR -> true
            H264.RTP_FU_A -> p.payloadLength >= 2 && p.payloadByte(1) and 0x80 != 0 && p.payloadByte(1) and 0x1F == H264.NAL_IDR
            H264.RTP_STAP_A -> {
                var i = 1
                var found = false
                while (i + 2 < p.payloadLength) {
                    val size = (p.payloadByte(i) shl 8) or p.payloadByte(i + 1)
                    if (size == 0 || i + 2 + size > p.payloadLength) break
                    if (p.payloadByte(i + 2) and 0x1F == H264.NAL_IDR) found = true
                    i += 2 + size
                }
                found
            }
            else -> false
        }
    }

    private fun trimZeros(b: ByteArray): ByteArray {
        var end = b.size
        while (end > 0 && b[end - 1].toInt() == 0) end--
        return if (end == b.size) b else b.copyOf(end)
    }
}

/** Extends 32-bit RTP timestamps across wrap-around. */
internal class TimestampUnwrapper {
    private var last = -1L
    private var offset = 0L

    fun unwrap(timestamp: Long): Long {
        if (last >= 0) {
            val diff = timestamp - last
            if (diff < -0x80000000L) offset += 0x100000000L else if (diff > 0x80000000L) offset -= 0x100000000L
        }
        last = timestamp
        return timestamp + offset
    }
}
