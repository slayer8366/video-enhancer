package io.github.slayer8366.enhancer.video

import java.nio.ByteBuffer

/** Y'CbCr matrix coefficients (Kr, Kb) from ITU-R BT.601, BT.709 and BT.2020. */
enum class YuvMatrix(val kr: Double, val kb: Double) {
    BT601(0.299, 0.114),
    BT709(0.2126, 0.0722),
    BT2020(0.2627, 0.0593),
}

/** One plane of a YUV_420_888 image, mirroring android.media.Image.Plane so it can be unit-tested. */
class PlaneView(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

/**
 * 8-bit 4:2:0 Y'CbCr <-> R'G'B' conversion. No transfer-function change happens:
 * both sides are gamma-encoded SDR, which is what BT.601/BT.709 video carries.
 *
 * Chroma siting follows the H.264/HEVC default (chroma_sample_loc_type 0):
 * horizontally co-sited with even luma columns, vertically between luma rows.
 */
class YuvConverter(matrix: YuvMatrix, private val fullRange: Boolean) {
    private val kr = matrix.kr
    private val kb = matrix.kb
    private val kg = 1.0 - kr - kb

    // Limited range: Y in [16,235], C in [16,240]. Full range: Y in [0,255], C in [0,255].
    private val yScale = if (fullRange) 1.0 else 219.0 / 255.0
    private val cScale = if (fullRange) 1.0 else 224.0 / 255.0
    private val yOff = if (fullRange) 0.0 else 16.0

    // Decode coefficients (Y'CbCr -> R'G'B'), operating on 0-255 code values.
    private val dY = 1.0 / yScale
    private val dCrR = 2.0 * (1.0 - kr) / cScale
    private val dCbB = 2.0 * (1.0 - kb) / cScale
    private val dCbG = -2.0 * (1.0 - kb) * kb / kg / cScale
    private val dCrG = -2.0 * (1.0 - kr) * kr / kg / cScale

    /**
     * Decodes the visible rectangle ([left], [top], [width], [height]) into opaque ARGB.
     * Chroma is upsampled bilinearly at the siting described above.
     */
    fun toArgb(
        y: PlaneView, u: PlaneView, v: PlaneView,
        left: Int, top: Int, width: Int, height: Int, out: IntArray,
    ) {
        val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
        val cw = (left + width + 1) / 2
        val ch = (top + height + 1) / 2
        for (row in 0 until height) {
            val ly = top + row
            // Vertical chroma position: between rows -> cy = (ly - 0.5) / 2.
            val cyf = (ly - 0.5) / 2.0
            val cy0 = floorClamp(cyf, ch)
            val cy1 = minOf(cy0 + 1, ch - 1)
            val fy = (cyf - cy0).coerceIn(0.0, 1.0)
            for (col in 0 until width) {
                val lx = left + col
                // Horizontal chroma position: co-sited with even columns -> cx = lx / 2.
                val cxf = lx / 2.0
                val cx0 = floorClamp(cxf, cw)
                val cx1 = minOf(cx0 + 1, cw - 1)
                val fx = cxf - cx0
                val yy = (yb.get(ly * y.rowStride + lx * y.pixelStride).toInt() and 0xFF) - yOff
                val cb = bilerp(ub, u, cx0, cx1, cy0, cy1, fx, fy) - 128.0
                val cr = bilerp(vb, v, cx0, cx1, cy0, cy1, fx, fy) - 128.0
                val r = dY * yy + dCrR * cr
                val g = dY * yy + dCbG * cb + dCrG * cr
                val b = dY * yy + dCbB * cb
                out[row * width + col] = (0xFF shl 24) or (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)
            }
        }
    }

    /**
     * Encodes [rows] rows of ARGB (row stride [width]) into the planes starting at
     * luma row [y0]. [y0] and [rows] must be even.
     * Chroma uses a [1,2,1] horizontal filter centred on even columns and a
     * two-row vertical average, matching the siting used by [toArgb].
     */
    fun fromArgb(
        argb: IntArray, width: Int, rows: Int, y0: Int,
        y: PlaneView, u: PlaneView, v: PlaneView,
    ) {
        require(y0 % 2 == 0 && rows % 2 == 0) { "4:2:0 needs even row offsets and counts" }
        val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
        for (row in 0 until rows) {
            val base = row * width
            val yRow = (y0 + row) * y.rowStride
            for (col in 0 until width) {
                val p = argb[base + col]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val luma = kr * r + kg * g + kb * b
                yb.put(yRow + col * y.pixelStride, clamp8(luma * yScale + yOff).toByte())
            }
        }
        val cw = (width + 1) / 2
        for (crow in 0 until rows / 2) {
            val r0 = 2 * crow * width
            val r1 = r0 + width
            val cy = y0 / 2 + crow
            for (cx in 0 until cw) {
                var rs = 0.0; var gs = 0.0; var bs = 0.0
                for (dx in -1..1) {
                    val x = (2 * cx + dx).coerceIn(0, width - 1)
                    val wgt = if (dx == 0) 2.0 else 1.0
                    val p0 = argb[r0 + x]
                    val p1 = argb[r1 + x]
                    rs += wgt * (((p0 shr 16) and 0xFF) + ((p1 shr 16) and 0xFF))
                    gs += wgt * (((p0 shr 8) and 0xFF) + ((p1 shr 8) and 0xFF))
                    bs += wgt * ((p0 and 0xFF) + (p1 and 0xFF))
                }
                rs /= 8.0; gs /= 8.0; bs /= 8.0
                val luma = kr * rs + kg * gs + kb * bs
                val cb = (bs - luma) / (2.0 * (1.0 - kb))
                val cr = (rs - luma) / (2.0 * (1.0 - kr))
                ub.put(cy * u.rowStride + cx * u.pixelStride, clamp8(cb * cScale + 128.0).toByte())
                vb.put(cy * v.rowStride + cx * v.pixelStride, clamp8(cr * cScale + 128.0).toByte())
            }
        }
    }

    private fun bilerp(
        buf: ByteBuffer, p: PlaneView, x0: Int, x1: Int, y0: Int, y1: Int, fx: Double, fy: Double,
    ): Double {
        fun at(x: Int, y: Int) = (buf.get(y * p.rowStride + x * p.pixelStride).toInt() and 0xFF).toDouble()
        val top = at(x0, y0) * (1 - fx) + at(x1, y0) * fx
        val bot = at(x0, y1) * (1 - fx) + at(x1, y1) * fx
        return top * (1 - fy) + bot * fy
    }

    private fun floorClamp(f: Double, n: Int): Int = kotlin.math.floor(f).toInt().coerceIn(0, n - 1)

    companion object {
        fun clamp8(v: Double): Int {
            val r = (v + 0.5).toInt()
            return if (v < 0) 0 else if (r > 255) 255 else r
        }
    }
}
