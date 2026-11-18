package com.pockettoolbox.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.pockettoolbox.app.model.LocalMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SupportedMediaFormats {
    val audioExtensions = linkedSetOf(
        "mp3", "m4a", "aac", "flac", "wav", "wave", "ogg", "oga", "opus",
        "amr", "ac3", "eac3", "mka", "aiff", "aif", "ape", "wma", "mid", "midi",
    )
    val videoExtensions = linkedSetOf(
        "mp4", "m4v", "mkv", "webm", "mov", "ts", "m2ts", "mts", "3gp", "3g2",
        "avi", "flv", "mpg", "mpeg", "vob", "ogv", "wmv", "asf",
    )
    val allExtensions: Set<String> = audioExtensions + videoExtensions
    val defaultExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus",
        "mp4", "m4v", "mkv", "webm", "mov", "ts", "3gp",
    )
}

class LocalMediaRepository(private val context: Context) {
    suspend fun inspect(uri: Uri, fallbackName: String? = null): LocalMediaItem? = withContext(Dispatchers.IO) {
        inspectBlocking(uri, fallbackName)
    }

    suspend fun scanDirectory(treeUri: Uri, enabledExtensions: Set<String>): List<LocalMediaItem> =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
            root.listFiles()
                .asSequence()
                .filter(DocumentFile::isFile)
                .filter { file -> file.extension() in enabledExtensions }
                .take(MAX_SCAN_ITEMS)
                .mapNotNull { file -> inspectBlocking(file.uri, file.name) }
                .sortedWith(compareByDescending<LocalMediaItem> { it.isVideo }.thenBy { it.name.lowercase() })
                .toList()
        }

    private fun inspectBlocking(uri: Uri, fallbackName: String?): LocalMediaItem? {
        val resolver = context.contentResolver
        var name = fallbackName.orEmpty()
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty().ifBlank { name }
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
        }
        if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/') ?: "未命名媒体"
        val extension = name.substringAfterLast('.', "").trim().lowercase()
        val mimeType = resolver.getType(uri)
        var duration = 0L
        var width = 0
        var height = 0
        var metadataHasVideo = false
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                metadataHasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            } finally {
                retriever.release()
            }
        }
        val isVideo = metadataHasVideo || mimeType?.startsWith("video/") == true ||
            extension in SupportedMediaFormats.videoExtensions
        if (
            extension !in SupportedMediaFormats.allExtensions &&
            mimeType?.startsWith("audio/") != true && mimeType?.startsWith("video/") != true
        ) return null
        return LocalMediaItem(
            uri = uri.toString(),
            name = name,
            extension = extension,
            mimeType = mimeType,
            sizeBytes = size.coerceAtLeast(0L),
            durationMillis = duration,
            width = width,
            height = height,
            isVideo = isVideo,
        )
    }

    private fun DocumentFile.extension(): String = name?.substringAfterLast('.', "")?.lowercase().orEmpty()

    private companion object {
        const val MAX_SCAN_ITEMS = 2_000
    }
}
