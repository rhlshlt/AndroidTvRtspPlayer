package io.github.rhlshlt.tvrtsp.core.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URI

class RtspUrlTest {

    @Test
    fun parsesTapoUrlWithPort() {
        val url = RtspUrl.parse("rtsp://192.168.1.20:554/stream1")
        assertEquals("rtsp", url.scheme)
        assertEquals("192.168.1.20", url.host)
        assertEquals(554, url.port)
        assertEquals(554, url.explicitPort)
        assertEquals("/stream1", url.pathAndQuery)
        assertFalse(url.hasCredentials)
        assertEquals("rtsp://192.168.1.20:554/stream1", url.requestUri)
    }

    @Test
    fun appliesDefaultPorts() {
        val rtsp = RtspUrl.parse("RTSP://Cam.local/stream2")
        assertEquals("rtsp", rtsp.scheme)
        assertEquals(554, rtsp.port)
        assertNull(rtsp.explicitPort)
        assertEquals("rtsp://Cam.local/stream2", rtsp.requestUri)
        assertEquals(322, RtspUrl.parse("rtsps://cam/x").port)
        assertEquals("/", RtspUrl.parse("rtsp://cam").pathAndQuery)
    }

    @Test
    fun rawSpecialCharactersInPasswordSplitAtLastAt() {
        val url = RtspUrl.parse("rtsp://tapo_user:p@ss:w/rd@192.168.1.20:554/stream1")
        assertEquals("tapo_user", url.username)
        assertEquals("p@ss:w/rd", url.password)
        assertEquals("192.168.1.20", url.host)
        assertEquals("/stream1", url.pathAndQuery)
    }

    @Test
    fun decodesPercentEncodedCredentials() {
        val url = RtspUrl.parse("rtsp://user%40home:pa%3Ass%25@10.0.0.2/stream1")
        assertEquals("user@home", url.username)
        assertEquals("pa:ss%", url.password)
    }

    @Test
    fun keepsInvalidPercentLiterally() {
        assertEquals("50%off", RtspUrl.parse("rtsp://u:50%off@h/x").password)
    }

    @Test
    fun encodesCredentialsSoMedia3SplitsThemBack() {
        val url = RtspUrl.of("192.168.1.20", "/stream1", 554, "tapo_user", "p@ss:w/rd%#?é")
        val withCreds = url.toUriWithCredentials()
        assertEquals("rtsp://tapo_user:p%40ss%3Aw%2Frd%25%23%3F%C3%A9@192.168.1.20:554/stream1", withCreds)
        // Media3 reads Uri.getUserInfo() (decoded) and splits at the first ':'; java.net.URI decodes the same way.
        val userInfo = URI(withCreds).userInfo
        assertEquals("tapo_user", userInfo.substringBefore(':'))
        assertEquals("p@ss:w/rd%#?é", userInfo.substringAfter(':'))
        // Media3 also strips user info by splitting the encoded authority at '@'.
        assertEquals(2, URI(withCreds).rawAuthority.split('@').size)
        // Round trip through our own parser.
        val back = RtspUrl.parse(withCreds)
        assertEquals("tapo_user", back.username)
        assertEquals("p@ss:w/rd%#?é", back.password)
    }

    @Test
    fun userWithoutPasswordGetsEmptyPasswordForMedia3() {
        val url = RtspUrl.parse("rtsp://viewer@cam/stream1")
        assertEquals("viewer", url.username)
        assertNull(url.password)
        assertEquals("rtsp://viewer:@cam/stream1", url.toUriWithCredentials())
    }

    @Test
    fun neverPrintsCredentials() {
        val url = RtspUrl.parse("rtsp://admin:hunter2@192.168.1.20:554/stream1")
        assertEquals("rtsp://***@192.168.1.20:554/stream1", url.redacted())
        assertEquals(url.redacted(), url.toString())
        assertFalse(url.toString().contains("hunter2"))
        assertFalse(url.toString().contains("admin"))
        assertFalse(url.requestUri.contains("hunter2"))
    }

    @Test
    fun parsesIpv6Literals() {
        val url = RtspUrl.parse("rtsp://u:p@[fe80::1%25eth0]:8554/x")
        assertEquals("fe80::1%25eth0", url.host)
        assertEquals(8554, url.port)
        assertEquals("rtsp://[fe80::1%25eth0]:8554/x", url.requestUri)
        assertTrue(url.isIpv6Literal)
        assertEquals("rtsp://[2001:db8::5]/stream1", RtspUrl.of("2001:db8::5", "stream1").requestUri)
    }

