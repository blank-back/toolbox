package com.pockettoolbox.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.Locale
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class RpgAudioVolumeSettings(
    val bgm: Int,
    val bgs: Int,
    val se: Int,
    val me: Int,
    val skipStartupSplash: Boolean,
) {
    init {
        require(listOf(bgm, bgs, se, me).all { it in 0..100 }) { "四项音量都必须是 0 到 100 的整数" }
    }
}

enum class RpgAudioVolumeEngine(val displayName: String) {
    MV_MZ("RPG Maker MV/MZ"),
    VX_ACE("RPG Maker VX Ace"),
}

data class RpgAudioVolumeInjectionResult(
    val engine: RpgAudioVolumeEngine,
    val replacedExisting: Boolean,
    val backupFiles: List<String>,
)

/** Injects a fixed master-volume patch into a writable, unpacked RPG Maker project. */
class RpgAudioVolumeInjector(private val context: Context) {
    suspend fun inject(
        treeUri: Uri,
        settings: RpgAudioVolumeSettings,
    ): RpgAudioVolumeInjectionResult = withContext(Dispatchers.IO) {
        val selectedRoot = directoryDocumentFile(context, treeUri)
            ?.takeIf { it.isDirectory && it.canRead() && it.canWrite() }
            ?: error("所选 RPG Maker 游戏目录不可读写")
        val entries = snapshotTree(selectedRoot)
        val mvMarker = findMarker(entries, MV_MZ_MARKERS)
        val vxAceMarker = findMarker(entries, listOf(VX_ACE_SCRIPTS_MARKER))
            ?: if (selectedRoot.name.equals("Data", ignoreCase = true)) {
                findMarker(entries, listOf(VX_ACE_DATA_DIRECTORY_SCRIPTS_MARKER))
            } else {
                null
            }
        when {
            mvMarker != null -> {
                val projectRoot = projectRootFor(selectedRoot, mvMarker.relativePath, mvMarker.marker)
                injectMvMz(projectRoot, settings)
            }

            vxAceMarker != null -> {
                val projectRoot = projectRootFor(selectedRoot, vxAceMarker.relativePath, vxAceMarker.marker)
                injectVxAce(projectRoot, settings)
            }

            else -> error("目录中未找到 MV/MZ 的 js/plugins.js 或 VX Ace 的 Data/Scripts.rvdata2")
        }
    }

