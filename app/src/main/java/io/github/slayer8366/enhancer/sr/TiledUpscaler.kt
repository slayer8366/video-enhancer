package io.github.slayer8366.enhancer.sr

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Random access to source pixels as non-premultiplied ARGB. */
interface PixelSource {
    val width: Int
    val height: Int

    /** Reads the rectangle into [dst] (row stride = [w]). The rectangle is always inside the image. */
    fun read(x: Int, y: Int, w: Int, h: Int, dst: IntArray)
}

class IntArraySource(private val pixels: IntArray, override val width: Int, override val height: Int) : PixelSource {
    override fun read(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
        for (r in 0 until h) System.arraycopy(pixels, (y + r) * width + x, dst, r * w, w)
    }
}

/** Receives finished output rows top to bottom. [pixels] holds [rows] rows of width [width]. */
fun interface RowSink {
    fun accept(pixels: IntArray, width: Int, rows: Int)
}

/** Per-tile timing, reported after every model call. */
data class TileProgress(val done: Long, val total: Long, val lastTileNanos: Long)

/**
 * Runs a fixed-input SR model over an image of any size, one horizontal band at
 * a time. Peak memory is one input band (width x tile) plus one output band
 * (width*scale x core*scale), so the output size is not limited by the heap.
 */
class TiledUpscaler(private val engine: SrEngine, private val pad: Int) {

    val tile = engine.tile
    val modelScale = engine.scale

    suspend fun run(
        src: PixelSource,
        outScale: Int,
        sink: RowSink,
        onTile: (TileProgress) -> Unit = {},
    ) {
        require(modelScale % outScale == 0) { "output scale $outScale must divide model scale $modelScale" }
        val factor = modelScale / outScale
        val w = src.width
        val h = src.height
        val rows = TilePlanner.plan(h, tile, pad)
        val cols = TilePlanner.plan(w, tile, pad)
        val total = rows.size.toLong() * cols.size
        var done = 0L

        val band = IntArray(w * minOf(tile, h))
        val input = FloatArray(tile * tile * 3)
        val outTile = tile * modelScale
        val core = tile - 2 * pad
        val outBand = IntArray(w * modelScale * core * modelScale)
        val scaledBand = if (factor > 1) IntArray((w * outScale) * core * outScale) else outBand

        for (r in rows) {
            val bandRows = minOf(tile, h - r.windowStart)
            src.read(0, r.windowStart, w, bandRows, band)
            for (c in cols) {
                currentCoroutineContext().ensureActive()
                TensorPacking.packWindow(band, w, bandRows, c.windowStart, 0, tile, input)
                val t0 = System.nanoTime()
                val out = engine.run(input)
                val dt = System.nanoTime() - t0
                TensorPacking.unpackRegion(
                    out, outTile,
                    ox = c.offsetInWindow * modelScale, oy = r.offsetInWindow * modelScale,
                    w = c.length * modelScale, h = r.length * modelScale,
                    dst = outBand, dstWidth = w * modelScale, dx = c.start * modelScale, dy = 0,
                )
                onTile(TileProgress(++done, total, dt))
            }
            val outRows = r.length * modelScale
            if (factor > 1) {
                Downscale.box(outBand, w * modelScale, outRows, factor, scaledBand)
                sink.accept(scaledBand, w * outScale, outRows / factor)
            } else {
                sink.accept(outBand, w * modelScale, outRows)
            }
        }
    }
}
