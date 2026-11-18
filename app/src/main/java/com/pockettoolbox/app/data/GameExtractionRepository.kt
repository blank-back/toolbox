package com.pockettoolbox.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.system.Os
import android.system.OsConstants
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class GameInputKind {
    RPG_ARCHIVE,
    RPG_ENCRYPTED_RESOURCE,
    RPG_SYSTEM_JSON,
    RPG_PACKAGE_RESOURCE,
    UNITY_PACKAGE,
    UNITY_MONO_ASSEMBLY,
    UNITY_IL2CPP_BINARY,
    UNITY_IL2CPP_METADATA,
    UNITY_ASSET,
    UNITY_DIRECTORY,
    UNKNOWN,
}

data class GameInputFile(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val kind: GameInputKind,
    val relativePath: String = name,
)

data class InstalledGameApp(
    val label: String,
    val packageName: String,
    val versionName: String?,
    val apkCount: Int,
)

data class RpgMakerDirectoryScan(
    val directoryName: String,
    val files: List<GameInputFile>,
    val detectedKey: String?,
    val keySource: String?,
    val audioVolumeEngine: RpgAudioVolumeEngine? = null,
)

enum class UnityBackend { MONO, IL2CPP, MIXED, UNKNOWN }

enum class UnityArtifactKind(val displayName: String) {
    MONO_ASSEMBLY("Mono 程序集"),
    IL2CPP_BINARY("IL2CPP 本机库"),
    IL2CPP_METADATA("IL2CPP 元数据"),
    ASSET("Unity 资源"),
    CONFIG("Unity 配置"),
    NESTED_PACKAGE("内嵌 APK"),
}

data class UnityArtifact(
    val path: String,
    val kind: UnityArtifactKind,
    val sizeBytes: Long,
)

data class UnityInspection(
    val input: GameInputFile,
    val backend: UnityBackend,
    val totalEntries: Int,
    val artifactCounts: Map<UnityArtifactKind, Int>,
    val previewArtifacts: List<UnityArtifact>,
    val previewTruncated: Boolean,
    val isArchive: Boolean,
    val isDirectory: Boolean = false,
    val installedPackageName: String? = null,
    val installedApkCount: Int = 0,
) {
    val artifactCount: Int get() = artifactCounts.values.sum()
    val hasCompleteIl2CppPair: Boolean
        get() = artifactCounts.getOrDefault(UnityArtifactKind.IL2CPP_BINARY, 0) > 0 &&
            artifactCounts.getOrDefault(UnityArtifactKind.IL2CPP_METADATA, 0) > 0
}

data class UnityExtractionSelection(
    val monoAssemblies: Boolean = true,
    val il2CppFiles: Boolean = true,
    val assets: Boolean = false,
    val configs: Boolean = true,
    val decodedImages: Boolean = true,
    val decodedAudio: Boolean = true,
    val decodedVideo: Boolean = true,
    val decodedText: Boolean = true,
) {
    fun includes(kind: UnityArtifactKind): Boolean = when (kind) {
        UnityArtifactKind.MONO_ASSEMBLY -> monoAssemblies
        UnityArtifactKind.IL2CPP_BINARY, UnityArtifactKind.IL2CPP_METADATA -> il2CppFiles
        UnityArtifactKind.ASSET -> assets
        UnityArtifactKind.CONFIG, UnityArtifactKind.NESTED_PACKAGE -> configs
    }

    val hasAny: Boolean
        get() = monoAssemblies || il2CppFiles || assets || configs || decodedImages || decodedAudio ||
            decodedVideo || decodedText
}

data class GameExtractionSummary(
    val filesWritten: Int,
    val bytesWritten: Long,
    val outputDirectoryName: String,
    val skippedCount: Int = 0,
    val failureRecords: List<GameExtractionFailure> = emptyList(),
    val existingFilesSkipped: Int = 0,
)

data class GameExtractionFailure(
    val item: String,
    val reason: String,
)

/**
 * Local-only extractors for documented RPG Maker formats and Unity package artifacts.
 *
 * Format references:
 * - https://github.com/uuksu/RPGMakerDecrypter
 * - https://github.com/Bioruebe/rmvdec
 * - https://github.com/AssetRipper/AssetRipper
 */
class GameExtractionRepository(private val context: Context) {
    private val resolver = context.contentResolver

    suspend fun describe(uri: Uri): GameInputFile = withContext(Dispatchers.IO) {
        val (name, size) = queryNameAndSize(uri)
        GameInputFile(
            uri = uri.toString(),
            name = name,
            sizeBytes = size,
            kind = classifyInputName(name),
        )
    }