    @Test
    fun ofTakesAPortWrittenInTheHost() {
        // The Phase 0 bug: "203.0.113.5:18554" was bracketed as if it were an IPv6 literal.
        val url = RtspUrl.of("203.0.113.5:18554", "/stream1", 554, "tapoview", "secret123")
        assertEquals("rtsp://203.0.113.5:18554/stream1", url.requestUri)
        assertEquals("203.0.113.5", url.host)
        assertEquals(18554, url.port)
        assertFalse(url.isIpv6Literal)
        assertEquals("rtsp://cam.lan:8554/x", RtspUrl.of(" cam.lan:8554 ", "x").requestUri)
        assertEquals("rtsp://[2001:db8::5]:8554/x", RtspUrl.of("[2001:db8::5]:8554", "x", 554).requestUri)
        assertEquals("rtsp://[2001:db8::5]:554/x", RtspUrl.of("[2001:db8::5]", "x", 554).requestUri)
        assertEquals("rtsp://[fe80::1]:554/x", RtspUrl.of("fe80::1", "x", 554).requestUri)
        assertEquals("rtsp://10.0.0.9/x", RtspUrl.of("10.0.0.9", "x").requestUri)
    }

    @Test
    fun ofRejectsAddressesThatAreNotJustAnAddress() {
        for (bad in listOf("", "  ", "rtsp://10.0.0.9/stream1", "10.0.0.9/stream1", "user@10.0.0.9", "10.0.0.9:", "10.0.0.9:99999",
            "10.0.0.9:port", "[fe80::1", "[fe80::1]x", "1.2.3.4:5:6", "cam lan", "fe80::1::2", "12345::", "::g")) {
            try {
                RtspUrl.of(bad, "/stream1")
                fail("Expected an error for \"$bad\"")
            } catch (_: RtspUrlException) {
                // expected
            }
        }
    }

    @Test
    fun validatesIpv6Text() {
        for (ok in listOf("::", "::1", "1::", "fe80::1", "2001:db8::5", "1:2:3:4:5:6:7:8", "::ffff:192.168.1.20", "fe80::1%25eth0", "FE80::A")) {
            assertTrue(ok, RtspUrl.isValidIpv6(ok))
        }
        for (bad in listOf("1.2.3.4:18554", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7::8", ":1", "1:", "1::2::3", "12345::1", "::g",
            "fe80::1%", "::1.2.3", "::256.1.1.1", "1.2.3.4::", "")) {
            assertFalse(bad, RtspUrl.isValidIpv6(bad))
        }
    }

    @Test
    fun keepsQueryStrings() {
        val url = RtspUrl.parse("rtsp://cam/cam/realmonitor?channel=1&subtype=0")
        assertEquals("/cam/realmonitor?channel=1&subtype=0", url.pathAndQuery)
        assertEquals("/?a=1", RtspUrl.parse("rtsp://cam?a=1").pathAndQuery)
    }

    @Test
    fun withPathKeepsHostPortAndCredentials() {
        val main = RtspUrl.of("10.0.0.9", "/stream1", 554, "viewer1", "secret99")
        val sub = main.withPath("/stream2")
        assertEquals("rtsp://10.0.0.9:554/stream2", sub.requestUri)
        assertEquals("viewer1", sub.username)
        assertEquals("secret99", sub.password)
    }

    @Test
    fun rejectsBadInput() {
        expectReason("", RtspUrlException.Reason.EMPTY)
        expectReason("http://cam/x", RtspUrlException.Reason.SCHEME)
        expectReason("cam/stream1", RtspUrlException.Reason.SCHEME)
        expectReason("rtsp://", RtspUrlException.Reason.HOST)
        expectReason("rtsp:///stream1", RtspUrlException.Reason.HOST)
        expectReason("rtsp://cam:0/x", RtspUrlException.Reason.PORT)
        expectReason("rtsp://cam:65536/x", RtspUrlException.Reason.PORT)
        expectReason("rtsp://cam:5x4/x", RtspUrlException.Reason.PORT)
        expectReason("rtsp://ca m/x", RtspUrlException.Reason.WHITESPACE)
        expectReason("rtsp://cam/x#part", RtspUrlException.Reason.FRAGMENT)
        expectReason("rtsp://:secret@cam/x", RtspUrlException.Reason.USER_INFO)
        expectReason("rtsp://fe80::1/x", RtspUrlException.Reason.HOST)
        expectReason("rtsp://cam/" + "a".repeat(RtspUrl.MAX_LENGTH), RtspUrlException.Reason.TOO_LONG)
        assertNull(RtspUrl.parseOrNull("not a url"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("rtsp://cam/stream1", RtspUrl.parse("  rtsp://cam/stream1 \n").requestUri)
    }

    @Test
    fun percentCodecRoundTrips() {
        val samples = listOf("plain", "a b", "ümlaut", "😀emoji", "%41", "~-._", "@:/?#[]!$&'()*+,;=")
        for (s in samples) assertEquals(s, PercentCodec.decode(PercentCodec.encode(s)))
        assertEquals("~-._AZaz09", PercentCodec.encode("~-._AZaz09"))
    }

    private fun expectReason(input: String, reason: RtspUrlException.Reason) {
        try {
            RtspUrl.parse(input)
            fail("Expected $reason for \"$input\"")
        } catch (e: RtspUrlException) {
            assertEquals("for \"$input\": ${e.message}", reason, e.reason)
        }
    }
}
