package io.github.rhlshlt.tvrtsp.core.rtsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Expected values come from an independent Python parser (for the C320WS parameter sets the user
 * captured) and from FFmpeg's trace_headers (for the x264-encoded fixtures).
 */
class H264Test {

    private fun b64(s: String) = Base64.getDecoder().decode(s)
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun c320wsStream1Sps() {
        val sps = H264Sps.parse(b64("Z00AMoqKUBQAWtNkAAAPoAAB1MAQ"))
        assertEquals(77, sps.profileIdc)
        assertEquals("Main", sps.profileName)
        assertEquals(0, sps.constraintFlags)
        assertEquals(50, sps.levelIdc)
        assertEquals("5", sps.levelName)
        assertEquals(13, sps.log2MaxFrameNum)
        assertEquals(0, sps.picOrderCntType)
        assertEquals(13, sps.log2MaxPicOrderCntLsb)
        assertEquals(1, sps.maxNumRefFrames)
        assertTrue(sps.gapsInFrameNumAllowed)
        assertEquals(2560, sps.width)
        assertEquals(1440, sps.height)
        assertEquals(2560, sps.codedWidth)
        assertEquals(1440, sps.codedHeight)
        assertTrue(sps.frameMbsOnly)
        val vui = sps.vui!!
        assertEquals(5, vui.videoFormat)
        assertEquals(true, vui.fullRange)
        assertNull(vui.colourPrimaries)
        assertEquals(1000L, vui.numUnitsInTick)
        assertEquals(30000L, vui.timeScale)
        assertEquals(false, vui.fixedFrameRate)
        assertFalse(vui.nalHrdPresent || vui.vclHrdPresent)
        assertEquals(15.0, sps.frameRate!!, 1e-9)
        assertFalse(sps.hasReorderLimit)
        assertEquals(14400, sps.mbsPerFrame)
        assertEquals(7, sps.maxDpbFrames)
        assertEquals(7, sps.maxNumReorderFrames)
        assertEquals(7, sps.maxDecFrameBuffering)
    }

    @Test
    fun c320wsStream2Sps() {
        val sps = H264Sps.parse(b64("Z00AFoqKUFAX/LNkAAAPoAAB1MAQ"))
        assertEquals(22, sps.levelIdc)
        assertEquals("2.2", sps.levelName)
        assertEquals(640, sps.codedWidth)
        assertEquals(368, sps.codedHeight)
        assertEquals(4, sps.cropBottom)
        assertEquals(640, sps.width)
        assertEquals(360, sps.height)
        assertEquals(15.0, sps.frameRate!!, 1e-9)
        assertEquals(920, sps.mbsPerFrame)
        assertEquals(8, sps.maxDpbFrames)
        assertEquals(8, sps.maxNumReorderFrames)
        assertTrue(sps.fullRange)
    }

    @Test
    fun c320wsPps() {
        val pps = H264Pps.parse(b64("aO48gA=="))
        assertEquals(0, pps.ppsId)
        assertEquals(0, pps.spsId)
        assertTrue(pps.cabac)
        assertEquals(1, pps.numSliceGroups)
        assertEquals(1, pps.numRefIdxL0DefaultActive)
        assertEquals(1, pps.numRefIdxL1DefaultActive)
        assertFalse(pps.weightedPred)
        assertEquals(0, pps.weightedBipredIdc)
        assertEquals(26, pps.picInitQp)
        assertTrue(pps.deblockingFilterControlPresent)
    }

    @Test
    fun x264MainProfileSpsMatchesTraceHeaders() {
        // libx264 Main, Level 2.2, 640x360, 15 fps, no B-frames (fixture main360-pcma.rtp)
        val sps = H264Sps.parse(hex("674d4016da0280bfe5c044000003000400000300783c58ba80"))
        assertEquals(77, sps.profileIdc)
        assertEquals(0x40, sps.constraintFlags)
        assertEquals(22, sps.levelIdc)
        assertEquals(4, sps.log2MaxFrameNum)
        assertEquals(2, sps.picOrderCntType)
        assertEquals(1, sps.maxNumRefFrames)
        assertFalse(sps.gapsInFrameNumAllowed)
        assertEquals(640, sps.width)
        assertEquals(360, sps.height)
        assertEquals(15.0, sps.frameRate!!, 1e-9)
        assertTrue(sps.hasReorderLimit)
        assertEquals(0, sps.maxNumReorderFrames)
        assertEquals(1, sps.maxDecFrameBuffering)
        assertFalse(sps.fullRange)
    }

