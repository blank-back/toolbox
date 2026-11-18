package com.pockettoolbox.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"
private val EMULATED_USER_PATH_PATTERN = Regex(
    """(?:/storage/emulated|/mnt/user|/mnt/pass_through|/mnt/runtime/(?:default|read|write|full)/emulated|/data/media)/(\d+)(?:/|\s|$)""",
)

internal fun primaryStorageRootDocumentUri(): Uri =
    DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY, "primary:")

internal fun supportsAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

internal fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

internal fun createAllFilesAccessSettingsIntent(context: Context): Intent {
    val appIntent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    return appIntent.takeIf { it.resolveActivity(context.packageManager) != null }
        ?: Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
}

internal fun directStorageRoot(): File = File("/storage")

internal data class DirectStorageVolume(
    val directory: File,
    val label: String,
)

@Suppress("DEPRECATION")
internal fun directStorageVolumes(context: Context): List<DirectStorageVolume> {
    val volumes = mutableListOf<DirectStorageVolume>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.getSystemService(StorageManager::class.java)
            ?.storageVolumes
            ?.forEach { volume ->
                val directory = volume.directory ?: return@forEach
                val label = when {
                    volume.isPrimary -> "内部存储"
                    volume.isRemovable -> "${volume.getDescription(context)}（SD 卡或 USB）"
                    else -> volume.getDescription(context)
                }
                volumes += DirectStorageVolume(directory.canonicalOrAbsolute(), label)
            }
    }

    val fallbackRoots = buildList {
        add(Environment.getExternalStorageDirectory())
        context.getExternalFilesDirs(null).filterNotNull().forEach { appDirectory ->
            val marker = "${File.separator}Android${File.separator}data${File.separator}"
            appDirectory.absolutePath.substringBefore(marker, missingDelimiterValue = "")
                .takeIf(String::isNotBlank)
                ?.let(::File)
                ?.let { add(it) }
        }
    }
    fallbackRoots.forEachIndexed { index, root ->
        val directory = root.canonicalOrAbsolute()
        if (volumes.none { it.directory.absolutePath == directory.absolutePath }) {
            volumes += DirectStorageVolume(
                directory = directory,
                label = if (index == 0) "内部存储" else "外置存储",
            )
        }
    }
    discoverReadableEmulatedUserRoots().forEach { directory ->
        if (volumes.none { it.directory.absolutePath == directory.absolutePath }) {
            val userId = directory.name.takeIf(String::isNotBlank) ?: "?"
            volumes += DirectStorageVolume(
                directory = directory,
                label = "Android 用户 $userId 存储",
            )
        }
    }
    return volumes
        .filter { it.directory.absolutePath == "/storage" || it.directory.absolutePath.startsWith("/storage/") }
        .filter { it.directory.isBrowsableDirectory() }
        .distinctBy { it.directory.absolutePath }
}

private fun discoverReadableEmulatedUserRoots(): List<File> {
    val candidates = linkedMapOf<String, File>()
    fun addCandidate(file: File) {
        val directory = file.canonicalOrAbsolute()
        candidates.putIfAbsent(directory.absolutePath, directory)
    }

    // /storage/emulated may reject listFiles() even when a process-visible user root
    // exists. Combine real directory entries with paths present in this process's
    // mount namespace; never guess OEM-specific numeric user IDs.
    runCatching { File("/storage/emulated").listFiles() }
        .getOrNull()
        .orEmpty()
        .filter(File::isDirectory)
        .forEach(::addCandidate)
    addCandidate(Environment.getExternalStorageDirectory())
    discoverEmulatedUserIdsFromMountInfo().forEach { userId ->
        addCandidate(File("/storage/emulated/$userId"))
    }

    return candidates.values.filter { it.isBrowsableDirectory() }
}

private fun discoverEmulatedUserIdsFromMountInfo(): Set<String> = runCatching {
    buildSet {
        File("/proc/self/mountinfo").useLines { lines ->
            lines.forEach { line ->
                EMULATED_USER_PATH_PATTERN.findAll(line).forEach { match ->
                    val userId = match.groupValues.getOrNull(1)
                        ?.takeIf { it.isNotBlank() }
                    if (userId != null) add(userId)
                }
            }
        }
    }
}.getOrDefault(emptySet())

private fun File.canonicalOrAbsolute(): File = runCatching { canonicalFile }.getOrDefault(absoluteFile)

private fun File.isBrowsableDirectory(): Boolean =
    isDirectory && runCatching { listFiles() != null }.getOrDefault(false)

internal fun directDirectoryFromUri(uri: Uri?): File? = uri
    ?.takeIf { it.scheme.equals("file", ignoreCase = true) }
    ?.path
    ?.let(::File)
    ?.let { file -> if (file.isDirectory) file else file.parentFile }

internal fun createDirectoryPickerIntent(
    initialUri: Uri? = null,
    allowWrite: Boolean,
    showAppChooser: Boolean,
    chooserTitle: String,
): Intent {
    val permissionFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
        (if (allowWrite) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
    val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(permissionFlags)
        initialUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
    }
    return if (showAppChooser) {
        Intent.createChooser(picker, chooserTitle).apply { addFlags(permissionFlags) }
    } else {
        picker
    }
}

internal fun readableDirectoryLocation(value: String?): String {
    val rawValue = value?.takeIf(String::isNotBlank) ?: return "尚未选择目录"
    return runCatching {
        val uri = Uri.parse(rawValue)
        if (uri.authority == EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY && DocumentsContract.isTreeUri(uri)) {
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            val volumeId = documentId.substringBefore(':')
            val relativePath = documentId.substringAfter(':', "").trim('/')
            val root = if (volumeId.equals("primary", ignoreCase = true)) {
                "/storage/emulated/0"
            } else {
                "/storage/$volumeId"
            }
            if (relativePath.isBlank()) root else "$root/$relativePath"
        } else {
            val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            documentId?.substringAfter(':')?.takeIf(String::isNotBlank)
                ?: Uri.decode(uri.lastPathSegment.orEmpty()).takeIf(String::isNotBlank)
                ?: rawValue
        }
    }.getOrDefault(rawValue)
}
