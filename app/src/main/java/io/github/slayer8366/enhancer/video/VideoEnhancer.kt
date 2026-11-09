package io.github.slayer8366.enhancer.video

import android.content.Context
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import io.github.slayer8366.enhancer.jobs.VideoCodecPref
import io.github.slayer8366.enhancer.jobs.VideoQuality
import io.github.slayer8366.enhancer.media.MediaStoreOutput
import io.github.slayer8366.enhancer.sr.IntArraySource
import io.github.slayer8366.enhancer.sr.ModelSpec
import io.github.slayer8366.enhancer.sr.SrEngine
import io.github.slayer8366.enhancer.sr.TileProgress
import io.github.slayer8366.enhancer.sr.TilePlanner
import io.github.slayer8366.enhancer.sr.TiledUpscaler
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer

data class VideoResult(val uri: Uri, val width: Int, val height: Int, val frames: Long, val notes: List<String>)

/**
 * Decode -> upscale every frame -> encode, with the audio track copied as-is.
 *
 * Frames are decoded to flexible YUV 4:2:0 (guaranteed for decoders since API 21),
 * converted to RGB with the stream's own matrix and range, upscaled, and written
 * straight into the encoder's input Image band by band. Presentation timestamps are
 * copied from the decoder, so variable frame rate survives. Rotation is passed to
 * the muxer as metadata rather than baked into pixels.
 *
 * Not handled yet (the job fails with a clear message instead): HDR (PQ/HLG) and
 * decoders that do not return 8-bit YUV_420_888 images.
 */
class VideoEnhancer(private val context: Context) {

    data class Progress(val frame: Long, val tile: TileProgress)