    @Test
    fun x264HighProfileWithBFramesMatchesTraceHeaders() {
        val sps = H264Sps.parse(hex("6764001eacd940a02ff97011000003000100000300320f162d96"))
        assertEquals(100, sps.profileIdc)
        assertEquals("High", sps.profileName)
        assertEquals(30, sps.levelIdc)
        assertEquals(1, sps.chromaFormatIdc)
        assertFalse(sps.scalingMatrixPresent)
        assertEquals(0, sps.picOrderCntType)
        assertEquals(6, sps.log2MaxPicOrderCntLsb)
        assertEquals(4, sps.maxNumRefFrames)
        assertEquals(640, sps.width)
        assertEquals(360, sps.height)
        assertEquals(25.0, sps.frameRate!!, 1e-9)
        assertEquals(2, sps.maxNumReorderFrames)
        assertEquals(4, sps.maxDecFrameBuffering)
    }

    @Test
    fun spsWithScalingListsMatchesTraceHeaders() {
        // x264 SPS with 4x4 and 8x8 scaling lists inserted; FFmpeg decodes it as 1280x720 High, Level 3.1.
        val sps = H264Sps.parse(hex(
            "6764001fad9470e0807c704040642020504c40a150884a20c620a21015883188288405620c620a21015883188288405620c620a21015883188288405620c620a2101588318828846ca02802dd80880000003008000001e078c18cb",
        ))
        assertTrue(sps.scalingMatrixPresent)
        assertEquals(31, sps.levelIdc)
        assertEquals(1280, sps.width)
        assertEquals(720, sps.height)
        assertEquals(30.0, sps.frameRate!!, 1e-9)
        assertEquals(4, sps.maxNumRefFrames)
        assertEquals(2, sps.maxNumReorderFrames)
    }

    @Test
    fun levelTable() {
        assertEquals("1b", H264Level.of(11, 77, constraintSet3 = true)!!.name)
        assertEquals("1.1", H264Level.of(11, 100, constraintSet3 = true)!!.name)
        assertEquals("5", H264Level.minimumFor(14400, 14400 * 15.0)!!.name)
        assertEquals("2.2", H264Level.minimumFor(920, 920 * 15.0)!!.name)
        assertEquals("4", H264Level.minimumFor(8160, 8160 * 30.0)!!.name)
        assertEquals("4.2", H264Level.minimumFor(8160, 8160 * 60.0)!!.name)
        assertNull(H264Level.of(99))
    }

    @Test
    fun removesEmulationPrevention() {
        val nal = byteArrayOf(0x67, 0, 0, 3, 1, 0, 0, 3, 0, 0x55)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0, 0, 0, 0x55), H264.unescapeRbsp(nal))
    }

    @Test
    fun splitsAnnexB() {
        val stream = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 5)
        val nals = H264.splitAnnexB(stream)
        assertEquals(3, nals.size)
        assertArrayEquals(byteArrayOf(0x67, 1, 2), nals[0])
        assertArrayEquals(byteArrayOf(0x68, 3), nals[1])
        assertArrayEquals(byteArrayOf(0x65, 4, 5), nals[2])
    }

    @Test
    fun readsExpGolomb() {
        // bits: 1 | 010 | 011 | 00100 | 00101 → ue 0,1,2,3 then se(4) = -2
        val r = BitReader(byteArrayOf(0b1010_0110.toByte(), 0b0100_0010.toByte(), 0b1000_0000.toByte()))
        assertEquals(0, r.readUe())
        assertEquals(1, r.readUe())
        assertEquals(2, r.readUe())
        assertEquals(3, r.readUe())
        assertEquals(-2, r.readSe())
    }

    @Test(expected = BitstreamException::class)
    fun rejectsTruncatedSps() {
        H264Sps.parse(byteArrayOf(0x67, 0x4D, 0x00))
    }
}
