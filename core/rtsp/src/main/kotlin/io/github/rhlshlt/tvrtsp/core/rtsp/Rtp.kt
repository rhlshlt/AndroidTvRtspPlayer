package io.github.rhlshlt.tvrtsp.core.rtsp

/** An RTP packet (RFC 3550) viewed in place: [payloadOffset]/[payloadLength] point into [buffer]. */
class RtpPacket(
    val payloadType: Int,
    val marker: Boolean,
    val sequenceNumber: Int,
    val timestamp: Long,
    val ssrc: Long,
    val buffer: ByteArray,
    val payloadOffset: Int,
    val payloadLength: Int,
) {
    fun payloadByte(index: Int): Int = buffer[payloadOffset + index].toInt() and 0xFF

    companion object {
        /** Parses [length] bytes at [offset]; returns null when they aren't a valid RTP v2 packet. */
        fun parse(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): RtpPacket? {
            if (length < 12) return null
            val b0 = buffer[offset].toInt() and 0xFF
            if (b0 shr 6 != 2) return null
            val padding = b0 and 0x20 != 0
            val extension = b0 and 0x10 != 0
            val csrcCount = b0 and 0x0F
            val b1 = buffer[offset + 1].toInt() and 0xFF
            val seq = ((buffer[offset + 2].toInt() and 0xFF) shl 8) or (buffer[offset + 3].toInt() and 0xFF)
            val ts = u32(buffer, offset + 4)
            val ssrc = u32(buffer, offset + 8)
            var headerLength = 12 + 4 * csrcCount
            if (length < headerLength) return null
            if (extension) {
                if (length < headerLength + 4) return null
                val words = ((buffer[offset + headerLength + 2].toInt() and 0xFF) shl 8) or
                    (buffer[offset + headerLength + 3].toInt() and 0xFF)
                headerLength += 4 + 4 * words
                if (length < headerLength) return null
            }
            var payloadLength = length - headerLength
            if (padding && payloadLength > 0) {
                val pad = buffer[offset + length - 1].toInt() and 0xFF
                if (pad > payloadLength) return null
                payloadLength -= pad
            }
            return RtpPacket(b1 and 0x7F, b1 and 0x80 != 0, seq, ts, ssrc, buffer, offset + headerLength, payloadLength)
        }

        private fun u32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
                ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
    }
}

/** A coded picture assembled from the RTP packets that share one timestamp. */
class H264AccessUnit(
    val rtpTimestamp: Long,
    val bytes: Int,
    val nalTypes: Set<Int>,
    val sliceTypes: Set<H264SliceType>,
    /** In-band SPS NAL units seen in this access unit (usually before an IDR). */
    val spsUnits: List<ByteArray>,
) {
    val isKeyframe: Boolean get() = H264.NAL_IDR in nalTypes
}

/**
 * Splits an H.264 RTP stream (RFC 6184: single NAL units, STAP-A, FU-A) into access units for
 * analysis. It keeps only what the analysis needs — NAL types, the first bytes of each slice and
 * in-band parameter sets — not the full picture data.
 */
class H264RtpAnalyzer {
    private var currentTimestamp: Long = -1
    private var bytes = 0
    private val nalTypes = mutableSetOf<Int>()
    private val sliceTypes = mutableSetOf<H264SliceType>()
    private val spsUnits = mutableListOf<ByteArray>()
    private var fuPrefix: java.io.ByteArrayOutputStream? = null
    private var fuType = -1
    private var fuBytes = 0
    private var fuFull: java.io.ByteArrayOutputStream? = null

    /** Payload structures seen that this analyzer doesn't unpack (STAP-B, MTAP, FU-B). */
    var unsupportedPackets = 0
        private set

    /**
     * Feeds one packet; returns the previous access unit when this packet starts a new one.
     * Access units are split on timestamp changes only, because some cameras set the RTP marker
     * bit on every NAL unit rather than on the last packet of a picture.
     */
    fun push(packet: RtpPacket): H264AccessUnit? {
        var finished: H264AccessUnit? = null
        if (currentTimestamp >= 0 && packet.timestamp != currentTimestamp) finished = flush()
        currentTimestamp = packet.timestamp
        if (packet.payloadLength < 1) return finished
        bytes += packet.payloadLength
        when (val type = packet.payloadByte(0) and 0x1F) {
            in 1..23 -> onNal(packet.buffer, packet.payloadOffset, packet.payloadLength)
            H264.RTP_STAP_A -> {
                var p = 1
                while (p + 2 <= packet.payloadLength) {
                    val size = (packet.payloadByte(p) shl 8) or packet.payloadByte(p + 1)
                    p += 2
                    if (size == 0 || p + size > packet.payloadLength) break
                    onNal(packet.buffer, packet.payloadOffset + p, size)
                    p += size
                }
            }
            H264.RTP_FU_A -> onFragment(packet)
            else -> if (type != 0) unsupportedPackets++
        }
        return finished
    }

    /** Returns the access unit in progress, if any, and resets. */
    fun flush(): H264AccessUnit? {
        if (currentTimestamp < 0 || nalTypes.isEmpty()) {
            reset()
            return null
        }
        val unit = H264AccessUnit(currentTimestamp, bytes, nalTypes.toSet(), sliceTypes.toSet(), spsUnits.toList())
        reset()
        return unit
    }

    private fun reset() {
        currentTimestamp = -1
        bytes = 0
        nalTypes.clear()
        sliceTypes.clear()
        spsUnits.clear()
        fuPrefix = null
        fuFull = null
        fuType = -1
    }

    private fun onNal(buffer: ByteArray, offset: Int, length: Int) {
        val type = buffer[offset].toInt() and 0x1F
        nalTypes += type
        when (type) {
            H264.NAL_SLICE, H264.NAL_IDR -> {
                val prefix = buffer.copyOfRange(offset, offset + minOf(length, PREFIX_BYTES))
                H264SliceType.of(prefix)?.let { sliceTypes += it }
            }
            H264.NAL_SPS -> spsUnits += buffer.copyOfRange(offset, offset + length)
        }
    }

    private fun onFragment(packet: RtpPacket) {
        if (packet.payloadLength < 2) return
        val indicator = packet.payloadByte(0)
        val header = packet.payloadByte(1)
        val start = header and 0x80 != 0
        val end = header and 0x40 != 0
        val type = header and 0x1F
        val dataOffset = packet.payloadOffset + 2
        val dataLength = packet.payloadLength - 2
        if (start) {
            fuType = type
            fuBytes = 0
            nalTypes += type
            val nalHeader = ((indicator and 0xE0) or type).toByte()
            fuPrefix = java.io.ByteArrayOutputStream(PREFIX_BYTES).apply { write(nalHeader.toInt()) }
            fuFull = if (type == H264.NAL_SPS) java.io.ByteArrayOutputStream().apply { write(nalHeader.toInt()) } else null
        }
        val prefix = fuPrefix ?: return // middle fragment without a start: lost start, skip
        if (prefix.size() < PREFIX_BYTES) {
            prefix.write(packet.buffer, dataOffset, minOf(dataLength, PREFIX_BYTES - prefix.size()))
        }
        fuFull?.write(packet.buffer, dataOffset, dataLength)
        fuBytes += dataLength
        if (end) {
            if (fuType == H264.NAL_SLICE || fuType == H264.NAL_IDR) {
                H264SliceType.of(prefix.toByteArray())?.let { sliceTypes += it }
            }
            fuFull?.let { spsUnits += it.toByteArray() }
            fuPrefix = null
            fuFull = null
        }
    }

    private companion object {
        const val PREFIX_BYTES = 32
    }
}
