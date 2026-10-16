package io.github.rhlshlt.tvrtsp.core.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Digest vectors come from RFC 2617 §3.5 and RFC 7616 §3.9.1; the MD5-sess and RTSP values were
 * computed independently with Python's hashlib.
 */
class AuthTest {

    private fun challenge(header: String) = WwwAuthenticate.parse(header).single()

    @Test
    fun rfc2617Example() {
        val c = challenge("Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", " +
            "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"")
        val header = HttpAuth.digest(c, Credentials("Mufasa", "Circle Of Life"), "GET", "/dir/index.html",
            DigestStyle.QOP_AUTH, nc = 1, cnonce = "0a4f113b")
        assertTrue(header, header.contains("response=\"6629fae49393a05397450978507c4ef1\""))
        assertTrue(header.contains("qop=auth, nc=00000001, cnonce=\"0a4f113b\""))
        assertTrue(header.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""))
        assertTrue(header.startsWith("Digest username=\"Mufasa\", realm=\"testrealm@host.com\""))
    }

    @Test
    fun rfc7616Md5AndSha256Examples() {
        val base = "realm=\"http-auth@example.org\", qop=\"auth, auth-int\", " +
            "nonce=\"7ypf/xlj9XXwfDPEoM4URrv/xwf94BcCAzFZH4GiTo0v\", opaque=\"FQhe/qaU925kfnzjCev0ciny7QMkPqMAFRtzCUYo5tdS\""
        val creds = Credentials("Mufasa", "Circle of Life")
        val cnonce = "f2/wE4q74E6zIJEtWaHKaf5wv/H5QzzpXusqGemxURZJ"
        val md5 = HttpAuth.digest(challenge("Digest $base, algorithm=MD5"), creds, "GET", "/dir/index.html", DigestStyle.QOP_AUTH, 1, cnonce)
        assertTrue(md5, md5.contains("response=\"8ca523f5e9506fed4657c9700eebdbec\""))
        assertTrue(md5.contains("algorithm=MD5"))
        val sha = HttpAuth.digest(challenge("Digest $base, algorithm=SHA-256"), creds, "GET", "/dir/index.html", DigestStyle.QOP_AUTH, 1, cnonce)
        assertTrue(sha, sha.contains("response=\"753927fa0e85d155564e2e272a28d1802ca10daf4496794697cf8db5856cb6c1\""))
    }

