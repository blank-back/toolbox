package com.pockettoolbox.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

internal fun directFileFromUri(uri: Uri): File? = uri
    .takeIf { it.scheme.equals("file", ignoreCase = true) }
    ?.path
    ?.let(::File)

internal fun directoryDocumentFile(context: Context, uri: Uri): DocumentFile? =
    directFileFromUri(uri)?.let(DocumentFile::fromFile)
        ?: DocumentFile.fromTreeUri(context, uri)

internal fun openInputStreamCompat(context: Context, uri: Uri): InputStream? =
    directFileFromUri(uri)?.takeIf(File::isFile)?.inputStream()
        ?: context.contentResolver.openInputStream(uri)

internal fun openOutputStreamCompat(context: Context, uri: Uri, mode: String = "w"): OutputStream? {
    val directFile = directFileFromUri(uri)
    if (directFile != null) {
        directFile.parentFile?.mkdirs()
        return FileOutputStream(directFile, mode == "a" || mode == "wa")
    }
    return context.contentResolver.openOutputStream(uri, mode)
}

internal fun queryNameAndSizeCompat(context: Context, uri: Uri): Pair<String, Long> {
    directFileFromUri(uri)?.let { file -> return file.name to file.length() }
    var name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
    var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
    return name.ifBlank { "未命名文件" } to size
}
