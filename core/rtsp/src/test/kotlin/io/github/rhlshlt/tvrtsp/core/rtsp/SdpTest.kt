package io.github.rhlshlt.tvrtsp.core.rtsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SdpTest {

    /**
     * Built before a real capture existed: the C320WS stream1 fmtp line is real, and the extra lines
     * (a smart_encoder attribute, an application track) check that the parser skips what it doesn't
     * use. The real answer is [c320wsStream2].
     */
    private val tapoLike = """
        v=0
        o=- 14665860 31787219 1 IN IP4 192.168.1.20
        s=Session streamed by "TP-LINK RTSP Server"
        t=0 0
        a=smart_encoder:virtual-i-frame=1;mode=0
        m=video 0 RTP/AVP 96
        c=IN IP4 0.0.0.0
        b=AS:4096
        a=range:npt=0-
        a=control:track1
        a=rtpmap:96 H264/90000
        a=fmtp:96 packetization-mode=1; profile-level-id=4D0032; sprop-parameter-sets=Z00AMoqKUBQAWtNkAAAPoAAB1MAQ,aO48gA==
        m=audio 0 RTP/AVP 8
        a=rtpmap:8 PCMA/8000
        a=control:track2
        m=application/TP-LINK 0 RTP/AVP smart/1/90000
        a=rtpmap:95 TP-LINK/90000
        a=control:track3
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun parsesTapoLikeAnswer() {
        val sdp = SessionDescription.parse(tapoLike)
        assertEquals("Session streamed by \"TP-LINK RTSP Server\"", sdp.sessionName)
        assertEquals(3, sdp.media.size)
        val video = sdp.video!!
        assertEquals(96, video.payloadType)
        assertEquals("H264", video.encoding)
        assertEquals(90000, video.rtpMap!!.clockRate)
        assertEquals("track1", video.control)
        assertEquals(4096, video.bandwidth["AS"])
        val fmtp = video.fmtp()
        assertEquals("1", fmtp["packetization-mode"])
        assertEquals("4D0032", fmtp["profile-level-id"])
        assertEquals("Z00AMoqKUBQAWtNkAAAPoAAB1MAQ,aO48gA==", fmtp["sprop-parameter-sets"])
        val audio = sdp.audio!!
        assertEquals("PCMA", audio.encoding)
        assertEquals(8000, audio.rtpMap!!.clockRate)
        assertEquals("track2", audio.control)
    }

    /**
     * The C320WS stream2 answer as the TV's camera check captured it on Oct 18, 2026 (firmware as
     * shipped; only the address is changed to the docs' example).
     */
    private val c320wsStream2 = """
        v=0
        o=- 14665860 31787219 1 IN IP4 192.168.1.20
        s=Session streamed by "TP-Link RTSP Server"
        t=0 0
        m=video 0 RTP/AVP 96
        c=IN IP4 0.0.0.0
        b=AS:4096
        a=range:npt=0-
        a=control:track1
        a=rtpmap:96 H264/90000
        a=fmtp:96 packetization-mode=1; profile-level-id=4D0016; sprop-parameter-sets=Z00AFoqKUFAX/LNkAAAPoAAB1MAQ,aO48gA==
        m=audio 0 RTP/AVP 8
        a=rtpmap:8 PCMA/8000
        a=control:track2
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun parsesRealC320wsAnswer() {
        val sdp = SessionDescription.parse(c320wsStream2)
        assertEquals("Session streamed by \"TP-Link RTSP Server\"", sdp.sessionName)
        assertEquals(2, sdp.media.size)
        val video = sdp.video!!
        assertEquals("track1", video.control)
        assertEquals("H264", video.encoding)
        assertEquals(4096, video.bandwidth["AS"])
        assertEquals("npt=0-", video.attribute("range"))
        assertEquals("track2", sdp.audio!!.control)
        assertEquals("PCMA", sdp.audio!!.encoding)
        // What Media3 needs: exactly two parameter sets, SPS first.
        val info = H264SdpInfo.from(video)!!
        assertTrue(info.hasSpsAndPps)
        assertEquals(1, info.ppsList.size)
        assertEquals(77, info.profileIdc)
        assertEquals(22, info.levelIdc)
        val sps = H264Sps.parse(info.sps!!)
        assertEquals(640, sps.width)
        assertEquals(360, sps.height)
    }

    @Test
    fun extractsH264ParameterSets() {
        val info = H264SdpInfo.from(SessionDescription.parse(tapoLike).video!!)!!
        assertEquals(1, info.packetizationMode)
        assertEquals(77, info.profileIdc)
        assertEquals(50, info.levelIdc)
        assertTrue(info.hasSpsAndPps)
        assertArrayEquals(Base64.getDecoder().decode("Z00AMoqKUBQAWtNkAAAPoAAB1MAQ"), info.sps)
        assertArrayEquals(Base64.getDecoder().decode("aO48gA=="), info.ppsList.single())
    }

    @Test
    fun staticPayloadTypeWithoutRtpmap() {
        val sdp = SessionDescription.parse("v=0\r\nm=audio 0 RTP/AVP 8\r\na=control:trackID=2\r\n")
        val map = sdp.audio!!.rtpMap!!
        assertEquals("PCMA", map.encoding)
        assertEquals(8000, map.clockRate)
        assertEquals(1, map.channels)
    }

    @Test
    fun missingSpropIsReported() {
        val sdp = SessionDescription.parse("v=0\nm=video 0 RTP/AVP 96\na=rtpmap:96 H264/90000\na=fmtp:96 packetization-mode=1\n")
        val info = H264SdpInfo.from(sdp.video!!)!!
        assertNull(info.spropText)
        assertTrue(!info.hasSpsAndPps)
    }

    @Test
    fun rejectsNonSdp() {
        try {
            SessionDescription.parse("<html>nope</html>")
            throw AssertionError("expected failure")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun resolvesControlUrlsLikeMedia3() {
        val req = "rtsp://192.168.1.20:554/stream1"
        assertEquals("rtsp://192.168.1.20:554/stream1/track1", ControlUrl.resolve("track1", req, null, null))
        assertEquals("rtsp://192.168.1.20:554/stream1/track1", ControlUrl.resolve("track1", req, "rtsp://192.168.1.20:554/stream1/", null))
        assertEquals("rtsp://cam/base/trackID=0", ControlUrl.resolve("trackID=0", req, "rtsp://cam/base", null))
        assertEquals("rtsp://cam/loc/t", ControlUrl.resolve("t", req, null, "rtsp://cam/loc"))
        assertEquals("rtsp://other/abs", ControlUrl.resolve("rtsp://other/abs", req, "rtsp://cam/base/", null))
        assertEquals("rtsp://cam/base/", ControlUrl.resolve("*", req, "rtsp://cam/base/", null))
        assertEquals(req, ControlUrl.resolve(null, req, null, null))
        assertEquals("rtsp://cam/live/track1?ch=1", ControlUrl.resolve("track1", "rtsp://cam/live?ch=1", null, null))
        assertEquals("rtsp://cam/track1", ControlUrl.resolve("track1", "rtsp://cam", null, null))
        assertNotNull(ControlUrl.resolve("track1", req, "", ""))
    }
}
