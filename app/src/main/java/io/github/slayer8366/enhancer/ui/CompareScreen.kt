package io.github.slayer8366.enhancer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.slayer8366.enhancer.jobs.HistoryRecord
import io.github.slayer8366.enhancer.jobs.MediaKind
import io.github.slayer8366.enhancer.photo.PhotoSources
import io.github.slayer8366.enhancer.sr.Models
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Loupe(val original: ImageBitmap, val enhanced: ImageBitmap, val x: Int, val y: Int, val size: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(vm: AppViewModel, r: HistoryRecord) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var split by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var nearest by remember { mutableStateOf(true) }
    var loupe by remember { mutableStateOf<Loupe?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Zoom is about the view centre (graphicsLayer's default origin), so the centroid is unused.
    val transform = rememberTransformableState { _, z, p, _ ->
        zoom = (zoom * z).coerceIn(1f, 40f)
        pan += p
    }
    val spec = Models.byId(r.modelId)

    // Video: compare a single frame at a chosen time.
    var timeFrac by remember { mutableFloatStateOf(0.5f) }
    var frames by remember { mutableStateOf<Pair<ImageBitmap, ImageBitmap>?>(null) }
    if (r.kind == MediaKind.VIDEO) {
        LaunchedEffect(timeFrac) {
            frames = withContext(Dispatchers.IO) {
                runCatching { videoFrames(context, r, timeFrac) }.onFailure { error = it.message }.getOrNull()
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Original  |  ${spec.name}", maxLines = 1) },
            navigationIcon = { IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { p ->
        Column(Modifier.padding(p).fillMaxSize()) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f).background(Color.Black)
                    .transformable(transform)
                    .pointerInput(r, zoom, pan) {
                        if (r.kind != MediaKind.PHOTO) return@pointerInput
                        detectTapGestures { tap ->
                            // Undo the zoom/pan (scale about the centre), then the Fit mapping.
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val q = c + (tap - pan - c) / zoom
                            val f = minOf(size.width / r.outWidth.toFloat(), size.height / r.outHeight.toFloat())
                            val left = (size.width - r.outWidth * f) / 2f
                            val top = (size.height - r.outHeight * f) / 2f
                            val ix = ((q.x - left) / f).toInt()
                            val iy = ((q.y - top) / f).toInt()
                            if (ix in 0 until r.outWidth && iy in 0 until r.outHeight) scope.launch {
                                loupe = withContext(Dispatchers.IO) {
                                    runCatching { loupeAt(context, r, ix, iy) }.onFailure { error = it.message }.getOrNull()
                                }
                            }
                        }
                    },
            ) {
                val layer = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y
                }
                val fq = if (nearest) FilterQuality.None else FilterQuality.Medium
                if (r.kind == MediaKind.PHOTO) {
                    // Both decoded to the same bounded size so they align; the loupe shows true pixels.
                    AsyncImage(ImageRequest.Builder(context).data(r.source).size(4096).build(), "original",
                        layer, contentScale = ContentScale.Fit, filterQuality = fq)
                    Box(Modifier.fillMaxSize().drawWithContent { clipRect(left = size.width * split) { this@drawWithContent.drawContent() } }) {
                        AsyncImage(ImageRequest.Builder(context).data(r.output).size(4096).build(), "enhanced",
                            layer, contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium)
                    }
                } else {
                    frames?.let { (a, b) ->
                        Image(a, "original frame", layer, contentScale = ContentScale.Fit, filterQuality = fq)
                        Box(Modifier.fillMaxSize().drawWithContent { clipRect(left = size.width * split) { this@drawWithContent.drawContent() } }) {
                            Image(b, "enhanced frame", layer, contentScale = ContentScale.Fit)
                        }
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    val x = size.width * split
                    drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Slider(value = split, onValueChange = { split = it })
                if (r.kind == MediaKind.VIDEO) {
                    Text("Frame time", style = MaterialTheme.typography.labelMedium)
                    Slider(value = timeFrac, onValueChange = { timeFrac = it }, onValueChangeFinished = {})
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(nearest, { nearest = !nearest }, label = { Text(if (nearest) "Original: nearest" else "Original: smooth") })
                    TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("Reset zoom") }
                }
                Text(
                    if (r.kind == MediaKind.PHOTO) "Pinch to zoom. Tap a spot for a 1:1 pixel loupe. Nearest shows the pixels " +
                        "that were actually captured; smooth interpolation can look like detail but isn't."
                    else "Frames are extracted with MediaMetadataRetriever at the nearest decodable time.",
                    style = MaterialTheme.typography.bodySmall,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    loupe?.let { l ->
        AlertDialog(
            onDismissRequest = { loupe = null },
            confirmButton = { TextButton(onClick = { loupe = null }) { Text("Close") } },
            title = { Text("1:1 at (${l.x}, ${l.y}), ${l.size}px") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Original (nearest)", style = MaterialTheme.typography.labelSmall)
                        Image(l.original, null, Modifier.fillMaxWidth().aspectRatio(1f), filterQuality = FilterQuality.None)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(spec.name, style = MaterialTheme.typography.labelSmall)
                        Image(l.enhanced, null, Modifier.fillMaxWidth().aspectRatio(1f), filterQuality = FilterQuality.None)
                    }
                }
            },
        )
    }
}

/** Decodes a square of the output at full resolution and the matching source region. */
private fun loupeAt(context: android.content.Context, r: HistoryRecord, cx: Int, cy: Int): Loupe {
    val s = r.outScale
    // Region size in output pixels, a multiple of the scale so it maps onto whole source pixels.
    val size = (192 / s) * s
    val x = (cx - size / 2).coerceIn(0, maxOf(0, r.outWidth - size)) / s * s
    val y = (cy - size / 2).coerceIn(0, maxOf(0, r.outHeight - size)) / s * s
    val w = minOf(size, r.outWidth - x)
    val h = minOf(size, r.outHeight - y)
    val enhanced = context.contentResolver.openInputStream(r.output)!!.use { stream ->
        val dec = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(stream)
        else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(stream, false)
        try { dec!!.decodeRegion(Rect(x, y, x + w, y + h), BitmapFactory.Options()) } finally { dec?.recycle() }
    }
    val sw = w / s
    val sh = h / s
    val px = IntArray(sw * sh)
    PhotoSources.open(context, r.source, context.contentResolver.getType(r.source)).use { it.read(x / s, y / s, sw, sh, px) }
    val original = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888)
    return Loupe(original.asImageBitmap(), enhanced.asImageBitmap(), x, y, size)
}

private fun videoFrames(context: android.content.Context, r: HistoryRecord, frac: Float): Pair<ImageBitmap, ImageBitmap> {
    fun frame(uri: android.net.Uri, maxW: Int?): Bitmap {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val t = (dur * 1000 * frac).toLong()
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            return if (maxW != null && w > maxW) {
                mmr.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, maxW, (h.toLong() * maxW / w).toInt())
            } else {
                mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
            } ?: error("No frame at ${t / 1000} ms")
        } finally {
            mmr.release()
        }
    }
    return frame(r.source, null).asImageBitmap() to frame(r.output, 3840).asImageBitmap()
}
