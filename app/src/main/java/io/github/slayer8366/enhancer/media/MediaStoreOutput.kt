package io.github.slayer8366.enhancer.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/** New files go next to the gallery as separate items; originals are never modified. */
object MediaStoreOutput {
    const val FOLDER = "Video Enhancer"
    val photoDir = "${Environment.DIRECTORY_PICTURES}/$FOLDER"
    val videoDir = "${Environment.DIRECTORY_MOVIES}/$FOLDER"

    fun createPhoto(context: Context, displayName: String, mime: String = "image/png"): Uri =
        insert(context, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), displayName, mime, photoDir)

    fun createVideo(context: Context, displayName: String, mime: String = "video/mp4"): Uri =
        insert(context, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), displayName, mime, videoDir)

    private fun insert(context: Context, collection: Uri, name: String, mime: String, dir: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(collection, values)
            ?: error("MediaStore refused to create $dir/$name")
    }

    fun publish(context: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    fun discard(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    fun isEnhancedPath(relativePath: String?): Boolean =
        relativePath != null && (relativePath.startsWith("$photoDir/") || relativePath.startsWith("$videoDir/") ||
            relativePath == photoDir || relativePath == videoDir)
}
