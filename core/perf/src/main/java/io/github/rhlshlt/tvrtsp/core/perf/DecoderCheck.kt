package io.github.rhlshlt.tvrtsp.core.perf

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import io.github.rhlshlt.tvrtsp.core.rtsp.CameraReport
import io.github.rhlshlt.tvrtsp.core.rtsp.H264Sps

/** Asks this TV's codec list whether it can decode the stream a camera check described. */
object DecoderCheck {

    /** One line for the report: which decoder MediaCodecList picks for the stream, or why none. */
    fun verdict(report: CameraReport): String {
        val sps = report.sps ?: return "Decoder check: skipped (no SPS in the SDP)"
        if (report.video?.encoding != "H264") return "Decoder check: skipped (not H.264)"
        val name = findAvcDecoder(sps)
        val what = "H.264 ${sps.profileName} Level ${sps.levelName} ${sps.width}×${sps.height}" +
            (sps.frameRate?.let { " at ${String.format(java.util.Locale.ROOT, "%.0f", it)} fps" } ?: "")
        return if (name != null) "Decoder check: this TV decodes $what with $name"
        else "Decoder check: no decoder on this TV accepts $what"
    }

    fun findAvcDecoder(sps: H264Sps): String? {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, sps.width, sps.height)
        avcProfile(sps)?.let { format.setInteger(MediaFormat.KEY_PROFILE, it) }
        avcLevel(sps.levelIdc, sps.constraintSet3)?.let { format.setInteger(MediaFormat.KEY_LEVEL, it) }
        sps.frameRate?.let { format.setFloat(MediaFormat.KEY_FRAME_RATE, it.toFloat()) }
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
    }

    private fun avcProfile(sps: H264Sps): Int? = when (sps.profileIdc) {
        66 -> if (sps.constraintFlags and 0x40 != 0) CodecProfileLevel.AVCProfileConstrainedBaseline else CodecProfileLevel.AVCProfileBaseline
        77 -> CodecProfileLevel.AVCProfileMain
        88 -> CodecProfileLevel.AVCProfileExtended
        100 -> CodecProfileLevel.AVCProfileHigh
        110 -> CodecProfileLevel.AVCProfileHigh10
        122 -> CodecProfileLevel.AVCProfileHigh422
        244 -> CodecProfileLevel.AVCProfileHigh444
        else -> null
    }

    private fun avcLevel(levelIdc: Int, constraintSet3: Boolean): Int? = when (levelIdc) {
        9 -> CodecProfileLevel.AVCLevel1b
        10 -> CodecProfileLevel.AVCLevel1
        11 -> if (constraintSet3) CodecProfileLevel.AVCLevel1b else CodecProfileLevel.AVCLevel11
        12 -> CodecProfileLevel.AVCLevel12
        13 -> CodecProfileLevel.AVCLevel13
        20 -> CodecProfileLevel.AVCLevel2
        21 -> CodecProfileLevel.AVCLevel21
        22 -> CodecProfileLevel.AVCLevel22
        30 -> CodecProfileLevel.AVCLevel3
        31 -> CodecProfileLevel.AVCLevel31
        32 -> CodecProfileLevel.AVCLevel32
        40 -> CodecProfileLevel.AVCLevel4
        41 -> CodecProfileLevel.AVCLevel41
        42 -> CodecProfileLevel.AVCLevel42
        50 -> CodecProfileLevel.AVCLevel5
        51 -> CodecProfileLevel.AVCLevel51
        52 -> CodecProfileLevel.AVCLevel52
        60 -> CodecProfileLevel.AVCLevel6
        61 -> CodecProfileLevel.AVCLevel61
        62 -> CodecProfileLevel.AVCLevel62
        else -> null
    }
}
