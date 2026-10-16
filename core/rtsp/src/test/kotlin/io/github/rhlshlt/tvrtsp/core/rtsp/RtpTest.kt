package io.github.rhlshlt.tvrtsp.core.rtsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The fixtures are real RTP packets from FFmpeg's RTP muxer (an independent RFC 6184 packetizer),
 * captured over UDP and stored in RTSP interleaved framing. Expected counts were computed with a
 * separate Python script; see src/test/resources/fixtures/README.md.
 */
class RtpTest {

    private fun frames(name: String): List<InterleavedFrame> {
        val data = FakeRtspServer.resource(name)
        val out = mutableListOf<InterleavedFrame>()
        var i = 0
        while (i < data.size) {
            assertEquals('$'.code.toByte(), data[i])
            val ch = data[i + 1].toInt() and 0xFF
            val len = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            out += InterleavedFrame(ch, data.copyOfRange(i + 4, i + 4 + len))
            i += 4 + len
        }
        return out
    }

    private fun units(name: String): List<H264AccessUnit> {
        val a = H264RtpAnalyzer()
        val result = mutableListOf<H264AccessUnit>()
        for (f in frames(name)) {
            if (f.channel != 0) continue
            a.push(RtpPacket.parse(f.data)!!)?.let { result += it }
        }
        a.flush()?.let { result += it }
        return result
    }

    @Test
    fun parsesRtpHeaderWithCsrcExtensionAndPadding() {
        val pkt = byteArrayOf(
            (0x80 or 0x20 or 0x10 or 0x01).toByte(), (0x80 or 96).toByte(), 0x12, 0x34, // V=2 P X CC=1, M PT=96, seq
            0x00, 0x00, 0x03, 0xE8.toByte(), // timestamp 1000
            0x01, 0x02, 0x03, 0x04, // SSRC
            0x0A, 0x0B, 0x0C, 0x0D, // CSRC
            0xBE.toByte(), 0xDE.toByte(), 0x00, 0x01, 0x11, 0x22, 0x33, 0x44, // extension, 1 word
            0x65, 0x77, // payload
            0x00, 0x02, // padding: 2 bytes (last byte = count)
        )
        val p = RtpPacket.parse(pkt)!!
        assertEquals(96, p.payloadType)
        assertTrue(p.marker)
        assertEquals(0x1234, p.sequenceNumber)
        assertEquals(1000L, p.timestamp)
        assertEquals(0x01020304L, p.ssrc)
        assertEquals(2, p.payloadLength)
        assertEquals(0x65, p.payloadByte(0))
        assertNull(RtpPacket.parse(byteArrayOf(0x40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))) // version 1
        assertNull(RtpPacket.parse(byteArrayOf(0x80.toByte(), 96)))
    }

    @Test
    fun depacketizesMainProfileFixture() {
        val aus = units("main360-pcma.rtp")
        assertEquals(63, aus.size)
        assertEquals(3, aus.count { it.isKeyframe })
        assertEquals(3, aus.count { H264SliceType.I in it.sliceTypes })
        assertEquals(60, aus.count { H264SliceType.P in it.sliceTypes })
        assertEquals(0, aus.count { H264SliceType.B in it.sliceTypes })
        val keyTs = aus.filter { it.isKeyframe }.map { it.rtpTimestamp }
        assertEquals(listOf(180_000L, 180_000L), keyTs.zipWithNext { a, b -> b - a })
        // repeat-headers=1: SPS before every IDR, identical to the one in the SDP
        val sps = Base64.getDecoder().decode("Z01AFtoCgL/lwEQAAAMABAAAAwB4PFi6gA==")
        val inBand = aus.flatMap { it.spsUnits }
        assertEquals(3, inBand.size)
        inBand.forEach { assertArrayEquals(sps, it) }
    }

    @Test
    fun detectsBFrames() {
        val aus = units("high360-bframes.rtp")
        assertEquals(80, aus.size)
        assertEquals(4, aus.count { it.isKeyframe })
        assertEquals(46, aus.count { H264SliceType.B in it.sliceTypes })
        assertEquals(30, aus.count { H264SliceType.P in it.sliceTypes })
    }

    @Test
    fun audioFixtureIsPcma() {
        val audio = frames("main360-pcma.rtp").filter { it.channel == 2 }.map { RtpPacket.parse(it.data)!! }
        assertEquals(33, audio.size)
        assertTrue(audio.all { it.payloadType == 8 })
    }

    @Test
    fun unwrapsTimestamps() {
        val u = TimestampUnwrapper()
        assertEquals(0xFFFF_FF00L, u.unwrap(0xFFFF_FF00L))
        assertEquals(0x1_0000_0100L, u.unwrap(0x100L))
        assertEquals(0x1_0000_0200L, u.unwrap(0x200L))
    }

    @Test
    fun sliceTypeFromPrefix() {
        // first_mb_in_slice = 0 ("1"), slice_type = 7 ("0001000") → I
        val nal = byteArrayOf(0x65, 0b1000_1000.toByte(), 0)
        assertEquals(H264SliceType.I, H264SliceType.of(nal))
        // slice_type = 1 ("010") → B
        assertEquals(H264SliceType.B, H264SliceType.of(byteArrayOf(0x01, 0b1010_0000.toByte())))
        assertNull(H264SliceType.of(byteArrayOf(0x01)))
        assertNotNull(H264SliceType.of(byteArrayOf(0x41, 0b1100_0000.toByte())))
        assertFalse(H264SliceType.of(byteArrayOf(0x41, 0b1100_0000.toByte())) == H264SliceType.B)
    }
}
