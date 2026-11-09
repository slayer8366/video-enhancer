package io.github.slayer8366.enhancer.sr

import android.content.Context
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

data class BenchResult(
    val backend: String,
    val runs: Int,
    val medianMs: Double,
    val p90Ms: Double,
    /** Mean and max absolute difference from the CPU FP32 result, in 8-bit levels. Null when the backend is the CPU. */
    val vsCpuMean: Double?,
    val vsCpuMax: Double?,
)

/**
 * Times one model on this device and, for GPU runs, checks numerical agreement with
 * the CPU reference on the same input. Blocking: call it from one background thread.
 */
object Benchmark {

    /** Deterministic test tile: gradients, hard edges, fine sinusoidal detail and noise. */
    fun testTile(tile: Int): FloatArray {
        val r = Random(1234)
        val a = FloatArray(tile * tile * 3)
        for (y in 0 until tile) for (x in 0 until tile) {
            val i = (y * tile + x) * 3
            val edge = if ((x / 16 + y / 16) % 2 == 0) 0.25f else 0.0f
            val fine = 0.15f * sin(x * 0.9f) * sin(y * 0.7f)
            val n = (r.nextFloat() - 0.5f) * 0.06f
            a[i] = (x / tile.toFloat() * 0.6f + edge + fine + n).coerceIn(0f, 1f)
            a[i + 1] = (y / tile.toFloat() * 0.6f + edge - fine + n).coerceIn(0f, 1f)
            a[i + 2] = (0.4f + edge + n).coerceIn(0f, 1f)
        }
        return a
    }

    fun run(context: Context, spec: ModelSpec, pref: AcceleratorPref, precision: GpuPrecision, runs: Int = 20): BenchResult {
        val input = testTile(spec.tile)
        val times = DoubleArray(runs)
        val out: FloatArray
        val backend: String
        LiteRtEngine.open(context, spec, pref, precision).use { e ->
            backend = e.backendLabel
            repeat(3) { e.run(input) } // warm-up: shader compilation, caches, clocks
            var last = FloatArray(0)
            for (i in 0 until runs) {
                val t0 = System.nanoTime()
                last = e.run(input)
                times[i] = (System.nanoTime() - t0) / 1e6
            }
            out = last
        }
        times.sort()
        var mean: Double? = null
        var max: Double? = null
        if (!backend.startsWith("CPU")) {
            val ref = LiteRtEngine.open(context, spec, AcceleratorPref.CPU, precision).use { it.run(input) }
            var sum = 0.0
            var mx = 0.0
            for (i in ref.indices) {
                val d = abs(TensorPacking.to8(out[i]) - TensorPacking.to8(ref[i])).toDouble()
                sum += d
                if (d > mx) mx = d
            }
            mean = sum / ref.size
            max = mx
        }
        return BenchResult(backend, runs, times[runs / 2], times[(runs * 9) / 10], mean, max)
    }
}
