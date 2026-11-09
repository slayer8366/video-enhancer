package io.github.slayer8366.enhancer.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random

class PngStreamWriterTest {

    private fun encode(w: Int, h: Int, px: IntArray, band: Int, text: Map<String, String> = emptyMap()): ByteArray {
        val bytes = ByteArrayOutputStream()
        PngStreamWriter(bytes, w, h, text).use { png ->
            var y = 0
            while (y < h) {
                val rows = minOf(band, h - y)
                png.writeRows(px.copyOfRange(y * w, (y + rows) * w), w, rows)
                y += rows
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun decodesPixelExactWithJdkDecoder() {
        val r = Random(42)
        for ((w, h) in listOf(1 to 1, 7 to 3, 333 to 211)) {
            // Mix of noise (exercises filters on hard content) and gradients (smooth content).
            val px = IntArray(w * h) { i ->
                val x = i % w; val y = i / w
                if ((x / 16 + y / 16) % 2 == 0) (0xFF shl 24) or r.nextInt(0x1000000)
                else (0xFF shl 24) or ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or ((x + y) and 0xFF)
            }
            for (band in listOf(1, 5, h)) {
                val img = ImageIO.read(ByteArrayInputStream(encode(w, h, px, band)))
                assertEquals(w, img.width)
                assertEquals(h, img.height)
                for (y in 0 until h) for (x in 0 until w) {
                    assertEquals("($x,$y) w=$w band=$band", px[y * w + x], img.getRGB(x, y))
                }
            }
        }
    }

    @Test
    fun writesUtf8TextChunks() {
        val text = mapOf("Software" to "Video Enhancer", "Warning" to "AI-generated pixels: µ test")
        val bytes = encode(4, 4, IntArray(16) { 0xFF102030.toInt() }, 4, text)
        val s = String(bytes, Charsets.ISO_8859_1)
        assertTrue(s.contains("iTXt"))
        assertTrue(String(bytes, Charsets.UTF_8).contains("AI-generated pixels: µ test"))
        // The JDK decoder must still accept the file.
        assertEquals(4, ImageIO.read(ByteArrayInputStream(bytes)).width)
    }

    @Test(expected = IllegalStateException::class)
    fun refusesToCloseIncompleteImage() {
        PngStreamWriter(ByteArrayOutputStream(), 4, 4).use { it.writeRows(IntArray(4), 4, 1) }
    }
}