    @Suppress("LongMethod")
    suspend fun enhance(
        source: Uri,
        sourceName: String,
        spec: ModelSpec,
        engine: SrEngine,
        pad: Int,
        outScale: Int,
        codecPref: VideoCodecPref,
        quality: VideoQuality,
        onStart: (info: VideoInfo, outW: Int, outH: Int, tilesPerFrame: Long) -> Unit,
        onProgress: (Progress) -> Unit,
    ): VideoResult {
        val info = VideoProbe.probe(context, source)
        if (info.isHdr) throw UnsupportedOperationException(
            "HDR video (PQ/HLG) isn't supported yet. Converting it to SDR needs tone mapping, which would change the image.",
        )
        val notes = mutableListOf<String>()
        val upscaler = TiledUpscaler(engine, pad)

        val extractor = MediaExtractor().apply { setDataSource(context, source, null) }
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var audio: AudioCopier? = null
        var outUri: Uri? = null
        var pfd: android.os.ParcelFileDescriptor? = null
        var success = false
        try {
            val vTrack = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val aTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(vTrack)
            val inFormat = extractor.getTrackFormat(vTrack)
            inFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            decoder = MediaCodec.createDecoderByType(info.mime).apply {
                configure(inFormat, null, null, 0)
                start()
            }

            val fps = info.frameRate ?: 30f
            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var decOutFormat: MediaFormat? = null
            var inputDone = false
            var decodeDone = false
            var frames = 0L
            var lastPts = 0L

            // Set up lazily from the first decoded frame, whose crop rect is the true visible size.
            var srcW = 0; var srcH = 0; var outW = 0; var outH = 0
            var srcArgb = IntArray(0)
            var inConv: YuvConverter? = null
            val outConv = YuvConverter(YuvMatrix.BT709, fullRange = false)
            var videoTrackOut = -1
            var audioTrackOut = -1
            var muxerStarted = false

            fun startMuxer(format: MediaFormat) {
                val m = muxer!!
                videoTrackOut = m.addTrack(format)
                if (aTrack != null) {
                    val af = extractor.getTrackFormat(aTrack)
                    try {
                        audioTrackOut = m.addTrack(af)
                        audio = AudioCopier(context, source, aTrack, af)
                    } catch (e: Exception) {
                        notes += "Audio (${af.getString(MediaFormat.KEY_MIME)}) can't be stored in MP4 here; output is silent."
                    }
                }
                m.start()
                muxerStarted = true
            }

            fun drainEncoder(endOfStream: Boolean) {
                val enc = encoder ?: return
                var idle = 0
                while (true) {
                    val idx = enc.dequeueOutputBuffer(encInfo, if (endOfStream) 10_000 else 0)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            if (++idle > 1_000) error("Encoder did not signal end of stream")
                        }
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxer(enc.outputFormat)
                        idx >= 0 -> {
                            idle = 0
                            val buf = enc.getOutputBuffer(idx)!!
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                            if (encInfo.size > 0) {
                                check(muxerStarted) { "Encoder produced data before its format" }
                                buf.position(encInfo.offset).limit(encInfo.offset + encInfo.size)
                                muxer!!.writeSampleData(videoTrackOut, buf, encInfo)
                                audio?.copyUntil(muxer!!, audioTrackOut, encInfo.presentationTimeUs)
                            }
                            enc.releaseOutputBuffer(idx, false)
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            suspend fun encoderInput(): Int {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val i = encoder!!.dequeueInputBuffer(10_000)
                    if (i >= 0) return i
                    drainEncoder(false)
                }
            }

            fun openEncoder() {
                val mimes = when (codecPref) {
                    VideoCodecPref.HEVC -> listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
                    VideoCodecPref.AVC -> listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
                }
                val tried = StringBuilder()
                for (mime in mimes) for (choice in EncoderSupport.candidates(mime, outW, outH)) {
                    val bpp = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) quality.hevcBpp else quality.avcBpp
                    val wanted = (bpp * outW * outH * fps).toLong()
                    val bitrate = wanted.coerceIn(1_000_000L, choice.maxBitrate.toLong()).toInt()
                    val fmt = MediaFormat.createVideoFormat(mime, outW, outH).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                        setFloat(MediaFormat.KEY_FRAME_RATE, fps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        setInteger(MediaFormat.KEY_PRIORITY, 1) // offline, not real time
                        setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                        setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                        setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                        if (choice.supportsVbr) setInteger(
                            MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                        )
                    }
                    val codec = MediaCodec.createByCodecName(choice.codecName)
                    try {
                        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        codec.start()
                    } catch (e: Exception) {
                        codec.release()
                        tried.append("${choice.codecName}: ${e.message}\n")
                        continue
                    }
                    encoder = codec
                    notes += "Encoder ${choice.codecName} (${if (choice.hardware) "hardware" else "software"}), " +
                        "${"%.1f".format(bitrate / 1e6)} Mbps" +
                        if (bitrate < wanted) " (capped by the encoder; ${"%.1f".format(wanted / 1e6)} Mbps requested)." else "."
                    return
                }
                val limits = mimes.flatMap { EncoderSupport.maxSizes(it) }.joinToString("\n")
                throw UnsupportedOperationException(
                    "No encoder on this device accepts ${outW}x$outH. Try a smaller output scale.\n$limits\n$tried",
                )
            }

            suspend fun processFrame(pts: Long) {
                val encIdx = encoderInput()
                val img = encoder!!.getInputImage(encIdx) ?: error("Encoder returned no input image")
                val p = img.planes.map { PlaneView(it.buffer, it.rowStride, it.pixelStride) }
                var y0 = 0
                upscaler.run(IntArraySource(srcArgb, srcW, srcH), outScale, { px, width, rows ->
                    outConv.fromArgb(px, width, rows, y0, p[0], p[1], p[2])
                    y0 += rows
                }) { onProgress(Progress(frames, it)) }
                check(y0 == outH) { "wrote $y0 of $outH rows" }
                encoder.queueInputBuffer(encIdx, 0, outW * outH * 3 / 2, pts, 0)
                frames++
                lastPts = pts
                drainEncoder(false)
            }

            while (!decodeDone) {
                currentCoroutineContext().ensureActive()
                val dec = decoder
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf: ByteBuffer = dec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(decInfo, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    decOutFormat = dec.outputFormat
                    continue
                }
                if (outIdx < 0) continue
                val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                var havePixels = false
                if (decInfo.size > 0) {
                    val image = dec.getOutputImage(outIdx) ?: error("Decoder returned no output image")
                    image.use {
                        if (it.format != ImageFormat.YUV_420_888) throw UnsupportedOperationException(
                            "Decoder output format ${it.format} isn't 8-bit YUV 4:2:0 (10-bit video isn't supported yet).",
                        )
                        val crop = it.cropRect
                        if (srcW == 0) {
                            val transfer = decOutFormat?.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
                            if (transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG) {
                                throw UnsupportedOperationException("HDR video (PQ/HLG) isn't supported yet.")
                            }
                            // 4:2:0 output needs even dimensions; drop one edge column/row if the scaled size would be odd.
                            srcW = if ((crop.width() * outScale) % 2 == 1) crop.width() - 1 else crop.width()
                            srcH = if ((crop.height() * outScale) % 2 == 1) crop.height() - 1 else crop.height()
                            if (srcW != crop.width() || srcH != crop.height()) notes += "Odd frame size: one edge pixel row/column dropped."
                            outW = srcW * outScale
                            outH = srcH * outScale
                            srcArgb = IntArray(srcW * srcH)
                            val (matrix, full) = colorOf(decOutFormat ?: inFormat, srcH)
                            inConv = YuvConverter(matrix, full)
                            notes += "Decoded as $matrix ${if (full) "full" else "limited"} range; encoded as BT709 limited range."
                            onStart(info, outW, outH, TilePlanner.tileCount(srcW, srcH, spec.tile, pad))
                            val newUri = MediaStoreOutput.createVideo(
                                context, "${sourceName.substringBeforeLast('.')}_${spec.id}_x$outScale.mp4",
                            )
                            outUri = newUri
                            val fd = context.contentResolver.openFileDescriptor(newUri, "rw") ?: error("Can't open output")
                            pfd = fd
                            muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                                setOrientationHint(info.rotation)
                            }
                            openEncoder()
                        }
                        val pl = it.planes.map { pp -> PlaneView(pp.buffer, pp.rowStride, pp.pixelStride) }
                        inConv!!.toArgb(pl[0], pl[1], pl[2], crop.left, crop.top, srcW, srcH, srcArgb)
                        havePixels = true
                    }
                }
                val pts = decInfo.presentationTimeUs
                dec.releaseOutputBuffer(outIdx, false)
                if (havePixels) processFrame(pts)
                if (eos) decodeDone = true
            }

            if (frames == 0L) error("No frames could be decoded")
            val encIdx = encoderInput()
            encoder!!.queueInputBuffer(encIdx, 0, 0, lastPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainEncoder(true)
            audio?.copyUntil(muxer!!, audioTrackOut, Long.MAX_VALUE)
            muxer!!.stop()
            success = true
            return VideoResult(outUri!!, outW, outH, frames, notes)
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            runCatching { audio?.close() }
            runCatching { pfd?.close() }
            extractor.release()
            outUri?.let { if (success) MediaStoreOutput.publish(context, it) else MediaStoreOutput.discard(context, it) }
        }
    }

