package io.github.slayer8366.enhancer.sr

import kotlin.math.min

/**
 * One tile along one axis.
 *
 * [start] and [length] describe the *core*: the source pixels whose upscaled
 * result is kept. [windowStart] is where the model's fixed-size input window
 * begins. The window is `tile` pixels long; when the image is shorter than one
 * tile the window runs past the edge and is filled by clamping to the last pixel.
 */
data class Span(val start: Int, val length: Int, val windowStart: Int) {
    /** Offset of the core inside the window, in source pixels. */
    val offsetInWindow: Int get() = start - windowStart
}

/**
 * Splits an axis into tiles for a model with a fixed square input.
 *
 * Interior tiles keep [pad] pixels of real context on both sides of the core.
 * At the image borders the window is shifted inward instead of being padded with
 * invented pixels, so every window contains as much real image as possible.
 * This is the same algorithm as the Python reference used to measure seams
 * (docs/MODELS.md): with the per-model padding chosen there, the tiled result
 * matches a wide-context reference to within the figures reported.
 */
object TilePlanner {
    fun plan(extent: Int, tile: Int, pad: Int): List<Span> {
        require(extent > 0) { "extent must be positive" }
        require(pad >= 0 && tile > 2 * pad) { "tile ($tile) must exceed 2 * pad ($pad)" }
        val core = tile - 2 * pad
        val spans = ArrayList<Span>((extent + core - 1) / core)
        var x = 0
        while (x < extent) {
            val len = min(core, extent - x)
            val window = if (extent >= tile) (x - pad).coerceIn(0, extent - tile) else 0
            spans += Span(x, len, window)
            x += core
        }
        return spans
    }

    /** Model invocations needed for one image of this size. */
    fun tileCount(width: Int, height: Int, tile: Int, pad: Int): Long {
        val core = tile - 2 * pad
        return ((width + core - 1) / core).toLong() * ((height + core - 1) / core)
    }
}
