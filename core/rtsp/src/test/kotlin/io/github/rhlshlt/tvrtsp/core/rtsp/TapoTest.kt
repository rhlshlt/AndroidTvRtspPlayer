package io.github.rhlshlt.tvrtsp.core.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapoTest {

    @Test
    fun buildsStandardLensUrls() {
        val s = Tapo.streams("192.168.1.20", "tapoview", "secret123")
        assertEquals("rtsp://192.168.1.20:554/stream1", s.main.requestUri)
        assertEquals("rtsp://192.168.1.20:554/stream2", s.sub.requestUri)
        assertEquals("tapoview", s.main.username)
        assertEquals("secret123", s.sub.password)
    }

    @Test
    fun buildsTelephotoUrls() {
        val s = Tapo.streams("cam.lan", lens = TapoLens.TELEPHOTO)
        assertEquals("rtsp://cam.lan:554/stream6", s.main.requestUri)
        assertEquals("rtsp://cam.lan:554/stream7", s.sub.requestUri)
    }

    @Test
    fun acceptsValidAccount() {
        assertTrue(Tapo.validateAccount("tapoview", "Secret123").isEmpty())
        assertTrue(Tapo.validateAccount("a".repeat(6), "b".repeat(32)).isEmpty())
    }

    @Test
    fun flagsLengthErrors() {
        val issues = Tapo.validateAccount("abcde", "x".repeat(33))
        assertEquals(2, issues.size)
        assertTrue(issues.all { it.severity == TapoAccountIssue.Severity.ERROR })
        assertEquals(setOf(TapoAccountIssue.Field.USERNAME, TapoAccountIssue.Field.PASSWORD), issues.map { it.field }.toSet())
    }

    @Test
    fun warnsAboutSpecialCharacters() {
        val issues = Tapo.validateAccount("tapo_view", "pass word!")
        assertEquals(2, issues.size)
        assertTrue(issues.all { it.severity == TapoAccountIssue.Severity.WARNING })
    }
}
