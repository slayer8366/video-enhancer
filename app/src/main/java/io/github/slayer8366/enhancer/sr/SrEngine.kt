package io.github.slayer8366.enhancer.sr

/** A super-resolution model with a fixed square input of [tile] pixels and integer [scale]. */
interface SrEngine : AutoCloseable {
    val tile: Int
    val scale: Int

    /** Human-readable description of where inference actually runs, e.g. "GPU (FP32)". */
    val backendLabel: String

    /** [input] is NHWC RGB float32 in [0,1], length tile*tile*3. Returns (tile*scale)^2*3 floats. */
    fun run(input: FloatArray): FloatArray
}