    @Test
    fun md5SessVector() {
        val c = challenge("Digest realm=\"testrealm@host.com\", qop=\"auth\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", algorithm=MD5-sess")
        val h = HttpAuth.digest(c, Credentials("Mufasa", "Circle Of Life"), "GET", "/dir/index.html", DigestStyle.QOP_AUTH, 1, "0a4f113b")
        assertTrue(h, h.contains("response=\"8e3825c57e897f5a0dec6c2d4e5059d0\""))
    }

    @Test
    fun legacyRtspDigestAsEnginesSendIt() {
        val c = challenge("Digest realm=\"TP-LINK IP-Camera\", nonce=\"a1b2c3d4e5\"")
        val h = HttpAuth.digest(c, Credentials("tapolab", "tapolab123"), "DESCRIBE", "rtsp://127.0.0.1:8554/stream1", DigestStyle.LEGACY)
        assertEquals(
            "Digest username=\"tapolab\", realm=\"TP-LINK IP-Camera\", nonce=\"a1b2c3d4e5\", " +
                "uri=\"rtsp://127.0.0.1:8554/stream1\", response=\"57a7f875a5abba1a7ea0ef0f425aa46d\"",
            h,
        )
    }

    @Test
    fun basicExample() {
        assertEquals("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==", HttpAuth.basic(Credentials("Aladdin", "open sesame")))
    }

    @Test
    fun parsesSeveralChallengesWithQuotedCommas() {
        val list = WwwAuthenticate.parse("Basic realm=\"cam\", Digest realm=\"a, b\", nonce=\"n1\", qop=\"auth,auth-int\", algorithm=MD5, stale=TRUE")
        assertEquals(2, list.size)
        assertTrue(list[0].isBasic)
        assertEquals("cam", list[0].realm)
        val d = list[1]
        assertTrue(d.isDigest)
        assertEquals("a, b", d.realm)
        assertEquals("n1", d.nonce)
        assertEquals(listOf("auth", "auth-int"), d.qopOptions)
        assertEquals("MD5", d.algorithm)
        assertTrue(d.stale)
        assertEquals("Digest realm=\"a, b\", nonce=\"n1\", qop=\"auth,auth-int\", algorithm=MD5, stale=TRUE", d.raw)
    }

    @Test
    fun unescapesQuotedPairsAndEscapesOnOutput() {
        val c = challenge("Digest realm=\"say \\\"hi\\\"\", nonce=\"n\"")
        assertEquals("say \"hi\"", c.realm)
        val h = HttpAuth.digest(c, Credentials("we\"ird", "p"), "OPTIONS", "rtsp://x/y", DigestStyle.LEGACY)
        assertTrue(h, h.contains("username=\"we\\\"ird\""))
        assertTrue(h, h.contains("realm=\"say \\\"hi\\\"\""))
    }

    @Test
    fun choosesDigestOverBasic() {
        val chosen = AuthSession.choose(WwwAuthenticate.parse(listOf("Basic realm=\"x\"", "Digest realm=\"x\", nonce=\"y\"")))
        assertNotNull(chosen)
        assertTrue(chosen!!.isDigest)
        val unsupported = AuthSession.choose(WwwAuthenticate.parse(listOf("Digest realm=\"x\", nonce=\"y\", algorithm=SHA-512-256", "Basic realm=\"x\"")))
        assertTrue(unsupported!!.isBasic)
    }

    @Test
    fun authSessionCountsNonceUses() {
        val c = challenge("Digest realm=\"r\", nonce=\"n\", qop=\"auth\"")
        val s = AuthSession(c, Credentials("u", "p"), DigestStyle.LEGACY, cnonce = "c0ffee")
        assertFalse(s.authorization("OPTIONS", "rtsp://h/x").contains("nc="))
        assertTrue(s.upgradeToQop())
        assertTrue(s.authorization("OPTIONS", "rtsp://h/x").contains("nc=00000001"))
        assertTrue(s.authorization("DESCRIBE", "rtsp://h/x").contains("nc=00000002"))
        assertFalse(s.upgradeToQop())
    }

    // Engine compatibility: mirrors Media3 1.11.1 and rtsp-client-android 6.1.1 header handling.

    private fun verdicts(vararg headers: String) = EngineAuthCompat.evaluate(headers.toList()).associateBy { it.engine }

    @Test
    fun plainDigestWorksInBothEngines() {
        val v = verdicts("Digest realm=\"TP-LINK IP-Camera\", nonce=\"abc123\"")
        assertEquals(EngineAuthVerdict.Level.OK, v[PlayerEngine.MEDIA3]!!.level)
        assertEquals(EngineAuthVerdict.Level.OK, v[PlayerEngine.RTSP_CLIENT_ANDROID]!!.level)
    }

    @Test
    fun qopAfterNonceIsARiskForBoth() {
        val v = verdicts("Digest realm=\"x\", nonce=\"y\", algorithm=\"MD5\", qop=\"auth\"")
        assertEquals(EngineAuthVerdict.Level.RISK, v[PlayerEngine.MEDIA3]!!.level)
        assertEquals(EngineAuthVerdict.Level.RISK, v[PlayerEngine.RTSP_CLIENT_ANDROID]!!.level)
    }

    @Test
    fun media3NeedsRealmThenNonce() {
        val v = verdicts("Digest qop=\"auth\", realm=\"x\", nonce=\"y\"")
        assertEquals(EngineAuthVerdict.Level.FAILS, v[PlayerEngine.MEDIA3]!!.level)
        assertEquals(EngineAuthVerdict.Level.RISK, v[PlayerEngine.RTSP_CLIENT_ANDROID]!!.level)
    }

    @Test
    fun nonMd5AlgorithmFailsBoth() {
        val v = verdicts("Digest realm=\"x\", nonce=\"y\", algorithm=SHA-256")
        assertEquals(EngineAuthVerdict.Level.FAILS, v[PlayerEngine.MEDIA3]!!.level)
        assertEquals(EngineAuthVerdict.Level.FAILS, v[PlayerEngine.RTSP_CLIENT_ANDROID]!!.level)
    }

    @Test
    fun basicOnlyWorksInBoth() {
        val v = verdicts("Basic realm=\"cam\"")
        assertEquals("Basic", v[PlayerEngine.MEDIA3]!!.scheme)
        assertEquals(EngineAuthVerdict.Level.OK, v[PlayerEngine.MEDIA3]!!.level)
        assertEquals(EngineAuthVerdict.Level.OK, v[PlayerEngine.RTSP_CLIENT_ANDROID]!!.level)
    }

    @Test
    fun media3StopsAtAnUnparsableBasicHeader() {
        val v = verdicts("Basic realm=\"cam\", charset=\"UTF-8\"", "Digest realm=\"cam\", nonce=\"n\"")
        assertEquals(EngineAuthVerdict.Level.FAILS, v[PlayerEngine.MEDIA3]!!.level)
        assertEquals(EngineAuthVerdict.Level.OK, v[PlayerEngine.RTSP_CLIENT_ANDROID]!!.level)
    }

    @Test
    fun opaqueIsOnlyEchoedByMedia3() {
        val v = verdicts("Digest realm=\"x\", nonce=\"y\", opaque=\"z\"")
        assertEquals(EngineAuthVerdict.Level.OK, v[PlayerEngine.MEDIA3]!!.level)
        assertEquals(EngineAuthVerdict.Level.RISK, v[PlayerEngine.RTSP_CLIENT_ANDROID]!!.level)
    }

    @Test
    fun media3RegexIsCaseSensitive() {
        val v = verdicts("digest realm=\"x\", nonce=\"y\"")
        assertEquals(EngineAuthVerdict.Level.FAILS, v[PlayerEngine.MEDIA3]!!.level)
        assertEquals(EngineAuthVerdict.Level.OK, v[PlayerEngine.RTSP_CLIENT_ANDROID]!!.level)
    }
}