    private fun colorOf(format: MediaFormat, height: Int): Pair<YuvMatrix, Boolean> {
        val matrix = when (format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)) {
            MediaFormat.COLOR_STANDARD_BT709 -> YuvMatrix.BT709
            MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> YuvMatrix.BT601
            MediaFormat.COLOR_STANDARD_BT2020 -> YuvMatrix.BT2020
            // Unsignalled: the usual convention is BT.709 for HD and BT.601 for SD.
            else -> if (height >= 720) YuvMatrix.BT709 else YuvMatrix.BT601
        }
        val full = format.intOrNull(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
        return matrix to full
    }
}

/** Copies compressed audio samples into the muxer, interleaved with video by timestamp. */
private class AudioCopier(context: Context, source: Uri, track: Int, format: MediaFormat) : AutoCloseable {
    private val ex = MediaExtractor().apply {
        setDataSource(context, source, null)
        selectTrack(track)
    }
    private val buf = ByteBuffer.allocateDirect(
        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1 shl 20,
    )
    private val info = MediaCodec.BufferInfo()
    private var done = false

    fun copyUntil(muxer: MediaMuxer, track: Int, ptsUs: Long) {
        while (!done) {
            val t = ex.sampleTime
            if (t < 0) { done = true; return }
            if (t > ptsUs) return
            buf.clear()
            val n = ex.readSampleData(buf, 0)
            if (n < 0) { done = true; return }
            val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            info.set(0, n, t, flags)
            try {
                muxer.writeSampleData(track, buf, info)
            } catch (e: Exception) {
                Log.w("AudioCopier", "dropping audio sample at $t", e)
            }
            ex.advance()
        }
    }

    override fun close() = ex.release()
}
