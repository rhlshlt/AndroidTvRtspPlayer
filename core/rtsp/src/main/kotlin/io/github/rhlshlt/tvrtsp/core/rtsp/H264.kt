package io.github.rhlshlt.tvrtsp.core.rtsp

/** H.264 (ITU-T H.264 / ISO 14496-10) helpers: NAL units, RBSP and Exp-Golomb reading. */
object H264 {
    const val NAL_SLICE = 1
    const val NAL_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    /** RTP payload structures from RFC 6184. */
    const val RTP_STAP_A = 24
    const val RTP_STAP_B = 25
    const val RTP_MTAP16 = 26
    const val RTP_MTAP24 = 27
    const val RTP_FU_A = 28
    const val RTP_FU_B = 29

    fun nalType(firstByte: Byte): Int = firstByte.toInt() and 0x1F

    fun nalTypeName(type: Int): String = when (type) {
        NAL_SLICE -> "non-IDR slice"
        2, 3, 4 -> "slice partition"
        NAL_IDR -> "IDR slice"
        NAL_SEI -> "SEI"
        NAL_SPS -> "SPS"
        NAL_PPS -> "PPS"
        NAL_AUD -> "access unit delimiter"
        10 -> "end of sequence"
        11 -> "end of stream"
        12 -> "filler"
        else -> "type $type"
    }