    @Suppress("DEPRECATION")
    suspend fun listInstalledGameApps(): List<InstalledGameApp> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .asSequence()
            .mapNotNull { resolveInfo -> resolveInfo.activityInfo?.applicationInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .mapNotNull { applicationInfo ->
                val apkFiles = installedApkFiles(applicationInfo)
                if (apkFiles.isEmpty()) return@mapNotNull null
                val versionName = runCatching {
                    packageManager.getPackageInfo(applicationInfo.packageName, 0).versionName
                }.getOrNull()
                InstalledGameApp(
                    label = applicationInfo.loadLabel(packageManager).toString()
                        .ifBlank { applicationInfo.packageName },
                    packageName = applicationInfo.packageName,
                    versionName = versionName,
                    apkCount = apkFiles.size,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

    suspend fun readRpgMakerKey(uri: Uri): Pair<String, String> = withContext(Dispatchers.IO) {
        val input = describeDirect(uri)
        when (input.kind) {
            GameInputKind.RPG_SYSTEM_JSON -> {
                val raw = openInputStreamCompat(context, uri)?.use { stream ->
                    stream.readLimited(MAX_SYSTEM_JSON_BYTES).toString(Charsets.UTF_8)
                } ?: error("无法读取 System.json")
                val key = normalizeRpgKey(JSONObject(raw.trimStart('\uFEFF')).optString("encryptionKey"))
                key to "System.json"
            }

            GameInputKind.RPG_ENCRYPTED_RESOURCE -> {
                require(
                    input.name.lowercase(Locale.ROOT).endsWith(".rpgmvp") ||
                        input.name.lowercase(Locale.ROOT).endsWith(".png_"),
                ) { "只有加密 PNG 可以自动推导密钥；音频请改选 System.json" }
                val bytes = openInputStreamCompat(context, uri)?.use { it.readExactly(32) }
                    ?: error("无法读取加密图片")
                require(bytes.size == 32) { "加密图片过短，无法推导密钥" }
                require(bytes.startsWith(RPG_MV_SIGNATURE)) { "文件缺少标准 RPGMV 加密头" }
                val keyBytes = ByteArray(RPG_MV_KEY_SIZE) { index ->
                    (bytes[RPG_MV_HEADER_SIZE + index].toInt() xor PNG_HEADER[index].toInt()).toByte()
                }
                keyBytes.toHex() to "加密 PNG 推导"
            }

            else -> error("请选择 System.json 或 .rpgmvp/.png_ 加密图片")
        }
    }

    suspend fun scanRpgMakerDirectory(treeUri: Uri): RpgMakerDirectoryScan = withContext(Dispatchers.IO) {
        val (root, entries) = snapshotDirectory(treeUri)
        val selectedVxAceDataDirectory = root.name.equals("Data", ignoreCase = true) && entries.any { entry ->
            entry.relativePath.normalizedGamePath() == "scripts.rvdata2"
        }
        val projectPrefix = if (selectedVxAceDataDirectory) {
            ""
        } else {
            findRpgProjectPrefix(entries.map { it.relativePath })
        }
        val supported = entries.mapNotNull { entry ->
            val name = entry.file.name ?: return@mapNotNull null
            val kind = classifyInputName(name)
            val projectRelativePath = projectPrefix?.let { prefix ->
                entry.relativePath.takeIf { path -> path.startsWith(prefix, ignoreCase = true) }
                    ?.substring(prefix.length)
                    ?.takeIf(String::isNotBlank)
            }
            val outputKind = when {
                kind == GameInputKind.RPG_ARCHIVE -> GameInputKind.RPG_ARCHIVE
                kind == GameInputKind.RPG_ENCRYPTED_RESOURCE -> GameInputKind.RPG_ENCRYPTED_RESOURCE
                projectRelativePath != null -> GameInputKind.RPG_PACKAGE_RESOURCE
                kind == GameInputKind.RPG_SYSTEM_JSON -> GameInputKind.RPG_SYSTEM_JSON
                else -> return@mapNotNull null
            }
            GameInputFile(
                uri = entry.file.uri.toString(),
                name = name,
                sizeBytes = entry.file.length(),
                kind = outputKind,
                relativePath = projectRelativePath ?: entry.relativePath,
            )
        }
        val processable = supported.filter { it.kind != GameInputKind.RPG_SYSTEM_JSON }
        require(processable.isNotEmpty()) { "目录内未找到受支持的 RPG Maker 项目、档案或加密资源" }

        var detectedKey: String? = null
        var keySource: String? = null
        val keyCandidates = entries.mapNotNull { entry ->
            val name = entry.file.name ?: return@mapNotNull null
            val kind = classifyInputName(name)
            val lower = name.lowercase(Locale.ROOT)
            if (
                kind != GameInputKind.RPG_SYSTEM_JSON &&
                !(kind == GameInputKind.RPG_ENCRYPTED_RESOURCE &&
                    (lower.endsWith(".rpgmvp") || lower.endsWith(".png_")))
            ) {
                return@mapNotNull null
            }
            GameInputFile(
                uri = entry.file.uri.toString(),
                name = name,
                sizeBytes = entry.file.length(),
                kind = kind,
                relativePath = entry.relativePath,
            )
        }
        for (candidate in keyCandidates) {
            try {
                val result = readRpgMakerKey(Uri.parse(candidate.uri))
                detectedKey = result.first
                keySource = "${result.second}：${candidate.relativePath}"
                break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Some games contain unrelated System.json files; keep looking.
            }
        }
        val projectPaths = processable.map { it.relativePath.replace('\\', '/').trimStart('/').lowercase(Locale.ROOT) }
        val audioVolumeEngine = when {
            projectPaths.any { it == "js/rmmz_core.js" || it == "js/rpg_core.js" } ->
                RpgAudioVolumeEngine.MV_MZ
            projectPaths.any { it == "data/scripts.rvdata2" } ||
                (selectedVxAceDataDirectory && projectPaths.any { it == "scripts.rvdata2" }) ->
                RpgAudioVolumeEngine.VX_ACE
            else -> null
        }
        RpgMakerDirectoryScan(
            directoryName = root.name ?: "game_folder",
            files = processable,
            detectedKey = detectedKey,
            keySource = keySource,
            audioVolumeEngine = audioVolumeEngine,
        )
    }

    suspend fun scanRpgMakerInstalledApp(packageName: String): RpgMakerDirectoryScan = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
        val apkFiles = installedApkFiles(applicationInfo)
        require(apkFiles.isNotEmpty()) { "无法读取该应用的安装包" }
        val appLabel = applicationInfo.loadLabel(packageManager).toString().ifBlank { packageName }
        val processable = mutableListOf<GameInputFile>()
        var detectedKey: String? = null
        var keySource: String? = null
        var visited = 0
        var hasRpgProjectMarker = false
        apkFiles.forEach { apkFile ->
            FileInputStream(apkFile).use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            visited += 1
                            require(visited <= MAX_ZIP_ENTRIES) { "安装包条目过多，已中止扫描" }
                            val relativePath = entry.name.replace('\\', '/').trimStart('/')
                            val name = relativePath.substringAfterLast('/')
                            val kind = classifyInputName(name)
                            val projectRelativePath = rpgPackageRelativePath(relativePath)
                            if (projectRelativePath != null && isRpgProjectMarker(projectRelativePath)) {
                                hasRpgProjectMarker = true
                            }
                            val outputKind = when {
                                kind == GameInputKind.RPG_ARCHIVE -> GameInputKind.RPG_ARCHIVE
                                kind == GameInputKind.RPG_ENCRYPTED_RESOURCE -> GameInputKind.RPG_ENCRYPTED_RESOURCE
                                projectRelativePath != null -> GameInputKind.RPG_PACKAGE_RESOURCE
                                else -> null
                            }
                            if (outputKind != null) {
                                processable += GameInputFile(
                                    uri = "package:$packageName#$relativePath",
                                    name = name,
                                    sizeBytes = entry.size,
                                    kind = outputKind,
                                    relativePath = projectRelativePath ?: relativePath,
                                )
                            }
                            if (detectedKey == null && kind == GameInputKind.RPG_SYSTEM_JSON) {
                                runCatching {
                                    val rawJson = zip.readLimited(MAX_SYSTEM_JSON_BYTES)
                                        .toString(Charsets.UTF_8)
                                        .trimStart('\uFEFF')
                                    detectedKey = normalizeRpgKey(JSONObject(rawJson).optString("encryptionKey"))
                                    keySource = "System.json：$relativePath"
                                }
                            } else if (
                                detectedKey == null &&
                                kind == GameInputKind.RPG_ENCRYPTED_RESOURCE &&
                                (name.lowercase(Locale.ROOT).endsWith(".rpgmvp") ||
                                    name.lowercase(Locale.ROOT).endsWith(".png_"))
                            ) {
                                runCatching {
                                    val bytes = zip.readExactly(32)
                                    require(bytes.size == 32 && bytes.startsWith(RPG_MV_SIGNATURE))
                                    val keyBytes = ByteArray(RPG_MV_KEY_SIZE) { index ->
                                        (bytes[RPG_MV_HEADER_SIZE + index].toInt() xor PNG_HEADER[index].toInt()).toByte()
                                    }
                                    detectedKey = keyBytes.toHex()
                                    keySource = "加密 PNG 推导：$relativePath"
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        }
        val explicitEncryptedResources = processable.any {
            it.kind == GameInputKind.RPG_ARCHIVE || it.kind == GameInputKind.RPG_ENCRYPTED_RESOURCE
        }
        require(processable.isNotEmpty() && (hasRpgProjectMarker || explicitEncryptedResources)) {
            "该应用的 APK 中未发现受支持的 RPG Maker 项目结构、档案或 MV/MZ 加密资源"
        }
        RpgMakerDirectoryScan(appLabel, processable, detectedKey, keySource)
    }

    suspend fun extractRpgMakerInstalledApp(
        packageName: String,
        outputTreeUri: Uri,
        keyHex: String,
        onProgress: suspend (String) -> Unit,
    ): GameExtractionSummary = withContext(Dispatchers.IO) {
        scanRpgMakerInstalledApp(packageName)
        @Suppress("DEPRECATION")
        val applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
        val apkFiles = installedApkFiles(applicationInfo)
        require(apkFiles.isNotEmpty()) { "应用已被卸载或安装包暂时不可读" }
        val tree = writableTree(outputTreeUri)
        var filesWritten = 0
        var bytesWritten = 0L
        var visited = 0
        var mvKey: ByteArray? = null
        apkFiles.forEach { apkFile ->
            FileInputStream(apkFile).use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            visited += 1
                            require(visited <= MAX_ZIP_ENTRIES) { "安装包条目过多，已中止导出" }
                            val relativePath = entry.name.replace('\\', '/').trimStart('/')
                            val name = relativePath.substringAfterLast('/')
                            val projectRelativePath = rpgPackageRelativePath(relativePath)
                            when (classifyInputName(name)) {
                                GameInputKind.RPG_ENCRYPTED_RESOURCE -> {
                                    onProgress("正在处理：$name")
                                    val key = mvKey ?: hexToBytes(normalizeRpgKey(keyHex)).also { mvKey = it }
                                    bytesWritten += decryptMvMzResource(
                                        source = zip,
                                        inputName = name,
                                        relativePath = projectRelativePath ?: relativePath,
                                        key = key,
                                        output = tree,
                                    )
                                    filesWritten += 1
                                }
                                GameInputKind.RPG_ARCHIVE -> {
                                    onProgress("正在处理：$name")
                                    val result = extractRgssArchive(zip, tree)
                                    filesWritten += result.first
                                    bytesWritten += result.second
                                }
                                else -> if (projectRelativePath != null) {
                                    onProgress("正在导出：$name")
                                    val target = createOutputFile(tree, projectRelativePath)
                                    bytesWritten += writeStreamToDocument(
                                        source = zip,
                                        target = target,
                                        remainingLimit = MAX_UNITY_OUTPUT_BYTES - bytesWritten,
                                    )
                                    filesWritten += 1
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        }
        require(filesWritten > 0) { "安装包中没有可导出的 RPG Maker 资源" }
        GameExtractionSummary(filesWritten, bytesWritten, tree.name ?: "所选输出目录")
    }

    suspend fun extractRpgMaker(
        inputs: List<GameInputFile>,
        outputTreeUri: Uri,
        keyHex: String,
        onProgress: suspend (String) -> Unit,
    ): GameExtractionSummary = withContext(Dispatchers.IO) {
        require(inputs.isNotEmpty()) { "请先选择 RPG Maker 档案或加密资源" }
        val supported = inputs.filter {
            it.kind == GameInputKind.RPG_ARCHIVE ||
                it.kind == GameInputKind.RPG_ENCRYPTED_RESOURCE ||
                it.kind == GameInputKind.RPG_PACKAGE_RESOURCE
        }
        require(supported.isNotEmpty()) { "所选文件中没有支持的 RPG Maker 格式" }
        val needsMvKey = supported.any { it.kind == GameInputKind.RPG_ENCRYPTED_RESOURCE }
        val key = if (needsMvKey) hexToBytes(normalizeRpgKey(keyHex)) else ByteArray(0)
        val tree = writableTree(outputTreeUri)
        var filesWritten = 0
        var bytesWritten = 0L

        supported.forEachIndexed { index, input ->
            onProgress("正在处理 ${index + 1}/${supported.size}：${input.name}")
            when (input.kind) {
                GameInputKind.RPG_ENCRYPTED_RESOURCE -> {
                    val written = decryptMvMzResource(
                        uri = Uri.parse(input.uri),
                        inputName = input.name,
                        relativePath = input.relativePath,
                        key = key,
                        output = tree,
                    )
                    filesWritten += 1
                    bytesWritten += written
                }

                GameInputKind.RPG_ARCHIVE -> {
                    val result = extractRgssArchive(Uri.parse(input.uri), tree)
                    filesWritten += result.first
                    bytesWritten += result.second
                }

                GameInputKind.RPG_PACKAGE_RESOURCE -> {
                    val target = createOutputFile(tree, input.relativePath)
                    val written = openInputStreamCompat(context, Uri.parse(input.uri))?.use { source ->
                        writeStreamToDocument(source, target, MAX_UNITY_OUTPUT_BYTES - bytesWritten)
                    } ?: error("无法读取 ${input.relativePath}")
                    filesWritten += 1
                    bytesWritten += written
                }

                else -> Unit
            }
        }
        GameExtractionSummary(filesWritten, bytesWritten, tree.name ?: "所选输出目录")
    }

    suspend fun inspectUnity(uri: Uri): UnityInspection = withContext(Dispatchers.IO) {
        val input = describeDirect(uri)
        if (isZip(uri)) {
            inspectUnityArchive(uri, input)
        } else {
            val kind = when (input.kind) {
                GameInputKind.UNITY_MONO_ASSEMBLY -> UnityArtifactKind.MONO_ASSEMBLY
                GameInputKind.UNITY_IL2CPP_BINARY -> UnityArtifactKind.IL2CPP_BINARY
                GameInputKind.UNITY_IL2CPP_METADATA -> UnityArtifactKind.IL2CPP_METADATA
                else -> classifyUnityPath(input.name) ?: detectUnityAssetKind(uri, input.sizeBytes, input.name)
            }
                ?: error("无法识别该文件；请选择 Unity APK/ZIP、程序集、IL2CPP 文件或资源文件")
            val artifact = UnityArtifact(input.name, kind, input.sizeBytes)
            UnityInspection(
                input = input.copy(kind = gameInputKindForUnityArtifact(kind)),
                backend = backendForCounts(mapOf(kind to 1)),
                totalEntries = 1,
                artifactCounts = mapOf(kind to 1),
                previewArtifacts = listOf(artifact),
                previewTruncated = false,
                isArchive = false,
            )
        }
    }

    suspend fun inspectInstalledUnityApp(packageName: String): UnityInspection = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
        val apkFiles = installedApkFiles(applicationInfo)
        require(apkFiles.isNotEmpty()) { "无法读取该应用的安装包" }
        val appLabel = applicationInfo.loadLabel(packageManager).toString().ifBlank { packageName }
        val counts = mutableMapOf<UnityArtifactKind, Int>()
        val preview = mutableListOf<UnityArtifact>()
        var totalEntries = 0
        apkFiles.forEach { apkFile ->
            val previewPrefix = apkFile.name.takeIf { apkFiles.size > 1 }
            val scan = inspectUnityIndexedArchive(apkFile, previewPrefix)
                ?: FileInputStream(apkFile).use { source ->
                    inspectUnityStreamingArchive(source, previewPrefix)
                }
            totalEntries += scan.totalEntries
            require(totalEntries <= MAX_ZIP_ENTRIES) { "安装包条目过多，已中止分析" }
            scan.counts.forEach { (kind, count) ->
                counts[kind] = counts.getOrDefault(kind, 0) + count
            }
            if (preview.size < MAX_UNITY_PREVIEW_ITEMS) {
                preview += scan.preview.take(MAX_UNITY_PREVIEW_ITEMS - preview.size)
            }
        }
        require(counts.isNotEmpty()) {
            "该应用的 APK 中未发现常见 Unity 结构；可能不是 Unity 游戏，或资源经过了自定义封装"
        }
        UnityInspection(
            input = GameInputFile(
                uri = "package:$packageName",
                name = appLabel,
                sizeBytes = apkFiles.sumOf(File::length),
                kind = GameInputKind.UNITY_PACKAGE,
            ),
            backend = backendForCounts(counts),
            totalEntries = totalEntries,
            artifactCounts = counts.toMap(),
            previewArtifacts = preview,
            previewTruncated = counts.values.sum() > preview.size,
            isArchive = true,
            installedPackageName = packageName,
            installedApkCount = apkFiles.size,
        )
    }

    suspend fun inspectUnityDirectory(treeUri: Uri): UnityInspection = withContext(Dispatchers.IO) {
        val (root, entries) = snapshotDirectory(treeUri)
        val counts = mutableMapOf<UnityArtifactKind, Int>()
        val preview = mutableListOf<UnityArtifact>()
        entries.forEach { entry ->
            classifyUnityDocument(entry)?.let { kind ->
                counts[kind] = counts.getOrDefault(kind, 0) + 1
                if (preview.size < MAX_UNITY_PREVIEW_ITEMS) {
                    preview += UnityArtifact(entry.relativePath, kind, entry.file.length())
                }
            }
        }
        require(counts.isNotEmpty()) { "目录内未发现常见 Unity 代码或资源结构" }
        val name = root.name ?: "Unity_game_folder"
        UnityInspection(
            input = GameInputFile(treeUri.toString(), name, -1L, GameInputKind.UNITY_DIRECTORY),
            backend = backendForCounts(counts),
            totalEntries = entries.size,
            artifactCounts = counts.toMap(),
            previewArtifacts = preview,
            previewTruncated = counts.values.sum() > preview.size,
            isArchive = false,
            isDirectory = true,
        )
    }

    suspend fun extractUnity(
        inspection: UnityInspection,
        outputTreeUri: Uri,
        selection: UnityExtractionSelection,
        onProgress: suspend (String) -> Unit,
    ): GameExtractionSummary = withContext(Dispatchers.IO) {
        require(selection.hasAny) { "请至少勾选一类要导出的文件" }
        val sourceUri = Uri.parse(inspection.input.uri)
        // Snapshot before creating output so choosing a destination inside the input tree cannot recurse into new files.
        val directorySnapshot = if (inspection.isDirectory) snapshotDirectory(sourceUri).second else emptyList()
        val tree = writableTree(outputTreeUri)
        var fileCount = 0
        var byteCount = 0L
        var existingFilesSkipped = 0
        var visitedArchiveEntries = 0
        val failureRecords = mutableListOf<GameExtractionFailure>()

        suspend fun extractArchive(source: InputStream, sourceName: String?) {
            ZipInputStream(BufferedInputStream(source)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        visitedArchiveEntries += 1
                        require(visitedArchiveEntries <= MAX_ZIP_ENTRIES) { "压缩包条目过多，已中止导出" }
                        val probe = probeUnityArchiveEntry(entry.name, entry.size, zip)
                        val kind = probe.kind
                        if (kind != null && selection.includes(kind)) {
                            if (findExistingOutputFile(tree, entry.name) != null) {
                                existingFilesSkipped += 1
                                zip.closeEntry()
                                entry = zip.nextEntry
                                continue
                            }
                            val progressName = entry.name.substringAfterLast('/')
                            onProgress(
                                if (sourceName == null) "正在导出：$progressName"
                                else "正在导出 $sourceName：$progressName",
                            )
                            val target = createOutputFile(tree, entry.name)
                            val written = writeStreamToDocument(
                                source = zip,
                                target = target,
                                remainingLimit = MAX_UNITY_OUTPUT_BYTES - byteCount,
                                prefix = probe.consumedPrefix,
                            )
                            byteCount += written
                            fileCount += 1
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        suspend fun extractIndexedArchive(archive: ZipFile, sourceName: String?) {
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                visitedArchiveEntries += 1
                require(visitedArchiveEntries <= MAX_ZIP_ENTRIES) { "压缩包条目过多，已中止导出" }
                val kind = classifyUnityPath(entry.name) ?: if (shouldProbeUnityPath(entry.name, entry.size)) {
                    archive.getInputStream(entry).use { source ->
                        probeUnityArchiveEntry(entry.name, entry.size, source).kind
                    }
                } else {
                    null
                }
                if (kind == null || !selection.includes(kind)) continue
                if (findExistingOutputFile(tree, entry.name) != null) {
                    existingFilesSkipped += 1
                    continue
                }
                val progressName = entry.name.substringAfterLast('/')
                onProgress(
                    if (sourceName == null) "正在导出：$progressName"
                    else "正在导出 $sourceName：$progressName",
                )
                val target = createOutputFile(tree, entry.name)
                val written = archive.getInputStream(entry).use { source ->
                    writeStreamToDocument(
                        source = source,
                        target = target,
                        remainingLimit = MAX_UNITY_OUTPUT_BYTES - byteCount,
                    )
                }
                byteCount += written
                fileCount += 1
            }
        }

        suspend fun tryExtractIndexedArchive(file: File, sourceName: String?): Boolean {
            val archive = runCatching { ZipFile(file) }.getOrNull() ?: return false
            archive.use { extractIndexedArchive(it, sourceName) }
            return true
        }

        if (inspection.installedPackageName != null) {
            @Suppress("DEPRECATION")
            val applicationInfo = context.packageManager.getApplicationInfo(inspection.installedPackageName, 0)
            val apkFiles = installedApkFiles(applicationInfo)
            require(apkFiles.isNotEmpty()) { "应用已被卸载或安装包暂时不可读" }
            apkFiles.forEach { apkFile ->
                val sourceName = apkFile.name.takeIf { apkFiles.size > 1 }
                if (!tryExtractIndexedArchive(apkFile, sourceName)) {
                    FileInputStream(apkFile).use { source -> extractArchive(source, sourceName) }
                }
            }
        } else if (inspection.isArchive) {
            var indexed = false
            openSeekableUnityInput(sourceUri)?.let { (directInput, descriptor) ->
                try {
                    indexed = tryExtractIndexedArchive(directInput, null)
                } finally {
                    runCatching { descriptor?.close() }
                }
            }
            if (!indexed) {
                openInputStreamCompat(context, sourceUri)?.use { raw -> extractArchive(raw, null) }
                    ?: error("无法读取 Unity 包")
            }
        } else if (inspection.isDirectory) {
            directorySnapshot.forEach { entry ->
                val kind = classifyUnityDocument(entry)
                if (kind != null && selection.includes(kind)) {
                    if (findExistingOutputFile(tree, entry.relativePath) != null) {
                        existingFilesSkipped += 1
                        return@forEach
                    }
                    onProgress("正在导出：${entry.relativePath.substringAfterLast('/')}")
                    val target = createOutputFile(tree, entry.relativePath)
                    val written = openInputStreamCompat(context, entry.file.uri)?.use { source ->
                        writeStreamToDocument(source, target, MAX_UNITY_OUTPUT_BYTES - byteCount)
                    } ?: error("无法读取 ${entry.relativePath}")
                    byteCount += written
                    fileCount += 1
                }
            }
        } else {
            val artifact = inspection.previewArtifacts.first()
            if (selection.includes(artifact.kind)) {
                if (findExistingOutputFile(tree, inspection.input.name) != null) {
                    existingFilesSkipped += 1
                } else {
                    onProgress("正在导出：${inspection.input.name}")
                    val target = createOutputFile(tree, inspection.input.name)
                    val written = openInputStreamCompat(context, sourceUri)?.use { source ->
                        writeStreamToDocument(source, target, MAX_UNITY_OUTPUT_BYTES)
                    } ?: error("无法读取所选文件")
                    byteCount += written
                    fileCount += 1
                }
            }
        }

        var decodedOutcome = UnityDecodedMediaOutcome.Empty
        if (selection.decodedImages || selection.decodedAudio || selection.decodedVideo || selection.decodedText) {
            decodedOutcome = runCatching {
                decodeUnityMedia(
                    inspection = inspection,
                    sourceUri = sourceUri,
                    directorySnapshot = directorySnapshot,
                    outputTree = tree,
                    exportImages = selection.decodedImages,
                    exportAudio = selection.decodedAudio,
                    exportVideo = selection.decodedVideo,
                    exportText = selection.decodedText,
                    maximumOutputBytes = MAX_UNITY_OUTPUT_BYTES - byteCount,
                    onProgress = onProgress,
                )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                UnityDecodedMediaOutcome(
                    filesWritten = 0,
                    bytesWritten = 0L,
                    imageCount = 0,
                    audioCount = 0,
                    videoCount = 0,
                    textCount = 0,
                    existingFilesSkipped = 0,
                    manifestWritten = false,
                    skippedCount = 1,
                    serializedFileCount = 0,
                    objectCount = 0,
                    resourceCount = 0,
                    diagnosticCount = 0,
                    failureRecords = listOf(
                        GameExtractionFailure(
                            item = "Unity 图片/音频/视频/文本对象解析",
                            reason = error.message ?: error::class.java.simpleName,
                        ),
                    ),
                )
            }
            fileCount += decodedOutcome.filesWritten
            byteCount += decodedOutcome.bytesWritten
            failureRecords += decodedOutcome.failureRecords
        }

        if (findExistingOutputFile(tree, UNITY_GUIDE_NAME) != null) {
            existingFilesSkipped += 1
        } else {
            val guide = createOutputFile(tree, UNITY_GUIDE_NAME)
            val guideText = buildUnityGuide(inspection, fileCount, selection, decodedOutcome)
            openOutputStreamCompat(context, guide.uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(guideText)
            } ?: error("无法写入逆向说明")
            fileCount += 1
            byteCount += guideText.toByteArray(Charsets.UTF_8).size
        }
        GameExtractionSummary(
            filesWritten = fileCount,
            bytesWritten = byteCount,
            outputDirectoryName = tree.name ?: "所选输出目录",
            skippedCount = decodedOutcome.skippedCount,
            failureRecords = failureRecords,
            existingFilesSkipped = existingFilesSkipped + decodedOutcome.existingFilesSkipped,
        )
    }

    private suspend fun decodeUnityMedia(
        inspection: UnityInspection,
        sourceUri: Uri,
        directorySnapshot: List<DocumentEntry>,
        outputTree: DocumentFile,
        exportImages: Boolean,
        exportAudio: Boolean,
        exportVideo: Boolean,
        exportText: Boolean,
        maximumOutputBytes: Long,
        onProgress: suspend (String) -> Unit,
    ): UnityDecodedMediaOutcome {
        require(maximumOutputBytes > 0L) { "本次资源导出已超过 8 GiB 安全上限" }
        val cacheRoot = File(context.cacheDir, UNITY_DECODE_CACHE_DIRECTORY).apply {
            require(isDirectory || mkdirs()) { "无法创建 Unity 解析缓存目录" }
        }
        val jobDirectory = File(cacheRoot, UUID.randomUUID().toString()).apply {
            require(mkdirs()) { "无法创建 Unity 解析任务目录" }
        }
        val stagingRoot = File(jobDirectory, "input").apply {
            require(mkdirs()) { "无法创建 Unity 输入暂存目录" }
        }
        val nativeOutput = File(jobDirectory, "output").apply {
            require(mkdirs()) { "无法创建 Unity 输出暂存目录" }
        }
        var stagingLease: Closeable? = null
        return try {
            val existingOutputIndex = runCatching {
                createUnityExistingOutputIndex(outputTree, jobDirectory)
            }.getOrNull()
            onProgress("正在暂存 Unity 资源及伴随数据…")
            val staging = stageUnityDecodeInput(
                inspection = inspection,
                sourceUri = sourceUri,
                directorySnapshot = directorySnapshot,
                stagingRoot = stagingRoot,
                onProgress = onProgress,
            )
            stagingLease = staging.closeable
            onProgress("正在解析 Unity 图片、音频、视频与文本对象…")
            val nativeReport = UnityNativeAssetBridge.exportMedia(
                input = staging.input,
                outputDirectory = nativeOutput,
                existingOutputIndex = existingOutputIndex,
                exportImages = exportImages,
                exportAudio = exportAudio,
                exportVideo = exportVideo,
                exportText = exportText,
                maximumOutputBytes = unityNativeOutputLimit(nativeOutput, maximumOutputBytes),
            )

            var filesWritten = 0
            var bytesWritten = 0L
            var copyFailures = 0
            var recordedCopyFailures = 0
            var manifestCopied = false
            var existingFilesSkipped = nativeReport.existingSkippedCount
            val failures = staging.failureRecords.toMutableList()
            if (staging.failedCount > staging.failureRecords.size && failures.size < MAX_UNITY_FAILURE_RECORDS) {
                failures += GameExtractionFailure(
                    "Unity 输入暂存",
                    "另有 ${staging.failedCount - staging.failureRecords.size} 个暂存失败条目未逐条列出",
                )
            }
            nativeReport.failureMessages.forEachIndexed { index, message ->
                if (failures.size < MAX_UNITY_FAILURE_RECORDS) {
                    failures += GameExtractionFailure("Unity 对象 ${index + 1}", message)
                }
            }
            val listedNativeFailures = (nativeReport.failureMessages.size -
                if (nativeReport.manifestWritten) 0 else 1).coerceAtLeast(0)
            val unlistedNativeFailures = (nativeReport.failedCount + nativeReport.unsupportedCount +
                nativeReport.diagnosticCount - listedNativeFailures).coerceAtLeast(0)
            if (unlistedNativeFailures > 0 && failures.size < MAX_UNITY_FAILURE_RECORDS) {
                failures += GameExtractionFailure(
                    "Unity 对象解析",
                    "另有 $unlistedNativeFailures 个失败或暂不支持的对象未逐条列出",
                )
            }

            nativeOutput.walkTopDown().filter(File::isFile).forEachIndexed { index, decodedFile ->
                val relativePath = decodedFile.relativeTo(nativeOutput).invariantSeparatorsPath
                if (findExistingOutputFile(outputTree, relativePath) != null) {
                    existingFilesSkipped += 1
                    if (relativePath == UNITY_EXPORT_MANIFEST_NAME) manifestCopied = true
                    return@forEachIndexed
                }
                runCatching {
                    if (index % UNITY_PROGRESS_BATCH_SIZE == 0) {
                        onProgress("正在写出 Unity 解析产物：${decodedFile.name}")
                    }
                    val target = createOutputFile(outputTree, relativePath)
                    val written = FileInputStream(decodedFile).use { source ->
                        writeStreamToDocument(source, target, maximumOutputBytes - bytesWritten)
                    }
                    filesWritten += 1
                    bytesWritten += written
                    if (relativePath == UNITY_EXPORT_MANIFEST_NAME) manifestCopied = true
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    copyFailures += 1
                    if (failures.size < MAX_UNITY_FAILURE_RECORDS) {
                        recordedCopyFailures += 1
                        failures += GameExtractionFailure(
                            item = relativePath,
                            reason = "写入输出目录失败：${error.message ?: error::class.java.simpleName}",
                        )
                    }
                }
            }
            if (copyFailures > recordedCopyFailures && failures.size < MAX_UNITY_FAILURE_RECORDS) {
                failures += GameExtractionFailure(
                    "Unity 输出写入",
                    "另有 ${copyFailures - recordedCopyFailures} 个写入失败条目未逐条列出",
                )
            }
            if (nativeReport.exportedCount == 0 && nativeReport.existingSkippedCount == 0) {
                failures += GameExtractionFailure(
                    item = "Unity 图片/音频/视频/文本对象",
                    reason = "没有发现可导出的所选对象；可能缺少配套 .resS/.resource、资源版本暂不支持或游戏使用了自定义封装",
                )
            }
            UnityDecodedMediaOutcome(
                filesWritten = filesWritten,
                bytesWritten = bytesWritten,
                imageCount = nativeReport.imageCount,
                audioCount = nativeReport.audioCount,
                videoCount = nativeReport.videoCount,
                textCount = nativeReport.textCount,
                existingFilesSkipped = existingFilesSkipped,
                manifestWritten = nativeReport.manifestWritten && manifestCopied,
                skippedCount = staging.failedCount + nativeReport.failedCount +
                    nativeReport.unsupportedCount + nativeReport.diagnosticCount + copyFailures +
                    if (nativeReport.exportedCount == 0 && nativeReport.existingSkippedCount == 0) 1 else 0,
                serializedFileCount = nativeReport.serializedFileCount,
                objectCount = nativeReport.objectCount,
                resourceCount = nativeReport.resourceCount,
                diagnosticCount = nativeReport.diagnosticCount,
                failureRecords = failures,
            )
        } finally {
            runCatching { stagingLease?.close() }
            deleteUnityDecodeJob(cacheRoot, jobDirectory)
        }
    }

    private suspend fun stageUnityDecodeInput(
        inspection: UnityInspection,
        sourceUri: Uri,
        directorySnapshot: List<DocumentEntry>,
        stagingRoot: File,
        onProgress: suspend (String) -> Unit,
    ): UnityStagingOutcome {
        var stagedBytes = 0L
        var stagedFiles = 0
        var failedFiles = 0
        val failures = mutableListOf<GameExtractionFailure>()

        fun recordStagingFailure(relativePath: String, error: Throwable) {
            if (error is CancellationException) throw error
            failedFiles += 1
            if (failures.size < MAX_UNITY_FAILURE_RECORDS) {
                failures += GameExtractionFailure(
                    item = relativePath,
                    reason = "暂存失败：${error.message ?: error::class.java.simpleName}",
                )
            }
        }

        fun copyStream(
            relativePath: String,
            source: InputStream,
            prefix: ByteArray = ByteArray(0),
            expectedBytes: Long = -1L,
        ): File? {
            val target = resolveUnityStagingFile(stagingRoot, relativePath)
            return runCatching {
                require(!target.exists()) { "候选路径重复或安全化后发生冲突" }
                if (expectedBytes > 0L) ensureUnityStagingCapacity(stagingRoot, expectedBytes)
                val written = writeStreamToLocal(
                    source = source,
                    target = target,
                    remainingLimit = MAX_UNITY_STAGING_BYTES - stagedBytes,
                    prefix = prefix,
                )
                stagedBytes += written
                stagedFiles += 1
                target
            }.getOrElse { error ->
                recordStagingFailure(relativePath, error)
                null
            }
        }

        fun copySource(relativePath: String, source: () -> InputStream): File? = runCatching {
            source().use { input -> copyStream(relativePath, input) }
        }.getOrElse { error ->
            recordStagingFailure(relativePath, error)
            null
        }

        var visitedArchiveEntries = 0
        suspend fun stageArchive(source: InputStream, sourceName: String?) {
            ZipInputStream(BufferedInputStream(source)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        visitedArchiveEntries += 1
                        require(visitedArchiveEntries <= MAX_ZIP_ENTRIES) {
                            "压缩包条目过多，已中止 Unity 候选提取"
                        }
                        val probe = probeUnityArchiveEntry(entry.name, entry.size, zip)
                        if (probe.kind?.isUnityDecodeCandidate() == true) {
                            val relativePath = if (sourceName == null) {
                                entry.name
                            } else {
                                "$sourceName/${entry.name}"
                            }
                            if (stagedFiles % UNITY_PROGRESS_BATCH_SIZE == 0) {
                                onProgress("正在提取 Unity 候选：${entry.name.substringAfterLast('/')}")
                            }
                            copyStream(
                                relativePath = relativePath,
                                source = zip,
                                prefix = probe.consumedPrefix,
                                expectedBytes = entry.size,
                            )
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        suspend fun stageIndexedArchive(archive: ZipFile, sourceName: String?) {
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                visitedArchiveEntries += 1
                require(visitedArchiveEntries <= MAX_ZIP_ENTRIES) {
                    "压缩包条目过多，已中止 Unity 候选提取"
                }
                val knownKind = classifyUnityPath(entry.name)
                val relativePath = if (sourceName == null) entry.name else "$sourceName/${entry.name}"
                val probeResult = runCatching {
                    when {
                        knownKind != null -> UnityArchiveEntryProbe(kind = knownKind)
                        !shouldProbeUnityPath(entry.name, entry.size) -> UnityArchiveEntryProbe()
                        else -> archive.getInputStream(entry).use { source ->
                            probeUnityArchiveEntry(entry.name, entry.size, source)
                        }
                    }
                }
                if (probeResult.isFailure) {
                    recordStagingFailure(relativePath, probeResult.exceptionOrNull()!!)
                    continue
                }
                val probe = probeResult.getOrThrow()
                if (probe.kind?.isUnityDecodeCandidate() != true) continue
                if (stagedFiles % UNITY_PROGRESS_BATCH_SIZE == 0) {
                    onProgress("正在提取 Unity 候选：${entry.name.substringAfterLast('/')}")
                }
                val entrySource = runCatching { archive.getInputStream(entry) }.getOrElse { error ->
                    recordStagingFailure(relativePath, error)
                    null
                }
                entrySource?.use { source ->
                    copyStream(
                        relativePath = relativePath,
                        source = source,
                        expectedBytes = entry.size,
                    )
                }
            }
        }

        suspend fun tryStageIndexedArchive(file: File, sourceName: String?): Boolean {
            val archive = runCatching { ZipFile(file) }.getOrNull() ?: return false
            archive.use { stageIndexedArchive(it, sourceName) }
            return true
        }

        val input = when {
            inspection.installedPackageName != null -> {
                @Suppress("DEPRECATION")
                val applicationInfo = context.packageManager.getApplicationInfo(inspection.installedPackageName, 0)
                val apkFiles = installedApkFiles(applicationInfo)
                require(apkFiles.isNotEmpty()) { "应用已被卸载或安装包暂时不可读" }
                apkFiles.forEachIndexed { index, apkFile ->
                    onProgress("正在扫描安装包 ${index + 1}/${apkFiles.size}：${apkFile.name}")
                    val sourceName = apkFile.name.takeIf { apkFiles.size > 1 }
                    if (!tryStageIndexedArchive(apkFile, sourceName)) {
                        FileInputStream(apkFile).use { source -> stageArchive(source, sourceName) }
                    }
                }
                stagingRoot
            }
            inspection.isArchive -> {
                onProgress("正在扫描 APK/ZIP 内的 Unity 候选资源…")
                var indexed = false
                openSeekableUnityInput(sourceUri)?.let { (directInput, descriptor) ->
                    try {
                        indexed = tryStageIndexedArchive(directInput, null)
                    } finally {
                        runCatching { descriptor?.close() }
                    }
                }
                if (!indexed) {
                    openInputStreamCompat(context, sourceUri)?.use { source -> stageArchive(source, null) }
                        ?: error("无法读取 Unity 包")
                }
                stagingRoot
            }
            inspection.isDirectory -> {
                val candidates = directorySnapshot.filter(::shouldStageUnityDecodeFile)
                ensureUnityStagingCapacity(
                    stagingRoot,
                    checkedUnityInputSize(candidates.map { it.file.length() }),
                )
                candidates.forEachIndexed { index, entry ->
                    if (index % UNITY_PROGRESS_BATCH_SIZE == 0) {
                        onProgress("正在暂存 Unity 资源 ${index + 1}/${candidates.size}…")
                    }
                    copySource(entry.relativePath) {
                        openInputStreamCompat(context, entry.file.uri) ?: error("无法读取 ${entry.relativePath}")
                    }
                }
                stagingRoot
            }
            else -> {
                openSeekableUnityInput(sourceUri)?.let { (directInput, descriptor) ->
                    onProgress("输入支持随机访问，无需复制到缓存")
                    return UnityStagingOutcome(directInput, failures, failedFiles, descriptor)
                }
                ensureUnityStagingCapacity(stagingRoot, inspection.input.sizeBytes)
                val target = copySource(inspection.input.name) {
                    openInputStreamCompat(context, sourceUri) ?: error("无法读取所选 Unity 文件")
                }
                target ?: error("Unity 文件暂存失败")
            }
        }
        require(stagedFiles > 0) { "没有可供解析的 Unity 资源文件" }
        return UnityStagingOutcome(input, failures, failedFiles)
    }

    private fun createUnityExistingOutputIndex(outputTree: DocumentFile, jobDirectory: File): File? {
        val indexFile = File(jobDirectory, UNITY_EXISTING_OUTPUT_INDEX_NAME)
        var indexedFiles = 0
        FileOutputStream(indexFile).bufferedWriter(Charsets.UTF_8).use { writer ->
            for (group in outputTree.listFiles()) {
                val groupName = group.name ?: continue
                if (!group.isDirectory || !UNITY_EXPORT_GROUP.matches(groupName)) continue
                for (categoryDirectory in group.listFiles()) {
                    val category = categoryDirectory.name?.lowercase(Locale.ROOT) ?: continue
                    if (!categoryDirectory.isDirectory || category !in UNITY_DECODED_CATEGORIES) continue
                    val pending = ArrayDeque<DocumentFile>()
                    pending.addLast(categoryDirectory)
                    while (pending.isNotEmpty()) {
                        val entry = pending.removeFirst()
                        if (entry.isDirectory) {
                            entry.listFiles().forEach(pending::addLast)
                            continue
                        }
                        if (!entry.isFile) continue
                        val pathId = entry.name?.let { name ->
                            UNITY_EXPORTED_PATH_ID.find(name)?.groupValues?.getOrNull(1)?.toLongOrNull()
                        } ?: continue
                        indexedFiles += 1
                        require(indexedFiles <= MAX_UNITY_EXISTING_OUTPUTS) {
                            "已有 Unity 解析产物超过索引上限，无法安全判断增量导出"
                        }
                        writer.append(groupName.lowercase(Locale.ROOT))
                            .append('|')
                            .append(category)
                            .append('|')
                            .append(pathId.toString())
                            .appendLine()
                    }
                }
            }
        }
        if (indexedFiles == 0) {
            indexFile.delete()
            return null
        }
        return indexFile
    }

    private fun openSeekableUnityInput(uri: Uri): Pair<File, Closeable?>? {
        directFileFromUri(uri)?.takeIf { it.isFile && it.canRead() }?.let { directFile ->
            return directFile to null
        }
        val descriptor = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
        return try {
            Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_SET)
            val procFile = File("/proc/self/fd/${descriptor.fd}")
            if (!procFile.exists()) {
                descriptor.close()
                null
            } else {
                procFile to descriptor
            }
        } catch (_: Throwable) {
            runCatching { descriptor.close() }
            null
        }
    }

    private fun checkedUnityInputSize(sizes: Iterable<Long>): Long {
        var total = 0L
        sizes.forEach { size ->
            if (size > 0L) {
                total = Math.addExact(total, size)
            }
        }
        return total
    }

    private fun ensureUnityStagingCapacity(directory: File, expectedBytes: Long) {
        if (expectedBytes <= 0L) return
        require(expectedBytes <= MAX_UNITY_STAGING_BYTES) { "Unity 输入暂存已超过 8 GiB 安全上限" }
        val reserve = maxOf(MIN_UNITY_CACHE_RESERVE_BYTES, expectedBytes / 10L)
        val required = runCatching { Math.addExact(expectedBytes, reserve) }.getOrElse { Long.MAX_VALUE }
        val available = directory.usableSpace
        require(available <= 0L || required <= available) {
            "应用缓存空间不足：暂存约需 ${formatUnityBytes(expectedBytes)}，当前可用 ${formatUnityBytes(available)}"
        }
    }

    private fun unityNativeOutputLimit(directory: File, requestedBytes: Long): Long {
        val available = directory.usableSpace
        if (available <= 0L) return requestedBytes
        val safeLimit = available - MIN_UNITY_CACHE_RESERVE_BYTES
        require(safeLimit > 0L) {
            "应用缓存剩余空间不足，至少需要保留 ${formatUnityBytes(MIN_UNITY_CACHE_RESERVE_BYTES)}"
        }
        return minOf(requestedBytes, safeLimit)
    }

    private fun formatUnityBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.1f MiB".format(Locale.US, bytes / (1024.0 * 1024.0))
        else -> "$bytes B"
    }

    private fun shouldStageUnityDecodeFile(entry: DocumentEntry): Boolean =
        classifyUnityDocument(entry)?.isUnityDecodeCandidate() == true

    private fun UnityArtifactKind.isUnityDecodeCandidate(): Boolean =
        this == UnityArtifactKind.ASSET || this == UnityArtifactKind.CONFIG ||
            this == UnityArtifactKind.NESTED_PACKAGE

    private fun resolveUnityStagingFile(root: File, unsafePath: String): File {
        val rawParts = unsafePath.replace('\\', '/').trimStart('/').split('/').filter(String::isNotBlank)
        require(rawParts.isNotEmpty() && rawParts.none { it == "." || it == ".." }) { "Unity 暂存路径不安全" }
        require(rawParts.size <= MAX_UNITY_STAGING_PATH_COMPONENTS) { "Unity 暂存路径层级过深" }
        val target = rawParts.map(::safeName).fold(root) { parent, name -> File(parent, name) }
        val canonicalRoot = root.canonicalFile
        val canonicalTarget = target.canonicalFile
        require(canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) { "Unity 暂存路径越界" }
        return canonicalTarget
    }

    private fun writeStreamToLocal(
        source: InputStream,
        target: File,
        remainingLimit: Long,
        prefix: ByteArray = ByteArray(0),
    ): Long {
        require(remainingLimit > 0L) { "Unity 输入暂存已超过 8 GiB 安全上限" }
        require(prefix.size.toLong() <= remainingLimit) { "Unity 输入暂存已超过 8 GiB 安全上限" }
        require(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建 Unity 暂存子目录" }
        return try {
            FileOutputStream(target).use { destination ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = prefix.size.toLong()
                if (prefix.isNotEmpty()) destination.write(prefix)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= remainingLimit) { "Unity 输入暂存已超过 8 GiB 安全上限" }
                    destination.write(buffer, 0, count)
                }
                total
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun deleteUnityDecodeJob(cacheRoot: File, jobDirectory: File) {
        val canonicalRoot = cacheRoot.canonicalFile
        val canonicalJob = jobDirectory.canonicalFile
        if (canonicalJob.parentFile == canonicalRoot) {
            canonicalJob.deleteRecursively()
        }
    }

    private fun describeDirect(uri: Uri): GameInputFile {
        val (name, size) = queryNameAndSize(uri)
        return GameInputFile(uri.toString(), name, size, classifyInputName(name))
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> = queryNameAndSizeCompat(context, uri)

    private fun decryptMvMzResource(
        uri: Uri,
        inputName: String,
        relativePath: String,
        key: ByteArray,
        output: DocumentFile,
    ): Long = openInputStreamCompat(context, uri)?.use { source ->
        decryptMvMzResource(source, inputName, relativePath, key, output)
    } ?: error("无法读取 $inputName")

    private fun decryptMvMzResource(
        source: InputStream,
        inputName: String,
        relativePath: String,
        key: ByteArray,
        output: DocumentFile,
    ): Long {
        require(key.size == RPG_MV_KEY_SIZE) { "RPG Maker MV/MZ 密钥必须是 32 位十六进制字符" }
        val outputName = decryptedResourceName(relativePath)
        val target = createOutputFile(output, outputName)
        return try {
            val fakeHeader = source.readExactly(RPG_MV_HEADER_SIZE)
            require(fakeHeader.size == RPG_MV_HEADER_SIZE && fakeHeader.startsWith(RPG_MV_SIGNATURE)) {
                "$inputName 缺少标准 RPGMV 加密头"
            }
            val encryptedHeader = source.readExactly(RPG_MV_KEY_SIZE)
            require(encryptedHeader.size == RPG_MV_KEY_SIZE) { "$inputName 内容不完整" }
            val decryptedHeader = ByteArray(RPG_MV_KEY_SIZE) { index ->
                (encryptedHeader[index].toInt() xor key[index].toInt()).toByte()
            }
            require(validResourceHeader(outputName, decryptedHeader)) { "$inputName 的密钥不正确或文件格式不受支持" }
            openOutputStreamCompat(context, target.uri, "w")?.use { destination ->
                destination.write(decryptedHeader)
                val copied = source.copyTo(destination)
                RPG_MV_KEY_SIZE + copied
            } ?: error("无法创建 $outputName")
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun extractRgssArchive(uri: Uri, output: DocumentFile): Pair<Int, Long> =
        openInputStreamCompat(context, uri)?.use { source -> extractRgssArchive(source, output) }
            ?: error("无法读取 RGSS 档案")

    private fun extractRgssArchive(source: InputStream, output: DocumentFile): Pair<Int, Long> {
        val cache = File.createTempFile("rgss_", ".archive", context.cacheDir)
        try {
            FileOutputStream(cache).use { destination -> source.copyTo(destination) }
            RandomAccessFile(cache, "r").use { archive ->
                val signature = ByteArray(7)
                archive.readFully(signature)
                require(signature.contentEquals(RGSS_SIGNATURE)) { "不是有效的 RGSSAD 档案" }
                return when (val version = archive.readUnsignedByte()) {
                    1 -> extractRgssV1(archive, output)
                    3 -> extractRgssV3(archive, output)
                    else -> error("暂不支持 RGSSAD 版本 $version")
                }
            }
        } finally {
            cache.delete()
        }
    }

    private fun extractRgssV1(archive: RandomAccessFile, output: DocumentFile): Pair<Int, Long> {
        var key = 0xDEADCAFEu
        var files = 0
        var bytes = 0L
        while (archive.filePointer < archive.length()) {
            require(files < MAX_ARCHIVE_ENTRIES) { "档案条目过多，已中止" }
            val nameLength = (archive.readUIntLE() xor key).toLong()
            key = advanceRgssKey(key)
            require(nameLength in 1..MAX_ARCHIVE_NAME_BYTES.toLong()) { "RGSSAD 文件名长度异常" }
            val encryptedName = ByteArray(nameLength.toInt())
            archive.readFully(encryptedName)
            encryptedName.indices.forEach { index ->
                encryptedName[index] = (encryptedName[index].toInt() xor (key and 0xFFu).toInt()).toByte()
                key = advanceRgssKey(key)
            }
            val fileSize = (archive.readUIntLE() xor key).toLong()
            key = advanceRgssKey(key)
            require(fileSize <= archive.length() - archive.filePointer) { "RGSSAD 文件大小超出档案边界" }
            val relativeName = String(encryptedName, Charsets.UTF_8)
            val target = createOutputFile(output, relativeName)
            try {
                openOutputStreamCompat(context, target.uri, "w")?.use { destination ->
                    decryptRgssData(archive, destination, fileSize, key)
                } ?: error("无法创建 $relativeName")
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
            files += 1
            bytes += fileSize
        }
        return files to bytes
    }

    private fun extractRgssV3(archive: RandomAccessFile, output: DocumentFile): Pair<Int, Long> {
        val masterKey = archive.readUIntLE() * 9u + 3u
        val entries = mutableListOf<RgssEntry>()
        while (true) {
            require(entries.size < MAX_ARCHIVE_ENTRIES) { "档案条目过多，已中止" }
            val offset = (archive.readUIntLE() xor masterKey).toLong()
            val size = (archive.readUIntLE() xor masterKey).toLong()
            val fileKey = archive.readUIntLE() xor masterKey
            val nameLength = (archive.readUIntLE() xor masterKey).toLong()
            if (offset == 0L) break
            require(nameLength in 1..MAX_ARCHIVE_NAME_BYTES.toLong()) { "RGSS3A 文件名长度异常" }
            require(offset <= archive.length() && size <= archive.length() - offset) { "RGSS3A 条目超出档案边界" }
            val name = ByteArray(nameLength.toInt())
            archive.readFully(name)
            name.indices.forEach { index ->
                val shift = (index % 4) * 8
                name[index] = (name[index].toInt() xor ((masterKey shr shift) and 0xFFu).toInt()).toByte()
            }
            entries += RgssEntry(String(name, Charsets.UTF_8), offset, size, fileKey)
        }
        var bytes = 0L
        entries.forEach { entry ->
            archive.seek(entry.offset)
            val target = createOutputFile(output, entry.name)
            try {
                openOutputStreamCompat(context, target.uri, "w")?.use { destination ->
                    decryptRgssData(archive, destination, entry.size, entry.key)
                } ?: error("无法创建 ${entry.name}")
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
            bytes += entry.size
        }
        return entries.size to bytes
    }

    private fun decryptRgssData(
        archive: RandomAccessFile,
        output: OutputStream,
        size: Long,
        initialKey: UInt,
    ) {
        var remaining = size
        var key = initialKey
        var keyIndex = 0
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0L) {
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val count = archive.read(buffer, 0, requested)
            if (count < 0) throw EOFException("RGSSAD 文件数据提前结束")
            for (index in 0 until count) {
                val shift = keyIndex * 8
                buffer[index] = (buffer[index].toInt() xor ((key shr shift) and 0xFFu).toInt()).toByte()
                keyIndex += 1
                if (keyIndex == 4) {
                    keyIndex = 0
                    key = advanceRgssKey(key)
                }
            }
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun inspectUnityArchive(uri: Uri, input: GameInputFile): UnityInspection {
        var indexedScan: UnityArchiveInspectionSnapshot? = null
        openSeekableUnityInput(uri)?.let { (directInput, descriptor) ->
            try {
                indexedScan = inspectUnityIndexedArchive(directInput, null)
            } finally {
                runCatching { descriptor?.close() }
            }
        }
        val scan = indexedScan ?: openInputStreamCompat(context, uri)?.use { source ->
            inspectUnityStreamingArchive(source)
        } ?: error("无法读取 Unity 包")
        require(scan.counts.isNotEmpty()) { "包内未发现常见 Unity 代码或资源结构" }
        return UnityInspection(
            input = input.copy(kind = GameInputKind.UNITY_PACKAGE),
            backend = backendForCounts(scan.counts),
            totalEntries = scan.totalEntries,
            artifactCounts = scan.counts,
            previewArtifacts = scan.preview,
            previewTruncated = scan.counts.values.sum() > scan.preview.size,
            isArchive = true,
        )
    }

    private fun inspectUnityIndexedArchive(
        file: File,
        previewPrefix: String?,
    ): UnityArchiveInspectionSnapshot? {
        val archive = runCatching { ZipFile(file) }.getOrNull() ?: return null
        return runCatching {
            archive.use { zip ->
                val counts = mutableMapOf<UnityArtifactKind, Int>()
                val preview = mutableListOf<UnityArtifact>()
                var totalEntries = 0
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    totalEntries += 1
                    require(totalEntries <= MAX_ZIP_ENTRIES) { "压缩包条目过多，已中止分析" }
                    val kind = classifyUnityPath(entry.name) ?: if (shouldProbeUnityPath(entry.name, entry.size)) {
                        runCatching {
                            zip.getInputStream(entry).use { source ->
                                probeUnityArchiveEntry(entry.name, entry.size, source).kind
                            }
                        }.getOrNull()
                    } else {
                        null
                    }
                    kind?.let {
                        counts[it] = counts.getOrDefault(it, 0) + 1
                        if (preview.size < MAX_UNITY_PREVIEW_ITEMS) {
                            val path = previewPrefix?.let { prefix -> "$prefix!/${entry.name}" } ?: entry.name
                            preview += UnityArtifact(path, it, entry.size)
                        }
                    }
                }
                UnityArchiveInspectionSnapshot(totalEntries, counts.toMap(), preview)
            }
        }.getOrNull()
    }

    private fun inspectUnityStreamingArchive(
        source: InputStream,
        previewPrefix: String? = null,
    ): UnityArchiveInspectionSnapshot {
        val counts = mutableMapOf<UnityArtifactKind, Int>()
        val preview = mutableListOf<UnityArtifact>()
        var totalEntries = 0
        ZipInputStream(BufferedInputStream(source)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    totalEntries += 1
                    require(totalEntries <= MAX_ZIP_ENTRIES) { "压缩包条目过多，已中止分析" }
                    classifyUnityArchiveEntry(entry.name, entry.size, zip)?.let { kind ->
                        counts[kind] = counts.getOrDefault(kind, 0) + 1
                        if (preview.size < MAX_UNITY_PREVIEW_ITEMS) {
                            val path = previewPrefix?.let { prefix -> "$prefix!/${entry.name}" } ?: entry.name
                            preview += UnityArtifact(path, kind, entry.size)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return UnityArchiveInspectionSnapshot(totalEntries, counts.toMap(), preview)
    }

    private fun installedApkFiles(applicationInfo: android.content.pm.ApplicationInfo): List<File> = buildList {
        applicationInfo.sourceDir?.let(::File)?.takeIf(File::isFile)?.let(::add)
        applicationInfo.splitSourceDirs.orEmpty()
            .map(::File)
            .filter(File::isFile)
            .forEach(::add)
    }

    private fun snapshotDirectory(treeUri: Uri): Pair<DocumentFile, List<DocumentEntry>> {
        val root = directoryDocumentFile(context, treeUri)?.takeIf(DocumentFile::isDirectory)
            ?: error("无法读取所选目录")
        val pending = ArrayDeque<DocumentEntry>()
        root.listFiles().forEach { child ->
            pending.addLast(DocumentEntry(child, child.name ?: "unnamed"))
        }
        val files = mutableListOf<DocumentEntry>()
        var visited = 0
        while (pending.isNotEmpty()) {
            val entry = pending.removeFirst()
            visited += 1
            require(visited <= MAX_ZIP_ENTRIES) { "目录条目超过 100000 个，已中止扫描" }
            if (entry.file.isDirectory) {
                entry.file.listFiles().forEach { child ->
                    val childName = child.name ?: "unnamed"
                    pending.addLast(DocumentEntry(child, "${entry.relativePath}/$childName"))
                }
            } else {
                // Some SAF providers report no MIME type for .rvdata2 and other game files,
                // which makes DocumentFile.isFile false even though the document is readable.
                files += entry
            }
        }
        return root to files
    }

    private fun classifyUnityPath(rawPath: String): UnityArtifactKind? {
        val path = rawPath.replace('\\', '/').trimStart('/').lowercase(Locale.ROOT)
        val fileName = path.substringAfterLast('/')
        return when {
            fileName == "global-metadata.dat" -> UnityArtifactKind.IL2CPP_METADATA
            isIl2CppBinaryName(fileName) -> UnityArtifactKind.IL2CPP_BINARY
            fileName.endsWith(".dll") && ("/managed/" in "/$path" || fileName.startsWith("assembly-csharp")) ->
                UnityArtifactKind.MONO_ASSEMBLY
            fileName.endsWith(".apk") -> UnityArtifactKind.NESTED_PACKAGE
            isUnityAssetPath(path, fileName) -> UnityArtifactKind.ASSET
            fileName == "boot.config" || fileName == "globalgamemanagers" ||
                fileName == "globalgamemanagers.assets" || fileName == "scriptingassemblies.json" ||
                fileName == "app.info" -> UnityArtifactKind.CONFIG
            else -> null
        }
    }

    private fun isUnityAssetPath(path: String, fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in UNITY_ASSET_EXTENSIONS ||
            UNITY_SPLIT_SUFFIX.matches(fileName) ||
            fileName == "data.unity3d" ||
            fileName.startsWith("level") && fileName.drop(5).all(Char::isDigit) ||
            ("/assets/bin/data/" in "/$path" && fileName.startsWith("sharedassets"))
    }

    private fun isLikelyUnityContentPath(path: String): Boolean =
        path.startsWith("assets/") || "/streamingassets/" in "/$path" ||
            "/assetbundles/" in "/$path"

    private fun classifyUnityDocument(entry: DocumentEntry): UnityArtifactKind? =
        classifyUnityPath(entry.relativePath)
            ?: if (shouldProbeUnityDocument(entry)) {
                detectUnityAssetKind(entry.file.uri, entry.file.length(), entry.relativePath)
            } else {
                null
            }

    private fun shouldProbeUnityDocument(entry: DocumentEntry): Boolean {
        return shouldProbeUnityPath(entry.relativePath, entry.file.length())
    }

    private fun shouldProbeUnityPath(path: String, sizeBytes: Long): Boolean {
        if (sizeBytes > 0L && sizeBytes < 20L) return false
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension !in UNITY_NON_CONTAINER_EXTENSIONS
    }

    private fun classifyUnityArchiveEntry(
        path: String,
        sizeBytes: Long,
        source: InputStream,
    ): UnityArtifactKind? = probeUnityArchiveEntry(path, sizeBytes, source).kind

    private fun probeUnityArchiveEntry(
        path: String,
        sizeBytes: Long,
        source: InputStream,
    ): UnityArchiveEntryProbe {
        classifyUnityPath(path)?.let { kind ->
            return UnityArchiveEntryProbe(kind = kind)
        }
        if (!shouldProbeUnityPath(path, sizeBytes)) return UnityArchiveEntryProbe()
        val header = source.readExactly(UNITY_HEADER_PROBE_BYTES)
        val kind = if (looksLikeUnityAssetHeader(header, sizeBytes, path)) {
            UnityArtifactKind.ASSET
        } else {
            null
        }
        return UnityArchiveEntryProbe(kind = kind, consumedPrefix = header)
    }

    private fun detectUnityAssetKind(uri: Uri, sizeBytes: Long, pathHint: String): UnityArtifactKind? =
        runCatching {
            openInputStreamCompat(context, uri)?.use { source ->
                val header = source.readExactly(UNITY_HEADER_PROBE_BYTES)
                if (looksLikeUnityAssetHeader(header, sizeBytes, pathHint)) UnityArtifactKind.ASSET else null
            }
        }.getOrNull()

    private fun looksLikeUnityAssetHeader(header: ByteArray, sizeBytes: Long, pathHint: String): Boolean {
        if (UNITY_ARCHIVE_SIGNATURES.any { signature -> header.startsWith(signature) }) return true
        if (looksLikeSerializedFileHeader(header, sizeBytes)) return true
        val normalizedPath = pathHint.replace('\\', '/').lowercase(Locale.ROOT)
        return isLikelyUnityContentPath(normalizedPath) &&
            (header.startsWith(ZIP_SIGNATURE) || header.startsWith(GZIP_SIGNATURE))
    }

    private fun looksLikeSerializedFileHeader(header: ByteArray, actualSize: Long): Boolean {
        if (header.size < 20) return false
        val version = header.readUInt32BigEndian(8)
        if (version !in 5L..100L) return false
        val endian = header[16].toInt() and 0xFF
        if (version >= 9L && endian !in 0..1) return false
        val extended = version >= 22L
        if (extended && header.size < 48) return false
        val metadataSize = if (extended) header.readUInt32BigEndian(20) else header.readUInt32BigEndian(0)
        val declaredSize = if (extended) header.readUInt64BigEndian(24) else header.readUInt32BigEndian(4)
        val dataOffset = if (extended) header.readUInt64BigEndian(32) else header.readUInt32BigEndian(12)
        val headerSize = if (extended) 48L else 20L
        if (metadataSize <= 0L || metadataSize > MAX_UNITY_METADATA_BYTES) return false
        if (declaredSize < headerSize || dataOffset < headerSize || dataOffset >= declaredSize) return false
        if (metadataSize > declaredSize || metadataSize > dataOffset) return false
        return actualSize <= 0L || declaredSize <= actualSize
    }

    private fun ByteArray.readUInt32BigEndian(offset: Int): Long {
        if (offset < 0 || offset + 4 > size) return -1L
        return ((this[offset].toLong() and 0xFFL) shl 24) or
            ((this[offset + 1].toLong() and 0xFFL) shl 16) or
            ((this[offset + 2].toLong() and 0xFFL) shl 8) or
            (this[offset + 3].toLong() and 0xFFL)
    }

    private fun ByteArray.readUInt64BigEndian(offset: Int): Long {
        if (offset < 0 || offset + 8 > size) return -1L
        var value = 0L
        for (index in offset until offset + 8) {
            if (value > (Long.MAX_VALUE ushr 8)) return -1L
            value = (value shl 8) or (this[index].toLong() and 0xFFL)
        }
        return value
    }

    private fun isIl2CppBinaryName(fileName: String): Boolean =
        fileName == "libil2cpp.so" || fileName == "gameassembly.dll" ||
            fileName == "gameassembly.so" || fileName == "gameassembly.dylib"

    private fun backendForCounts(counts: Map<UnityArtifactKind, Int>): UnityBackend {
        val mono = counts.getOrDefault(UnityArtifactKind.MONO_ASSEMBLY, 0) > 0
        val il2Cpp = counts.getOrDefault(UnityArtifactKind.IL2CPP_BINARY, 0) > 0 ||
            counts.getOrDefault(UnityArtifactKind.IL2CPP_METADATA, 0) > 0
        return when {
            mono && il2Cpp -> UnityBackend.MIXED
            mono -> UnityBackend.MONO
            il2Cpp -> UnityBackend.IL2CPP
            else -> UnityBackend.UNKNOWN
        }
    }

    private fun gameInputKindForUnityArtifact(kind: UnityArtifactKind): GameInputKind = when (kind) {
        UnityArtifactKind.MONO_ASSEMBLY -> GameInputKind.UNITY_MONO_ASSEMBLY
        UnityArtifactKind.IL2CPP_BINARY -> GameInputKind.UNITY_IL2CPP_BINARY
        UnityArtifactKind.IL2CPP_METADATA -> GameInputKind.UNITY_IL2CPP_METADATA
        UnityArtifactKind.ASSET, UnityArtifactKind.CONFIG -> GameInputKind.UNITY_ASSET
        UnityArtifactKind.NESTED_PACKAGE -> GameInputKind.UNITY_PACKAGE
    }

    private fun isZip(uri: Uri): Boolean = openInputStreamCompat(context, uri)?.use { stream ->
        val signature = stream.readExactly(4)
        signature.size >= 4 && signature[0] == 0x50.toByte() && signature[1] == 0x4B.toByte() &&
            signature[2] in byteArrayOf(0x03, 0x05, 0x07) && signature[3] in byteArrayOf(0x04, 0x06, 0x08)
    } ?: false

    private fun writableTree(uri: Uri): DocumentFile =
        directoryDocumentFile(context, uri)?.takeIf { it.isDirectory && it.canWrite() }
            ?: error("所选输出目录不可写，请重新选择")

    private fun createOutputFile(root: DocumentFile, unsafePath: String): DocumentFile {
        val parts = outputPathParts(unsafePath)
        var directory = root
        parts.dropLast(1).forEach { part ->
            val existing = directory.findFile(part)
            directory = existing?.takeIf(DocumentFile::isDirectory)
                ?: directory.createDirectory(part)
                ?: error("无法创建目录 $part")
        }
        val name = parts.last()
        return createUniqueFile(directory, name, mimeForName(name))
    }

    private fun findExistingOutputFile(root: DocumentFile, unsafePath: String): DocumentFile? {
        var current = root
        for (part in outputPathParts(unsafePath)) {
            current = current.findFile(part) ?: return null
        }
        return current.takeIf(DocumentFile::isFile)
    }

    private fun outputPathParts(unsafePath: String): List<String> {
        val normalized = unsafePath.replace('\\', '/').trimStart('/')
        val rawParts = normalized.split('/').filter(String::isNotBlank)
        require(rawParts.isNotEmpty() && rawParts.none { it == "." || it == ".." }) { "文件路径不安全" }
        val boundedParts = if (rawParts.size <= MAX_PATH_COMPONENTS) {
            rawParts
        } else {
            rawParts.take(MAX_PATH_COMPONENTS - 1) + rawParts.last()
        }
        return boundedParts.map(::safeName)
    }

    private fun createUniqueFile(parent: DocumentFile, requestedName: String, mimeType: String): DocumentFile {
        val safe = safeName(requestedName)
        val dot = safe.lastIndexOf('.').takeIf { it > 0 } ?: safe.length
        val base = safe.substring(0, dot)
        val extension = safe.substring(dot)
        for (index in 1..999) {
            val candidate = if (index == 1) safe else "$base ($index)$extension"
            if (parent.findFile(candidate) == null) {
                return parent.createFile(mimeType, candidate) ?: error("无法创建文件 $candidate")
            }
        }
        error("无法分配输出文件名称")
    }

    private fun writeStreamToDocument(
        source: InputStream,
        target: DocumentFile,
        remainingLimit: Long,
        prefix: ByteArray = ByteArray(0),
    ): Long {
        require(remainingLimit > 0L) { "本次资源导出已超过 8 GiB 安全上限" }
        require(prefix.size.toLong() <= remainingLimit) { "本次资源导出已超过 8 GiB 安全上限" }
        return try {
            openOutputStreamCompat(context, target.uri, "w")?.use { destination ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = prefix.size.toLong()
                if (prefix.isNotEmpty()) destination.write(prefix)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= remainingLimit) { "本次资源导出已超过 8 GiB 安全上限" }
                    destination.write(buffer, 0, count)
                }
                total
            } ?: error("无法写入 ${target.name}")
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun buildUnityGuide(
        inspection: UnityInspection,
        exportedCount: Int,
        selection: UnityExtractionSelection,
        decoded: UnityDecodedMediaOutcome,
    ): String = buildString {
        appendLine("随身工具箱 · Unity 逆向导出说明")
        appendLine("输入：${inspection.input.name}")
        inspection.installedPackageName?.let { packageName ->
            appendLine("已安装应用包名：$packageName")
            appendLine("安装 APK 数量：${inspection.installedApkCount}")
        }
        appendLine("检测后端：${inspection.backend}")
        appendLine("已导出文件：$exportedCount")
        if (selection.decodedImages || selection.decodedAudio || selection.decodedVideo || selection.decodedText) {
            appendLine("Unity 序列化文件：${decoded.serializedFileCount}")
            appendLine("扫描对象：${decoded.objectCount}；伴随资源：${decoded.resourceCount}")
            appendLine(
                "解析图片：${decoded.imageCount}；解析音频：${decoded.audioCount}；" +
                    "解析视频：${decoded.videoCount}；解析文本：${decoded.textCount}",
            )
            if (decoded.existingFilesSkipped > 0) {
                appendLine("同路径同名产物已存在并跳过：${decoded.existingFilesSkipped}")
            }
            appendLine("导出清单：${if (decoded.manifestWritten) "已写出或沿用同路径已有清单" else "未生成"}")
            if (decoded.skippedCount > 0) appendLine("跳过或失败：${decoded.skippedCount}")
            if (decoded.diagnosticCount > 0) appendLine("载入诊断：${decoded.diagnosticCount}")
        }
        if ((inspection.backend == UnityBackend.IL2CPP || inspection.backend == UnityBackend.MIXED) &&
            !inspection.hasCompleteIl2CppPair
        ) {
            appendLine("警告：当前输入没有同时找到 IL2CPP 本机库与 global-metadata.dat，请从同一游戏构建补齐后再反编译。")
        }
        appendLine()
        appendLine("Mono：用 ILSpy 打开 Assembly-CSharp.dll 等 Managed 程序集查看可恢复的 C# 代码。")
        appendLine("ILSpy：https://github.com/icsharpcode/ILSpy")
        appendLine()
        appendLine("IL2CPP：需要匹配同一构建的 libil2cpp.so/GameAssembly.dll 与 global-metadata.dat，再交给 Cpp2IL 等桌面工具恢复程序集。")
        appendLine("Cpp2IL：https://github.com/SamboyCoding/Cpp2IL")
        appendLine()
        appendLine("Unity 资源：图片、音频、视频和 TextAsset 会分别归入各序列化文件子路径下的 picture/audio/video/text 目录；PNG 使用无损快速压缩。")
        appendLine("若仍需完整对象、模型或工程级恢复，可继续使用 AssetRipper 分析已保留的 .assets/.bundle/.resS 等原始文件。")
        appendLine("AssetRipper：https://github.com/AssetRipper/AssetRipper")
        appendLine()
        appendLine("本应用在本机解析常见 Unity 图片、音频、视频与文本对象并导出逆向所需文件；不声称能够覆盖自定义加密、所有 Unity 版本或完整还原 C# 源码。")
        appendLine("请仅分析你拥有或已获授权分析的内容，并遵守著作权、许可证和当地法律。")
    }

    private fun classifyInputName(name: String): GameInputKind {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".rgssad") || lower.endsWith(".rgss2a") || lower.endsWith(".rgss3a") ->
                GameInputKind.RPG_ARCHIVE
            RPG_ENCRYPTED_EXTENSIONS.any(lower::endsWith) -> GameInputKind.RPG_ENCRYPTED_RESOURCE
            lower == "system.json" -> GameInputKind.RPG_SYSTEM_JSON
            lower.endsWith(".apk") || lower.endsWith(".xapk") || lower.endsWith(".zip") -> GameInputKind.UNITY_PACKAGE
            isIl2CppBinaryName(lower) -> GameInputKind.UNITY_IL2CPP_BINARY
            lower.endsWith(".dll") -> GameInputKind.UNITY_MONO_ASSEMBLY
            lower == "global-metadata.dat" -> GameInputKind.UNITY_IL2CPP_METADATA
            classifyUnityPath(lower) != null -> GameInputKind.UNITY_ASSET
            else -> GameInputKind.UNKNOWN
        }
    }

    private fun rpgPackageRelativePath(rawPath: String): String? {
        val normalized = rawPath.replace('\\', '/').trimStart('/')
        val lower = normalized.lowercase(Locale.ROOT)
        val relativePath = when {
            lower.startsWith("assets/www/") -> normalized.substring("assets/www/".length)
            lower.startsWith("www/") -> normalized.substring("www/".length)
            else -> null
        }
        return relativePath?.takeIf(String::isNotBlank)
    }

    private fun findRpgProjectPrefix(paths: List<String>): String? {
        val normalizedPaths = paths.map { it.replace('\\', '/').trimStart('/') }
        RPG_STRONG_PROJECT_MARKERS.forEach { marker ->
            normalizedPaths.firstOrNull { path ->
                val normalized = path.lowercase(Locale.ROOT)
                normalized == marker || normalized.endsWith("/$marker")
            }?.let { path ->
                return path.substring(0, path.length - marker.length)
            }
        }
        val lowerPaths = normalizedPaths.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
        normalizedPaths.forEach { path ->
            val lower = path.lowercase(Locale.ROOT)
            if (lower.endsWith(RPG_SYSTEM_MARKER)) {
                val prefix = path.substring(0, path.length - RPG_SYSTEM_MARKER.length)
                val lowerPrefix = prefix.lowercase(Locale.ROOT)
                if (RPG_PROJECT_COMPANION_MARKERS.any { lowerPrefix + it in lowerPaths }) {
                    return prefix
                }
            }
        }
        return null
    }

    private fun String.normalizedGamePath(): String =
        replace('\\', '/').trim('/').lowercase(Locale.ROOT)

    private fun isRpgProjectMarker(projectRelativePath: String): Boolean {
        val path = projectRelativePath.lowercase(Locale.ROOT)
        return path == "data/system.json" ||
            path == "data/scripts.rvdata2" ||
            path == "js/rpg_core.js" ||
            path == "js/rmmz_core.js" ||
            path == "js/rpg_managers.js"
    }

    private fun decryptedResourceName(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        val mapping = RPG_RESOURCE_EXTENSION_MAP.entries.firstOrNull { lower.endsWith(it.key) }
            ?: error("不支持的 RPG Maker 资源扩展名")
        return name.dropLast(mapping.key.length) + mapping.value
    }

    private fun validResourceHeader(name: String, header: ByteArray): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".png") -> header.startsWith(PNG_SIGNATURE)
            lower.endsWith(".ogg") -> header.startsWith(OGG_SIGNATURE)
            lower.endsWith(".m4a") -> header.size >= 8 &&
                header.copyOfRange(4, 8).contentEquals("ftyp".toByteArray(Charsets.US_ASCII))
            else -> false
        }
    }

    private fun normalizeRpgKey(value: String): String {
        val normalized = value.filterNot(Char::isWhitespace).removePrefix("0x").removePrefix("0X")
        require(normalized.length == 32 && normalized.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "RPG Maker MV/MZ 密钥必须是 32 位十六进制字符"
        }
        return normalized.lowercase(Locale.ROOT)
    }

    private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun RandomAccessFile.readUIntLE(): UInt {
        val b0 = read()
        val b1 = read()
        val b2 = read()
        val b3 = read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) throw EOFException("档案目录提前结束")
        return b0.toUInt() or (b1.toUInt() shl 8) or (b2.toUInt() shl 16) or (b3.toUInt() shl 24)
    }

    private fun advanceRgssKey(key: UInt): UInt = key * 7u + 3u

    private fun InputStream.readExactly(count: Int): ByteArray {
        val output = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(output, offset, count - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == count) output else output.copyOf(offset)
    }

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val bytes = readExactly(limit + 1)
        require(bytes.size <= limit) { "System.json 超过 2 MiB，拒绝读取" }
        return bytes
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun safeName(value: String): String = value
        .replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]"), "_")
        .trim()
        .trimEnd('.', ' ')
        .ifBlank { "unnamed" }
        .take(120)

    private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "json" -> "application/json"
        "txt" -> "text/plain"
        "apk" -> "application/vnd.android.package-archive"
        "zip", "xapk" -> "application/zip"
        else -> "application/octet-stream"
    }

    private data class RgssEntry(val name: String, val offset: Long, val size: Long, val key: UInt)
    private data class DocumentEntry(val file: DocumentFile, val relativePath: String)
    private data class UnityArchiveEntryProbe(
        val kind: UnityArtifactKind? = null,
        val consumedPrefix: ByteArray = ByteArray(0),
    )
    private data class UnityArchiveInspectionSnapshot(
        val totalEntries: Int,
        val counts: Map<UnityArtifactKind, Int>,
        val preview: List<UnityArtifact>,
    )
    private data class UnityStagingOutcome(
        val input: File,
        val failureRecords: List<GameExtractionFailure>,
        val failedCount: Int,
        val closeable: Closeable? = null,
    )
    private data class UnityDecodedMediaOutcome(
        val filesWritten: Int,
        val bytesWritten: Long,
        val imageCount: Int,
        val audioCount: Int,
        val videoCount: Int,
        val textCount: Int,
        val existingFilesSkipped: Int,
        val manifestWritten: Boolean,
        val skippedCount: Int,
        val serializedFileCount: Int,
        val objectCount: Int,
        val resourceCount: Int,
        val diagnosticCount: Int,
        val failureRecords: List<GameExtractionFailure>,
    ) {
        companion object {
            val Empty = UnityDecodedMediaOutcome(
                filesWritten = 0,
                bytesWritten = 0L,
                imageCount = 0,
                audioCount = 0,
                videoCount = 0,
                textCount = 0,
                existingFilesSkipped = 0,
                manifestWritten = false,
                skippedCount = 0,
                serializedFileCount = 0,
                objectCount = 0,
                resourceCount = 0,
                diagnosticCount = 0,
                failureRecords = emptyList(),
            )
        }
    }

    private companion object {
        const val RPG_MV_HEADER_SIZE = 16
        const val RPG_MV_KEY_SIZE = 16
        const val MAX_SYSTEM_JSON_BYTES = 2 * 1024 * 1024
        const val MAX_ARCHIVE_NAME_BYTES = 16 * 1024
        const val MAX_ARCHIVE_ENTRIES = 50_000
        const val MAX_ZIP_ENTRIES = 100_000
        const val MAX_UNITY_PREVIEW_ITEMS = 120
        const val MAX_UNITY_FAILURE_RECORDS = 200
        const val MAX_UNITY_EXISTING_OUTPUTS = 1_000_000
        const val UNITY_PROGRESS_BATCH_SIZE = 16
        const val UNITY_HEADER_PROBE_BYTES = 64
        const val MAX_UNITY_METADATA_BYTES = 512L * 1024L * 1024L
        const val MAX_PATH_COMPONENTS = 32
        const val MAX_UNITY_STAGING_PATH_COMPONENTS = 128
        const val MAX_UNITY_OUTPUT_BYTES = 8L * 1024L * 1024L * 1024L
        const val MAX_UNITY_STAGING_BYTES = 8L * 1024L * 1024L * 1024L
        const val MIN_UNITY_CACHE_RESERVE_BYTES = 256L * 1024L * 1024L
        const val UNITY_DECODE_CACHE_DIRECTORY = "unity_decode_jobs"
        const val UNITY_EXISTING_OUTPUT_INDEX_NAME = "existing_outputs.index"
        const val UNITY_EXPORT_MANIFEST_NAME = "unity_export_manifest.json"
        const val UNITY_GUIDE_NAME = "逆向说明.txt"

        val UNITY_EXPORT_GROUP = Regex("^\\d{4,}_.+")
        val UNITY_EXPORTED_PATH_ID = Regex(" @(-?\\d+)(?:\\.[^/]+)?$")
        val UNITY_DECODED_CATEGORIES = setOf("picture", "audio", "video", "text")

        val RGSS_SIGNATURE = byteArrayOf(0x52, 0x47, 0x53, 0x53, 0x41, 0x44, 0x00)
        val RPG_MV_SIGNATURE = byteArrayOf(0x52, 0x50, 0x47, 0x4D, 0x56, 0x00, 0x00, 0x00)
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val PNG_HEADER = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        )
        val OGG_SIGNATURE = "OggS".toByteArray(Charsets.US_ASCII)
        val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        val GZIP_SIGNATURE = byteArrayOf(0x1F, 0x8B.toByte())
        val UNITY_ARCHIVE_SIGNATURES = listOf("UnityFS", "UnityWeb", "UnityRaw").map(String::toByteArray)
        val UNITY_SPLIT_SUFFIX = Regex(".+\\.split\\d+$")
        val UNITY_NON_CONTAINER_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "tga",
            "wav", "mp3", "ogg", "m4a", "aac", "flac", "mp4", "webm", "mkv",
            "txt", "json", "xml", "csv", "html", "js", "css", "md",
            "dll", "so", "dylib", "dex", "arsc", "class",
        )
        val RPG_RESOURCE_EXTENSION_MAP = linkedMapOf(
            ".rpgmvp" to ".png",
            ".rpgmvo" to ".ogg",
            ".rpgmvm" to ".m4a",
            ".png_" to ".png",
            ".ogg_" to ".ogg",
            ".m4a_" to ".m4a",
        )
        val RPG_ENCRYPTED_EXTENSIONS = RPG_RESOURCE_EXTENSION_MAP.keys.toList()
        const val RPG_SYSTEM_MARKER = "data/system.json"
        val RPG_STRONG_PROJECT_MARKERS = listOf(
            "js/rmmz_core.js",
            "js/rpg_core.js",
            "js/rpg_managers.js",
            "data/scripts.rvdata2",
        )
        val RPG_PROJECT_COMPANION_MARKERS = listOf(
            "index.html",
            "data/actors.json",
            "data/mapinfos.json",
            "js/plugins.js",
        )
        val UNITY_ASSET_EXTENSIONS = setOf("assets", "asset", "bundle", "unity3d", "resource", "ress")
    }
}
