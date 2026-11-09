package io.github.slayer8366.enhancer.sr

/**
 * Conversions between packed ARGB ints and the models' NHWC float32 RGB tensors
 * with values in [0, 1] (the contract in each model's metadata.json).
 */
object TensorPacking {

    /**
     * Copies a `tile x tile` window starting at ([wx], [wy]) from [src] into [dst].
     * Coordinates past the right or bottom edge are clamped to the last pixel.
     *
     * Pixels are expected as non-premultiplied ARGB (what Bitmap.getPixels returns).
     * Transparent pixels are composited onto white, because the models only see RGB.
     */
    fun packWindow(
        src: IntArray, srcWidth: Int, srcHeight: Int,
        wx: Int, wy: Int, tile: Int, dst: FloatArray,
    ) {
        require(dst.size >= tile * tile * 3)
        var o = 0
        for (ty in 0 until tile) {
            val sy = minOf(wy + ty, srcHeight - 1)
            val row = sy * srcWidth
            for (tx in 0 until tile) {
                val p = src[row + minOf(wx + tx, srcWidth - 1)]
                val a = p ushr 24
                var r = (p shr 16) and 0xFF
                var g = (p shr 8) and 0xFF
                var b = p and 0xFF
                if (a != 0xFF) {
                    val inv = 255 - a
                    r = (r * a + 255 * inv + 127) / 255
                    g = (g * a + 255 * inv + 127) / 255
                    b = (b * a + 255 * inv + 127) / 255
                }
                dst[o] = r * INV_255
                dst[o + 1] = g * INV_255
                dst[o + 2] = b * INV_255
                o += 3
            }
        }
    }

    /**
     * Writes a `w x h` region of the model output, starting at ([ox], [oy]) inside
     * an `outTile x outTile` result, into [dst] at ([dx], [dy]). Values are clamped
     * to [0, 1] and rounded to the nearest 8-bit level.
     */
    fun unpackRegion(
        out: FloatArray, outTile: Int, ox: Int, oy: Int, w: Int, h: Int,
        dst: IntArray, dstWidth: Int, dx: Int, dy: Int,
    ) {
        for (y in 0 until h) {
            var i = ((oy + y) * outTile + ox) * 3
            var d = (dy + y) * dstWidth + dx
            for (x in 0 until w) {
                dst[d++] = (0xFF shl 24) or
                    (to8(out[i]) shl 16) or (to8(out[i + 1]) shl 8) or to8(out[i + 2])
                i += 3
            }
        }
    }

    fun to8(v: Float): Int {
        val c = if (v < 0f) 0f else if (v > 1f) 1f else v
        return (c * 255f + 0.5f).toInt()
    }

    private const val INV_255 = 1f / 255f
}
