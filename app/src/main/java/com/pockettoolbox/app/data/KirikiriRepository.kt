package com.pockettoolbox.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.Locale
import java.util.zip.Adler32
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class KirikiriArchiveInput(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val relativePath: String = name,
)

data class KirikiriEntryInspection(
    val selectionKey: String,
    val archiveName: String,
    val path: String,
    val originalSize: Long,
    val protected: Boolean,
    val canExtract: Boolean,
    val protectionScheme: String? = null,
    val limitation: String? = null,
)

data class KirikiriInspection(
    val archives: List<KirikiriArchiveInput>,
    val sourceLabel: String,
    val sourceDirectoryUri: String? = null,
    val entryCount: Int,
    val compressedEntryCount: Int,
    val protectedEntryCount: Int,
    val originalBytes: Long,
    val previewPaths: List<String>,
    val entries: List<KirikiriEntryInspection> = emptyList(),
    val parseFailureCount: Int = 0,
    val parseFailures: List<GameExtractionFailure> = emptyList(),
    val detectedProtectionSchemes: List<String> = emptyList(),
    val unsupportedProtectedArchiveCount: Int = 0,
) {
    val canExtract: Boolean
        get() = archives.isNotEmpty() && entries.any(KirikiriEntryInspection::canExtract)

    val extractableEntryCount: Int
        get() = entries.count(KirikiriEntryInspection::canExtract)
}