    private fun injectMvMz(
        projectRoot: DocumentFile,
        settings: RpgAudioVolumeSettings,
    ): RpgAudioVolumeInjectionResult {
        val jsDirectory = projectRoot.findChildIgnoreCase("js")?.takeIf(DocumentFile::isDirectory)
            ?: error("项目缺少 js 目录")
        val registry = jsDirectory.findChildIgnoreCase("plugins.js")?.takeUnless(DocumentFile::isDirectory)
            ?: error("项目缺少 js/plugins.js，无法注册插件")
        val pluginDirectory = jsDirectory.findChildIgnoreCase("plugins")?.let { existing ->
            existing.takeIf(DocumentFile::isDirectory) ?: error("js/plugins 不是目录")
        } ?: jsDirectory.createDirectory("plugins") ?: error("无法创建 js/plugins 目录")

        val registryOriginal = readSmallFile(registry, MAX_PLUGIN_REGISTRY_BYTES)
        val pluginExisting = pluginDirectory.findChildIgnoreCase(PLUGIN_FILE_NAME)?.takeUnless(DocumentFile::isDirectory)
        val pluginOriginal = pluginExisting?.let { readSmallFile(it, MAX_PLUGIN_SOURCE_BYTES) }
        val pluginSource = renderAudioTemplate(MV_MZ_TEMPLATE_ASSET, settings).toByteArray(Charsets.UTF_8)
        val registryUpdated = updatePluginRegistry(registryOriginal, settings)

        val backupFiles = mutableListOf<String>()
        backupIfAbsent(jsDirectory, "plugins.js.AudioVolumeControl.bak", registryOriginal)?.let { backupFiles += it }
        if (pluginOriginal != null) {
            backupIfAbsent(pluginDirectory, "$PLUGIN_FILE_NAME.bak", pluginOriginal)?.let { backupFiles += it }
        }
        val originalPluginName = pluginExisting?.name
        var pluginTarget = pluginExisting
        var pluginCreated = false
        var pluginRenamed = false
        try {
            if (pluginTarget == null) {
                pluginTarget = createFileWithExactName(
                    directory = pluginDirectory,
                    mimeType = JAVASCRIPT_MIME_TYPE,
                    exactName = PLUGIN_FILE_NAME,
                    displayNameWithoutExtension = PLUGIN_NAME,
                )
                pluginCreated = true
            } else if (originalPluginName != PLUGIN_FILE_NAME) {
                require(renameToExactName(pluginTarget, PLUGIN_FILE_NAME)) {
                    "无法将现有插件文件重命名为 $PLUGIN_FILE_NAME"
                }
                pluginRenamed = true
            }
            val target = requireNotNull(pluginTarget)
            require(target.name == PLUGIN_FILE_NAME) {
                "插件文件名必须精确为 $PLUGIN_FILE_NAME"
            }
            replaceFile(target, pluginSource)
            replaceFile(registry, registryUpdated)
            require(readSmallFile(target, MAX_PLUGIN_SOURCE_BYTES).contentEquals(pluginSource)) {
                "$PLUGIN_FILE_NAME 写回校验失败"
            }
            require(readSmallFile(registry, MAX_PLUGIN_REGISTRY_BYTES).contentEquals(registryUpdated)) {
                "js/plugins.js 写回校验失败"
            }
        } catch (error: Throwable) {
            runCatching {
                val target = pluginTarget
                if (pluginCreated) {
                    target?.delete()
                } else if (target != null && pluginOriginal != null) {
                    replaceFile(target, pluginOriginal)
                    if (pluginRenamed && originalPluginName != null) renameToExactName(target, originalPluginName)
                }
            }
            runCatching { replaceFile(registry, registryOriginal) }
            throw error
        }
        return RpgAudioVolumeInjectionResult(
            engine = RpgAudioVolumeEngine.MV_MZ,
            replacedExisting = pluginOriginal != null || registryContainsPlugin(registryOriginal),
            backupFiles = backupFiles,
        )
    }

    private fun injectVxAce(
        projectRoot: DocumentFile,
        settings: RpgAudioVolumeSettings,
    ): RpgAudioVolumeInjectionResult {
        val dataDirectory = projectRoot.takeIf { root ->
            root.name.equals("Data", ignoreCase = true) &&
                root.findChildIgnoreCase("Scripts.rvdata2")?.isDirectory == false
        } ?: projectRoot.findChildIgnoreCase("Data")?.takeIf(DocumentFile::isDirectory)
        ?: error("VX Ace 项目缺少 Data 目录")
        val scriptsFile = dataDirectory.findChildIgnoreCase("Scripts.rvdata2")?.takeUnless(DocumentFile::isDirectory)
            ?: error("VX Ace 项目缺少 Data/Scripts.rvdata2")
        val original = readSmallFile(scriptsFile, MAX_VX_ACE_SCRIPTS_BYTES)
        val updated = updateVxAceScripts(original, settings)
        val backupFiles = mutableListOf<String>()
        backupIfAbsent(dataDirectory, "Scripts.rvdata2.AudioVolumeControl.bak", original)?.let { backupFiles += it }
        try {
            replaceFile(scriptsFile, updated.bytes)
            require(readSmallFile(scriptsFile, MAX_VX_ACE_SCRIPTS_BYTES).contentEquals(updated.bytes)) {
                "Scripts.rvdata2 写回校验失败"
            }
        } catch (error: Throwable) {
            runCatching { replaceFile(scriptsFile, original) }
            throw error
        }
        return RpgAudioVolumeInjectionResult(
            engine = RpgAudioVolumeEngine.VX_ACE,
            replacedExisting = updated.replacedExisting,
            backupFiles = backupFiles,
        )
    }

