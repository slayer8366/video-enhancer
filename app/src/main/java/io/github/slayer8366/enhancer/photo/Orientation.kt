package io.github.slayer8366.enhancer.photo

/**
 * EXIF orientation (values 1-8 of TIFF tag 0x0112) as a pixel mapping, so that
 * region-decoded pixels can be put into display orientation without a Matrix and
 * without decoding the whole image.
 */
class Orientation(val exif: Int, val sourceWidth: Int, val sourceHeight: Int) {

    init {
        require(exif in 1..8) { "EXIF orientation must be 1..8, was $exif" }
    }

    val swapsAxes: Boolean get() = exif >= 5
    val displayWidth: Int get() = if (swapsAxes) sourceHeight else sourceWidth
    val displayHeight: Int get() = if (swapsAxes) sourceWidth else sourceHeight

    /** Source x of display pixel (x, y). */
    fun sx(x: Int, y: Int): Int = when (exif) {
        1, 4 -> x
        2, 3 -> sourceWidth - 1 - x
        5, 6 -> y
        else -> sourceWidth - 1 - y // 7, 8
    }

    /** Source y of display pixel (x, y). */
    fun sy(x: Int, y: Int): Int = when (exif) {
        1, 2 -> y
        3, 4 -> sourceHeight - 1 - y
        5, 8 -> x
        else -> sourceHeight - 1 - x // 6, 7
    }

    /** Smallest source rectangle [left, top, right, bottom) covering a display rectangle. */
    fun sourceRect(x: Int, y: Int, w: Int, h: Int): IntArray {
        val xs = intArrayOf(sx(x, y), sx(x + w - 1, y), sx(x, y + h - 1), sx(x + w - 1, y + h - 1))
        val ys = intArrayOf(sy(x, y), sy(x + w - 1, y), sy(x, y + h - 1), sy(x + w - 1, y + h - 1))
        return intArrayOf(xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)
    }

    /**
     * Fills [dst] (row stride [w]) with the display rectangle, given [region] which
     * holds the pixels of [sourceRect] (row stride = rect width).
     */
    fun remap(x: Int, y: Int, w: Int, h: Int, region: IntArray, rect: IntArray, dst: IntArray) {
        val rw = rect[2] - rect[0]
        for (j in 0 until h) {
            for (i in 0 until w) {
                val px = sx(x + i, y + j) - rect[0]
                val py = sy(x + i, y + j) - rect[1]
                dst[j * w + i] = region[py * rw + px]
            }
        }
    }
}
