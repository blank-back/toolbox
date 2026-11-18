package com.pockettoolbox.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class DirectStorageSelectionMode { FILE, DIRECTORY }

@Composable
internal fun DirectStoragePickerDialog(
    title: String,
    mode: DirectStorageSelectionMode,
    initialDirectory: File?,
    onDismiss: () -> Unit,
    onSelected: (List<File>) -> Unit,
) {
    val context = LocalContext.current
    val storageRoot = remember { canonicalFileOrSelf(directStorageRoot()) }
    val storageVolumes = remember(context) { directStorageVolumes(context) }
    val storageVolumeByPath = remember(storageVolumes) {
        storageVolumes.associateBy { canonicalFileOrSelf(it.directory).absolutePath }
    }
    val initialPath = remember(initialDirectory, storageRoot) {
        val requested = canonicalFileOrSelf(initialDirectory ?: storageRoot)
        requested.takeIf { it.isDirectory && it.isInside(storageRoot) } ?: storageRoot
    }
    var currentPath by rememberSaveable(title, mode, initialPath.absolutePath) {
        mutableStateOf(initialPath.absolutePath)
    }
    var selectedFilePaths by remember(title, mode) { mutableStateOf<Set<String>>(emptySet()) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var readError by remember { mutableStateOf<String?>(null) }
    val currentDirectory = remember(currentPath) { canonicalFileOrSelf(File(currentPath)) }
    val atStorageRoot = currentDirectory.absolutePath == storageRoot.absolutePath
    val emulatedUserId = currentDirectory.emulatedStorageUserId()

    LaunchedEffect(currentPath, storageVolumes) {
        loading = true
        readError = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val rawEntries = if (atStorageRoot) {
                    storageVolumes.map(DirectStorageVolume::directory).toTypedArray()
                        .takeIf { it.isNotEmpty() }
                        ?: error("系统未返回可用的存储卷")
                } else {
                    currentDirectory.listFiles() ?: error("无法列出目录内容")
                }
                rawEntries
                    .asSequence()
                    .map(::canonicalFileOrSelf)
                    .filter { entry ->
                        entry.isInside(storageRoot) && (atStorageRoot || entry.isDirectory || entry.isFile)
                    }
                    .let { entries ->
                        if (atStorageRoot) entries else entries.sortedWith(
                            compareByDescending<File> { it.isDirectory }
                                .thenBy { it.name.lowercase() },
                        )
                    }
                    .toList()
            }
        }
        entries = result.getOrNull().orEmpty()
        if (result.isFailure || (!currentDirectory.canRead() && entries.isEmpty())) {
            readError = "无法读取该目录；请确认已授予“所有文件访问”权限"
        }
        loading = false
    }

    val selectedFiles = selectedFilePaths.map(::File).filter(File::isFile)
    val canUseCurrentDirectory = !atStorageRoot && currentDirectory.isDirectory && currentDirectory.canRead()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (atStorageRoot) "存储位置（/storage）" else currentDirectory.absolutePath,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (mode == DirectStorageSelectionMode.FILE && selectedFilePaths.isNotEmpty()) {
                    Text("已选择 ${selectedFilePaths.size} 个文件", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                if (emulatedUserId != null && emulatedUserId != PRIMARY_ANDROID_USER_ID) {
                    Text(
                        "当前应用运行在 Android 用户 $emulatedUserId。存储位置页会探测并列出当前进程实际可枚举的用户存储；不同厂商的分身挂载和兼容策略可能使列表不同。",
                        fontSize = 10.sp,
                    )
                }
                val parent = currentDirectory.storagePickerParent(
                    storageRoot = storageRoot,
                    volumeRootPaths = storageVolumeByPath.keys,
                )
                val returnsToStorageLocations = parent?.absolutePath == storageRoot.absolutePath
                OutlinedButton(
                    onClick = { currentPath = requireNotNull(parent).absolutePath },
                    enabled = parent != null && parent.isInside(storageRoot),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.ArrowUpward, null)
                    Text(if (returnsToStorageLocations) "返回存储位置" else "上一级")
                }
                when {
                    loading -> Row(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    readError != null -> Text(requireNotNull(readError), fontSize = 12.sp)
                    entries.isEmpty() -> Text("该目录为空", fontSize = 12.sp)
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(entries, key = { it.absolutePath }) { entry ->
                            val storageVolume = storageVolumeByPath[entry.absolutePath]
                            val isDirectory = storageVolume != null || entry.isDirectory
                            val selected = entry.absolutePath in selectedFilePaths
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (isDirectory) {
                                        currentPath = entry.absolutePath
                                    } else if (mode == DirectStorageSelectionMode.FILE) {
                                        selectedFilePaths = if (selected) {
                                            selectedFilePaths - entry.absolutePath
                                        } else {
                                            selectedFilePaths + entry.absolutePath
                                        }
                                    }
                                }.padding(vertical = 9.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    if (isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile,
                                    contentDescription = null,
                                )
                                if (mode == DirectStorageSelectionMode.FILE && !isDirectory) {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = { checked ->
                                            selectedFilePaths = if (checked) {
                                                selectedFilePaths + entry.absolutePath
                                            } else {
                                                selectedFilePaths - entry.absolutePath
                                            }
                                        },
                                    )
                                }
                                Text(
                                    storageVolume?.let { "${it.label}\n${entry.absolutePath}" }
                                        ?: entry.name.ifBlank { entry.absolutePath },
                                    modifier = Modifier.weight(1f),
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val selected = if (mode == DirectStorageSelectionMode.DIRECTORY) {
                        listOf(currentDirectory)
                    } else {
                        selectedFiles
                    }
                    onSelected(selected)
                },
                enabled = if (mode == DirectStorageSelectionMode.DIRECTORY) {
                    canUseCurrentDirectory
                } else {
                    selectedFiles.isNotEmpty()
                },
            ) {
                Text(if (mode == DirectStorageSelectionMode.DIRECTORY) "选择当前目录" else "选择所选文件")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun canonicalFileOrSelf(file: File): File = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)

private fun File.storagePickerParent(
    storageRoot: File,
    volumeRootPaths: Set<String>,
): File? {
    val currentPath = normalizedStoragePath()
    val storageRootPath = storageRoot.normalizedStoragePath()
    if (currentPath == storageRootPath) return null

    // /storage/emulated is a protected multi-user mount layer, not a directory apps can browse.
    // Volume roots must therefore return to the picker's virtual /storage page directly.
    if (
        currentPath in volumeRootPaths.mapTo(hashSetOf()) { File(it).normalizedStoragePath() } ||
        isEmulatedStorageUserRoot() ||
        currentPath == EMULATED_STORAGE_PARENT
    ) {
        return storageRoot
    }

    val physicalParent = parentFile?.let(::canonicalFileOrSelf) ?: return storageRoot
    if (physicalParent.normalizedStoragePath() == EMULATED_STORAGE_PARENT) return storageRoot
    return physicalParent.takeIf { it.isInside(storageRoot) } ?: storageRoot
}

private fun File.isEmulatedStorageUserRoot(): Boolean {
    val path = normalizedStoragePath()
    if (!path.startsWith(EMULATED_STORAGE_PREFIX)) return false
    return path.removePrefix(EMULATED_STORAGE_PREFIX).isNotBlank() &&
        '/' !in path.removePrefix(EMULATED_STORAGE_PREFIX)
}

private fun File.emulatedStorageUserId(): String? {
    val path = normalizedStoragePath()
    if (!path.startsWith(EMULATED_STORAGE_PREFIX)) return null
    return path.removePrefix(EMULATED_STORAGE_PREFIX)
        .substringBefore('/')
        .takeIf(String::isNotBlank)
}

private fun File.normalizedStoragePath(): String = absolutePath.replace('\\', '/').trimEnd('/')

private fun File.isInside(root: File): Boolean {
    val rootPath = root.normalizedStoragePath()
    val path = normalizedStoragePath()
    return path == rootPath || path.startsWith("$rootPath/")
}

private const val EMULATED_STORAGE_PARENT = "/storage/emulated"
private const val EMULATED_STORAGE_PREFIX = "$EMULATED_STORAGE_PARENT/"
private const val PRIMARY_ANDROID_USER_ID = "0"
