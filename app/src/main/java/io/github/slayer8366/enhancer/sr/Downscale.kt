package io.github.slayer8366.enhancer.sr

/**
 * Integer-factor box (area) downscaling of opaque ARGB pixels, used to turn the
 * models' native 4x output into 2x or 1x.
 *
 * Averaging happens on the 8-bit sRGB-encoded values, as most image tools do
 * (e.g. OpenCV INTER_AREA, Pillow BOX). Averaging in linear light would be more
 * physically correct and would keep thin bright lines slightly brighter.
 */
object Downscale {
    fun box(src: IntArray, srcWidth: Int, srcHeight: Int, factor: Int, dst: IntArray) {
        if (factor == 1) {
            System.arraycopy(src, 0, dst, 0, srcWidth * srcHeight)
            return
        }
        require(srcWidth % factor == 0 && srcHeight % factor == 0) { "size not divisible by $factor" }
        val dw = srcWidth / factor
        val dh = srcHeight / factor
        val n = factor * factor
        val half = n / 2
        for (y in 0 until dh) {
            for (x in 0 until dw) {
                var r = 0; var g = 0; var b = 0
                for (j in 0 until factor) {
                    var i = (y * factor + j) * srcWidth + x * factor
                    for (k in 0 until factor) {
                        val p = src[i++]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                }
                dst[y * dw + x] = (0xFF shl 24) or
                    (((r + half) / n) shl 16) or (((g + half) / n) shl 8) or ((b + half) / n)
            }
        }
    }
}