    private fun updatePluginRegistry(
        original: ByteArray,
        settings: RpgAudioVolumeSettings,
    ): ByteArray {
        val text = original.toString(Charsets.UTF_8).trimStart('\uFEFF')
        val assignment = PLUGIN_ASSIGNMENT.find(text) ?: error("js/plugins.js 缺少标准 var \$plugins 注册表")
        val arrayStart = text.indexOf('[', assignment.range.last + 1)
        val arrayEnd = text.lastIndexOf(']')
        require(arrayStart >= 0 && arrayEnd > arrayStart) { "js/plugins.js 的插件数组格式不完整" }
        val current = JSONArray(text.substring(arrayStart, arrayEnd + 1))
        val previouslyDisabledSplashPlugins = readPreviouslyDisabledSplashPlugins(current)
        val disabledSplashPlugins = linkedSetOf<String>()
        val updated = JSONArray()
        for (index in 0 until current.length()) {
            val entry = current.optJSONObject(index) ?: continue
            val name = entry.optString("name")
            if (name.equals(PLUGIN_NAME, ignoreCase = true)) continue
            if (isKnownStartupSplashPlugin(name)) {
                when {
                    settings.skipStartupSplash -> {
                        if (entry.optBoolean("status", false) || name in previouslyDisabledSplashPlugins) {
                            disabledSplashPlugins += name
                        }
                        entry.put("status", false)
                    }

                    name in previouslyDisabledSplashPlugins -> entry.put("status", true)
                }
            }
            updated.put(entry)
        }
        updated.put(
            JSONObject()
                .put("name", PLUGIN_NAME)
                .put("status", true)
                .put("description", "Pocket Toolbox injected audio and startup control")
                .put(
                    "parameters",
                    JSONObject()
                        .put("BGMVolume", settings.bgm.toString())
                        .put("BGSVolume", settings.bgs.toString())
                        .put("SEVolume", settings.se.toString())
                        .put("MEVolume", settings.me.toString())
                        .put("SkipStartupSplash", settings.skipStartupSplash.toString())
                        .put("DisabledSplashPlugins", JSONArray(disabledSplashPlugins.toList()).toString()),
                ),
        )
        return buildString {
            append(text.substring(0, arrayStart))
            append(updated.toString(4))
            append(text.substring(arrayEnd + 1))
            if (isEmpty() || this[length - 1] != '\n') append('\n')
        }.toByteArray(Charsets.UTF_8)
    }

