package io.github.slayer8366.enhancer.jobs

import android.content.Context
import io.github.slayer8366.enhancer.sr.AcceleratorPref
import io.github.slayer8366.enhancer.sr.GpuPrecision
import io.github.slayer8366.enhancer.sr.ModelSpec
import io.github.slayer8366.enhancer.sr.Models
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VideoCodecPref(val label: String) { HEVC("HEVC (H.265)"), AVC("AVC (H.264)") }

/** Target bits per pixel per frame. HEVC needs roughly two thirds of AVC's rate for similar quality. */
enum class VideoQuality(val label: String, val hevcBpp: Double, val avcBpp: Double) {
    STANDARD("Standard", 0.06, 0.10),
    HIGH("High", 0.12, 0.18),
    ARCHIVAL("Archival", 0.25, 0.35),
}

data class EnhanceSettings(
    val accelerator: AcceleratorPref = AcceleratorPref.AUTO,
    val gpuPrecision: GpuPrecision = GpuPrecision.FP32,
    /** Per-model tile overlap override in px; absent = model default. */
    val overlap: Map<String, Int> = emptyMap(),
    val videoCodec: VideoCodecPref = VideoCodecPref.HEVC,
    val videoQuality: VideoQuality = VideoQuality.HIGH,
) {
    fun overlapFor(spec: ModelSpec): Int = (overlap[spec.id] ?: spec.defaultPad).coerceIn(0, spec.tile / 2 - 1)
}

object SettingsStore {
    private const val PREFS = "settings"
    private val state = MutableStateFlow(EnhanceSettings())
    val settings: StateFlow<EnhanceSettings> = state.asStateFlow()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        state.value = EnhanceSettings(
            accelerator = enumOr(p.getString("accelerator", null), AcceleratorPref.AUTO),
            gpuPrecision = enumOr(p.getString("gpuPrecision", null), GpuPrecision.FP32),
            overlap = Models.all.mapNotNull { m ->
                p.getInt("overlap_${m.id}", -1).takeIf { it >= 0 }?.let { m.id to it }
            }.toMap(),
            videoCodec = enumOr(p.getString("videoCodec", null), VideoCodecPref.HEVC),
            videoQuality = enumOr(p.getString("videoQuality", null), VideoQuality.HIGH),
        )
        loaded = true
    }

    fun update(context: Context, transform: (EnhanceSettings) -> EnhanceSettings) {
        val s = transform(state.value)
        state.value = s
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("accelerator", s.accelerator.name)
            putString("gpuPrecision", s.gpuPrecision.name)
            Models.all.forEach { m ->
                val v = s.overlap[m.id]
                if (v == null) remove("overlap_${m.id}") else putInt("overlap_${m.id}", v)
            }
            putString("videoCodec", s.videoCodec.name)
            putString("videoQuality", s.videoQuality.name)
        }.apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}

/** Last Lab benchmark per model (median ms per tile and the backend it ran on), used for estimates. */
data class BenchRecord(val medianMs: Double, val backend: String)

object BenchStore {
    private const val PREFS = "bench"
    private val state = MutableStateFlow<Map<String, BenchRecord>>(emptyMap())
    val results: StateFlow<Map<String, BenchRecord>> = state.asStateFlow()

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        state.value = Models.all.mapNotNull { m ->
            val ms = p.getFloat("${m.id}_ms", -1f)
            if (ms > 0) m.id to BenchRecord(ms.toDouble(), p.getString("${m.id}_backend", "") ?: "") else null
        }.toMap()
    }

    fun put(context: Context, modelId: String, record: BenchRecord) {
        state.value = state.value + (modelId to record)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("${modelId}_ms", record.medianMs.toFloat())
            .putString("${modelId}_backend", record.backend)
            .apply()
    }
}