    /**
     * Removes emulation-prevention bytes (`00 00 03` → `00 00`) from a NAL unit, skipping the
     * one-byte NAL header, and returns the RBSP.
     */
    fun unescapeRbsp(nal: ByteArray, from: Int = 1, to: Int = nal.size): ByteArray {
        val out = ByteArray(maxOf(0, to - from))
        var n = 0
        var zeros = 0
        for (i in from until to) {
            val b = nal[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out[n++] = b.toByte()
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.copyOf(n)
    }

    /** Splits an Annex B byte stream (start codes `00 00 01` or `00 00 00 01`) into NAL units. */
    fun splitAnnexB(stream: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>() // index of first NAL byte after each start code
        var i = 0
        while (i + 2 < stream.size) {
            if (stream[i].toInt() == 0 && stream[i + 1].toInt() == 0 && stream[i + 2].toInt() == 1) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        val nals = mutableListOf<ByteArray>()
        for ((k, start) in starts.withIndex()) {
            var end = if (k + 1 < starts.size) starts[k + 1] - 3 else stream.size
            while (end > start && stream[end - 1].toInt() == 0) end-- // trailing zero / 4-byte start code
            if (end > start) nals += stream.copyOfRange(start, end)
        }
        return nals
    }
}

/** Reads bits MSB-first from an RBSP, with Exp-Golomb codes (H.264 clause 9.1). */
class BitReader(private val data: ByteArray) {
    private var bitPos = 0

    val bitsLeft: Int get() = data.size * 8 - bitPos
    val position: Int get() = bitPos

    fun readBit(): Int {
        if (bitPos >= data.size * 8) throw BitstreamException("Read past the end of the data")
        val v = (data[bitPos ushr 3].toInt() shr (7 - (bitPos and 7))) and 1
        bitPos++
        return v
    }

    fun readFlag(): Boolean = readBit() == 1

    /** Reads up to 31 bits. */
    fun readBits(n: Int): Int {
        require(n in 0..31)
        var v = 0
        repeat(n) { v = (v shl 1) or readBit() }
        return v
    }

    fun readU32(): Long = (readBits(16).toLong() shl 16) or readBits(16).toLong()

    fun skipBits(n: Int) {
        if (n > bitsLeft) throw BitstreamException("Skip past the end of the data")
        bitPos += n
    }

    /** ue(v). */
    fun readUe(): Int {
        var leadingZeros = 0
        while (readBit() == 0) {
            leadingZeros++
            if (leadingZeros > 31) throw BitstreamException("Exp-Golomb code too long")
        }
        if (leadingZeros == 0) return 0
        val value = (1L shl leadingZeros) - 1 + readBits(leadingZeros).toLong()
        if (value > Int.MAX_VALUE) throw BitstreamException("Exp-Golomb value too large")
        return value.toInt()
    }

    /** se(v). */
    fun readSe(): Int {
        val k = readUe()
        return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
    }

    /** more_rbsp_data(): true while data remains before the rbsp_stop_one_bit. */
    fun moreRbspData(): Boolean {
        if (bitsLeft <= 0) return false
        var lastOne = -1
        for (i in data.size - 1 downTo 0) {
            val b = data[i].toInt() and 0xFF
            if (b != 0) {
                lastOne = i * 8 + (7 - Integer.numberOfTrailingZeros(b))
                break
            }
        }
        return lastOne > bitPos
    }
}

class BitstreamException(message: String) : IllegalArgumentException(message)

/** One row of H.264 Table A-1. */
data class H264Level(
    val levelIdc: Int,
    val name: String,
    /** Max macroblock processing rate (MB/s). */
    val maxMbps: Long,
    /** Max frame size (macroblocks). */
    val maxFs: Int,
    /** Max decoded picture buffer size (macroblocks). */
    val maxDpbMbs: Int,
) {
    companion object {
        private val TABLE = listOf(
            H264Level(10, "1", 1_485, 99, 396),
            H264Level(9, "1b", 1_485, 99, 396),
            H264Level(11, "1.1", 3_000, 396, 900),
            H264Level(12, "1.2", 6_000, 396, 2_376),
            H264Level(13, "1.3", 11_880, 396, 2_376),
            H264Level(20, "2", 11_880, 396, 2_376),
            H264Level(21, "2.1", 19_800, 792, 4_752),
            H264Level(22, "2.2", 20_250, 1_620, 8_100),
            H264Level(30, "3", 40_500, 1_620, 8_100),
            H264Level(31, "3.1", 108_000, 3_600, 18_000),
            H264Level(32, "3.2", 216_000, 5_120, 20_480),
            H264Level(40, "4", 245_760, 8_192, 32_768),
            H264Level(41, "4.1", 245_760, 8_192, 32_768),
            H264Level(42, "4.2", 522_240, 8_704, 34_816),
            H264Level(50, "5", 589_824, 22_080, 110_400),
            H264Level(51, "5.1", 983_040, 36_864, 184_320),
            H264Level(52, "5.2", 2_073_600, 36_864, 184_320),
            H264Level(60, "6", 4_177_920, 139_264, 696_320),
            H264Level(61, "6.1", 8_355_840, 139_264, 696_320),
            H264Level(62, "6.2", 16_711_680, 139_264, 696_320),
        )

        /**
         * Looks up a level. level_idc 11 with constraint_set3_flag in Baseline, Main or Extended
         * profile means Level 1b; so does level_idc 9.
         */
        fun of(levelIdc: Int, profileIdc: Int = 0, constraintSet3: Boolean = false): H264Level? {
            if (levelIdc == 11 && constraintSet3 && profileIdc in setOf(66, 77, 88)) return TABLE.first { it.levelIdc == 9 }
            return TABLE.firstOrNull { it.levelIdc == levelIdc }
        }

        /** The smallest level whose limits fit [mbsPerFrame] at [mbsPerSecond]. */
        fun minimumFor(mbsPerFrame: Int, mbsPerSecond: Double): H264Level? =
            TABLE.filter { it.levelIdc != 9 }.firstOrNull { it.maxFs >= mbsPerFrame && it.maxMbps >= mbsPerSecond }
    }
}

/** Video usability information (Annex E) fields this project uses. */
data class H264Vui(
    val aspectRatioIdc: Int?,
    val sarWidth: Int?,
    val sarHeight: Int?,
    val videoFormat: Int?,
    val fullRange: Boolean?,
    val colourPrimaries: Int?,
    val transferCharacteristics: Int?,
    val matrixCoefficients: Int?,
    val timingInfoPresent: Boolean,
    val numUnitsInTick: Long?,
    val timeScale: Long?,
    val fixedFrameRate: Boolean?,
    val nalHrdPresent: Boolean,
    val vclHrdPresent: Boolean,
    val picStructPresent: Boolean,
    val bitstreamRestrictionPresent: Boolean,
    val maxNumReorderFrames: Int?,
    val maxDecFrameBuffering: Int?,
)

/** A parsed sequence parameter set with the values derived from it. */
data class H264Sps(
    val profileIdc: Int,
    /** constraint_set0..5 flags and reserved bits, as the second SPS byte. */
    val constraintFlags: Int,
    val levelIdc: Int,
    val spsId: Int,
    val chromaFormatIdc: Int,
    val separateColourPlane: Boolean,
    val bitDepthLuma: Int,
    val bitDepthChroma: Int,
    val scalingMatrixPresent: Boolean,
    val log2MaxFrameNum: Int,
    val picOrderCntType: Int,
    val log2MaxPicOrderCntLsb: Int?,
    val maxNumRefFrames: Int,
    val gapsInFrameNumAllowed: Boolean,
    val picWidthInMbs: Int,
    val picHeightInMapUnits: Int,
    val frameMbsOnly: Boolean,
    val mbAdaptiveFrameField: Boolean,
    val direct8x8Inference: Boolean,
    val cropLeft: Int,
    val cropRight: Int,
    val cropTop: Int,
    val cropBottom: Int,
    val vui: H264Vui?,
) {
    val constraintSet3: Boolean get() = constraintFlags and 0x10 != 0
    val frameHeightInMbs: Int get() = (if (frameMbsOnly) 1 else 2) * picHeightInMapUnits
    val mbsPerFrame: Int get() = picWidthInMbs * frameHeightInMbs
    val codedWidth: Int get() = picWidthInMbs * 16
    val codedHeight: Int get() = frameHeightInMbs * 16

    private val chromaArrayType: Int get() = if (separateColourPlane) 0 else chromaFormatIdc
    private val cropUnitX: Int get() = if (chromaArrayType == 0) 1 else if (chromaFormatIdc == 3) 1 else 2
    private val cropUnitY: Int
        get() {
            val subHeightC = if (chromaArrayType == 0) 1 else if (chromaFormatIdc == 1) 2 else 1
            return subHeightC * (if (frameMbsOnly) 1 else 2)
        }

    /** Displayed width after frame cropping. */
    val width: Int get() = codedWidth - cropUnitX * (cropLeft + cropRight)

    /** Displayed height after frame cropping. */
    val height: Int get() = codedHeight - cropUnitY * (cropTop + cropBottom)

    val level: H264Level? get() = H264Level.of(levelIdc, profileIdc, constraintSet3)

    /** Frames per second from VUI timing (time_scale / (2 × num_units_in_tick)), if present. */
    val frameRate: Double?
        get() {
            val v = vui ?: return null
            val tick = v.numUnitsInTick ?: return null
            val scale = v.timeScale ?: return null
            if (!v.timingInfoPresent || tick == 0L) return null
            return scale.toDouble() / (2.0 * tick)
        }

    val fullRange: Boolean get() = vui?.fullRange == true

    /** MaxDpbFrames = Min(MaxDpbMbs / (PicWidthInMbs × FrameHeightInMbs), 16) (clause A.3.1). */
    val maxDpbFrames: Int?
        get() {
            val l = level ?: return null
            return minOf(l.maxDpbMbs / mbsPerFrame, 16)
        }

    /** True when the SPS states reordering limits (bitstream_restriction_flag = 1). */
    val hasReorderLimit: Boolean get() = vui?.bitstreamRestrictionPresent == true

    /**
     * max_num_reorder_frames: as written, or inferred per clause E.2.1 when absent: 0 for the
     * intra profiles (44, 86, 100, 110, 122, 244) with constraint_set3_flag, otherwise MaxDpbFrames.
     * A decoder that trusts this value may hold that many frames before output.
     */
    val maxNumReorderFrames: Int?
        get() {
            vui?.maxNumReorderFrames?.let { return it }
            if (profileIdc in setOf(44, 86, 100, 110, 122, 244) && constraintSet3) return 0
            return maxDpbFrames
        }

    /** max_dec_frame_buffering, as written or inferred the same way. */
    val maxDecFrameBuffering: Int?
        get() {
            vui?.maxDecFrameBuffering?.let { return it }
            if (profileIdc in setOf(44, 86, 100, 110, 122, 244) && constraintSet3) return 0
            return maxDpbFrames
        }

    val profileName: String
        get() = when (profileIdc) {
            66 -> if (constraintFlags and 0x40 != 0) "Constrained Baseline" else "Baseline"
            77 -> "Main"
            88 -> "Extended"
            100 -> if (constraintFlags and 0x0C == 0x0C) "Constrained High" else "High"
            110 -> "High 10"
            122 -> "High 4:2:2"
            244 -> "High 4:4:4 Predictive"
            44 -> "CAVLC 4:4:4 Intra"
            else -> "profile_idc $profileIdc"
        }

    val levelName: String get() = level?.name ?: "level_idc $levelIdc"

    companion object {
        private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

        /** Parses an SPS NAL unit (starting with its NAL header byte, no start code). */
        fun parse(nal: ByteArray): H264Sps {
            if (nal.isEmpty() || H264.nalType(nal[0]) != H264.NAL_SPS) throw BitstreamException("Not an SPS NAL unit")
            val r = BitReader(H264.unescapeRbsp(nal))
            val profileIdc = r.readBits(8)
            val constraintFlags = r.readBits(8)
            val levelIdc = r.readBits(8)
            val spsId = r.readUe()
            var chromaFormatIdc = 1
            var separateColourPlane = false
            var bitDepthLuma = 8
            var bitDepthChroma = 8
            var scalingMatrixPresent = false
            if (profileIdc in HIGH_PROFILES) {
                chromaFormatIdc = r.readUe()
                if (chromaFormatIdc == 3) separateColourPlane = r.readFlag()
                bitDepthLuma = r.readUe() + 8
                bitDepthChroma = r.readUe() + 8
                r.readFlag() // qpprime_y_zero_transform_bypass_flag
                scalingMatrixPresent = r.readFlag()
                if (scalingMatrixPresent) {
                    val lists = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until lists) {
                        if (r.readFlag()) skipScalingList(r, if (i < 6) 16 else 64)
                    }
                }
            }
            val log2MaxFrameNum = r.readUe() + 4
            val pocType = r.readUe()
            var log2MaxPocLsb: Int? = null
            when (pocType) {
                0 -> log2MaxPocLsb = r.readUe() + 4
                1 -> {
                    r.readFlag() // delta_pic_order_always_zero_flag
                    r.readSe() // offset_for_non_ref_pic
                    r.readSe() // offset_for_top_to_bottom_field
                    val cycle = r.readUe()
                    if (cycle > 255) throw BitstreamException("num_ref_frames_in_pic_order_cnt_cycle too large")
                    repeat(cycle) { r.readSe() }
                }
            }
            val maxNumRefFrames = r.readUe()
            val gaps = r.readFlag()
            val widthMbs = r.readUe() + 1
            val heightMapUnits = r.readUe() + 1
            val frameMbsOnly = r.readFlag()
            val mbaff = if (!frameMbsOnly) r.readFlag() else false
            val direct8x8 = r.readFlag()
            var cl = 0
            var cr = 0
            var ct = 0
            var cb = 0
            if (r.readFlag()) {
                cl = r.readUe(); cr = r.readUe(); ct = r.readUe(); cb = r.readUe()
            }
            val vui = if (r.readFlag()) parseVui(r) else null
            return H264Sps(
                profileIdc, constraintFlags, levelIdc, spsId, chromaFormatIdc, separateColourPlane,
                bitDepthLuma, bitDepthChroma, scalingMatrixPresent, log2MaxFrameNum, pocType, log2MaxPocLsb,
                maxNumRefFrames, gaps, widthMbs, heightMapUnits, frameMbsOnly, mbaff, direct8x8,
                cl, cr, ct, cb, vui,
            )
        }

        private fun skipScalingList(r: BitReader, size: Int) {
            var last = 8
            var next = 8
            for (j in 0 until size) {
                if (next != 0) {
                    val delta = r.readSe()
                    next = (last + delta + 256) % 256
                }
                last = if (next == 0) last else next
            }
        }

        private fun parseVui(r: BitReader): H264Vui {
            var aspectIdc: Int? = null
            var sarW: Int? = null
            var sarH: Int? = null
            if (r.readFlag()) {
                aspectIdc = r.readBits(8)
                if (aspectIdc == 255) {
                    sarW = r.readBits(16); sarH = r.readBits(16)
                }
            }
            if (r.readFlag()) r.readFlag() // overscan_info_present → overscan_appropriate
            var videoFormat: Int? = null
            var fullRange: Boolean? = null
            var primaries: Int? = null
            var transfer: Int? = null
            var matrix: Int? = null
            if (r.readFlag()) {
                videoFormat = r.readBits(3)
                fullRange = r.readFlag()
                if (r.readFlag()) {
                    primaries = r.readBits(8); transfer = r.readBits(8); matrix = r.readBits(8)
                }
            }
            if (r.readFlag()) {
                r.readUe(); r.readUe() // chroma_sample_loc_type_top/bottom_field
            }
            val timing = r.readFlag()
            var tick: Long? = null
            var scale: Long? = null
            var fixed: Boolean? = null
            if (timing) {
                tick = r.readU32(); scale = r.readU32(); fixed = r.readFlag()
            }
            val nalHrd = r.readFlag()
            if (nalHrd) skipHrd(r)
            val vclHrd = r.readFlag()
            if (vclHrd) skipHrd(r)
            if (nalHrd || vclHrd) r.readFlag() // low_delay_hrd_flag
            val picStruct = r.readFlag()
            val restriction = r.readFlag()
            var reorder: Int? = null
            var decBuffering: Int? = null
            if (restriction) {
                r.readFlag() // motion_vectors_over_pic_boundaries_flag
                r.readUe(); r.readUe(); r.readUe(); r.readUe() // max_bytes/bits, log2_max_mv_length h/v
                reorder = r.readUe()
                decBuffering = r.readUe()
            }
            return H264Vui(aspectIdc, sarW, sarH, videoFormat, fullRange, primaries, transfer, matrix,
                timing, tick, scale, fixed, nalHrd, vclHrd, picStruct, restriction, reorder, decBuffering)
        }

        private fun skipHrd(r: BitReader) {
            val cpbCnt = r.readUe() + 1
            if (cpbCnt > 32) throw BitstreamException("cpb_cnt_minus1 too large")
            r.readBits(4); r.readBits(4) // bit_rate_scale, cpb_size_scale
            repeat(cpbCnt) {
                r.readUe(); r.readUe(); r.readFlag() // bit_rate_value_minus1, cpb_size_value_minus1, cbr_flag
            }
            r.readBits(5); r.readBits(5); r.readBits(5); r.readBits(5)
        }
    }
}

/** The first fields of a picture parameter set. */
data class H264Pps(
    val ppsId: Int,
    val spsId: Int,
    val cabac: Boolean,
    val numSliceGroups: Int,
    val numRefIdxL0DefaultActive: Int,
    val numRefIdxL1DefaultActive: Int,
    val weightedPred: Boolean,
    val weightedBipredIdc: Int,
    val picInitQp: Int,
    val deblockingFilterControlPresent: Boolean,
) {
    companion object {
        fun parse(nal: ByteArray): H264Pps {
            if (nal.isEmpty() || H264.nalType(nal[0]) != H264.NAL_PPS) throw BitstreamException("Not a PPS NAL unit")
            val r = BitReader(H264.unescapeRbsp(nal))
            val ppsId = r.readUe()
            val spsId = r.readUe()
            val cabac = r.readFlag()
            r.readFlag() // bottom_field_pic_order_in_frame_present_flag
            val sliceGroups = r.readUe() + 1
            if (sliceGroups > 1) {
                // Slice groups (FMO) only exist in Baseline/Extended; stop with defaults for the rest.
                return H264Pps(ppsId, spsId, cabac, sliceGroups, 0, 0, false, 0, 26, false)
            }
            val l0 = r.readUe() + 1
            val l1 = r.readUe() + 1
            val weighted = r.readFlag()
            val bipred = r.readBits(2)
            val qp = 26 + r.readSe()
            r.readSe() // pic_init_qs_minus26
            r.readSe() // chroma_qp_index_offset
            val deblocking = r.readFlag()
            return H264Pps(ppsId, spsId, cabac, sliceGroups, l0, l1, weighted, bipred, qp, deblocking)
        }
    }
}

/** slice_type of a coded slice (clause 7.4.3), from the first bytes of a slice NAL unit. */
enum class H264SliceType { P, B, I, SP, SI;

    companion object {
        /** Reads first_mb_in_slice and slice_type; needs only the first few bytes of the NAL unit. */
        fun of(nalPrefix: ByteArray, length: Int = nalPrefix.size): H264SliceType? = try {
            val r = BitReader(H264.unescapeRbsp(nalPrefix, 1, minOf(length, nalPrefix.size)))
            r.readUe() // first_mb_in_slice
            when (r.readUe() % 5) {
                0 -> P
                1 -> B
                2 -> I
                3 -> SP
                else -> SI
            }
        } catch (_: BitstreamException) {
            null
        }
    }
}