    private fun readPreviouslyDisabledSplashPlugins(plugins: JSONArray): Set<String> {
        for (index in 0 until plugins.length()) {
            val entry = plugins.optJSONObject(index) ?: continue
            if (!entry.optString("name").equals(PLUGIN_NAME, ignoreCase = true)) continue
            val encoded = entry.optJSONObject("parameters")
                ?.optString("DisabledSplashPlugins")
                .orEmpty()
            if (encoded.isBlank()) return emptySet()
            return runCatching {
                val values = JSONArray(encoded)
                buildSet {
                    for (valueIndex in 0 until values.length()) {
                        values.optString(valueIndex).takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }.getOrDefault(emptySet())
        }
        return emptySet()
    }

    private fun isKnownStartupSplashPlugin(name: String): Boolean =
        name.lowercase(Locale.ROOT).removeSuffix(".js") in KNOWN_STARTUP_SPLASH_PLUGINS

    private fun registryContainsPlugin(bytes: ByteArray): Boolean = runCatching {
        val text = bytes.toString(Charsets.UTF_8)
        val assignment = PLUGIN_ASSIGNMENT.find(text) ?: return@runCatching false
        val start = text.indexOf('[', assignment.range.last + 1)
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return@runCatching false
        val array = JSONArray(text.substring(start, end + 1))
        (0 until array.length()).any { index ->
            array.optJSONObject(index)?.optString("name")?.equals(PLUGIN_NAME, ignoreCase = true) == true
        }
    }.getOrDefault(false)

    private fun updateVxAceScripts(
        original: ByteArray,
        settings: RpgAudioVolumeSettings,
    ): VxAceUpdate {
        val root = RubyMarshalReader(original).readRoot() as? RubyArray
            ?: error("Scripts.rvdata2 顶层不是标准脚本数组")
        val existingEntries = root.values.mapIndexed { index, value -> parseScriptEntry(value, index) }
        val matchingIndexes = existingEntries.mapIndexedNotNull { index, entry ->
            index.takeIf { entry.name.equals(PLUGIN_NAME, ignoreCase = true) }
        }
        val retained = root.values.filterIndexed { index, _ -> index !in matchingIndexes }.toMutableList()
        val retainedEntries = retained.mapIndexed { index, value -> parseScriptEntry(value, index) }
        val mainIndex = retainedEntries.indexOfFirst { it.name.trim().equals("Main", ignoreCase = true) }
            .let { if (it >= 0) it else retained.size }
        val usedIds = existingEntries.mapTo(hashSetOf()) { it.id }
        val newId = nextScriptId(usedIds)
        val rubySource = renderAudioTemplate(VX_ACE_TEMPLATE_ASSET, settings).toByteArray(Charsets.UTF_8)
        val compressedSource = deflate(rubySource)
        require(inflate(compressedSource, MAX_INJECTED_SCRIPT_BYTES).contentEquals(rubySource)) {
            "VX Ace 脚本压缩校验失败"
        }
        val newEntry = RubyArray(
            mutableListOf(
                RubyFixnum(newId),
                encodedRubyString(PLUGIN_NAME.toByteArray(Charsets.UTF_8), utf8 = true),
                encodedRubyString(compressedSource, utf8 = false),
            ),
        )
        retained.add(mainIndex, newEntry)
        root.values.clear()
        root.values.addAll(retained)
        val output = RubyMarshalWriter().writeRoot(root)

        val verifiedRoot = RubyMarshalReader(output).readRoot() as? RubyArray
            ?: error("VX Ace 脚本写回校验失败")
        val verifiedEntries = verifiedRoot.values.mapIndexed { index, value -> parseScriptEntry(value, index) }
        val injectedIndexes = verifiedEntries.mapIndexedNotNull { index, entry ->
            index.takeIf { entry.name.equals(PLUGIN_NAME, ignoreCase = true) }
        }
        require(injectedIndexes.size == 1) { "VX Ace 同名脚本去重校验失败" }
        val verifiedMain = verifiedEntries.indexOfFirst { it.name.trim().equals("Main", ignoreCase = true) }
        require(verifiedMain < 0 || injectedIndexes.single() < verifiedMain) { "AudioVolumeControl 必须位于 Main 之前" }
        val injected = verifiedEntries[injectedIndexes.single()]
        require(inflate(injected.compressedSource, MAX_INJECTED_SCRIPT_BYTES).contentEquals(rubySource)) {
            "VX Ace 注入脚本内容校验失败"
        }
        return VxAceUpdate(output, matchingIndexes.isNotEmpty())
    }

    private fun parseScriptEntry(value: RubyValue, index: Int): VxAceScriptEntry {
        val row = value as? RubyArray ?: error("Scripts.rvdata2 第 ${index + 1} 项不是数组")
        require(row.values.size >= 3) { "Scripts.rvdata2 第 ${index + 1} 项字段不足" }
        val id = (row.values[0] as? RubyFixnum)?.value
            ?: error("Scripts.rvdata2 第 ${index + 1} 项缺少脚本 ID")
        val nameValue = row.values[1] as? RubyString
            ?: error("Scripts.rvdata2 第 ${index + 1} 项缺少脚本名称")
        val source = row.values[2] as? RubyString
            ?: error("Scripts.rvdata2 第 ${index + 1} 项缺少压缩源码")
        return VxAceScriptEntry(id, nameValue.bytes.toString(Charsets.UTF_8), source.bytes)
    }

    private fun nextScriptId(used: Set<Int>): Int {
        val max = used.filter { it >= 0 }.maxOrNull() ?: 0
        if (max < RUBY_FIXNUM_MAX && max + 1 !in used) return max + 1
        return (1..RUBY_FIXNUM_MAX).firstOrNull { it !in used } ?: error("无法分配 VX Ace 脚本 ID")
    }

    private fun renderAudioTemplate(
        assetPath: String,
        settings: RpgAudioVolumeSettings,
    ): String {
        val template = context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        require(template.length <= MAX_TEMPLATE_CHARACTERS) { "AudioVolumeControl 模板过大" }
        val replacements = linkedMapOf(
            "{{BGM_VOLUME}}" to settings.bgm,
            "{{BGS_VOLUME}}" to settings.bgs,
            "{{SE_VOLUME}}" to settings.se,
            "{{ME_VOLUME}}" to settings.me,
            "{{SKIP_STARTUP_SPLASH}}" to settings.skipStartupSplash,
        )
        var rendered = template
        replacements.forEach { (placeholder, value) ->
            require(rendered.contains(placeholder)) { "AudioVolumeControl 模板缺少占位符 $placeholder" }
            rendered = rendered.replace(placeholder, value.toString())
        }
        require(!TEMPLATE_PLACEHOLDER.containsMatchIn(rendered)) {
            "AudioVolumeControl 模板仍包含未处理的占位符"
        }
        return rendered.trimEnd() + "\n"
    }

    private fun snapshotTree(root: DocumentFile): List<TreeEntry> {
        val pending = ArrayDeque<TreeEntry>()
        root.listFiles().forEach { child -> pending.addLast(TreeEntry(child, child.name ?: "unnamed")) }
        val entries = mutableListOf<TreeEntry>()
        while (pending.isNotEmpty()) {
            require(entries.size < MAX_DIRECTORY_ENTRIES) { "游戏目录条目过多，已停止音量补丁扫描" }
            val entry = pending.removeFirst()
            entries += entry
            if (entry.file.isDirectory) {
                entry.file.listFiles().forEach { child ->
                    pending.addLast(TreeEntry(child, "${entry.relativePath}/${child.name ?: "unnamed"}"))
                }
            }
        }
        return entries
    }

    private fun findMarker(entries: List<TreeEntry>, markers: List<String>): MarkerMatch? {
        markers.forEach { marker ->
            entries.firstOrNull { entry ->
                val path = entry.relativePath.normalizedPath()
                !entry.file.isDirectory && (path == marker || path.endsWith("/$marker"))
            }?.let { return MarkerMatch(it.relativePath, marker) }
        }
        return null
    }

    private fun projectRootFor(root: DocumentFile, markerPath: String, marker: String): DocumentFile {
        val normalized = markerPath.replace('\\', '/').trimStart('/')
        val prefix = normalized.substring(0, normalized.length - marker.length).trim('/')
        var current = root
        if (prefix.isNotBlank()) {
            prefix.split('/').filter(String::isNotBlank).forEach { part ->
                current = current.findChildIgnoreCase(part)?.takeIf(DocumentFile::isDirectory)
                    ?: error("无法定位 RPG Maker 项目根目录")
            }
        }
        return current
    }

    private fun DocumentFile.findChildIgnoreCase(name: String): DocumentFile? =
        listFiles().firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun readSmallFile(file: DocumentFile, limit: Int): ByteArray =
        openInputStreamCompat(context, file.uri)?.use { input ->
            val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "${file.name} 超过可安全修改的大小上限" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("无法读取 ${file.name}")

    private fun backupIfAbsent(directory: DocumentFile, name: String, bytes: ByteArray): String? {
        if (findExistingBackup(directory, name) != null) return null
        val backup = try {
            createFileWithExactName(
                directory = directory,
                mimeType = BACKUP_MIME_TYPE,
                exactName = name,
                displayNameWithoutExtension = name,
            )
        } catch (error: Throwable) {
            // A provider may report creation failure when the document already
            // exists but was not visible in the first directory snapshot.
            if (findExistingBackup(directory, name) != null) return null
            throw error
        }
        return try {
            replaceFile(backup, bytes)
            backup.name ?: name
        } catch (error: Throwable) {
            backup.delete()
            throw error
        }
    }

    private fun findExistingBackup(directory: DocumentFile, name: String): DocumentFile? {
        directory.findChildIgnoreCase(name)?.let { return it }
        return directory.findChildIgnoreCase("$name.bin")?.also { legacy ->
            // Older versions passed the full .bak name to createFile() with
            // application/octet-stream, so RawDocumentFile appended .bin.
            renameToExactName(legacy, name)
        }
    }

    private fun createFileWithExactName(
        directory: DocumentFile,
        mimeType: String,
        exactName: String,
        displayNameWithoutExtension: String,
    ): DocumentFile {
        require('/' !in exactName && '\\' !in exactName) { "文件名不安全：$exactName" }
        val created = directory.createFile(mimeType, displayNameWithoutExtension)
            ?: directory.createFile("application/octet-stream", displayNameWithoutExtension)
            ?: error("无法创建 $exactName")
        if (created.name == exactName || renameToExactName(created, exactName)) return created
        val actualName = created.name ?: "未知名称"
        created.delete()
        error("无法以精确文件名创建 $exactName（实际创建为 $actualName）")
    }

    private fun renameToExactName(file: DocumentFile, exactName: String): Boolean {
        if (file.name == exactName) return true
        if (file.renameTo(exactName) && file.name == exactName) return true

        // A case-only rename can fail on case-insensitive storage. Move through a
        // unique temporary name before applying the exact plugin filename.
        val originalName = file.name ?: return false
        val temporaryName = ".pockettoolbox_${System.nanoTime()}_$exactName"
        if (!file.renameTo(temporaryName)) return false
        if (file.renameTo(exactName) && file.name == exactName) return true
        file.renameTo(originalName)
        return false
    }

    private fun replaceFile(file: DocumentFile, bytes: ByteArray) {
        openOutputStreamCompat(context, file.uri, "w")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: error("无法写入 ${file.name}")
    }

    private fun deflate(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        DeflaterOutputStream(output).use { it.write(bytes) }
        output.toByteArray()
    }

    private fun inflate(bytes: ByteArray, limit: Int): ByteArray =
        InflaterInputStream(ByteArrayInputStream(bytes)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "注入脚本解压后超过安全上限" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

    private fun String.normalizedPath(): String = replace('\\', '/').trimStart('/').lowercase(Locale.ROOT)

    private data class TreeEntry(val file: DocumentFile, val relativePath: String)
    private data class MarkerMatch(val relativePath: String, val marker: String)
    private data class VxAceScriptEntry(val id: Int, val name: String, val compressedSource: ByteArray)
    private data class VxAceUpdate(val bytes: ByteArray, val replacedExisting: Boolean)

    companion object {
        private const val PLUGIN_NAME = "AudioVolumeControl"
        private const val PLUGIN_FILE_NAME = "$PLUGIN_NAME.js"
        private const val JAVASCRIPT_MIME_TYPE = "application/javascript"
        private const val BACKUP_MIME_TYPE = "application/x-pockettoolbox-backup"
        private const val MV_MZ_TEMPLATE_ASSET = "rpgmaker/AudioVolumeControl.mv_mz.js.template"
        private const val VX_ACE_TEMPLATE_ASSET = "rpgmaker/AudioVolumeControl.vx_ace.rb.template"
        private val PLUGIN_ASSIGNMENT = Regex("""\bvar\s+\${'$'}plugins\s*=""")
        private val TEMPLATE_PLACEHOLDER = Regex("""\{\{[A-Z0-9_]+\}\}""")
        private val KNOWN_STARTUP_SPLASH_PLUGINS = setOf(
            "madewithmv",
            "madewithmz",
            "customlogo",
            "splashscreen",
            "splash",
            "mog_titlesplashscreen",
        )
        private val MV_MZ_MARKERS = listOf("js/rmmz_core.js", "js/rpg_core.js")
        private const val VX_ACE_SCRIPTS_MARKER = "data/scripts.rvdata2"
        private const val VX_ACE_DATA_DIRECTORY_SCRIPTS_MARKER = "scripts.rvdata2"
        private const val MAX_DIRECTORY_ENTRIES = 100_000
        private const val MAX_PLUGIN_REGISTRY_BYTES = 4 * 1024 * 1024
        private const val MAX_PLUGIN_SOURCE_BYTES = 2 * 1024 * 1024
        private const val MAX_TEMPLATE_CHARACTERS = 128 * 1024
        private const val MAX_VX_ACE_SCRIPTS_BYTES = 128 * 1024 * 1024
        private const val MAX_INJECTED_SCRIPT_BYTES = 2 * 1024 * 1024
        private const val RUBY_FIXNUM_MAX = (1 shl 30) - 1
    }
}

private sealed interface RubyValue
private data object RubyNil : RubyValue
private data class RubyBoolean(val value: Boolean) : RubyValue
private data class RubyFixnum(val value: Int) : RubyValue
private data class RubySymbol(val name: String) : RubyValue
private class RubyString(
    var bytes: ByteArray,
    val instanceVariables: MutableList<Pair<String, RubyValue>> = mutableListOf(),
) : RubyValue

private class RubyArray(val values: MutableList<RubyValue>) : RubyValue

private fun encodedRubyString(bytes: ByteArray, utf8: Boolean): RubyString =
    RubyString(bytes, mutableListOf("E" to RubyBoolean(utf8)))

/** Minimal, fail-closed Ruby Marshal 4.8 codec for VX Ace's Scripts.rvdata2 shape. */
private class RubyMarshalReader(private val data: ByteArray) {
    private var position = 0
    private val symbols = mutableListOf<RubySymbol>()
    private val objects = mutableListOf<RubyValue>()

    fun readRoot(): RubyValue {
        val majorVersion = readByte()
        val minorVersion = readByte()
        require(majorVersion == 4 && minorVersion == 8) { "不支持的 Ruby Marshal 版本" }
        val value = readValue(0)
        require(position == data.size) { "Scripts.rvdata2 存在无法识别的尾部数据" }
        return value
    }

    private fun readValue(depth: Int): RubyValue {
        require(depth <= MAX_DEPTH) { "Ruby Marshal 嵌套层级过深" }
        return when (val tag = readByte()) {
            '0'.code -> RubyNil
            'T'.code -> RubyBoolean(true)
            'F'.code -> RubyBoolean(false)
            'i'.code -> RubyFixnum(readLong())
            ':'.code -> readSymbolDefinition()
            ';'.code -> symbols.getOrNull(readLong()) ?: error("Ruby Marshal 符号引用越界")
            '@'.code -> objects.getOrNull(readLong()) ?: error("Ruby Marshal 对象引用越界")
            '"'.code -> RubyString(readBlob(MAX_BLOB_BYTES)).also(objects::add)
            '['.code -> {
                val array = RubyArray(mutableListOf())
                objects += array
                repeat(readCount(MAX_ARRAY_ITEMS, "数组")) { array.values += readValue(depth + 1) }
                array
            }

            'I'.code -> {
                val inner = readValue(depth + 1) as? RubyString
                    ?: error("Scripts.rvdata2 含有不支持的实例变量包装类型")
                repeat(readCount(MAX_IVARS, "实例变量")) {
                    inner.instanceVariables += readSymbolReference() to readValue(depth + 1)
                }
                inner
            }

            else -> error("Scripts.rvdata2 含有不支持的 Ruby Marshal 标记 0x${tag.toString(16)}")
        }
    }

    private fun readSymbolDefinition(): RubySymbol {
        val symbol = RubySymbol(readBlob(MAX_SYMBOL_BYTES).toString(Charsets.UTF_8))
        symbols += symbol
        return symbol
    }

    private fun readSymbolReference(): String = when (val tag = readByte()) {
        ':'.code -> readSymbolDefinition().name
        ';'.code -> symbols.getOrNull(readLong())?.name ?: error("Ruby Marshal 符号引用越界")
        else -> error("Ruby Marshal 实例变量名称标记无效：0x${tag.toString(16)}")
    }

    private fun readBlob(limit: Int): ByteArray {
        val length = readCount(limit, "字符串")
        require(position <= data.size - length) { "Ruby Marshal 字符串超出文件边界" }
        return data.copyOfRange(position, position + length).also { position += length }
    }

    private fun readCount(limit: Int, label: String): Int {
        val value = readLong()
        require(value in 0..limit) { "Ruby Marshal $label 数量异常：$value" }
        return value
    }

    private fun readLong(): Int {
        var header = readByte()
        if (header == 0) return 0
        if (header > 127) header -= 256
        if (header > 4) return header - 5
        if (header < -4) return header + 5
        if (header > 0) {
            var value = 0
            repeat(header) { index -> value = value or (readByte() shl (index * 8)) }
            return value
        }
        var value = -1
        repeat(-header) { index ->
            value = value and (0xFF shl (index * 8)).inv()
            value = value or (readByte() shl (index * 8))
        }
        return value
    }

    private fun readByte(): Int {
        require(position < data.size) { "Ruby Marshal 数据意外结束" }
        return data[position++].toInt() and 0xFF
    }

    companion object {
        private const val MAX_DEPTH = 64
        private const val MAX_ARRAY_ITEMS = 100_000
        private const val MAX_IVARS = 32
        private const val MAX_SYMBOL_BYTES = 4096
        private const val MAX_BLOB_BYTES = 128 * 1024 * 1024
    }
}

private class RubyMarshalWriter {
    private val output = ByteArrayOutputStream()
    private val symbols = linkedMapOf<String, Int>()
    private val objects = IdentityHashMap<RubyValue, Int>()

    fun writeRoot(value: RubyValue): ByteArray {
        output.write(4)
        output.write(8)
        writeValue(value, 0)
        return output.toByteArray()
    }

    private fun writeValue(value: RubyValue, depth: Int) {
        require(depth <= MAX_DEPTH) { "Ruby Marshal 写入层级过深" }
        when (value) {
            RubyNil -> output.write('0'.code)
            is RubyBoolean -> output.write(if (value.value) 'T'.code else 'F'.code)
            is RubyFixnum -> {
                output.write('i'.code)
                writeLong(value.value)
            }

            is RubySymbol -> writeSymbol(value.name)
            is RubyString -> writeString(value, depth)
            is RubyArray -> writeArray(value, depth)
        }
    }

    private fun writeString(value: RubyString, depth: Int) {
        if (writeLinkIfKnown(value)) return
        if (value.instanceVariables.isNotEmpty()) output.write('I'.code)
        registerObject(value)
        output.write('"'.code)
        writeBlob(value.bytes)
        if (value.instanceVariables.isNotEmpty()) {
            writeLong(value.instanceVariables.size)
            value.instanceVariables.forEach { (name, item) ->
                writeSymbol(name)
                writeValue(item, depth + 1)
            }
        }
    }

    private fun writeArray(value: RubyArray, depth: Int) {
        if (writeLinkIfKnown(value)) return
        registerObject(value)
        output.write('['.code)
        writeLong(value.values.size)
        value.values.forEach { writeValue(it, depth + 1) }
    }

    private fun writeSymbol(name: String) {
        val existing = symbols[name]
        if (existing != null) {
            output.write(';'.code)
            writeLong(existing)
        } else {
            symbols[name] = symbols.size
            output.write(':'.code)
            writeBlob(name.toByteArray(Charsets.UTF_8))
        }
    }

    private fun writeLinkIfKnown(value: RubyValue): Boolean {
        val index = objects[value] ?: return false
        output.write('@'.code)
        writeLong(index)
        return true
    }

    private fun registerObject(value: RubyValue) {
        objects[value] = objects.size
    }

    private fun writeBlob(bytes: ByteArray) {
        writeLong(bytes.size)
        output.write(bytes)
    }

    private fun writeLong(value: Int) {
        if (value == 0) {
            output.write(0)
            return
        }
        if (value in 1..122) {
            output.write(value + 5)
            return
        }
        if (value in -123..-1) {
            output.write((value - 5) and 0xFF)
            return
        }
        var remaining = value
        val body = ByteArrayOutputStream(4)
        for (length in 1..4) {
            body.write(remaining and 0xFF)
            remaining = remaining shr 8
            if (remaining == 0) {
                output.write(length)
                output.write(body.toByteArray())
                return
            }
            if (remaining == -1) {
                output.write((-length) and 0xFF)
                output.write(body.toByteArray())
                return
            }
        }
        error("整数超出 Ruby Marshal long 范围")
    }

    companion object {
        private const val MAX_DEPTH = 64
    }
}
