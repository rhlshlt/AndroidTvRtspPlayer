package io.github.rhlshlt.tvrtsp.core.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class CameraCheckTest {

    private val user = "tapolab"
    private val pass = "p@ss:w/rd%" // characters that break naive URL handling

    private fun url(server: FakeRtspServer, path: String = "/stream1", withCreds: Boolean = true) =
        RtspUrl.parse("${server.baseUrl}$path").let { if (withCreds) it.withCredentials(user, pass) else it }

    private fun check(server: FakeRtspServer, u: RtspUrl, analyzeMs: Long = 0, readTimeoutMs: Int = 2_000): CameraReport {
        val report = CameraCheck().run(u, CameraCheckOptions(analyzeMs = analyzeMs, readTimeoutMs = readTimeoutMs))
        server.failure?.let { throw AssertionError("fake server failed", it) }
        return report
    }

    @Test
    fun describeOnlyWithDigest() {
        FakeRtspServer(FakeRtspServer.MAIN360_SDP, user, pass).use { server ->
            val r = check(server, url(server))
            assertTrue(r.outcome.title, r.ok)
            assertEquals(listOf(
                "OPTIONS ${server.baseUrl}/stream1",
                "OPTIONS ${server.baseUrl}/stream1",
                "DESCRIBE ${server.baseUrl}/stream1",
            ), server.requests)
            assertEquals(true, r.acceptedLegacyDigest)
            assertTrue(r.authTrace.single().contains("RFC 2069 style"))
            assertTrue(r.engineAuth.all { it.level == EngineAuthVerdict.Level.OK })
            assertEquals("FakeCam/1.0", r.server)
            assertTrue("GET_PARAMETER" in r.publicMethods)
            assertEquals("H264", r.video!!.encoding)
            assertEquals("${server.baseUrl}/stream1/track1", r.video!!.controlUrl)
            assertEquals("PCMA", r.audio!!.encoding)
            assertEquals(640, r.sps!!.width)
            assertNull(r.analysis)
            assertTrue(r.findings.any { it.code == "SPROP" && it.severity == Severity.OK })
            assertTrue(r.findings.any { it.code == "AUTH-DIGEST" && it.severity == Severity.OK })
            assertEquals(Severity.INFO, r.worstFor(PlayerEngine.MEDIA3))
        }
    }

    @Test
    fun fullAnalysisOfCapturedStream() {
        val stream = FakeRtspServer.resource("main360-pcma.rtp")
        FakeRtspServer(FakeRtspServer.MAIN360_SDP, user, pass, stream = stream).use { server ->
            val r = check(server, url(server), analyzeMs = 10_000)
            assertTrue(r.outcome.title, r.ok)
            val base = server.baseUrl
            assertEquals(listOf(
                "OPTIONS $base/stream1", "OPTIONS $base/stream1", "DESCRIBE $base/stream1",
                "SETUP $base/stream1/track1", "SETUP $base/stream1/track2", "PLAY $base/stream1",
            ), server.requests.take(6))
            assertEquals("RTP/AVP/TCP;unicast;interleaved=0-1", server.transports[0])
            assertEquals("RTP/AVP/TCP;unicast;interleaved=2-3", server.transports[1])
            val a = r.analysis!!
            assertEquals(63, a.frames)
            assertEquals(3, a.keyframes)
            assertEquals(2.0, a.keyframeIntervalAvg!!, 1e-9)
            assertEquals(15.0, a.measuredFps!!, 1e-9)
            assertFalse(a.bFrames)
            assertEquals(3, a.sliceTypeCounts[H264SliceType.I])
            assertEquals(60, a.sliceTypeCounts[H264SliceType.P])
            assertEquals(33, a.audioPackets)
            assertEquals(2, a.rtcpPackets)
            assertEquals(0, a.sequenceGaps)
            assertTrue(a.inBandSps)
            assertEquals(true, a.inBandSpsMatchesSdp)
            assertNotNull(a.firstPacketMs)
            assertNotNull(a.firstKeyframeMs)
            assertEquals(60, a.sessionTimeoutSec)
            assertTrue(a.videoKbps!! > 0)
            assertTrue(a.audioKbps!! in 50.0..80.0) // G.711: 64 kbit/s
            assertTrue(r.findings.any { it.code == "B-FRAMES" && it.severity == Severity.OK })
            assertTrue(r.findings.any { it.code == "GOP" && it.message.startsWith("Keyframe every 2.00 s") })
            // Every request after the first challenge carried a Digest answer for its own URI.
            assertTrue(server.authorizations.all { it.startsWith("Digest username=\"tapolab\"") })
        }
    }

    @Test
    fun reportsBFrames() {
        val stream = FakeRtspServer.resource("high360-bframes.rtp")
        FakeRtspServer(FakeRtspServer.HIGH360_SDP, stream = stream).use { server ->
            val r = check(server, url(server, withCreds = false), analyzeMs = 10_000)
            assertTrue(r.ok)
            val a = r.analysis!!
            assertEquals(80, a.frames)
            assertTrue(a.bFrames)
            assertEquals(25.0, a.measuredFps!!, 1e-9)
            assertEquals(1.0, a.keyframeIntervalAvg!!, 1e-9)
            assertTrue(r.findings.any { it.code == "B-FRAMES" && it.severity == Severity.WARNING })
            assertTrue(r.findings.any { it.code == "REORDER" && it.message.contains("2 reordered") })
            assertTrue(r.findings.any { it.code == "AUDIO" && it.message == "No audio track" })
        }
    }

    @Test
    fun cameraThatRequiresQopIsFlagged() {
        FakeRtspServer(FakeRtspServer.MAIN360_SDP, user, pass, qopOnly = true).use { server ->
            val r = check(server, url(server))
            assertTrue(r.outcome.title, r.ok)
            assertEquals(false, r.acceptedLegacyDigest)
            assertEquals(2, r.authTrace.size)
            assertTrue(r.findings.any { it.code == "AUTH-QOP" && it.severity == Severity.ERROR })
            assertTrue(r.engineAuth.all { it.level == EngineAuthVerdict.Level.RISK })
        }
    }

    @Test
    fun basicAuthIsWarnedAbout() {
        FakeRtspServer(FakeRtspServer.MAIN360_SDP, user, pass, scheme = "Basic").use { server ->
            val r = check(server, url(server))
            assertTrue(r.ok)
            assertTrue(r.findings.any { it.code == "AUTH-BASIC" && it.severity == Severity.WARNING })
        }
    }

    @Test
    fun wrongPassword() {
        FakeRtspServer(FakeRtspServer.MAIN360_SDP, user, pass).use { server ->
            val r = check(server, url(server).withCredentials(user, "wrong-password"))
            assertEquals(ProbeOutcome.AuthFailed, r.outcome)
            assertEquals("E-401", r.outcome.code)
            assertFalse(r.toText().contains("wrong-password"))
            assertFalse(r.toJson().contains("wrong-password"))
        }
    }

    @Test
    fun missingCredentials() {
        FakeRtspServer(FakeRtspServer.MAIN360_SDP, user, pass).use { server ->
            assertEquals(ProbeOutcome.AuthRequired, check(server, url(server, withCreds = false)).outcome)
        }
    }

    @Test
    fun wrongPath() {
        FakeRtspServer(FakeRtspServer.MAIN360_SDP, user, pass).use { server ->
            val r = check(server, url(server, "/stream9"))
            assertEquals(ProbeOutcome.NotFound, r.outcome)
            assertTrue(r.findings.first().message.contains("/stream1"))
        }
    }

    @Test
    fun busyAtSetup() {
        FakeRtspServer(FakeRtspServer.MAIN360_SDP, setupStatus = 453).use { server ->
            val r = check(server, url(server, withCreds = false), analyzeMs = 2_000)
            val outcome = r.outcome as ProbeOutcome.CameraBusy
            assertEquals(ProbeStep.SETUP, outcome.step)
            assertEquals(453, outcome.status)
        }
    }

    @Test
    fun viewerLimitShowsAsBusy() {
        // A Tapo camera serves about two RTSP viewers; the third should be reported as busy.
        val stream = FakeRtspServer.resource("main360-pcma.rtp")
        FakeRtspServer(FakeRtspServer.MAIN360_SDP, stream = stream, viewerLimit = 2, holdAfterStreamMs = 1_500).use { server ->
            val u = url(server, withCreds = false)
            val pool = Executors.newFixedThreadPool(3)
            val results = pool.invokeAll((1..3).map { Callable { CameraCheck().run(u, CameraCheckOptions(analyzeMs = 5_000)) } })
                .map { it.get() }
            pool.shutdown()
            assertEquals(2, results.count { it.ok })
            val busy = results.single { !it.ok }.outcome as ProbeOutcome.CameraBusy
            assertEquals(ProbeStep.SETUP, busy.step)
        }
    }

    @Test
    fun connectionRefused() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val r = CameraCheck().run(RtspUrl.parse("rtsp://127.0.0.1:$port/stream1"))
        assertTrue(r.outcome.toString(), r.outcome is ProbeOutcome.Refused)
        assertNotNull(r.dnsMs)
    }

    @Test
    fun silentCameraTimesOut() {
        FakeRtspServer(FakeRtspServer.MAIN360_SDP, silent = true).use { server ->
            val start = System.nanoTime()
            val r = check(server, url(server, withCreds = false), readTimeoutMs = 300)
            val outcome = r.outcome as ProbeOutcome.Timeout
            assertEquals(ProbeStep.OPTIONS, outcome.step)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 3_000)
        }
    }

    @Test
    fun dnsFailure() {
        val check = CameraCheck(resolver = HostResolver { host, _ -> throw UnknownHostException(host) })
        val r = check.run(RtspUrl.parse("rtsp://no-such-camera.invalid/stream1"))
        val outcome = r.outcome as ProbeOutcome.DnsFailure
        assertFalse(outcome.timedOut)
        assertEquals("E-DNS", outcome.code)
    }

    @Test
    fun c320wsFindingsFromSdpAlone() {
        val sdp = SessionDescription.parse(
            "v=0\r\nm=video 0 RTP/AVP 96\r\na=control:track1\r\na=rtpmap:96 H264/90000\r\n" +
                "a=fmtp:96 packetization-mode=1; profile-level-id=4D0032; sprop-parameter-sets=Z00AMoqKUBQAWtNkAAAPoAAB1MAQ,aO48gA==\r\n" +
                "m=audio 0 RTP/AVP 8\r\na=rtpmap:8 PCMA/8000\r\na=control:track2\r\n",
        )
        val h264 = H264SdpInfo.from(sdp.video!!)!!
        val sps = H264Sps.parse(h264.sps!!)
        val track = TrackInfo("video", "H264", 96, 90000, null, "track1", "rtsp://cam/stream1/track1", emptyMap())
        val audio = TrackInfo("audio", "PCMA", 8, 8000, null, "track2", "rtsp://cam/stream1/track2", emptyMap())
        val f = Findings.build(ProbeOutcome.Ok, "Digest", true, emptyList(), sdp, track, audio, h264, sps, null, null)
        val byCode = f.associateBy { it.code }
        assertEquals(Severity.WARNING, byCode["LEVEL"]!!.severity)
        val reorder = byCode["REORDER"]!!
        assertEquals(Severity.WARNING, reorder.severity)
        assertTrue(reorder.message, reorder.message.contains("up to 7 frame(s) (about 0.47 s at 15.0 fps)"))
        assertEquals(Severity.INFO, byCode["RANGE"]!!.severity)
        assertEquals(Severity.OK, byCode["SPROP"]!!.severity)
        assertEquals(Severity.OK, byCode["AUDIO"]!!.severity)
        assertTrue(byCode["SPS"]!!.message.startsWith("Main profile, Level 5, 2560×1440 at 15.0 fps"))
    }

    @Test
    fun media3SpropRules() {
        fun findingsFor(fmtp: String): List<Finding> {
            val sdp = SessionDescription.parse("v=0\nm=video 0 RTP/AVP 96\na=control:t\na=rtpmap:96 H264/90000\na=fmtp:96 $fmtp\n")
            val h = H264SdpInfo.from(sdp.video!!)
            val track = TrackInfo("video", "H264", 96, 90000, null, "t", "rtsp://c/t", emptyMap())
            return Findings.build(ProbeOutcome.Ok, null, null, emptyList(), sdp, track, null, h, null, null, null)
        }
        val missing = findingsFor("packetization-mode=1")
        assertTrue(missing.any { it.code == "SPROP" && it.severity == Severity.ERROR && it.engine == PlayerEngine.MEDIA3 })
        val three = findingsFor("packetization-mode=1;sprop-parameter-sets=Z00AMoqKUBQAWtNkAAAPoAAB1MAQ,aO48gA==,aO48gA==")
        assertTrue(three.any { it.code == "SPROP" && it.message.contains("3 entries") })
        val swapped = findingsFor("packetization-mode=1;sprop-parameter-sets=aO48gA==,Z00AMoqKUBQAWtNkAAAPoAAB1MAQ")
        assertTrue(swapped.any { it.code == "SPROP" && it.message.contains("SPS first") })
    }

    @Test
    fun jsonAndTextRendering() {
        FakeRtspServer(FakeRtspServer.MAIN360_SDP, user, pass).use { server ->
            val r = check(server, url(server))
            val json = r.toJson()
            assertTrue(json.contains("\"code\": \"OK\""))
            assertTrue(json.contains("\"target\": \"rtsp://***@127.0.0.1:${server.port}/stream1\""))
            assertFalse(json.contains(pass))
            assertFalse(json.contains("tapolab\""))
            val text = r.toText()
            assertTrue(text, text.startsWith("Camera check: rtsp://***@127.0.0.1:${server.port}/stream1\nResult: OK"))
            assertFalse(text.contains(pass))
        }
    }

    @Test
    fun jsonEscaping() {
        assertEquals("{\n  \"a\": \"q\\\"\\\\\\n\\u0001\",\n  \"b\": [\n    1,\n    null,\n    true\n  ]\n}",
            Json.stringify(linkedMapOf("a" to "q\"\\\n\u0001", "b" to listOf(1, Double.NaN, true))))
    }
}
