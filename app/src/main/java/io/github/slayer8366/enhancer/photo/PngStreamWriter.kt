package io.github.slayer8366.enhancer.photo

import java.io.Closeable
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

/**
 * Writes an 8-bit RGB PNG row by row, so an image of any size can be produced
 * without holding it in memory (Bitmap.compress needs the whole bitmap).
 *
 * Text entries are written as uncompressed iTXt (UTF-8). Rows use the adaptive
 * filter choice recommended by the PNG spec (minimum sum of absolute differences).
 */
class PngStreamWriter(
    out: OutputStream,
    val width: Int,
    val height: Int,
    text: Map<String, String> = emptyMap(),
    compressionLevel: Int = 6,
) : Closeable {

    private val raw = DataOutputStream(out)
    private val deflater = Deflater(compressionLevel)
    private val idat = DeflaterOutputStream(IdatStream(), deflater, 1 shl 16)
    private val stride = width * 3
    private var prev = ByteArray(stride)
    private var cur = ByteArray(stride)
    private val candidates = Array(5) { ByteArray(stride) }
    private var rowsWritten = 0
    private var closed = false

    init {
        require(width > 0 && height > 0) { "empty image" }
        raw.write(SIGNATURE)
        chunk("IHDR") {
            it.writeInt(width); it.writeInt(height)
            it.writeByte(8) // bit depth
            it.writeByte(2) // colour type: truecolour RGB
            it.writeByte(0); it.writeByte(0); it.writeByte(0) // deflate, adaptive filtering, no interlace
        }
        // Pixels are sRGB-encoded (inputs are decoded into sRGB before upscaling).
        chunk("sRGB") { it.writeByte(0) } // perceptual intent
        for ((key, value) in text) {
            chunk("iTXt") {
                it.write(key.toByteArray(Charsets.ISO_8859_1)); it.writeByte(0)
                it.writeByte(0); it.writeByte(0) // uncompressed, method 0
                it.writeByte(0) // empty language tag
                it.writeByte(0) // empty translated keyword
                it.write(value.toByteArray(Charsets.UTF_8))
            }
        }
    }

    val isComplete: Boolean get() = rowsWritten == height

    /** Appends [rows] rows of opaque ARGB pixels, [rowWidth] must equal [width]. */
    fun writeRows(pixels: IntArray, rowWidth: Int, rows: Int) {
        require(rowWidth == width) { "row width $rowWidth != image width $width" }
        check(rowsWritten + rows <= height) { "too many rows" }
        for (r in 0 until rows) {
            var o = 0
            var i = r * rowWidth
            for (x in 0 until width) {
                val p = pixels[i++]
                cur[o++] = (p shr 16).toByte()
                cur[o++] = (p shr 8).toByte()
                cur[o++] = p.toByte()
            }
            writeFiltered()
            val t = prev; prev = cur; cur = t
            rowsWritten++
        }
    }

    private fun writeFiltered() {
        var best = 0
        var bestScore = Long.MAX_VALUE
        for (f in 0..4) {
            val dst = candidates[f]
            var score = 0L
            for (i in 0 until stride) {
                val x = cur[i].toInt() and 0xFF
                val a = if (i >= 3) cur[i - 3].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= 3) prev[i - 3].toInt() and 0xFF else 0
                val v = when (f) {
                    0 -> x
                    1 -> x - a
                    2 -> x - b
                    3 -> x - ((a + b) ushr 1)
                    else -> x - paeth(a, b, c)
                }
                val byte = v.toByte()
                dst[i] = byte
                score += abs(byte.toInt())
            }
            if (score < bestScore) { bestScore = score; best = f }
        }
        idat.write(best)
        idat.write(candidates[best], 0, stride)
    }

    override fun close() {
        if (closed) return
        closed = true
        check(rowsWritten == height) { "PNG incomplete: $rowsWritten of $height rows" }
        idat.finish()
        idat.flush()
        deflater.end()
        chunk("IEND") {}
        raw.flush()
    }

    /** Closes without completing; the output is truncated and must be discarded. */
    fun abort() {
        closed = true
        deflater.end()
    }

    private fun chunk(type: String, body: (DataOutputStream) -> Unit) {
        val buf = java.io.ByteArrayOutputStream()
        body(DataOutputStream(buf))
        writeChunk(type, buf.toByteArray(), buf.size())
    }

    private fun writeChunk(type: String, data: ByteArray, len: Int) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data, 0, len)
        raw.writeInt(len)
        raw.write(typeBytes)
        raw.write(data, 0, len)
        raw.writeInt(crc.value.toInt())
    }

    /** Buffers deflated bytes and emits them as IDAT chunks of up to 256 KiB. */
    private inner class IdatStream : OutputStream() {
        private val buf = ByteArray(1 shl 18)
        private var n = 0
        override fun write(b: Int) {
            if (n == buf.size) emit()
            buf[n++] = b.toByte()
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                if (n == buf.size) emit()
                val k = minOf(left, buf.size - n)
                System.arraycopy(b, o, buf, n, k)
                n += k; o += k; left -= k
            }
        }
        override fun flush() { if (n > 0) emit() }
        private fun emit() { writeChunk("IDAT", buf, n); n = 0 }
    }

    companion object {
        private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        fun paeth(a: Int, b: Int, c: Int): Int {
            val p = a + b - c
            val pa = abs(p - a)
            val pb = abs(p - b)
            val pc = abs(p - c)
            return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
        }
    }
}
