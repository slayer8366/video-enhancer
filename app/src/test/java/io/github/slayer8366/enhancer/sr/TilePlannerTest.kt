package io.github.slayer8366.enhancer.sr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePlannerTest {

    /** Expected values were produced by the Python reference used for the seam measurement. */
    @Test
    fun matchesPythonReference() {
        fun spans(n: Int, pad: Int) = TilePlanner.plan(n, 128, pad).map { Triple(it.start, it.length, it.windowStart) }
        assertEquals(listOf(Triple(0, 112, 0), Triple(112, 112, 104), Triple(224, 76, 172)), spans(300, 8))
        assertEquals(
            listOf(Triple(0, 96, 0), Triple(96, 96, 80), Triple(192, 96, 172), Triple(288, 12, 172)),
            spans(300, 16),
        )
        assertEquals(listOf(Triple(0, 100, 0)), spans(100, 8))
        assertEquals(listOf(Triple(0, 80, 0), Triple(80, 48, 0)), spans(128, 24))
        assertEquals(listOf(Triple(0, 80, 0), Triple(80, 49, 1)), spans(129, 24))
        assertEquals(Triple(1056, 24, 952), spans(1080, 16).last())
        assertEquals(12, spans(1080, 16).size)
    }

    @Test
    fun coversEveryPixelOnceAndKeepsContext() {
        for (pad in listOf(0, 4, 8, 16, 24, 32)) {
            for (n in listOf(1, 2, 7, 64, 127, 128, 129, 200, 1000, 1080, 1920, 4033)) {
                val spans = TilePlanner.plan(n, 128, pad)
                var next = 0
                for (s in spans) {
                    assertEquals("contiguous n=$n pad=$pad", next, s.start)
                    next = s.start + s.length
                    assertTrue(s.windowStart >= 0)
                    // Core sits inside the 128-px window.
                    assertTrue(s.offsetInWindow >= 0 && s.offsetInWindow + s.length <= 128)
                    if (n >= 128) {
                        assertTrue(s.windowStart + 128 <= n)
                        // At least `pad` px of real context on each side, unless that side is the image edge.
                        val leftCtx = s.offsetInWindow
                        val rightCtx = s.windowStart + 128 - (s.start + s.length)
                        assertTrue("left ctx n=$n pad=$pad $s", leftCtx >= pad || s.windowStart == 0)
                        assertTrue("right ctx n=$n pad=$pad $s", rightCtx >= pad || s.windowStart + 128 == n)
                    }
                }
                assertEquals(n, next)
            }
        }
    }

    @Test
    fun tileCountMatchesPlan() {
        assertEquals(3L * 4, TilePlanner.tileCount(300, 400, 128, 8))
        assertEquals(
            TilePlanner.plan(1920, 128, 16).size.toLong() * TilePlanner.plan(1080, 128, 16).size,
            TilePlanner.tileCount(1920, 1080, 128, 16),
        )
    }
}
