package io.github.slayer8366.enhancer.photo

import android.content.Context
import android.net.Uri
import io.github.slayer8366.enhancer.BuildConfig
import io.github.slayer8366.enhancer.media.MediaStoreOutput
import io.github.slayer8366.enhancer.sr.ModelKind
import io.github.slayer8366.enhancer.sr.ModelSpec
import io.github.slayer8366.enhancer.sr.SrEngine
import io.github.slayer8366.enhancer.sr.TileProgress
import io.github.slayer8366.enhancer.sr.TilePlanner
import io.github.slayer8366.enhancer.sr.TiledUpscaler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhotoResult(val uri: Uri, val width: Int, val height: Int, val notes: List<String>)

/**
 * Upscales one photo into a new PNG in Pictures/Video Enhancer. The PNG is
 * streamed band by band, so there is no cap on output resolution beyond storage.
 */
class PhotoEnhancer(private val context: Context) {

    suspend fun enhance(
        source: Uri,
        sourceName: String,
        mime: String?,
        spec: ModelSpec,
        engine: SrEngine,
        pad: Int,
        outScale: Int,
        onStart: (outW: Int, outH: Int, tiles: Long) -> Unit,
        onTile: (TileProgress) -> Unit,
    ): PhotoResult {
        PhotoSources.open(context, source, mime).use { src ->
            val outW = src.width * outScale
            val outH = src.height * outScale
            onStart(outW, outH, TilePlanner.tileCount(src.width, src.height, spec.tile, pad))

            val name = "${sourceName.substringBeforeLast('.')}_${spec.id}_x$outScale.png"
            val out = MediaStoreOutput.createPhoto(context, name)
            val notes = mutableListOf("Input read by ${src.method}.")
            try {
                val text = provenance(sourceName, spec, engine.backendLabel, pad, outScale)
                context.contentResolver.openOutputStream(out)!!.buffered(1 shl 20).use { os ->
                    val png = PngStreamWriter(os, outW, outH, text)
                    try {
                        TiledUpscaler(engine, pad).run(src, outScale, { px, w, rows -> png.writeRows(px, w, rows) }, onTile)
                    } catch (t: Throwable) {
                        png.abort(); throw t
                    }
                    png.close()
                }
                if (src.hasAlpha) notes += "Source had transparency; it was flattened onto white (models are RGB-only)."
                MediaStoreOutput.publish(context, out)
                return PhotoResult(out, outW, outH, notes)
            } catch (t: Throwable) {
                MediaStoreOutput.discard(context, out)
                throw t
            }
        }
    }

    private fun provenance(sourceName: String, spec: ModelSpec, backend: String, pad: Int, outScale: Int) = linkedMapOf(
        "Software" to "Video Enhancer ${BuildConfig.VERSION_NAME} (LiteRT ${BuildConfig.LITERT_VERSION})",
        "Source" to sourceName,
        "Description" to "${spec.name} (${spec.kind.label}) ${spec.scale}x super-resolution, saved at ${outScale}x. " +
            "Backend: $backend. Tile overlap: $pad px. Model: ${spec.source}.",
        "Warning" to buildString {
            append("AI-generated pixels: detail in this image was predicted by a neural network, not captured.")
            if (spec.kind == ModelKind.PERCEPTUAL) append(" This model synthesises plausible texture that was never in the original.")
        },
        "Creation Time" to SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(Date()),
    )
}
