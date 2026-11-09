package io.github.slayer8366.enhancer.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.slayer8366.enhancer.jobs.EnhanceService
import io.github.slayer8366.enhancer.jobs.JobPhase
import io.github.slayer8366.enhancer.jobs.JobRepository
import io.github.slayer8366.enhancer.jobs.JobState
import io.github.slayer8366.enhancer.jobs.MediaKind
import io.github.slayer8366.enhancer.media.MediaItem
import io.github.slayer8366.enhancer.media.ThumbnailCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun EnhancerTheme(content: @Composable () -> Unit) {
    val scheme = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(LocalContext.current) else darkColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun Thumbnail(item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bmp: ImageBitmap? by produceState<ImageBitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { ThumbnailCache.get(context, item)?.asImageBitmap() }
    }
    Box(modifier.background(Color(0xFF1C1C1C))) {
        bmp?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        Row(Modifier.align(Alignment.BottomStart).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (item.kind == MediaKind.VIDEO) Badge(formatClock(item.durationMs))
            if (item.enhanced) Badge("AI", MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
fun Badge(text: String, color: Color = Color(0xCC000000)) {
    Text(
        text, color = Color.White, fontSize = 11.sp,
        modifier = Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.padding(top = 6.dp))
            content()
        }
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun JobCard(s: JobState, onOpenOutput: (() -> Unit)? = null) {
    SectionCard(s.request.sourceName) {
        Text(
            when (s.phase) {
                JobPhase.QUEUED -> "Queued"
                JobPhase.PREPARING -> "Loading model…"
                JobPhase.RUNNING -> "Running"
                JobPhase.FINISHING -> "Finishing file"
                JobPhase.DONE -> "Done"
                JobPhase.FAILED -> "Failed"
                JobPhase.CANCELLED -> "Cancelled"
            },
            color = if (s.phase == JobPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        if (s.active) {
            LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }
        Mono(
            buildString {
                append("%.2f%%".format(s.fraction * 100))
                if (s.request.kind == MediaKind.VIDEO && s.framesTotal > 0) append("  frame ${s.framesDone + 1}/~${s.framesTotal}")
                if (s.tilesTotal > 0) append("  tile ${s.tilesDone}/${s.tilesTotal}")
            },
        )
        s.backend?.let { KeyValue("Backend", it) }
        s.outputSize?.let { KeyValue("Output", it) }
        if (s.msPerTile > 0) KeyValue("Model time", "%.1f ms / tile".format(s.msPerTile))
        s.etaMs?.takeIf { s.active && it > 0 }?.let { KeyValue("Remaining", "≈ " + EnhanceService.formatDuration(it)) }
        if (s.finishedAtMs > 0 && s.startedAtMs > 0) KeyValue("Took", EnhanceService.formatDuration(s.finishedAtMs - s.startedAtMs))
        s.thermal?.takeIf { s.active }?.let { KeyValue("Thermal", it) }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        s.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        Row {
            if (s.active) TextButton(onClick = { JobRepository.cancel(s.request.id) }) { Text("Cancel") }
            if (s.phase == JobPhase.DONE && onOpenOutput != null) OutlinedButton(onClick = onOpenOutput) { Text("Open result") }
        }
    }
}

@Composable
fun Mono(text: String) = Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)

fun formatClock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    else -> "%.0f KB".format(b / 1024.0)
}

fun megapixels(w: Long, h: Long) = "%.1f MP".format(w * h / 1e6)
