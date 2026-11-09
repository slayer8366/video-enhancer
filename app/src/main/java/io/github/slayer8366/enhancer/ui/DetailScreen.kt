package io.github.slayer8366.enhancer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.slayer8366.enhancer.jobs.BenchStore
import io.github.slayer8366.enhancer.jobs.EnhanceService
import io.github.slayer8366.enhancer.jobs.History
import io.github.slayer8366.enhancer.jobs.JobRepository
import io.github.slayer8366.enhancer.jobs.JobRequest
import io.github.slayer8366.enhancer.jobs.MediaKind
import io.github.slayer8366.enhancer.jobs.SettingsStore
import io.github.slayer8366.enhancer.jobs.VideoCodecPref
import io.github.slayer8366.enhancer.media.MediaItem
import io.github.slayer8366.enhancer.sr.ModelKind
import io.github.slayer8366.enhancer.sr.Models
import io.github.slayer8366.enhancer.sr.TilePlanner
import io.github.slayer8366.enhancer.video.EncoderSupport
import io.github.slayer8366.enhancer.video.VideoInfo
import io.github.slayer8366.enhancer.video.VideoProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: AppViewModel, item: MediaItem) {
    val context = LocalContext.current
    val settings by SettingsStore.settings.collectAsState()
    val jobs by JobRepository.jobs.collectAsState()
    val history by History.records.collectAsState()
    val benches by BenchStore.results.collectAsState()
    val record = remember(history, item.id) { History.forOutput(item.id) }

    var modelId by rememberSaveable { mutableStateOf(Models.QUICKSRNET_LARGE.id) }
    var outScale by rememberSaveable { mutableIntStateOf(4) }
    val spec = Models.byId(modelId)
    val pad = settings.overlapFor(spec)

    val video: Result<VideoInfo>? by produceState<Result<VideoInfo>?>(null, item.id) {
        if (item.kind == MediaKind.VIDEO) value = withContext(Dispatchers.IO) { runCatching { VideoProbe.probe(context, item.uri) } }
    }
    val srcW = video?.getOrNull()?.width ?: item.displayWidth
    val srcH = video?.getOrNull()?.height ?: item.displayHeight
    val outW = srcW.toLong() * outScale
    val outH = srcH.toLong() * outScale
    val tiles = if (srcW > 0 && srcH > 0) TilePlanner.tileCount(srcW, srcH, spec.tile, pad) else 0L

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(item.name, maxLines = 1) },
            navigationIcon = { IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { p ->
        Column(Modifier.padding(p).fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth().height(300.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                if (item.kind == MediaKind.PHOTO) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(item.uri).size(2048).build(),
                        contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Thumbnail(item, Modifier.fillMaxSize())
                    FilledTonalButton(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mime ?: "video/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    }) { Icon(Icons.Filled.PlayArrow, null); Text("Play") }
                }
            }

            SectionCard("Details") {
                KeyValue("Resolution", "${srcW}x$srcH (${megapixels(srcW.toLong(), srcH.toLong())})")
                KeyValue("Type", item.mime ?: "?")
                KeyValue("Size", formatBytes(item.sizeBytes))
                video?.getOrNull()?.let { v ->
                    KeyValue("Duration", formatClock(v.durationUs / 1000))
                    KeyValue("Frame rate", v.frameRate?.let { "%.3f fps".format(it) } ?: "not signalled (30 assumed)")
                    KeyValue("Frames (est.)", "${v.estimatedFrames}")
                    KeyValue("Codec", v.mime)
                    KeyValue("Audio", v.audioMime ?: "none")
                    if (v.rotation != 0) KeyValue("Rotation", "${v.rotation}° (kept as metadata)")
                    if (v.isHdr) Text("HDR video: not supported yet (would need tone mapping).", color = MaterialTheme.colorScheme.error)
                }
                video?.exceptionOrNull()?.let { Text("Couldn't read video: ${it.message}", color = MaterialTheme.colorScheme.error) }
            }

            record?.let { r ->
                SectionCard("Provenance") {
                    KeyValue("Model", Models.byId(r.modelId).name)
                    KeyValue("Scale", "${r.outScale}x (model ${Models.byId(r.modelId).scale}x)")
                    KeyValue("Backend", r.backend)
                    KeyValue("Tile overlap", "${r.pad} px")
                    KeyValue("Model time", "%.1f ms / tile".format(r.msPerTile))
                    KeyValue("Total time", EnhanceService.formatDuration(r.totalMs))
                    KeyValue("Source", r.sourceName)
                    r.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { vm.push(Screen.Compare(r)) }, Modifier.padding(top = 8.dp)) { Text("Compare with original") }
                }
            }

            jobs.filter { it.request.source == item.uri }.forEach { s ->
                JobCard(s, onOpenOutput = s.output?.let { out ->
                    { vm.media.value.firstOrNull { it.uri.lastPathSegment == out.lastPathSegment }?.let { vm.push(Screen.Detail(it)) } }
                })
            }

            SectionCard("Enhance") {
                Text("Model", style = MaterialTheme.typography.labelLarge)
                Models.all.forEach { m ->
                    val selected = m.id == modelId
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { modelId = m.id },
                        border = BorderStroke(if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                Badge(m.kind.label, if (m.kind == ModelKind.FIDELITY) Color(0xFF2E7D32) else Color(0xFF8E24AA))
                            }
                            Text(m.summary, style = MaterialTheme.typography.bodySmall)
                            Text(m.caveat, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text("Output scale", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                val scales = listOf(1, 2, 4)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    scales.forEachIndexed { i, s ->
                        SegmentedButton(
                            selected = outScale == s, onClick = { outScale = s },
                            shape = SegmentedButtonDefaults.itemShape(i, scales.size),
                        ) { Text(if (s == 1) "1× restore" else "$s×") }
                    }
                }
                Text(
                    "Models always run at 4×; 2× and 1× are area-downsampled from that result.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                KeyValue("Output", "${outW}x$outH (${megapixels(outW, outH)})")
                KeyValue(if (item.kind == MediaKind.VIDEO) "Tiles / frame" else "Tiles", "%,d (overlap %d px)".format(tiles, pad))
                // Prefer a real job's average (includes thermal throttling over time) over the short benchmark.
                val lastRun = history.firstOrNull { it.modelId == spec.id && it.msPerTile > 0 }
                val bench = benches[spec.id]
                val basis = when {
                    lastRun != null -> lastRun.msPerTile to "your last ${spec.name} job: %.1f ms/tile, ${lastRun.backend}".format(lastRun.msPerTile)
                    bench != null -> bench.medianMs to "Lab benchmark: %.1f ms/tile, ${bench.backend}; long jobs may throttle".format(bench.medianMs)
                    else -> null
                }
                if (basis != null && tiles > 0) {
                    val frames = video?.getOrNull()?.estimatedFrames ?: 1L
                    KeyValue("Estimate", "≈ " + EnhanceService.formatDuration((tiles * frames * basis.first).toLong()) +
                        " (model time only; ${basis.second})")
                } else {
                    Text("Run the Lab benchmark for a time estimate on this phone.", style = MaterialTheme.typography.bodySmall)
                }
                if (item.kind == MediaKind.PHOTO) {
                    Text("Saved as a new lossless PNG in Pictures/Video Enhancer, with provenance text in the file.",
                        style = MaterialTheme.typography.bodySmall)
                }

                val v = video?.getOrNull()
                var encoderLine: String? = null
                if (item.kind == MediaKind.VIDEO && v != null && outW > 0) {
                    val ew = (outW - outW % 2).toInt(); val eh = (outH - outH % 2).toInt()
                    val mime = if (settings.videoCodec == VideoCodecPref.HEVC) "video/hevc" else "video/avc"
                    val alt = if (mime == "video/hevc") "video/avc" else "video/hevc"
                    val ok = remember(ew, eh, mime) { EncoderSupport.candidates(mime, ew, eh).firstOrNull() }
                    val okAlt = remember(ew, eh, alt) { if (ok == null) EncoderSupport.candidates(alt, ew, eh).firstOrNull() else null }
                    encoderLine = when {
                        ok != null -> "✓ ${ok.codecName} accepts ${ew}x$eh"
                        okAlt != null -> "✓ ${okAlt.codecName} (${alt.substringAfter('/')}) accepts ${ew}x$eh"
                        else -> "✗ No encoder accepts ${ew}x$eh.\n" + remember(mime) { EncoderSupport.maxSizes(mime) }.joinToString("\n")
                    }
                    Text(encoderLine, style = MaterialTheme.typography.bodySmall,
                        color = if (encoderLine.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }

                val blocked = item.kind == MediaKind.VIDEO && (v == null || v.isHdr || encoderLine?.startsWith("✗") == true)
                Button(
                    enabled = !blocked && srcW > 0,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        JobRepository.enqueue(
                            context,
                            JobRequest(JobRepository.newId(), item.uri, item.name, item.kind, spec.id, outScale),
                        )
                    },
                ) { Text("Enhance with ${spec.name}") }
            }
            Box(Modifier.size(24.dp))
        }
    }
}
