package io.github.slayer8366.enhancer.sr

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer

enum class AcceleratorPref(val label: String) {
    AUTO("Auto (GPU, else CPU)"),
    GPU("GPU only"),
    CPU("CPU only"),
}

enum class GpuPrecision(val label: String, val litert: CompiledModel.GpuOptions.Precision) {
    FP32("FP32 (exact)", CompiledModel.GpuOptions.Precision.FP32),
    FP16("FP16 (faster)", CompiledModel.GpuOptions.Precision.FP16),
}

/**
 * LiteRT 2.x CompiledModel runner. GPU and CPU are tried as separate, single-
 * accelerator compilations so [backendLabel] states where inference really runs,
 * rather than a set that might fall back silently.
 *
 * Not thread-safe: create, use and close it on one thread.
 */
class LiteRtEngine private constructor(
    private val env: Environment,
    private val model: CompiledModel,
    override val backendLabel: String,
    override val tile: Int,
    override val scale: Int,
) : SrEngine {

    private val inputs: List<TensorBuffer> = model.createInputBuffers()
    private val outputs: List<TensorBuffer> = model.createOutputBuffers()

    override fun run(input: FloatArray): FloatArray {
        inputs[0].writeFloat(input)
        model.run(inputs, outputs)
        return outputs[0].readFloat()
    }

    override fun close() {
        closeModel()
        env.close()
    }

    private fun closeModel() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
    }

    companion object {
        private const val TAG = "LiteRtEngine"

        fun open(
            context: Context,
            spec: ModelSpec,
            pref: AcceleratorPref,
            precision: GpuPrecision,
            cpuThreads: Int = Runtime.getRuntime().availableProcessors(),
        ): LiteRtEngine {
            val env = Environment.create(context)
            val attempts = when (pref) {
                AcceleratorPref.AUTO -> listOf(Accelerator.GPU, Accelerator.CPU)
                AcceleratorPref.GPU -> listOf(Accelerator.GPU)
                AcceleratorPref.CPU -> listOf(Accelerator.CPU)
            }
            val errors = StringBuilder()
            for (acc in attempts) {
                var model: CompiledModel? = null
                var engine: LiteRtEngine? = null
                try {
                    val options = CompiledModel.Options(acc)
                    val label = when (acc) {
                        Accelerator.GPU -> {
                            options.gpuOptions = CompiledModel.GpuOptions(precision = precision.litert)
                            "GPU (${precision.name})"
                        }
                        else -> {
                            options.cpuOptions = CompiledModel.CpuOptions(numThreads = cpuThreads)
                            "CPU ($cpuThreads threads, FP32)"
                        }
                    }
                    model = CompiledModel.create(context.assets, spec.assetPath, options, env)
                    engine = LiteRtEngine(env, model, label, spec.tile, spec.scale)
                    // Fail fast if the compiled graph cannot actually execute on this backend.
                    engine.run(FloatArray(spec.tile * spec.tile * 3))
                    return engine
                } catch (t: Throwable) {
                    runCatching { engine?.closeModel() ?: model?.close() }
                    Log.w(TAG, "${spec.id} on $acc failed", t)
                    errors.append("$acc: ${t.message}\n")
                }
            }
            env.close()
            throw IllegalStateException("Could not run ${spec.name} on ${pref.label}:\n$errors")
        }

        fun availableAccelerators(context: Context): Set<Accelerator> =
            Environment.create(context).use { it.getAvailableAccelerators() }
    }
}
