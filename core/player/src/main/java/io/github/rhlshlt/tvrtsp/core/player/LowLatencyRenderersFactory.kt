package io.github.rhlshlt.tvrtsp.core.player

import android.content.Context
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

/**
 * Asks video decoders for Android's low-latency mode (MediaFormat.KEY_LOW_LATENCY, API 30+) when
 * they advertise FEATURE_LowLatency. The C320WS SPS sets no reorder limit, so without this some
 * decoders hold several frames before showing them.
 *
 * Media3 has no switch for this, so the codec adapter factory is wrapped: the key is added to the
 * MediaFormat just before the codec is configured.
 *
 * @param onVideoCodec called on the playback thread with the codec name and whether the key was set
 */
@OptIn(UnstableApi::class)
class LowLatencyRenderersFactory(
    context: Context,
    private val requestLowLatency: Boolean,
    private val onVideoCodec: (codecName: String, lowLatency: Boolean) -> Unit,
) : DefaultRenderersFactory(context) {

    override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory {
        val delegate = super.getCodecAdapterFactory()
        return MediaCodecAdapter.Factory { configuration ->
            if (MimeTypes.isVideo(configuration.format.sampleMimeType)) {
                val supported = configuration.codecInfo.capabilities
                    ?.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency) == true
                val apply = requestLowLatency && supported
                if (apply) configuration.mediaFormat.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                onVideoCodec(configuration.codecInfo.name, apply)
            }
            delegate.createAdapter(configuration)
        }
    }
}
