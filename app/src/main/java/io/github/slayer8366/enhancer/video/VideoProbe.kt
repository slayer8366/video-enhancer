package io.github.slayer8366.enhancer.video

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

data class VideoInfo(
    val mime: String,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationUs: Long,
    val frameRate: Float?,
    val audioMime: String?,
    val transfer: Int?,
) {
    val isHdr: Boolean get() = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
    val estimatedFrames: Long get() = ((durationUs / 1e6) * (frameRate ?: 30f)).toLong().coerceAtLeast(1)
}

object VideoProbe {
    fun probe(context: Context, uri: Uri): VideoInfo {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            var video: MediaFormat? = null
            var audioMime: String? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (video == null && m.startsWith("video/")) video = f
                if (audioMime == null && m.startsWith("audio/")) audioMime = m
            }
            val v = video ?: error("No video track")
            return VideoInfo(
                mime = v.getString(MediaFormat.KEY_MIME)!!,
                width = v.getInteger(MediaFormat.KEY_WIDTH),
                height = v.getInteger(MediaFormat.KEY_HEIGHT),
                rotation = v.intOrNull(MediaFormat.KEY_ROTATION) ?: 0,
                durationUs = if (v.containsKey(MediaFormat.KEY_DURATION)) v.getLong(MediaFormat.KEY_DURATION) else 0L,
                frameRate = if (v.containsKey(MediaFormat.KEY_FRAME_RATE)) v.getNumber(MediaFormat.KEY_FRAME_RATE)?.toFloat() else null,
                audioMime = audioMime,
                transfer = v.intOrNull(MediaFormat.KEY_COLOR_TRANSFER),
            )
        } finally {
            ex.release()
        }
    }
}

internal fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

data class EncoderChoice(
    val codecName: String,
    val mime: String,
    val hardware: Boolean,
    val maxBitrate: Int,
    val supportsVbr: Boolean,
)

object EncoderSupport {
    private val FLEX = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

    /** All encoders that can take flexible YUV input at exactly [width] x [height], hardware first. */
    fun candidates(mime: String, width: Int, height: Int): List<EncoderChoice> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .mapNotNull { info ->
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return@mapNotNull null
                val vc = caps.videoCapabilities ?: return@mapNotNull null
                if (FLEX !in caps.colorFormats || !vc.isSizeSupported(width, height)) return@mapNotNull null
                EncoderChoice(
                    codecName = info.name,
                    mime = mime,
                    hardware = info.isHardwareAccelerated,
                    maxBitrate = vc.bitrateRange.upper,
                    supportsVbr = caps.encoderCapabilities?.isBitrateModeSupported(
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                    ) == true,
                )
            }
            .sortedByDescending { it.hardware }

    /** Largest frame each encoder for [mime] advertises, for explaining why a size is unsupported. */
    fun maxSizes(mime: String): List<String> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            .mapNotNull { info ->
                val vc = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: return@mapNotNull null
                "${info.name}: up to ${vc.supportedWidths.upper}x${vc.supportedHeights.upper}"
            }
}
