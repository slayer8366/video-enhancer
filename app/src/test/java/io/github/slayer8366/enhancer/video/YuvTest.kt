package io.github.slayer8366.enhancer.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs

class YuvTest {

    /** Builds NV12-style (pixelStride 2, interleaved) or I420-style (pixelStride 1) planes with row padding. */
    private class Frame(val w: Int, val h: Int, interleaved: Boolean) {
        val yStride = w + 16
        val cStride = if (interleaved) w + 16 else w / 2 + 8
        val y = PlaneView(ByteBuffer.allocate(yStride * h), yStride, 1)
        private val chroma = ByteBuffer.allocate(cStride * (h / 2) + 1)
        val u: PlaneView
        val v: PlaneView
        init {
            if (interleaved) {
                u = PlaneView(chroma.duplicate(), cStride, 2)
                v = PlaneView(chroma.duplicate().apply { position(1) }.slice(), cStride, 2)
            } else {
                u = PlaneView(chroma, cStride, 1)
                v = PlaneView(ByteBuffer.allocate(cStride * (h / 2)), cStride, 1)
            }
        }
        fun yAt(x: Int, yy: Int) = y.buffer.get(yy * yStride + x).toInt() and 0xFF
        fun uAt(x: Int, yy: Int) = u.buffer.get(yy * u.rowStride + x * u.pixelStride).toInt() and 0xFF
        fun vAt(x: Int, yy: Int) = v.buffer.get(yy * v.rowStride + x * v.pixelStride).toInt() and 0xFF
    }

    private fun solid(w: Int, h: Int, rgb: Int) = IntArray(w * h) { (0xFF shl 24) or rgb }

    @Test
    fun bt709LimitedPrimariesMatchPublishedCodeValues() {
        // BT.709 8-bit limited range: red = (63, 102, 240), green = (173, 42, 26), blue = (32, 240, 118),
        // white = (235, 128, 128), black = (16, 128, 128).
        val conv = YuvConverter(YuvMatrix.BT709, fullRange = false)
        val cases = mapOf(
            0xFF0000 to intArrayOf(63, 102, 240),
            0x00FF00 to intArrayOf(173, 42, 26),
            0x0000FF to intArrayOf(32, 240, 118),
            0xFFFFFF to intArrayOf(235, 128, 128),
            0x000000 to intArrayOf(16, 128, 128),
        )
        for ((rgb, yuv) in cases) {
            val f = Frame(8, 4, interleaved = true)
            conv.fromArgb(solid(8, 4, rgb), 8, 4, 0, f.y, f.u, f.v)
            assertEquals("Y of %06x".format(rgb), yuv[0], f.yAt(3, 1))
            assertEquals("Cb of %06x".format(rgb), yuv[1], f.uAt(1, 1))
            assertEquals("Cr of %06x".format(rgb), yuv[2], f.vAt(1, 1))
        }
    }

    @Test
    fun roundTripIsNearLosslessForSmoothContent() {
        for (matrix in YuvMatrix.entries) for (full in listOf(false, true)) for (inter in listOf(true, false)) {
            val w = 64; val h = 48
            val src = IntArray(w * h) { i ->
                val x = i % w; val y = i / w
                (0xFF shl 24) or ((40 + x * 2) shl 16) or ((60 + y * 3) shl 8) or (90 + (x + y))
            }
            val f = Frame(w, h, inter)
            val conv = YuvConverter(matrix, full)
            // Write in two bands to exercise the y0 offset.
            conv.fromArgb(src.copyOfRange(0, w * 20), w, 20, 0, f.y, f.u, f.v)
            conv.fromArgb(src.copyOfRange(w * 20, w * h), w, h - 20, 20, f.y, f.u, f.v)
            val back = IntArray(w * h)
            conv.toArgb(f.y, f.u, f.v, 0, 0, w, h, back)
            var maxErr = 0
            for (i in src.indices) for (s in intArrayOf(16, 8, 0)) {
                maxErr = maxOf(maxErr, abs(((src[i] shr s) and 0xFF) - ((back[i] shr s) and 0xFF)))
            }
            assertTrue("$matrix full=$full interleaved=$inter maxErr=$maxErr", maxErr <= 3)
        }
    }

    @Test
    fun cropRectangleIsHonoured() {
        val w = 16; val h = 8
        val f = Frame(w, h, interleaved = true)
        val conv = YuvConverter(YuvMatrix.BT601, fullRange = false)
        val src = IntArray(w * h) { i -> if (i % w >= 8) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        conv.fromArgb(src, w, h, 0, f.y, f.u, f.v)
        val out = IntArray(4 * 4)
        conv.toArgb(f.y, f.u, f.v, 10, 2, 4, 4, out)
        assertTrue(out.all { (it and 0xFF) > 250 })
    }
}
