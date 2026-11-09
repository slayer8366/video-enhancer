package io.github.slayer8366.enhancer.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.slayer8366.enhancer.BuildConfig
import io.github.slayer8366.enhancer.jobs.BenchRecord
import io.github.slayer8366.enhancer.jobs.BenchStore
import io.github.slayer8366.enhancer.jobs.SettingsStore
import io.github.slayer8366.enhancer.jobs.VideoCodecPref
import io.github.slayer8366.enhancer.jobs.VideoQuality
import io.github.slayer8366.enhancer.sr.AcceleratorPref
import io.github.slayer8366.enhancer.sr.Benchmark
import io.github.slayer8366.enhancer.sr.GpuPrecision
import io.github.slayer8366.enhancer.sr.LiteRtEngine
import io.github.slayer8366.enhancer.sr.Models
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
private fun <E : Enum<E>> EnumRow(values: List<E>, selected: E, label: (E) -> String, onSelect: (E) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        values.forEachIndexed { i, v ->
            SegmentedButton(selected = v == selected, onClick = { onSelect(v) },
                shape = SegmentedButtonDefaults.itemShape(i, values.size)) { Text(label(v), maxLines = 1) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsStore.settings.collectAsState()
    val bench = remember { mutableStateMapOf<String, String>() }
    val accelerators by produceState("checking…") {
        value = withContext(Dispatchers.Default) {
            runCatching { LiteRtEngine.availableAccelerators(context).joinToString() }.getOrElse { "error: ${it.message}" }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Lab & settings") },
            navigationIcon = { IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { p ->
        Column(Modifier.padding(p).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionCard("This device") {
                KeyValue("Model", "${Build.MANUFACTURER} ${Build.MODEL}")
                if (Build.VERSION.SDK_INT >= 31) KeyValue("SoC", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
                KeyValue("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                KeyValue("LiteRT", "${BuildConfig.LITERT_VERSION}; accelerators: $accelerators")
                KeyValue("CPU cores", "${Runtime.getRuntime().availableProcessors()}")
                KeyValue("Heap limit", formatBytes(Runtime.getRuntime().maxMemory()))
            }

            SectionCard("Inference") {
                Text("Accelerator", style = MaterialTheme.typography.labelLarge)
                EnumRow(AcceleratorPref.entries, settings.accelerator, { it.label }) { v ->
                    SettingsStore.update(context) { it.copy(accelerator = v) }
                }
                Text("GPU precision", style = MaterialTheme.typography.labelLarge)
                EnumRow(GpuPrecision.entries, settings.gpuPrecision, { it.label }) { v ->
                    SettingsStore.update(context) { it.copy(gpuPrecision = v) }
                }
                Text("FP16 is usually faster on phone GPUs but rounds intermediate values. The benchmark below " +
                    "reports how far GPU output drifts from the CPU FP32 result on this device.",
                    style = MaterialTheme.typography.bodySmall)
            }

            SectionCard("Video output") {
                Text("Codec (falls back to the other if unsupported)", style = MaterialTheme.typography.labelLarge)
                EnumRow(VideoCodecPref.entries, settings.videoCodec, { it.label }) { v ->
                    SettingsStore.update(context) { it.copy(videoCodec = v) }
                }
                Text("Quality", style = MaterialTheme.typography.labelLarge)
                EnumRow(VideoQuality.entries, settings.videoQuality, { it.label }) { v ->
                    SettingsStore.update(context) { it.copy(videoQuality = v) }
                }
                Text("Bit rate = bits-per-pixel x width x height x fps, capped only by what the encoder accepts.",
                    style = MaterialTheme.typography.bodySmall)
            }

            Models.all.forEach { m ->
                SectionCard("${m.name} · ${m.kind.label}") {
                    Text(m.summary, style = MaterialTheme.typography.bodySmall)
                    Text(m.caveat, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    KeyValue("Size", "${m.params}, ${m.tile}px input, ${m.scale}x")
                    KeyValue("Source", m.source)
                    KeyValue("License", m.license)
                    val pad = settings.overlapFor(m)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tile overlap: $pad px" + if (pad == m.defaultPad) " (measured default)" else " (default ${m.defaultPad})",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (pad != m.defaultPad) TextButton(onClick = {
                            SettingsStore.update(context) { it.copy(overlap = it.overlap - m.id) }
                        }) { Text("Reset") }
                    }
                    Slider(
                        value = pad.toFloat(), valueRange = 0f..48f, steps = 11,
                        onValueChange = { v -> SettingsStore.update(context) { it.copy(overlap = it.overlap + (m.id to v.roundToInt())) } },
                    )
                    val core = m.tile - 2 * pad
                    Text("Each tile keeps a ${core}px core: %.2fx the compute of no overlap.".format((m.tile.toDouble() / core).let { it * it }),
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        bench[m.id] = "running… (close other jobs for clean numbers)"
                        scope.launch {
                            bench[m.id] = withContext(Dispatchers.Default) {
                                runCatching {
                                    val r = Benchmark.run(context, m, settings.accelerator, settings.gpuPrecision)
                                    BenchStore.put(context, m.id, BenchRecord(r.medianMs, r.backend))
                                    buildString {
                                        append("${r.backend}: median %.2f ms, p90 %.2f ms per tile (%d runs)".format(r.medianMs, r.p90Ms, r.runs))
                                        if (r.vsCpuMean != null) append("\nvs CPU FP32: mean %.3f, max %.0f levels of 255".format(r.vsCpuMean, r.vsCpuMax))
                                    }
                                }.getOrElse { "failed: ${it.message}" }
                            }
                        }
                    }) { Text("Benchmark on this phone") }
                    bench[m.id]?.let { Mono(it) }
                }
            }

            SectionCard("How the defaults were chosen") {
                Text(
                    "PSNR/SSIM on the luma channel, 4x, 4px border crop, Set5/Set14 LR images as distributed " +
                        "(bicubic baseline reproduces the published 28.42 dB on Set5). Seam overlap: tiled output " +
                        "compared with a wide-overlap reference. Full tables and method: docs/MODELS.md in the repository.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
