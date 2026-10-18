package io.github.rhlshlt.tvrtsp.core.player

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat

/** What decoders advertise, looked up by name. Results are cached: MediaCodecList is slow to build. */
internal object DecoderFeatures {
    private val lowLatency = HashMap<String, Boolean?>()

    /**
     * Whether the decoder [codecName] advertises Android's low-latency mode (FEATURE_LowLatency) for
     * [mimeType], or for its first video type if it doesn't handle [mimeType]. Null when [codecName]
     * is null or no such decoder exists.
     */
    @Synchronized
    fun lowLatencySupported(codecName: String?, mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC): Boolean? {
        if (codecName == null) return null
        if (lowLatency.containsKey(codecName)) return lowLatency[codecName]
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { !it.isEncoder && it.name == codecName }
        val type = info?.supportedTypes?.let { types ->
            types.firstOrNull { it.equals(mimeType, ignoreCase = true) } ?: types.firstOrNull { it.startsWith("video/") }
        }
        val supported = if (info == null || type == null) {
            null
        } else {
            try {
                info.getCapabilitiesForType(type).isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)
            } catch (_: IllegalArgumentException) {
                false
            }
        }
        lowLatency[codecName] = supported
        return supported
    }
}