class KirikiriRepository(private val context: Context) {
    suspend fun inspectFiles(uris: List<Uri>): KirikiriInspection = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "请先选择 KRKR/吉里吉里 XP3 文件" }
        val failures = mutableListOf<GameExtractionFailure>()
        val archives = uris.mapNotNull { uri ->
            val fallbackName = uri.lastPathSegment ?: "未知文件"
            try {
                require(hasXp3Signature(uri)) { "不是标准 XP3 档案" }
                val (name, size) = queryNameAndSize(uri)
                KirikiriArchiveInput(uri.toString(), name, size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failures += GameExtractionFailure(fallbackName, error.readableMessage("无法读取或识别"))
                null
            }
        }
        require(archives.isNotEmpty()) { failures.failureMessage("所选内容中没有可解析的 XP3 档案") }
        inspectArchives(
            archives = archives,
            sourceLabel = if (archives.size == 1) archives.first().name else "${archives.size} 个 XP3 档案",
            initialFailures = failures,
        )
    }

    suspend fun inspectDirectory(treeUri: Uri): KirikiriInspection = withContext(Dispatchers.IO) {
        val root = directoryDocumentFile(context, treeUri)?.takeIf(DocumentFile::isDirectory)
            ?: error("无法读取所选目录")
        val pending = ArrayDeque<Pair<DocumentFile, String>>()
        root.listFiles().forEach { child -> pending.addLast(child to (child.name ?: "unnamed")) }
        val archives = mutableListOf<KirikiriArchiveInput>()
        val failures = mutableListOf<GameExtractionFailure>()
        var visited = 0
        while (pending.isNotEmpty()) {
            val (entry, relativePath) = pending.removeFirst()
            visited += 1
            require(visited <= MAX_DIRECTORY_ENTRIES) { "目录条目过多，已中止 KRKR 扫描" }
            if (entry.isDirectory) {
                entry.listFiles().forEach { child ->
                    pending.addLast(child to "$relativePath/${child.name ?: "unnamed"}")
                }
            } else if (entry.isFile && entry.name?.lowercase(Locale.ROOT)?.endsWith(".xp3") == true) {
                try {
                    if (hasXp3Signature(entry.uri)) {
                        archives += KirikiriArchiveInput(
                            uri = entry.uri.toString(),
                            name = entry.name ?: "archive.xp3",
                            sizeBytes = entry.length(),
                            relativePath = relativePath,
                        )
                    } else {
                        failures += GameExtractionFailure(relativePath, "不是标准 XP3 档案")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failures += GameExtractionFailure(relativePath, error.readableMessage("无法读取或识别"))
                }
            }
        }
        require(archives.isNotEmpty()) { failures.failureMessage("目录内未找到可解析的 KRKR/吉里吉里 XP3 档案") }
        inspectArchives(
            archives = archives,
            sourceLabel = root.name ?: "KRKR 游戏目录",
            sourceDirectoryUri = treeUri.toString(),
            initialFailures = failures,
        )
    }

    suspend fun extract(
        archives: List<KirikiriArchiveInput>,
        outputTreeUri: Uri,
        selectedEntryKeys: Set<String>,
        onProgress: suspend (String) -> Unit,
    ): GameExtractionSummary = withContext(Dispatchers.IO) {
        require(archives.isNotEmpty()) { "没有可处理的 XP3 档案" }
        require(selectedEntryKeys.isNotEmpty()) { "没有选择需要导出的 XP3 条目" }
        val failuresByKey = linkedMapOf<String, GameExtractionFailure>()
        fun recordFailure(selectionKey: String, item: String, reason: String) {
            failuresByKey.putIfAbsent(selectionKey, GameExtractionFailure(item, reason))
        }
        val selectedKeysByArchive = selectedEntryKeys.groupBy { key -> key.substringBefore(SELECTION_KEY_SEPARATOR) }
        val parsedArchives = mutableListOf<Pair<KirikiriArchiveInput, ParsedXp3>>()
        try {
            archives.forEach { input ->
                val archiveKeys = selectedKeysByArchive[input.uri].orEmpty()
                if (archiveKeys.isEmpty()) return@forEach
                try {
                    parsedArchives += input to copyAndParse(Uri.parse(input.uri))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    archiveKeys.forEach { key ->
                        recordFailure(
                            selectionKey = key,
                            item = "${input.name}!/${selectionPath(key)}",
                            reason = error.readableMessage("档案解析失败"),
                        )
                    }
                }
            }
            val resolvedArchives = mutableListOf<ResolvedXp3>()
            parsedArchives.forEachIndexed { index, (input, parsed) ->
                val needsProtectedFilter = parsed.entries.withIndex().any { (entryIndex, entry) ->
                    entry.protected && selectionKey(input, entryIndex, entry) in selectedEntryKeys
                }
                val filter = if (needsProtectedFilter) {
                    onProgress("正在识别 XP3 过滤器 ${index + 1}/${parsedArchives.size}：${input.name}")
                    try {
                        detectProtectionFilter(parsed)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                } else {
                    null
                }
                resolvedArchives += ResolvedXp3(input, parsed, filter)
            }
            val selectedEntries = resolvedArchives.flatMap { resolved ->
                resolved.parsed.entries.mapIndexedNotNull { entryIndex, entry ->
                    val key = selectionKey(resolved.input, entryIndex, entry)
                    if (key !in selectedEntryKeys) return@mapIndexedNotNull null
                    if (entry.protected && resolved.filter == null) {
                        recordFailure(
                            selectionKey = key,
                            item = "${resolved.input.name}!/${entry.path}",
                            reason = "过滤器无法唯一识别并验证",
                        )
                        return@mapIndexedNotNull null
                    }
                    SelectedXp3Entry(key, resolved, entry)
                }
            }
            val resolvedKeys = selectedEntries.asSequence().map { it.selectionKey }.toSet()
            selectedEntryKeys.forEach { key ->
                if (key !in resolvedKeys && key !in failuresByKey) {
                    recordFailure(
                        selectionKey = key,
                        item = selectionPath(key),
                        reason = "条目与当前档案内容不一致，请重新解析后选择",
                    )
                }
            }
            val output = writableTree(outputTreeUri)
            var filesWritten = 0
            var bytesWritten = 0L
            selectedEntries.forEachIndexed { index, selected ->
                val entry = selected.entry
                onProgress("正在导出 ${index + 1}/${selectedEntries.size}：${entry.path.substringAfterLast('/')}")
                if (entry.originalSize > MAX_OUTPUT_BYTES - bytesWritten) {
                    recordFailure(
                        selectionKey = selected.selectionKey,
                        item = "${selected.archive.input.name}!/${entry.path}",
                        reason = "加入该条目会超过本次 8 GiB 输出安全上限",
                    )
                    return@forEachIndexed
                }
                var target: DocumentFile? = null
                try {
                    target = createOutputFile(output, entry.path)
                    val entryWritten = openOutputStreamCompat(context, target.uri, "w")?.use { destination ->
                        writeEntry(
                            parsed = selected.archive.parsed,
                            entry = entry,
                            filter = selected.archive.filter.takeIf { entry.protected },
                            destination = destination,
                        )
                    } ?: error("无法写入 ${entry.path}")
                    bytesWritten += entryWritten
                    filesWritten += 1
                } catch (cancelled: CancellationException) {
                    target?.delete()
                    throw cancelled
                } catch (error: Throwable) {
                    target?.delete()
                    recordFailure(
                        selectionKey = selected.selectionKey,
                        item = "${selected.archive.input.name}!/${entry.path}",
                        reason = error.readableMessage("导出失败"),
                    )
                }
            }
            GameExtractionSummary(
                filesWritten = filesWritten,
                bytesWritten = bytesWritten,
                outputDirectoryName = output.name ?: "所选输出目录",
                skippedCount = failuresByKey.size,
                failureRecords = failuresByKey.values.take(MAX_FAILURE_RECORDS),
            )
        } finally {
            parsedArchives.forEach { (_, parsed) -> parsed.file.delete() }
        }
    }

    private fun inspectArchives(
        archives: List<KirikiriArchiveInput>,
        sourceLabel: String,
        sourceDirectoryUri: String? = null,
        initialFailures: List<GameExtractionFailure> = emptyList(),
    ): KirikiriInspection {
        var entryCount = 0
        var compressedEntryCount = 0
        var protectedEntryCount = 0
        var originalBytes = 0L
        val preview = mutableListOf<String>()
        val inspectedEntries = mutableListOf<KirikiriEntryInspection>()
        val protectionSchemes = linkedSetOf<String>()
        val parseFailures = initialFailures.toMutableList()
        val parsedArchives = mutableListOf<KirikiriArchiveInput>()
        var unsupportedProtectedArchiveCount = 0
        archives.forEach { archive ->
            val parsed = try {
                copyAndParse(Uri.parse(archive.uri))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                parseFailures += GameExtractionFailure(archive.relativePath, error.readableMessage("档案解析失败"))
                return@forEach
            }
            try {
                require(parsed.entries.isNotEmpty()) { "XP3 索引中没有可识别的文件条目" }
                val archiveCompressedCount = parsed.entries.count { entry ->
                    entry.segments.any(Xp3Segment::compressed)
                }
                val archiveProtectedCount = parsed.entries.count(Xp3Entry::protected)
                val filter = if (archiveProtectedCount > 0) {
                    try {
                        detectProtectionFilter(parsed)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                } else {
                    null
                }
                var archiveOriginalBytes = 0L
                parsed.entries.forEach { entry ->
                    archiveOriginalBytes = safeAdd(archiveOriginalBytes, entry.originalSize)
                }
                val combinedOriginalBytes = safeAdd(originalBytes, archiveOriginalBytes)
                val archiveEntries = parsed.entries.mapIndexed { entryIndex, entry ->
                    val supported = !entry.protected || filter != null
                    KirikiriEntryInspection(
                        selectionKey = selectionKey(archive, entryIndex, entry),
                        archiveName = archive.name,
                        path = entry.path,
                        originalSize = entry.originalSize,
                        protected = entry.protected,
                        canExtract = supported,
                        protectionScheme = filter?.displayName?.takeIf { entry.protected },
                        limitation = if (supported) null else "受保护内容的过滤器无法唯一识别并验证",
                    )
                }
                val archivePreview = parsed.entries.take(MAX_PREVIEW_ITEMS - preview.size).map { entry ->
                    if (archives.size == 1) entry.path else "${archive.name}!/${entry.path}"
                }

                parsedArchives += archive
                entryCount += parsed.entries.size
                compressedEntryCount += archiveCompressedCount
                protectedEntryCount += archiveProtectedCount
                originalBytes = combinedOriginalBytes
                inspectedEntries += archiveEntries
                preview += archivePreview
                if (archiveProtectedCount > 0) {
                    if (filter == null) unsupportedProtectedArchiveCount += 1
                    else protectionSchemes += filter.displayName
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                parseFailures += GameExtractionFailure(archive.relativePath, error.readableMessage("档案解析失败"))
            } finally {
                parsed.file.delete()
            }
        }
        return KirikiriInspection(
            archives = parsedArchives,
            sourceLabel = sourceLabel,
            sourceDirectoryUri = sourceDirectoryUri,
            entryCount = entryCount,
            compressedEntryCount = compressedEntryCount,
            protectedEntryCount = protectedEntryCount,
            originalBytes = originalBytes,
            previewPaths = preview,
            entries = inspectedEntries,
            parseFailureCount = parseFailures.size,
            parseFailures = parseFailures.take(MAX_FAILURE_RECORDS),
            detectedProtectionSchemes = protectionSchemes.toList(),
            unsupportedProtectedArchiveCount = unsupportedProtectedArchiveCount,
        )
    }

    private fun copyAndParse(uri: Uri): ParsedXp3 {
        val cache = File.createTempFile("xp3_", ".archive", context.cacheDir)
        try {
            openInputStreamCompat(context, uri)?.use { source ->
                FileOutputStream(cache).use { destination -> source.copyTo(destination) }
            } ?: error("无法读取 XP3 档案")
            val entries = parseXp3(cache)
            return ParsedXp3(cache, entries)
        } catch (error: Throwable) {
            cache.delete()
            throw error
        }
    }

    private fun parseXp3(file: File): List<Xp3Entry> = RandomAccessFile(file, "r").use { archive ->
        require(archive.length() >= XP3_MAGIC.size + 8L) { "XP3 档案过短" }
        val magic = ByteArray(XP3_MAGIC.size).also(archive::readFully)
        require(magic.contentEquals(XP3_MAGIC)) { "不是标准 XP3 档案" }
        val entries = mutableListOf<Xp3Entry>()
        var pointerLocation = XP3_MAGIC.size.toLong()
        var blockCount = 0
        while (true) {
            blockCount += 1
            require(blockCount <= MAX_INDEX_BLOCKS) { "XP3 索引块过多" }
            archive.seek(pointerLocation)
            val indexOffset = archive.readLongLe()
            require(indexOffset in 0 until archive.length()) { "XP3 索引偏移无效" }
            archive.seek(indexOffset)
            val flag = archive.readUnsignedByte()
            val method = flag and INDEX_METHOD_MASK
            val indexBytes = when (method) {
                INDEX_RAW -> {
                    val size = archive.readBoundedSize(MAX_INDEX_BYTES, "XP3 索引")
                    ByteArray(size).also(archive::readFully)
                }
                INDEX_ZLIB -> {
                    val compressedSize = archive.readBoundedSize(MAX_INDEX_BYTES, "XP3 压缩索引")
                    val originalSize = archive.readBoundedSize(MAX_INDEX_BYTES, "XP3 解压索引")
                    val compressed = ByteArray(compressedSize).also(archive::readFully)
                    inflateExact(compressed, originalSize)
                }
                else -> error("不支持的 XP3 索引编码方式：$method")
            }
            entries += parseIndex(indexBytes, archive.length())
            pointerLocation = archive.filePointer
            if (flag and INDEX_CONTINUE == 0) break
        }
        require(entries.size <= MAX_XP3_ENTRIES) { "XP3 文件条目过多" }
        entries
    }

    private fun parseIndex(data: ByteArray, archiveLength: Long): List<Xp3Entry> {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val entries = mutableListOf<Xp3Entry>()
        while (buffer.remaining() >= CHUNK_HEADER_SIZE) {
            val tag = buffer.readTag()
            val size = buffer.long
            require(size in 0..buffer.remaining().toLong()) { "XP3 索引块长度无效" }
            val end = buffer.position() + size.toInt()
            if (tag == "File") parseFileChunk(buffer, end, archiveLength)?.let(entries::add)
            buffer.position(end)
            require(entries.size <= MAX_XP3_ENTRIES) { "XP3 文件条目过多" }
        }
        return entries
    }

    private fun parseFileChunk(buffer: ByteBuffer, end: Int, archiveLength: Long): Xp3Entry? {
        var path: String? = null
        var originalSize = -1L
        var archivedSize = -1L
        var protected = false
        var adler32: Long? = null
        val segments = mutableListOf<Xp3Segment>()
        while (buffer.position() + CHUNK_HEADER_SIZE <= end) {
            val tag = buffer.readTag()
            val size = buffer.long
            require(size in 0..(end - buffer.position()).toLong()) { "XP3 File 子块长度无效" }
            val sectionEnd = buffer.position() + size.toInt()
            when (tag) {
                "info" -> {
                    require(size >= 22) { "XP3 info 子块过短" }
                    protected = buffer.int != 0
                    originalSize = buffer.long
                    archivedSize = buffer.long
                    val nameLength = buffer.short.toInt() and 0xFFFF
                    require(nameLength in 1..MAX_XP3_PATH_CHARS) { "XP3 文件名长度无效" }
                    require(nameLength * 2 <= sectionEnd - buffer.position()) { "XP3 文件名内容不完整" }
                    val nameBytes = ByteArray(nameLength * 2).also(buffer::get)
                    path = String(nameBytes, Charsets.UTF_16LE).replace('\\', '/').trimStart('/')
                }
                "segm" -> {
                    require(size % SEGMENT_RECORD_SIZE == 0L) { "XP3 segm 子块长度无效" }
                    while (buffer.position() + SEGMENT_RECORD_SIZE <= sectionEnd) {
                        val flags = buffer.int
                        val offset = buffer.long
                        val segmentOriginalSize = buffer.long
                        val segmentArchivedSize = buffer.long
                        require(offset >= 0 && segmentArchivedSize >= 0 && offset <= archiveLength - segmentArchivedSize) {
                            "XP3 分段偏移超出档案范围"
                        }
                        require(segmentOriginalSize >= 0) { "XP3 分段原始长度无效" }
                        val method = flags and SEGMENT_METHOD_MASK
                        require(method == SEGMENT_RAW || method == SEGMENT_ZLIB) { "不支持的 XP3 分段编码方式：$method" }
                        segments += Xp3Segment(
                            compressed = method == SEGMENT_ZLIB,
                            offset = offset,
                            originalSize = segmentOriginalSize,
                            archivedSize = segmentArchivedSize,
                        )
                    }
                }
                "adlr" -> {
                    require(size >= 4) { "XP3 adlr 子块过短" }
                    adler32 = buffer.int.toLong() and 0xFFFF_FFFFL
                }
            }
            buffer.position(sectionEnd)
        }
        val safePath = path?.takeIf(String::isNotBlank) ?: return null
        require(originalSize >= 0 && archivedSize >= 0 && segments.isNotEmpty()) { "XP3 文件条目信息不完整" }
        var segmentTotal = 0L
        segments.forEach { segment -> segmentTotal = safeAdd(segmentTotal, segment.originalSize) }
        require(segmentTotal == originalSize) { "XP3 文件分段总长度不匹配" }
        return Xp3Entry(
            path = safePath,
            originalSize = originalSize,
            archivedSize = archivedSize,
            protected = protected,
            adler32 = adler32 ?: error("XP3 文件条目缺少 adlr 校验值"),
            segments = segments,
        )
    }

    private fun hasXp3Signature(uri: Uri): Boolean = openInputStreamCompat(context, uri)?.use { source ->
        val signature = ByteArray(XP3_MAGIC.size)
        var offset = 0
        while (offset < signature.size) {
            val count = source.read(signature, offset, signature.size - offset)
            if (count < 0) break
            offset += count
        }
        offset == signature.size && signature.contentEquals(XP3_MAGIC)
    } ?: false

    private fun inflateExact(compressed: ByteArray, expectedSize: Int): ByteArray {
        val output = ByteArray(expectedSize)
        var offset = 0
        InflaterInputStream(ByteArrayInputStream(compressed)).use { inflater ->
            while (offset < output.size) {
                val count = inflater.read(output, offset, output.size - offset)
                if (count < 0) break
                offset += count
            }
            require(offset == expectedSize && inflater.read() == -1) { "XP3 zlib 索引长度不匹配" }
        }
        return output
    }

    private fun detectProtectionFilter(parsed: ParsedXp3): Xp3ContentFilter? {
        val sampleEntries = parsed.entries
            .asSequence()
            .filter(Xp3Entry::protected)
            .filter { it.originalSize in 1L..MAX_FILTER_SAMPLE_BYTES }
            .sortedBy(Xp3Entry::originalSize)
            .take(MAX_FILTER_SAMPLES)
            .toList()
        if (sampleEntries.isEmpty()) return null
        val samples = sampleEntries.map { entry -> FilterSample(entry, readEntryData(parsed, entry)) }
        val candidates = KirikiriXp3Filters.automatic + fixedXorCandidates(samples)

        val checksumVerified = candidates
            .asSequence()
            .filter { filter -> !filter.hashAfterCrypt && filter.id != "none" }
            .filter { filter -> samples.all { sample -> decryptedAdler(sample, filter) == sample.entry.adler32 } }
            .toList()
        selectUniqueFilter(checksumVerified, samples, minimumSignatureScore = 0)?.let { return it }

        if (samples.any { sample -> adler32(sample.encryptedData) != sample.entry.adler32 }) return null
        val signatureVerified = candidates
            .asSequence()
            .filter { filter -> filter.hashAfterCrypt || filter.id == "none" }
            .filter { filter ->
                samples.any { sample ->
                    contentSignatureScore(sample.entry.path, decryptSample(sample, filter)) >= STRONG_SIGNATURE_SCORE
                }
            }
            .toList()
        return selectUniqueFilter(signatureVerified, samples, minimumSignatureScore = STRONG_SIGNATURE_SCORE)
    }

    private fun fixedXorCandidates(samples: List<FilterSample>): List<Xp3ContentFilter> {
        val keys = linkedSetOf<Int>()
        samples.forEach { sample ->
            expectedMagicCandidates(sample.entry.path).forEach magicLoop@{ magic ->
                if (sample.encryptedData.size < magic.size || magic.isEmpty()) return@magicLoop
                val key = (sample.encryptedData[0].toInt() xor magic[0].toInt()) and 0xFF
                if (key != 0 && magic.indices.all { index ->
                        (sample.encryptedData[index].toInt() xor key).toByte() == magic[index]
                    }
                ) {
                    keys += key
                }
            }
        }
        return keys.map(KirikiriXp3Filters::fixedXor)
    }

    private fun expectedMagicCandidates(path: String): List<ByteArray> = when (
        path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    ) {
        "png" -> listOf(PNG_MAGIC)
        "jpg", "jpeg" -> listOf(JPEG_MAGIC)
        "ogg" -> listOf("OggS".toByteArray(Charsets.US_ASCII))
        "wav", "webp" -> listOf("RIFF".toByteArray(Charsets.US_ASCII))
        "bmp" -> listOf("BM".toByteArray(Charsets.US_ASCII))
        "tlg" -> listOf("TLG".toByteArray(Charsets.US_ASCII))
        "psb" -> listOf("PSB\u0000".toByteArray(Charsets.US_ASCII))
        "zip" -> listOf(ZIP_MAGIC)
        "mp3" -> listOf("ID3".toByteArray(Charsets.US_ASCII))
        else -> GENERIC_MAGIC_CANDIDATES
    }

    private fun selectUniqueFilter(
        candidates: List<Xp3ContentFilter>,
        samples: List<FilterSample>,
        minimumSignatureScore: Int,
    ): Xp3ContentFilter? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1 && minimumSignatureScore == 0) return candidates.first()
        val ranked = candidates.map { filter ->
            filter to samples.sumOf { sample ->
                contentSignatureScore(sample.entry.path, decryptSample(sample, filter))
            }
        }.sortedByDescending { it.second }
        val best = ranked.first()
        if (best.second < minimumSignatureScore) return null
        if (ranked.getOrNull(1)?.second == best.second) return null
        return best.first
    }

    private fun decryptedAdler(sample: FilterSample, filter: Xp3ContentFilter): Long =
        adler32(decryptSample(sample, filter))

    private fun decryptSample(sample: FilterSample, filter: Xp3ContentFilter): ByteArray =
        sample.encryptedData.copyOf().also { data ->
            filter.decrypt(sample.entry.filterContext(), 0L, data, 0, data.size)
        }

    private fun readEntryData(parsed: ParsedXp3, entry: Xp3Entry): ByteArray {
        require(entry.originalSize <= MAX_FILTER_SAMPLE_BYTES) { "XP3 过滤器检测样本过大" }
        val output = ByteArrayOutputStream(entry.originalSize.toInt())
        var total = 0L
        entry.segments.forEach { segment ->
            openSegmentStream(parsed.file, segment).use { source ->
                var remaining = segment.originalSize
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (remaining > 0L) {
                    val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                    total += count
                }
                require(remaining == 0L) { "${entry.path} 的 XP3 检测样本不完整" }
            }
        }
        require(total == entry.originalSize) { "${entry.path} 的 XP3 检测样本长度不匹配" }
        return output.toByteArray()
    }

    private fun writeEntry(
        parsed: ParsedXp3,
        entry: Xp3Entry,
        filter: Xp3ContentFilter?,
        destination: java.io.OutputStream,
    ): Long {
        val encryptedChecksum = Adler32()
        val decryptedChecksum = Adler32()
        val context = entry.filterContext()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryOffset = 0L
        entry.segments.forEach { segment ->
            openSegmentStream(parsed.file, segment).use { source ->
                var remaining = segment.originalSize
                while (remaining > 0L) {
                    val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) break
                    encryptedChecksum.update(buffer, 0, count)
                    filter?.decrypt(context, entryOffset, buffer, 0, count)
                    decryptedChecksum.update(buffer, 0, count)
                    destination.write(buffer, 0, count)
                    remaining -= count
                    entryOffset += count
                }
                require(remaining == 0L) { "${entry.path} 的 XP3 分段长度不完整" }
            }
        }
        require(entryOffset == entry.originalSize) { "${entry.path} 的 XP3 文件长度不匹配" }
        val actualChecksum = if (filter?.hashAfterCrypt == true) encryptedChecksum.value else decryptedChecksum.value
        require(actualChecksum == entry.adler32) {
            val filterName = filter?.displayName ?: "标准 XP3"
            "${entry.path} 经 $filterName 处理后校验失败，已删除无效输出"
        }
        return entryOffset
    }

    private fun openSegmentStream(file: File, segment: Xp3Segment): InputStream {
        val bounded = RandomAccessFileInputStream(file, segment.offset, segment.archivedSize)
        return if (segment.compressed) InflaterInputStream(bounded) else bounded
    }

    private fun Xp3Entry.filterContext(): Xp3FilterContext = Xp3FilterContext(path, adler32, originalSize)

    private fun selectionKey(archive: KirikiriArchiveInput, entryIndex: Int, entry: Xp3Entry): String =
        archive.uri + SELECTION_KEY_SEPARATOR + entryIndex + SELECTION_KEY_SEPARATOR + entry.path

    private fun selectionPath(selectionKey: String): String {
        val firstSeparator = selectionKey.indexOf(SELECTION_KEY_SEPARATOR)
        val secondSeparator = selectionKey.indexOf(SELECTION_KEY_SEPARATOR, firstSeparator + 1)
        return if (secondSeparator >= 0 && secondSeparator < selectionKey.lastIndex) {
            selectionKey.substring(secondSeparator + 1)
        } else {
            "未知条目"
        }
    }

    private fun Throwable.readableMessage(fallback: String): String = message
        ?.replace(Regex("[\\r\\n]+"), " ")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(MAX_FAILURE_REASON_CHARS)
        ?: fallback

    private fun List<GameExtractionFailure>.failureMessage(fallback: String): String {
        if (isEmpty()) return fallback
        val details = take(3).joinToString("；") { failure -> "${failure.item}：${failure.reason}" }
        val suffix = if (size > 3) "；另有 ${size - 3} 项" else ""
        return "$fallback。$details$suffix"
    }

    private fun adler32(data: ByteArray): Long = Adler32().run {
        update(data, 0, data.size)
        value
    }

    private fun contentSignatureScore(path: String, data: ByteArray): Int {
        if (data.isEmpty()) return 0
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val expectedMatch = when (extension) {
            "png" -> data.startsWithBytes(PNG_MAGIC)
            "jpg", "jpeg" -> data.startsWithBytes(JPEG_MAGIC)
            "ogg" -> data.startsWithAscii("OggS")
            "wav" -> data.startsWithAscii("RIFF") && data.hasAsciiAt(8, "WAVE")
            "webp" -> data.startsWithAscii("RIFF") && data.hasAsciiAt(8, "WEBP")
            "bmp" -> data.startsWithAscii("BM")
            "tlg" -> data.startsWithAscii("TLG")
            "psb" -> data.startsWithAscii("PSB\u0000")
            "zip" -> data.startsWithBytes(ZIP_MAGIC)
            "mp3" -> data.startsWithAscii("ID3") || (data.size >= 2 && data[0].toInt() and 0xFF == 0xFF && data[1].toInt() and 0xE0 == 0xE0)
            "json", "ks", "tjs", "txt", "asd", "csv" -> looksLikeTextOrKirikiriScript(data)
            else -> false
        }
        if (expectedMatch) return 120
        return knownMagicScore(data)
    }

    private fun looksLikeTextOrKirikiriScript(data: ByteArray): Boolean {
        if (data.startsWithBytes(byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0x00, 0xFF.toByte(), 0xFE.toByte())) ||
            data.startsWithBytes(byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0x01, 0xFF.toByte(), 0xFE.toByte())) ||
            data.startsWithBytes(byteArrayOf(0xFE.toByte(), 0xFE.toByte(), 0x02, 0xFF.toByte(), 0xFE.toByte())) ||
            data.startsWithBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) ||
            data.startsWithBytes(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) ||
            data.startsWithBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        ) return true
        val sampleSize = minOf(data.size, 2048)
        var readable = 0
        var controls = 0
        for (index in 0 until sampleSize) {
            when (data[index].toInt() and 0xFF) {
                0x09, 0x0A, 0x0D -> readable += 1
                in 0x20..0x7E -> readable += 1
                in 0x00..0x08, in 0x0B..0x0C, in 0x0E..0x1F -> controls += 1
            }
        }
        return controls <= sampleSize / 50 && readable >= sampleSize * 3 / 5
    }

    private fun knownMagicScore(data: ByteArray): Int = when {
        data.startsWithBytes(PNG_MAGIC) -> 100
        data.startsWithBytes(JPEG_MAGIC) -> 100
        data.startsWithBytes(ZIP_MAGIC) -> 100
        data.startsWithAscii("OggS") -> 100
        data.startsWithAscii("RIFF") -> 100
        data.startsWithAscii("TLG") -> 100
        data.startsWithAscii("PSB\u0000") -> 100
        data.startsWithAscii("ID3") -> 100
        data.startsWithAscii("BM") -> 50
        else -> 0
    }

    private fun ByteArray.startsWithBytes(expected: ByteArray): Boolean =
        size >= expected.size && expected.indices.all { index -> this[index] == expected[index] }

    private fun ByteArray.startsWithAscii(expected: String): Boolean = hasAsciiAt(0, expected)

    private fun ByteArray.hasAsciiAt(offset: Int, expected: String): Boolean =
        offset >= 0 && size - offset >= expected.length && expected.indices.all { index ->
            this[offset + index].toInt() and 0xFF == expected[index].code
        }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> = queryNameAndSizeCompat(context, uri)

    private fun writableTree(uri: Uri): DocumentFile =
        directoryDocumentFile(context, uri)?.takeIf { it.isDirectory && it.canWrite() }
            ?: error("所选输出目录不可写，请重新选择")

    private fun createOutputFile(root: DocumentFile, unsafePath: String): DocumentFile {
        val parts = unsafePath.replace('\\', '/').trimStart('/').split('/').filter(String::isNotBlank)
        require(parts.isNotEmpty() && parts.none { it == "." || it == ".." }) { "XP3 文件路径不安全" }
        require(parts.size <= MAX_PATH_COMPONENTS) { "XP3 文件路径层级过深" }
        var directory = root
        parts.dropLast(1).map(::safeName).forEach { part ->
            directory = directory.findFile(part)?.takeIf(DocumentFile::isDirectory)
                ?: directory.createDirectory(part)
                ?: error("无法创建目录 $part")
        }
        val fileName = safeName(parts.last())
        val dot = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
        val base = fileName.substring(0, dot)
        val extension = fileName.substring(dot)
        for (index in 1..999) {
            val candidate = if (index == 1) fileName else "$base ($index)$extension"
            if (directory.findFile(candidate) == null) {
                return directory.createFile(mimeForName(candidate), candidate)
                    ?: error("无法创建文件 $candidate")
            }
        }
        error("无法分配 XP3 输出文件名")
    }

    private fun safeName(value: String): String = value
        .replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]"), "_")
        .trim()
        .trimEnd('.', ' ')
        .ifBlank { "unnamed" }
        .take(120)

    private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "json" -> "application/json"
        "tjs", "ks", "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun RandomAccessFile.readLongLe(): Long = java.lang.Long.reverseBytes(readLong())

    private fun RandomAccessFile.readBoundedSize(limit: Int, label: String): Int {
        val value = readLongLe()
        require(value in 0..limit.toLong()) { "$label 长度异常" }
        return value.toInt()
    }

    private fun ByteBuffer.readTag(): String {
        require(remaining() >= 4) { "XP3 索引标签不完整" }
        return String(ByteArray(4).also { get(it) }, Charsets.US_ASCII)
    }

    private fun safeAdd(left: Long, right: Long): Long {
        require(right >= 0 && left <= Long.MAX_VALUE - right) { "XP3 资源总大小溢出" }
        return left + right
    }

    private data class ParsedXp3(val file: File, val entries: List<Xp3Entry>)
    private data class ResolvedXp3(
        val input: KirikiriArchiveInput,
        val parsed: ParsedXp3,
        val filter: Xp3ContentFilter?,
    )
    private data class SelectedXp3Entry(
        val selectionKey: String,
        val archive: ResolvedXp3,
        val entry: Xp3Entry,
    )
    private data class FilterSample(val entry: Xp3Entry, val encryptedData: ByteArray)
    private data class Xp3Entry(
        val path: String,
        val originalSize: Long,
        val archivedSize: Long,
        val protected: Boolean,
        val adler32: Long,
        val segments: List<Xp3Segment>,
    )
    private data class Xp3Segment(
        val compressed: Boolean,
        val offset: Long,
        val originalSize: Long,
        val archivedSize: Long,
    )

    private class RandomAccessFileInputStream(
        file: File,
        offset: Long,
        private var remaining: Long,
    ) : InputStream() {
        private val archive = RandomAccessFile(file, "r")

        init {
            archive.seek(offset)
        }

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = archive.read()
            if (value >= 0) remaining -= 1
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val requested = minOf(length.toLong(), remaining).toInt()
            val count = archive.read(buffer, offset, requested)
            if (count > 0) remaining -= count
            return count
        }

        override fun close() = archive.close()
    }

    private companion object {
        val XP3_MAGIC = byteArrayOf(
            0x58, 0x50, 0x33, 0x0D, 0x0A, 0x20, 0x0A, 0x1A,
            0x8B.toByte(), 0x67, 0x01,
        )
        val PNG_MAGIC = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        val GENERIC_MAGIC_CANDIDATES = listOf(
            PNG_MAGIC,
            JPEG_MAGIC,
            ZIP_MAGIC,
            "OggS".toByteArray(Charsets.US_ASCII),
            "RIFF".toByteArray(Charsets.US_ASCII),
            "TLG".toByteArray(Charsets.US_ASCII),
            "PSB\u0000".toByteArray(Charsets.US_ASCII),
            "BM".toByteArray(Charsets.US_ASCII),
            "ID3".toByteArray(Charsets.US_ASCII),
        )
        const val INDEX_METHOD_MASK = 0x07
        const val INDEX_RAW = 0
        const val INDEX_ZLIB = 1
        const val INDEX_CONTINUE = 0x80
        const val SEGMENT_METHOD_MASK = 0x07
        const val SEGMENT_RAW = 0
        const val SEGMENT_ZLIB = 1
        const val CHUNK_HEADER_SIZE = 12
        const val SEGMENT_RECORD_SIZE = 28
        const val MAX_INDEX_BYTES = 64 * 1024 * 1024
        const val MAX_INDEX_BLOCKS = 32
        const val MAX_XP3_ENTRIES = 100_000
        const val MAX_XP3_PATH_CHARS = 4_096
        const val MAX_DIRECTORY_ENTRIES = 100_000
        const val MAX_PATH_COMPONENTS = 64
        const val MAX_PREVIEW_ITEMS = 20
        const val MAX_FILTER_SAMPLES = 3
        const val MAX_FILTER_SAMPLE_BYTES = 4L * 1024L * 1024L
        const val MAX_FAILURE_RECORDS = 100
        const val MAX_FAILURE_REASON_CHARS = 240
        const val STRONG_SIGNATURE_SCORE = 100
        const val MAX_OUTPUT_BYTES = 8L * 1024L * 1024L * 1024L
        const val SELECTION_KEY_SEPARATOR = '\u0000'
    }
}
