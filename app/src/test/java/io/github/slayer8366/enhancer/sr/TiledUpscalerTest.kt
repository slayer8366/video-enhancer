package io.github.slayer8366.enhancer.sr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

/**
 * A fake model with a known receptive field: a (2r+1)^2 box blur (clamp-to-edge)
 * followed by nearest-neighbour 4x upscaling.
 * With overlap >= r, tiling must reproduce whole-image processing exactly.
 */
private class BoxBlurNearest(private val radius: Int) : SrEngine {
    override val tile = 128
    override val scale = 4
    override val backendLabel = "fake"
    override fun close() {}

    override fun run(input: FloatArray): FloatArray {
        val blurred = FloatArray(input.size)
        for (y in 0 until tile) for (x in 0 until tile) for (c in 0 until 3) {
            var s = 0f
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val yy = (y + dy).coerceIn(0, tile - 1); val xx = (x + dx).coerceIn(0, tile - 1)
                s += input[(yy * tile + xx) * 3 + c]
            }
            blurred[(y * tile + x) * 3 + c] = s / ((2 * radius + 1) * (2 * radius + 1))
        }
        val o = tile * scale
        val out = FloatArray(o * o * 3)
        for (y in 0 until o) for (x in 0 until o) for (c in 0 until 3) {
            out[(y * o + x) * 3 + c] = blurred[((y / scale) * tile + x / scale) * 3 + c]
        }
        return out
    }
}

class TiledUpscalerTest {

    private fun randomImage(w: Int, h: Int, seed: Int): IntArray {
        val r = Random(seed)
        return IntArray(w * h) { (0xFF shl 24) or r.nextInt(0x1000000) }
    }

    /** Whole-image reference: clamp-to-edge blur at the image borders, then nearest upscale. */
    private fun reference(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val out = IntArray(w * 4 * h * 4)
        val blurred = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0f; var g = 0f; var b = 0f
            val n = (2 * radius + 1) * (2 * radius + 1)
            val inv = 1f / 255f // same expression as TensorPacking, so rounding cannot diverge
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val p = src[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                r += ((p shr 16) and 0xFF) * inv; g += ((p shr 8) and 0xFF) * inv; b += (p and 0xFF) * inv
            }
            blurred[y * w + x] = (0xFF shl 24) or (TensorPacking.to8(r / n) shl 16) or
                (TensorPacking.to8(g / n) shl 8) or TensorPacking.to8(b / n)
        }
        for (y in 0 until h * 4) for (x in 0 until w * 4) out[y * w * 4 + x] = blurred[(y / 4) * w + x / 4]
        return out
    }

    private fun tiled(src: IntArray, w: Int, h: Int, radius: Int, pad: Int, outScale: Int = 4): IntArray {
        val ow = w * outScale
        val out = IntArray(ow * h * outScale)
        var row = 0
        runBlocking {
            TiledUpscaler(BoxBlurNearest(radius), pad).run(IntArraySource(src, w, h), outScale, { px, width, rows ->
                assertEquals(ow, width)
                System.arraycopy(px, 0, out, row * ow, width * rows)
                row += rows
            })
        }
        assertEquals(h * outScale, row)
        return out
    }

    @Test
    fun tiledMatchesWholeImageWhenOverlapCoversReceptiveField() {
        // Images larger than one tile in both axes, odd sizes, and a single-tile case.
        for ((w, h) in listOf(300 to 260, 129 to 333, 64 to 50, 128 to 128)) {
            val src = randomImage(w, h, w * 31 + h)
            val ref = reference(src, w, h, radius = 3)
            assertArrayEquals("w=$w h=$h", ref, tiled(src, w, h, radius = 3, pad = 4))
        }
    }

    /** Positive control: without enough overlap the same comparison must fail. */
    @Test
    fun tiledDiffersWhenOverlapIsTooSmall() {
        val (w, h) = 300 to 260
        val src = randomImage(w, h, 7)
        val ref = reference(src, w, h, radius = 3)
        assertNotEquals(ref.toList(), tiled(src, w, h, radius = 3, pad = 0).toList())
    }

    @Test
    fun outputScaleTwoIsBoxDownsampleOfFour() {
        val (w, h) = 150 to 140
        val src = randomImage(w, h, 3)
        val full = tiled(src, w, h, radius = 1, pad = 2, outScale = 4)
        val expected = IntArray(w * 2 * h * 2)
        Downscale.box(full, w * 4, h * 4, 2, expected)
        assertArrayEquals(expected, tiled(src, w, h, radius = 1, pad = 2, outScale = 2))
    }
}
