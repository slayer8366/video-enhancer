package io.github.slayer8366.enhancer.sr

import org.junit.Assert.assertEquals
import org.junit.Test

class TensorPackingTest {

    @Test
    fun packsNhwcRgbInUnitRangeAndClampsEdges() {
        val w = 3; val h = 2
        val src = IntArray(w * h) { i -> (0xFF shl 24) or ((i * 10) shl 16) or ((i * 20) shl 8) or (i * 30) }
        val tile = 4
        val dst = FloatArray(tile * tile * 3)
        TensorPacking.packWindow(src, w, h, 1, 0, tile, dst)
        // (0,0) of the window is source (1,0) = index 1.
        assertEquals(10 / 255f, dst[0], 1e-6f)
        assertEquals(20 / 255f, dst[1], 1e-6f)
        assertEquals(30 / 255f, dst[2], 1e-6f)
        // Window x=3 -> source x=4, clamped to 2; window y=3 -> clamped to row 1 -> index 5.
        val o = (3 * tile + 3) * 3
        assertEquals(50 / 255f, dst[o], 1e-6f)
        assertEquals(100 / 255f, dst[o + 1], 1e-6f)
        assertEquals(150 / 255f, dst[o + 2], 1e-6f)
    }

    @Test
    fun transparentPixelsAreCompositedOntoWhite() {
        val dst = FloatArray(3)
        TensorPacking.packWindow(intArrayOf(0x00000000), 1, 1, 0, 0, 1, dst)
        assertEquals(1f, dst[0], 0f)
        // 50% black over white -> mid grey.
        TensorPacking.packWindow(intArrayOf(0x80000000.toInt()), 1, 1, 0, 0, 1, dst)
        assertEquals(127 / 255f, dst[0], 1e-6f)
    }

    @Test
    fun unpackRoundsAndClamps() {
        assertEquals(0, TensorPacking.to8(-0.2f))
        assertEquals(255, TensorPacking.to8(1.7f))
        assertEquals(128, TensorPacking.to8(0.5f))
        assertEquals(1, TensorPacking.to8(0.5f / 255f + 1e-4f))
        val out = floatArrayOf(1f, 0f, 0.5f)
        val dst = IntArray(1)
        TensorPacking.unpackRegion(out, 1, 0, 0, 1, 1, dst, 1, 0, 0)
        assertEquals(0xFFFF0080.toInt(), dst[0])
    }
}
