package io.github.slayer8366.enhancer.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import io.github.slayer8366.enhancer.jobs.MediaKind

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val kind: MediaKind,
    val name: String,
    val mime: String?,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAdded: Long,
    val relativePath: String?,
) {
    val enhanced: Boolean get() = MediaStoreOutput.isEnhancedPath(relativePath)

    /** Size as displayed (MediaStore stores pre-rotation dimensions for images). */
    val displayWidth: Int get() = if (orientation % 180 == 90) height else width
    val displayHeight: Int get() = if (orientation % 180 == 90) width else height
}

object MediaRepository {
    private val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.ORIENTATION,
        MediaStore.MediaColumns.DURATION,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.RELATIVE_PATH,
    )

    fun query(context: Context): List<MediaItem> {
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val out = ArrayList<MediaItem>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            projection, selection, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(projection[0])
            val iType = c.getColumnIndexOrThrow(projection[1])
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val video = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id),
                    kind = if (video) MediaKind.VIDEO else MediaKind.PHOTO,
                    name = c.getString(2) ?: "item_$id",
                    mime = c.getString(3),
                    width = c.getInt(4),
                    height = c.getInt(5),
                    orientation = c.getInt(6),
                    durationMs = c.getLong(7),
                    sizeBytes = c.getLong(8),
                    dateAdded = c.getLong(9),
                    relativePath = c.getString(10),
                )
            }
        }
        return out
    }
}

/** MediaStore's own cached thumbnails, held in a small byte-bounded LRU. */
object ThumbnailCache {
    private val cache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }

    fun get(context: Context, item: MediaItem, px: Int = 320): Bitmap? {
        cache.get(item.id)?.let { return it }
        val bmp = runCatching { context.contentResolver.loadThumbnail(item.uri, Size(px, px), null) }.getOrNull()
        if (bmp != null) cache.put(item.id, bmp)
        return bmp
    }
}
