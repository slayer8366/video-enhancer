package io.github.slayer8366.enhancer.photo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationTest {

    private val sw = 5
    private val sh = 3
    private val src = IntArray(sw * sh) { it }

    /** Independent definitions of the eight EXIF transforms, built from rotate/flip primitives. */
    private fun rot90cw(a: Array<IntArray>) = Array(a[0].size) { x -> IntArray(a.size) { y -> a[a.size - 1 - y][x] } }
    private fun flipH(a: Array<IntArray>) = Array(a.size) { y -> a[y].reversedArray() }
    private fun flipV(a: Array<IntArray>) = Array(a.size) { y -> a[a.size - 1 - y] }

    private fun expected(exif: Int): Array<IntArray> {
        val m = Array(sh) { y -> IntArray(sw) { x -> src[y * sw + x] } }
        return when (exif) {
            1 -> m
            2 -> flipH(m)
            3 -> rot90cw(rot90cw(m))
            4 -> flipV(m)
            5 -> rot90cw(flipV(m)) // transpose: display(0,0) = source(0,0)
            6 -> rot90cw(m)
            7 -> rot90cw(flipH(m)) // transverse: display(0,0) = source(W-1,H-1)
            else -> rot90cw(rot90cw(rot90cw(m))) // 8: 270 cw
        }
    }

    @Test
    fun mappingMatchesRotateAndFlipDefinitions() {
        for (exif in 1..8) {
            val o = Orientation(exif, sw, sh)
            val exp = expected(exif)
            assertEquals(exp.size, o.displayHeight)
            assertEquals(exp[0].size, o.displayWidth)
            for (y in 0 until o.displayHeight) for (x in 0 until o.displayWidth) {
                assertEquals("exif=$exif ($x,$y)", exp[y][x], src[o.sy(x, y) * sw + o.sx(x, y)])
            }
        }
    }

    /** Output of Pillow 13's ImageOps.exif_transpose on the same 5x3 ramp (independent reference). */
    @Test
    fun matchesPillowForTheMirroredRotations() {
        val pillow = mapOf(
            5 to intArrayOf(0, 5, 10, 1, 6, 11, 2, 7, 12, 3, 8, 13, 4, 9, 14),
            7 to intArrayOf(14, 9, 4, 13, 8, 3, 12, 7, 2, 11, 6, 1, 10, 5, 0),
        )
        for ((exif, want) in pillow) {
            val o = Orientation(exif, sw, sh)
            val got = IntArray(15) { i -> val x = i % 3; val y = i / 3; src[o.sy(x, y) * sw + o.sx(x, y)] }
            assertArrayEquals("exif=$exif", want, got)
        }
    }

    @Test
    fun regionRemapEqualsDirectMapping() {
        for (exif in 1..8) {
            val o = Orientation(exif, sw, sh)
            val exp = expected(exif)
            // Every sub-rectangle of the display image.
            for (y in 0 until o.displayHeight) for (x in 0 until o.displayWidth)
                for (h in 1..o.displayHeight - y) for (w in 1..o.displayWidth - x) {
                    val rect = o.sourceRect(x, y, w, h)
                    val rw = rect[2] - rect[0]
                    val region = IntArray(rw * (rect[3] - rect[1])) { i -> src[(rect[1] + i / rw) * sw + rect[0] + i % rw] }
                    val dst = IntArray(w * h)
                    o.remap(x, y, w, h, region, rect, dst)
                    val want = IntArray(w * h) { i -> exp[y + i / w][x + i % w] }
                    assertArrayEquals("exif=$exif rect=($x,$y,$w,$h)", want, dst)
                }
        }
    }
}
