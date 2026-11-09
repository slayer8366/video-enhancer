package io.github.slayer8366.enhancer.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import io.github.slayer8366.enhancer.sr.PixelSource
import java.io.Closeable

interface PhotoSource : PixelSource, Closeable {
    /** Short description of how the image is being read, for provenance notes. */
    val method: String
    val hasAlpha: Boolean
}

private val SRGB: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)

/**
 * Decodes only the band being upscaled, so input size is not limited by memory.
 * EXIF orientation is applied by pixel remapping ([Orientation]); pixels are
 * converted to sRGB by the decoder (wide-gamut colours are clipped).
 */
class RegionPhotoSource(
    private val decoder: BitmapRegionDecoder,
    private val orientation: Orientation,
) : PhotoSource {
    override val width = orientation.displayWidth
    override val height = orientation.displayHeight
    override val method = "region decode, EXIF orientation ${orientation.exif}"
    override var hasAlpha = false
        private set

    private val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = SRGB
    }
    private var scratch = IntArray(0)

    override fun read(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
        val r = orientation.sourceRect(x, y, w, h)
        val rw = r[2] - r[0]
        val rh = r[3] - r[1]
        val bmp = decoder.decodeRegion(Rect(r[0], r[1], r[2], r[3]), options)
            ?: error("decodeRegion returned null for $r")
        try {
            check(bmp.width == rw && bmp.height == rh) { "decoder returned ${bmp.width}x${bmp.height}, expected ${rw}x$rh" }
            if (bmp.hasAlpha()) hasAlpha = true
            if (orientation.exif == 1) {
                bmp.getPixels(dst, 0, w, 0, 0, w, h)
            } else {
                if (scratch.size < rw * rh) scratch = IntArray(rw * rh)
                bmp.getPixels(scratch, 0, rw, 0, 0, rw, rh)
                orientation.remap(x, y, w, h, scratch, r, dst)
            }
        } finally {
            bmp.recycle()
        }
    }

    override fun close() = decoder.recycle()
}

/** Whole-image fallback for formats the region decoder can't handle. ImageDecoder applies orientation itself. */
class BitmapPhotoSource(private val bitmap: Bitmap, override val method: String) : PhotoSource {
    override val width = bitmap.width
    override val height = bitmap.height
    override val hasAlpha = bitmap.hasAlpha()
    override fun read(x: Int, y: Int, w: Int, h: Int, dst: IntArray) = bitmap.getPixels(dst, 0, w, x, y, w, h)
    override fun close() = bitmap.recycle()
}

object PhotoSources {
    /** JPEG, PNG and WebP go through the region decoder. HEIF/AVIF/others use a full decode,
     *  because their rotation can live in the container (irot) rather than in EXIF. */
    private val regionTypes = setOf("image/jpeg", "image/png", "image/webp")

    fun open(context: Context, uri: Uri, mime: String?): PhotoSource {
        val resolver = context.contentResolver
        if (mime in regionTypes) {
            runCatching {
                val exif = resolver.openInputStream(uri)!!.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }.let { if (it in 1..8) it else 1 }
                val decoder = resolver.openInputStream(uri)!!.use { stream ->
                    if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(stream)
                    else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(stream, false)
                } ?: error("no region decoder")
                return RegionPhotoSource(decoder, Orientation(exif, decoder.width, decoder.height))
            }
        }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { d, _, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.setTargetColorSpace(SRGB)
        }
        return BitmapPhotoSource(bitmap, "full decode (ImageDecoder)")
    }
}
