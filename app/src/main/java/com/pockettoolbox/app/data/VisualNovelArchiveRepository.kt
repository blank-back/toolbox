package com.pockettoolbox.app.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import java.nio.charset.Charset
import java.util.ArrayDeque
import java.util.Locale
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class VisualNovelArchiveFormat(val displayName: String, val engineName: String) {
    RENPY_RPA3("RPA-3.0", "Ren'Py"),
    MAJIRO_ARC("Majiro ARC", "Majiro"),
    BGI_PACK("PackFile", "BGI / Ethornell"),
    BURIKO_ARC20("BURIKO ARC20", "BGI / Ethornell"),
    NSCRIPTER_SAR("SAR", "NScripter / ONScripter"),
    NSCRIPTER_NSA("NSA", "NScripter / ONScripter"),
    NSCRIPTER_NS2("NS2", "NScripter / ONScripter"),
    NITROPLUS_NPA("NPA", "Nitroplus"),
    ARTEMIS_PFS("PFS", "Artemis"),
    WILL_ARC("WILL ARC", "Will / Pulltop"),
    KAGUYA_LINK("LINK", "KaGuYa"),
    YURIS_YPF("YPF", "YU-RIS"),
    NEKOSDK_PACK4("NEKOPACK4", "NekoSDK"),
    QLIE_FILEPACK("FilePack", "QLIE"),
    ENTIS_NOA("NOA", "EntisGLS"),
    CMVS_CPZ("CPZ", "CMVS"),
}

data class VisualNovelArchiveInput(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val relativePath: String = name,
    val format: VisualNovelArchiveFormat,
)

data class VisualNovelArchiveInspection(
    val archives: List<VisualNovelArchiveInput>,
    val sourceLabel: String,
    val sourceDirectoryUri: String? = null,
    val entryCount: Int,
    val unsupportedEntryCount: Int,
    val outputBytes: Long,
    val previewPaths: List<String>,
    val formatCounts: Map<VisualNovelArchiveFormat, Int>,
    val unsupportedReasons: List<String>,
) {
    val canExtract: Boolean
        get() = archives.isNotEmpty() && entryCount > unsupportedEntryCount
}

/**
 * Signature-first visual-novel archive registry inspired by GARbro's plugin model.
 *
 * This is an Android-native, bounded streaming implementation. It intentionally
 * extracts only variants that can be handled without a game-specific key. Other
 * handlers may still recognize an archive while marking encrypted/compressed entries
 * unsupported, preventing accidental garbage output.
 */
class VisualNovelArchiveRepository(private val context: Context) {
    private data class Candidate(
        val uri: String,
        val name: String,
        val sizeBytes: Long,
        val relativePath: String = name,
    )

    private data class ArchiveHandler(
        val format: VisualNovelArchiveFormat,
        val matches: (ByteArray, String) -> Boolean,
        val parse: (RandomAccessFile, String) -> List<ArchiveEntry>,
    )

    private data class ParsedArchive(
        val file: File,
        val candidate: Candidate,
        val format: VisualNovelArchiveFormat,
        val entries: List<ArchiveEntry>,
    )

    private data class ArchiveEntry(
        val path: String,
        val segments: List<ArchiveSegment>,
        val outputSize: Long,
        val unsupportedReason: String? = null,
        val encoding: EntryEncoding = EntryEncoding.RAW,
    )

    private enum class EntryEncoding { RAW, ZLIB, ROTATE_RIGHT_2, NEKOPACK_ZLIB }

    private data class ArchiveSegment(
        val offset: Long,
        val storedSize: Long,
        val prefix: ByteArray = ByteArray(0),
    )

    private val handlers: List<ArchiveHandler> by lazy {
        listOf(
            ArchiveHandler(
                format = VisualNovelArchiveFormat.RENPY_RPA3,
                matches = { header, _ -> header.startsWithAscii("RPA-3.0 ") },
                parse = ::parseRpa3,
            ),
            ArchiveHandler(
                format = VisualNovelArchiveFormat.MAJIRO_ARC,
                matches = { header, _ -> header.startsWithAscii("MajiroArcV") },
                parse = ::parseMajiro,
            ),
            ArchiveHandler(
                format = VisualNovelArchiveFormat.BGI_PACK,
                matches = { header, _ -> header.startsWithAscii("PackFile    ") },
                parse = ::parseBgiPack,
            ),
            ArchiveHandler(
                format = VisualNovelArchiveFormat.BURIKO_ARC20,
                matches = { header, _ -> header.startsWithAscii("BURIKO ARC20") },
                parse = ::parseBurikoArc20,
            ),
            ArchiveHandler(
                format = VisualNovelArchiveFormat.NSCRIPTER_NSA,
                matches = { _, name -> name.lowercase(Locale.ROOT).endsWith(".nsa") },
                parse = ::parseNsa,
            ),
            ArchiveHandler(
                format = VisualNovelArchiveFormat.NSCRIPTER_SAR,
                matches = { _, name -> name.lowercase(Locale.ROOT).endsWith(".sar") },
                parse = ::parseSar,
            ),
            ArchiveHandler(
                format = VisualNovelArchiveFormat.NSCRIPTER_NS2,
                matches = { _, name -> name.lowercase(Locale.ROOT).endsWith(".ns2") },
                parse = ::parseNs2,
            ),
            ArchiveHandler(
                format = VisualNovelArchiveFormat.NITROPLUS_NPA,
                matches = { header, _ -> header.startsWith(byteArrayOf('N'.code.toByte(), 'P'.code.toByte(), 'A'.code.toByte(), 1)) },
                parse = ::parseNitroplusNpa,
            ),
            ArchiveHandler(
                format = VisualNovelArchiveFormat.ARTEMIS_PFS,
                matches = { header, _ ->
                    header.size >= 3 && header[0] == 'p'.code.toByte() && header[1] == 'f'.code.toByte() &&
                        header[2].toInt().toChar() in setOf('2', '6', '8')
                },
                parse = ::parseArtemisPfs,
            ),
            ArchiveHandler(
                format = VisualNovelArchiveFormat.KAGUYA_LINK,
                matches = { header, _ -> header.startsWithAscii("LINK") },
                parse = ::parseKaguyaLink,
            ),
            ArchiveHandler(
                format = VisualNovelArchiveFormat.YURIS_YPF,
                matches = { header, _ -> header.startsWith(byteArrayOf('Y'.code.toByte(), 'P'.code.toByte(), 'F'.code.toByte(), 0)) },
                parse = ::parseYurisYpfRecognition,
            ),
            ArchiveHandler(
                format = VisualNovelArchiveFormat.NEKOSDK_PACK4,
                matches = { header, _ -> header.startsWithAscii("NEKOPACK4") },
                parse = ::parseNekoPack4,
            ),
            ArchiveHandler(
                format = VisualNovelArchiveFormat.QLIE_FILEPACK,
                matches = { header, _ -> header.startsWithAscii("FilePackVer") },
                parse = ::parseQlieRecognition,
            ),
            ArchiveHandler(
                format = VisualNovelArchiveFormat.ENTIS_NOA,
                matches = { header, _ -> header.startsWithAscii("Entis\u001A") || header.startsWithAscii("VIST\u001A") },
                parse = ::parseEntisRecognition,
            ),
            ArchiveHandler(
                format = VisualNovelArchiveFormat.CMVS_CPZ,
                matches = { header, _ ->
                    header.size >= 4 && header.startsWithAscii("CPZ") && header[3].toInt().toChar() in setOf('1', '2', '3', '5', '6', '7')
                },
                parse = ::parseCmvsRecognition,
            ),
            // WILL ARC has no stable magic. Keep it last and validate its complete index
            // before accepting an .arc file, so signature-based handlers always win.
            ArchiveHandler(
                format = VisualNovelArchiveFormat.WILL_ARC,
                matches = { _, name -> name.lowercase(Locale.ROOT).endsWith(".arc") },
                parse = ::parseWillArc,
            ),
        )
    }

    suspend fun inspectFiles(uris: List<Uri>): VisualNovelArchiveInspection = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "请至少选择一个 Visual Novel 档案" }
        val candidates = uris.map { uri ->
            val (name, size) = queryNameAndSize(uri)
            Candidate(uri.toString(), name, size)
        }
        inspectCandidates(
            candidates = candidates,
            sourceLabel = if (candidates.size == 1) candidates.first().name else "${candidates.size} 个 VN 档案",
            requireEveryCandidate = true,
        )
    }

    suspend fun inspectDirectory(treeUri: Uri): VisualNovelArchiveInspection = withContext(Dispatchers.IO) {
        val root = directoryDocumentFile(context, treeUri)?.takeIf(DocumentFile::isDirectory)
            ?: error("无法读取所选目录")
        val pending = ArrayDeque<Pair<DocumentFile, String>>()
        root.listFiles().forEach { child -> pending.addLast(child to (child.name ?: "unnamed")) }
        val candidates = mutableListOf<Candidate>()
        var visited = 0
        while (pending.isNotEmpty()) {
            val (entry, relativePath) = pending.removeFirst()
            visited += 1
            require(visited <= MAX_DIRECTORY_ENTRIES) { "目录条目过多，已中止 Visual Novel 档案扫描" }
            if (entry.isDirectory) {
                entry.listFiles().forEach { child ->
                    pending.addLast(child to "$relativePath/${child.name ?: "unnamed"}")
                }
            } else if (entry.isFile && isCandidateName(entry.name.orEmpty())) {
                candidates += Candidate(
                    uri = entry.uri.toString(),
                    name = entry.name ?: "archive.bin",
                    sizeBytes = entry.length(),
                    relativePath = relativePath,
                )
            }
        }
        require(candidates.isNotEmpty()) { "目录内未找到可能的 Visual Novel 档案" }
        inspectCandidates(
            candidates = candidates,
            sourceLabel = root.name ?: "Visual Novel 游戏目录",
            sourceDirectoryUri = treeUri.toString(),
            requireEveryCandidate = false,
        )
    }

    suspend fun extract(
        archives: List<VisualNovelArchiveInput>,
        outputTreeUri: Uri,
        onProgress: suspend (String) -> Unit,
    ): GameExtractionSummary = withContext(Dispatchers.IO) {
        require(archives.isNotEmpty()) { "没有可处理的 Visual Novel 档案" }
        val parsedArchives = mutableListOf<ParsedArchive>()
        try {
            archives.forEach { input ->
                val candidate = Candidate(input.uri, input.name, input.sizeBytes, input.relativePath)
                val parsed = copyAndParse(candidate)
                require(parsed.format == input.format) { "${input.name} 的格式与分析时不一致，请重新选择" }
                parsedArchives += parsed
            }
            val supportedCount = parsedArchives.sumOf { archive -> archive.entries.count { it.unsupportedReason == null } }
            require(supportedCount > 0) { "所选 Visual Novel 档案中没有可可靠导出的条目" }
            val output = writableTree(outputTreeUri)
            var filesWritten = 0
            var bytesWritten = 0L
            parsedArchives.forEachIndexed { archiveIndex, archive ->
                archive.entries.forEachIndexed entryLoop@{ entryIndex, entry ->
                    if (entry.unsupportedReason != null) return@entryLoop
                    onProgress(
                        "正在导出 ${archiveIndex + 1}/${parsedArchives.size} · " +
                            "${entryIndex + 1}/${archive.entries.size}：${entry.path.substringAfterLast('/')}"
                    )
                    require(entry.outputSize <= MAX_OUTPUT_BYTES - bytesWritten) {
                        "本次 Visual Novel 资源导出已超过 8 GiB 安全上限"
                    }
                    val target = createOutputFile(output, entry.path)
                    try {
                        openOutputStreamCompat(context, target.uri, "w")?.use { destination ->
                            val entryWritten = writeEntry(archive.file, entry, destination)
                            require(entryWritten == entry.outputSize) { "${entry.path} 的输出长度不完整" }
                            bytesWritten = safeAdd(bytesWritten, entryWritten)
                        } ?: error("无法写入 ${entry.path}")
                    } catch (error: Throwable) {
                        target.delete()
                        throw error
                    }
                    filesWritten += 1
                }
            }
            GameExtractionSummary(filesWritten, bytesWritten, output.name ?: "所选输出目录")
        } finally {
            parsedArchives.forEach { it.file.delete() }
        }
    }

    private fun writeEntry(
        archiveFile: File,
        entry: ArchiveEntry,
        destination: java.io.OutputStream,
    ): Long = when (entry.encoding) {
        EntryEncoding.RAW -> {
            var written = 0L
            entry.segments.forEach { segment ->
                if (segment.prefix.isNotEmpty()) {
                    destination.write(segment.prefix)
                    written = safeAdd(written, segment.prefix.size.toLong())
                }
                RegionInputStream(archiveFile, segment.offset, segment.storedSize).use { source ->
                    written = safeAdd(written, copyExactly(source, destination, segment.storedSize))
                }
            }
            written
        }

        EntryEncoding.ZLIB -> {
            require(entry.segments.size == 1 && entry.segments.single().prefix.isEmpty()) {
                "${entry.path} 的 zlib 分段结构不受支持"
            }
            val segment = entry.segments.single()
            RegionInputStream(archiveFile, segment.offset, segment.storedSize).use { region ->
                InflaterInputStream(region).use { inflater ->
                    copyExactly(inflater, destination, entry.outputSize)
                }
            }
        }

        EntryEncoding.ROTATE_RIGHT_2 -> {
            require(entry.segments.size == 1 && entry.segments.single().prefix.isEmpty()) {
                "${entry.path} 的脚本分段结构不受支持"
            }
            val segment = entry.segments.single()
            RegionInputStream(archiveFile, segment.offset, segment.storedSize).use { source ->
                copyTransformExactly(source, destination, segment.storedSize) { value ->
                    ((value ushr 2) or (value shl 6)) and 0xFF
                }
            }
        }

        EntryEncoding.NEKOPACK_ZLIB -> {
            require(entry.segments.size == 1 && entry.segments.single().prefix.isEmpty()) {
                "${entry.path} 的 NEKOPACK 分段结构不受支持"
            }
            val segment = entry.segments.single()
            require(segment.storedSize >= 8) { "${entry.path} 的 NEKOPACK 数据过短" }
            val headerSize = minOf(4L, segment.storedSize).toInt()
            val encryptedHeader = RandomAccessFile(archiveFile, "r").use { source ->
                source.seek(segment.offset)
                ByteArray(headerSize).also { source.readFully(it) }
            }
            var key = segment.storedSize / 8L + 0x22L
            encryptedHeader.indices.forEach { index ->
                encryptedHeader[index] = ((encryptedHeader[index].toInt() and 0xFF) xor (key.toInt() and 0xFF)).toByte()
                key = (key shl 3) and 0xFFFF_FFFFL
            }
            RegionInputStream(archiveFile, segment.offset + headerSize, segment.storedSize - headerSize - 4L).use { body ->
                SequenceInputStream(ByteArrayInputStream(encryptedHeader), body).use { packed ->
                    InflaterInputStream(packed).use { inflater -> copyExactly(inflater, destination, entry.outputSize) }
                }
            }
        }
    }

    private fun inspectCandidates(
        candidates: List<Candidate>,
        sourceLabel: String,
        sourceDirectoryUri: String? = null,
        requireEveryCandidate: Boolean,
    ): VisualNovelArchiveInspection {
        val archives = mutableListOf<VisualNovelArchiveInput>()
        val formatCounts = linkedMapOf<VisualNovelArchiveFormat, Int>()
        val preview = mutableListOf<String>()
        val unsupportedReasons = linkedSetOf<String>()
        var entryCount = 0
        var unsupportedEntryCount = 0
        var outputBytes = 0L
        candidates.forEach { candidate ->
            val parsed = try {
                copyAndParse(candidate)
            } catch (error: Throwable) {
                if (requireEveryCandidate) throw error
                return@forEach
            }
            try {
                archives += VisualNovelArchiveInput(
                    uri = candidate.uri,
                    name = candidate.name,
                    sizeBytes = candidate.sizeBytes,
                    relativePath = candidate.relativePath,
                    format = parsed.format,
                )
                formatCounts[parsed.format] = formatCounts.getOrDefault(parsed.format, 0) + 1
                entryCount += parsed.entries.size
                unsupportedEntryCount += parsed.entries.count { it.unsupportedReason != null }
                parsed.entries.forEach { entry ->
                    entry.unsupportedReason?.let { reason -> unsupportedReasons += reason }
                    if (entry.unsupportedReason == null) outputBytes = safeAdd(outputBytes, entry.outputSize)
                    if (preview.size < MAX_PREVIEW_ITEMS) {
                        val path = if (candidates.size == 1) entry.path else "${candidate.name}!/${entry.path}"
                        preview += if (entry.unsupportedReason == null) path else "[暂不支持] $path"
                    }
                }
            } finally {
                parsed.file.delete()
            }
        }
        require(archives.isNotEmpty()) {
            "未识别到当前支持的 VN 档案；不会仅凭扩展名强行解析"
        }
        require(entryCount > 0) { "已识别档案格式，但索引中没有文件条目" }
        return VisualNovelArchiveInspection(
            archives = archives,
            sourceLabel = sourceLabel,
            sourceDirectoryUri = sourceDirectoryUri,
            entryCount = entryCount,
            unsupportedEntryCount = unsupportedEntryCount,
            outputBytes = outputBytes,
            previewPaths = preview,
            formatCounts = formatCounts,
            unsupportedReasons = unsupportedReasons.toList(),
        )
    }

    private fun copyAndParse(candidate: Candidate): ParsedArchive {
        val cache = File.createTempFile("vn_archive_", ".bin", context.cacheDir)
        try {
            openInputStreamCompat(context, Uri.parse(candidate.uri))?.use { source ->
                FileOutputStream(cache).use { destination -> source.copyTo(destination) }
            } ?: error("无法读取 ${candidate.name}")
            RandomAccessFile(cache, "r").use { archive ->
                require(archive.length() >= MIN_ARCHIVE_BYTES) { "${candidate.name} 过短，不是受支持的 VN 档案" }
                val header = archive.readHeader(32)
                val handler = handlers.firstOrNull { it.matches(header, candidate.name) }
                    ?: error("${candidate.name} 的签名不属于当前支持的 VN 档案")
                archive.seek(0)
                val entries = handler.parse(archive, candidate.name)
                require(entries.isNotEmpty()) { "${candidate.name} 的档案索引为空" }
                require(entries.size <= MAX_ARCHIVE_ENTRIES) { "${candidate.name} 的文件条目过多" }
                return ParsedArchive(cache, candidate, handler.format, entries)
            }
        } catch (error: Throwable) {
            cache.delete()
            throw error
        }
    }

    private fun parseBgiPack(archive: RandomAccessFile, sourceName: String): List<ArchiveEntry> {
        require(archive.readAscii(0, 12) == "PackFile    ") { "$sourceName 不是 BGI PackFile 档案" }
        val count = archive.readUIntLeAt(12).toIntChecked("BGI 文件数")
        require(count in 1..MAX_ARCHIVE_ENTRIES) { "BGI 文件数异常" }
        val indexSize = checkedMultiply(count.toLong(), 0x20L, "BGI 索引")
        val baseOffset = safeAdd(0x10L, indexSize)
        require(baseOffset <= archive.length()) { "BGI 索引超出档案范围" }
        return List(count) { index ->
            val record = 0x10L + index * 0x20L
            val name = archive.readFixedName(record, 0x10)
            val offset = safeAdd(baseOffset, archive.readUIntLeAt(record + 0x10))
            val size = archive.readUIntLeAt(record + 0x14)
            requirePlacement(offset, size, archive.length(), name)
            val marker = archive.readAscii(offset, minOf(size, 16L).toInt())
            val unsupported = when {
                marker.startsWith("DSC FORMAT 1.00") -> "BGI DSC 压缩"
                marker.startsWith("BSE 1.") -> "BGI BSE 加密"
                else -> null
            }
            rawEntry(name, offset, size, unsupported)
        }
    }

    private fun parseBurikoArc20(archive: RandomAccessFile, sourceName: String): List<ArchiveEntry> {
        require(archive.readAscii(0, 12) == "BURIKO ARC20") { "$sourceName 不是 BURIKO ARC20 档案" }
        val count = archive.readIntLeAt(12)
        require(count in 1..MAX_ARCHIVE_ENTRIES) { "BURIKO ARC20 文件数异常" }
        val indexSize = checkedMultiply(count.toLong(), 0x80L, "BURIKO ARC20 索引")
        val baseOffset = safeAdd(0x10L, indexSize)
        require(baseOffset <= archive.length()) { "BURIKO ARC20 索引超出档案范围" }
        return List(count) { index ->
            val record = 0x10L + index * 0x80L
            val name = archive.readFixedName(record, 0x60)
            val offset = safeAdd(baseOffset, archive.readUIntLeAt(record + 0x60))
            val size = archive.readUIntLeAt(record + 0x64)
            requirePlacement(offset, size, archive.length(), name)
            val marker = archive.readAscii(offset, minOf(size, 16L).toInt())
            val unsupported = when {
                marker.startsWith("BSE 1.") -> "BURIKO BSE 加密"
                marker.startsWith("DSC FORMAT 1.00") -> "BGI DSC 压缩"
                else -> null
            }
            rawEntry(name, offset, size, unsupported)
        }
    }

    private fun parseMajiro(archive: RandomAccessFile, sourceName: String): List<ArchiveEntry> {
        val signature = archive.readAscii(0, 16)
        require(signature.startsWith("MajiroArcV") && signature.endsWith(".000\u0000")) {
            "$sourceName 不是 Majiro ARC 档案"
        }
        val version = signature.getOrNull(10)?.digitToIntOrNull()
            ?: error("Majiro ARC 版本号无效")
        require(version in 1..3) { "暂不支持 Majiro ARC V$version" }
        val count = archive.readIntLeAt(16)
        require(count in 1..MAX_ARCHIVE_ENTRIES) { "Majiro ARC 文件数异常" }
        val namesOffset = archive.readUIntLeAt(20)
        val dataOffset = archive.readUIntLeAt(24)
        val entrySize = 4L * (version + 1L)
        val tableCount = count + if (version == 1) 1 else 0
        val expectedNamesOffset = safeAdd(0x1CL, checkedMultiply(tableCount.toLong(), entrySize, "Majiro 索引"))
        require(namesOffset == expectedNamesOffset && dataOffset > namesOffset && dataOffset <= archive.length()) {
            "Majiro ARC 索引范围无效"
        }
        val namesSize = (dataOffset - namesOffset).toIntChecked("Majiro 文件名表")
        require(namesSize <= MAX_INDEX_BYTES) { "Majiro 文件名表过大" }
        archive.seek(namesOffset)
        val namesData = ByteArray(namesSize).also { archive.readFully(it) }
        val names = decodeNullTerminatedNames(namesData, count)
        val hashSize = if (version < 3) 4L else 8L
        var tablePosition = 0x1CL
        var nextOffset = archive.readUIntLeAt(tablePosition + hashSize)
        return List(count) { index ->
            val offset = nextOffset
            val size = if (version == 1) {
                nextOffset = archive.readUIntLeAt(tablePosition + entrySize + hashSize)
                require(nextOffset >= offset) { "Majiro ARC 条目偏移倒序" }
                nextOffset - offset
            } else {
                val storedSize = archive.readUIntLeAt(tablePosition + hashSize + 4)
                if (index + 1 < count) {
                    nextOffset = archive.readUIntLeAt(tablePosition + entrySize + hashSize)
                }
                storedSize
            }
            tablePosition += entrySize
            require(offset >= dataOffset) { "Majiro ARC 条目位于数据区之前" }
            requirePlacement(offset, size, archive.length(), names[index])
            rawEntry(names[index], offset, size)
        }
    }

    private fun parseSar(archive: RandomAccessFile, sourceName: String): List<ArchiveEntry> {
        archive.seek(0)
        val count = archive.readUnsignedShort()
        require(count in 1..MAX_ARCHIVE_ENTRIES) { "$sourceName 的 SAR 文件数异常" }
        val baseOffset = archive.readUIntBe()
        require(baseOffset in (10L * count)..archive.length()) { "$sourceName 的 SAR 索引范围无效" }
        return List(count) {
            val name = normalizeEntryPath(String(archive.readCString(baseOffset), SHIFT_JIS))
            require(archive.filePointer + 8 <= baseOffset) { "SAR 索引内容不完整" }
            val offset = safeAdd(baseOffset, archive.readUIntBe())
            val size = archive.readUIntBe()
            requirePlacement(offset, size, archive.length(), name)
            rawEntry(name, offset, size)
        }
    }

    private fun parseNsa(archive: RandomAccessFile, sourceName: String): List<ArchiveEntry> {
        archive.seek(0)
        val first = archive.readUnsignedShort()
        val indexStart = if (first == 0) 2L else 0L
        archive.seek(indexStart)
        val count = archive.readUnsignedShort()
        require(count in 1..MAX_ARCHIVE_ENTRIES) { "$sourceName 的 NSA 文件数异常" }
        val baseOffset = safeAdd(indexStart, archive.readUIntBe())
        require(baseOffset in (15L * count)..archive.length()) { "$sourceName 的 NSA 索引范围无效" }
        return List(count) {
            val name = normalizeEntryPath(String(archive.readCString(baseOffset), SHIFT_JIS))
            require(archive.filePointer + 13 <= baseOffset) { "NSA 索引内容不完整" }
            val compression = archive.readUnsignedByte()
            val offset = safeAdd(baseOffset, archive.readUIntBe())
            val storedSize = archive.readUIntBe()
            val unpackedSize = archive.readUIntBe()
            requirePlacement(offset, storedSize, archive.length(), name)
            val unsupported = when (compression) {
                0 -> null
                1 -> "NSA SPB 压缩"
                2 -> "NSA LZSS 压缩"
                4 -> "NSA NBZ/BZip2 压缩"
                else -> "未知 NSA 压缩类型 $compression"
            }
            ArchiveEntry(
                path = name,
                segments = listOf(ArchiveSegment(offset, storedSize)),
                outputSize = if (compression == 0) storedSize else unpackedSize,
                unsupportedReason = unsupported,
            )
        }
    }

    private fun parseNs2(archive: RandomAccessFile, sourceName: String): List<ArchiveEntry> {
        require(sourceName.lowercase(Locale.ROOT).endsWith(".ns2")) { "$sourceName 不是 NS2 档案" }
        if (archive.length() < 8L) {
            return recognitionOnlyEntry(sourceName, "NScripter NS2", "NS2 档案头过短或使用口令加密")
        }
        val dataOffset = archive.readUIntLeAt(0)
        if (dataOffset <= 4L || dataOffset >= archive.length()) {
            return recognitionOnlyEntry(sourceName, "NScripter NS2", "NS2 索引可能使用口令/MD5 分块加密")
        }
        var cursor = 4L
        var currentOffset = dataOffset
        val entries = mutableListOf<ArchiveEntry>()
        while (cursor < dataOffset) {
            archive.seek(cursor)
            if (archive.readUnsignedByte() != '"'.code) break
            val rawName = ArrayList<Byte>()
            var closed = false
            while (archive.filePointer < dataOffset) {
                val value = archive.readUnsignedByte()
                if (value == '"'.code) {
                    closed = true
                    break
                }
                require(rawName.size < MAX_NAME_BYTES) { "NS2 文件名过长" }
                rawName += value.toByte()
            }
            require(closed && rawName.isNotEmpty() && archive.filePointer + 4 <= dataOffset) { "NS2 索引内容不完整" }
            val name = normalizeEntryPath(String(rawName.toByteArray(), SHIFT_JIS))
            val sizePosition = archive.filePointer
            val size = archive.readUIntLeAt(sizePosition)
            cursor = sizePosition + 4
            requirePlacement(currentOffset, size, archive.length(), name)
            entries += rawEntry(name, currentOffset, size)
            currentOffset = safeAdd(currentOffset, size)
            require(entries.size <= MAX_ARCHIVE_ENTRIES) { "NS2 文件条目过多" }
        }
        require(entries.isNotEmpty()) { "NS2 索引为空" }
        return entries
    }

    private fun parseRpa3(archive: RandomAccessFile, sourceName: String): List<ArchiveEntry> {
        val headerLine = archive.readAsciiLine(MAX_RPA_HEADER_BYTES)
        val parts = headerLine.trim().split(Regex("\\s+"))
        require(parts.size >= 3 && parts[0] == "RPA-3.0") { "$sourceName 不是标准 RPA-3.0 档案" }
        val indexOffset = parts[1].toLongOrNull(16) ?: error("RPA 索引偏移无效")
        val key = parts[2].toLongOrNull(16)?.and(0xFFFF_FFFFL) ?: error("RPA XOR key 无效")
        require(indexOffset in 1 until archive.length()) { "RPA 索引偏移超出档案范围" }
        val compressedIndex = SharedRafRegionInputStream(archive, indexOffset, archive.length() - indexOffset)
        val root = InflaterInputStream(compressedIndex).use { inflater ->
            SafeRpaPickleReader(LimitedInputStream(inflater, MAX_INDEX_BYTES.toLong())).read()
        }
        val dictionary = root as? Map<*, *> ?: error("RPA pickle 根对象不是字典")
        require(dictionary.size in 1..MAX_ARCHIVE_ENTRIES) { "RPA 文件条目数量异常" }
        return dictionary.map { (rawName, rawChunks) ->
            val name = normalizeEntryPath(pickleString(rawName))
            val chunks = rawChunks as? List<*> ?: error("RPA 条目 $name 的分段列表无效")
            require(chunks.isNotEmpty() && chunks.size <= MAX_RPA_SEGMENTS) { "RPA 条目 $name 的分段数量异常" }
            val segments = chunks.map { rawChunk ->
                val tuple = rawChunk as? List<*> ?: error("RPA 条目 $name 的分段信息无效")
                require(tuple.size >= 2) { "RPA 条目 $name 的分段字段不足" }
                val offset = xorRpaNumber(tuple[0], key)
                val totalSize = xorRpaNumber(tuple[1], key)
                val prefix = when (val rawPrefix = tuple.getOrNull(2)) {
                    is PickleBytes -> rawPrefix.bytes
                    null, PickleNone -> ByteArray(0)
                    else -> error("RPA 条目 $name 的前缀类型不受支持")
                }
                require(totalSize >= prefix.size) { "RPA 条目 $name 的长度小于前缀长度" }
                val storedSize = totalSize - prefix.size
                requirePlacement(offset, storedSize, indexOffset, name)
                ArchiveSegment(offset, storedSize, prefix)
            }
            var outputSize = 0L
            segments.forEach { segment ->
                outputSize = safeAdd(outputSize, safeAdd(segment.storedSize, segment.prefix.size.toLong()))
            }
            ArchiveEntry(name, segments, outputSize)
        }
    }

    private fun parseNitroplusNpa(archive: RandomAccessFile, sourceName: String): List<ArchiveEntry> {
        require(archive.readHeader(4).startsWith(byteArrayOf('N'.code.toByte(), 'P'.code.toByte(), 'A'.code.toByte(), 1))) {
            "$sourceName 不是 Nitroplus NPA 档案"
        }
        require(archive.length() >= 41L) { "$sourceName 的 NPA 档案头不完整" }
        val key1 = archive.readIntLeAt(7)
        val key2 = archive.readIntLeAt(11)
        archive.seek(15)
        val compressed = archive.readUnsignedByte() != 0
        val encrypted = archive.readUnsignedByte() != 0
        val totalCount = archive.readIntLeAt(17)
        val folderCount = archive.readIntLeAt(21)
        val fileCount = archive.readIntLeAt(25)
        val directorySize = archive.readUIntLeAt(37)
        require(totalCount in 1..MAX_ARCHIVE_ENTRIES && folderCount >= 0 && fileCount >= 0) {
            "$sourceName 的 NPA 条目数量异常"
        }
        require(folderCount.toLong() + fileCount.toLong() <= totalCount.toLong() && fileCount > 0) {
            "$sourceName 的 NPA 文件数量无效"
        }
        require(directorySize > 0L && directorySize < archive.length() - 40L) { "$sourceName 的 NPA 索引范围无效" }
        val dataOffset = safeAdd(41L, directorySize)
        if (encrypted) {
            return listOf(
                ArchiveEntry(
                    path = "${sourceName.substringBeforeLast('.').ifBlank { "archive" }}_encrypted_NPA.unsupported",
                    segments = emptyList(),
                    outputSize = 0,
                    unsupportedReason = "Nitroplus NPA 游戏专用加密",
                )
            )
        }
        val archiveKey = key1 * key2
        val entries = ArrayList<ArchiveEntry>(fileCount)
        var cursor = 41L
        repeat(totalCount) { entryIndex ->
            require(cursor + 4 <= dataOffset) { "NPA 索引提前结束" }
            val nameSize = archive.readIntLeAt(cursor)
            require(nameSize in 1..MAX_NAME_BYTES && cursor + 4L + nameSize + 17L <= dataOffset) {
                "NPA 文件名或索引长度异常"
            }
            archive.seek(cursor + 4)
            val rawName = ByteArray(nameSize).also { archive.readFully(it) }
            rawName.indices.forEach { index ->
                rawName[index] = ((rawName[index].toInt() and 0xFF) + npaNameDelta(index, entryIndex, archiveKey)).toByte()
            }
            val type = archive.readUnsignedByte()
            val infoOffset = cursor + 5L + nameSize
            if (type != 1) {
                val name = normalizeEntryPath(String(rawName, SHIFT_JIS))
                val relativeOffset = archive.readUIntLeAt(infoOffset + 4)
                val storedSize = archive.readUIntLeAt(infoOffset + 8)
                val unpackedSize = archive.readUIntLeAt(infoOffset + 12)
                val offset = safeAdd(dataOffset, relativeOffset)
                requirePlacement(offset, storedSize, archive.length(), name)
                val isPacked = compressed && name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in IMAGE_EXTENSIONS
                entries += ArchiveEntry(
                    path = name,
                    segments = listOf(ArchiveSegment(offset, storedSize)),
                    outputSize = if (isPacked) unpackedSize else storedSize,
                    unsupportedReason = null,
                    encoding = if (isPacked) EntryEncoding.ZLIB else EntryEncoding.RAW,
                )
            }
            cursor += 4L + nameSize + 17L
        }
        require(entries.size == fileCount) { "NPA 索引声明的文件数与实际条目不一致" }
        return entries
    }

    private fun npaNameDelta(index: Int, entryIndex: Int, archiveKey: Int): Int {
        var key = 0xFC * index
        key -= archiveKey shr 24
        key -= archiveKey shr 16
        key -= archiveKey shr 8
        key -= archiveKey and 0xFF
        key -= entryIndex shr 24
        key -= entryIndex shr 16
        key -= entryIndex shr 8
        key -= entryIndex
        return key and 0xFF
    }

    private fun parseArtemisPfs(archive: RandomAccessFile, sourceName: String): List<ArchiveEntry> {
        require(archive.length() >= 15L && archive.readAscii(0, 2) == "pf") { "$sourceName 不是 Artemis PFS 档案" }
        archive.seek(2)
        val version = archive.readUnsignedByte().toChar().digitToIntOrNull()
            ?: error("$sourceName 的 PFS 版本无效")
        require(version == 2 || version == 6 || version == 8) { "暂不支持 PFS$version" }
        val indexSize = archive.readUIntLeAt(3).toIntChecked("PFS 索引")
        require(indexSize in 12..MAX_INDEX_BYTES && 7L + indexSize <= archive.length()) { "PFS 索引范围无效" }
        archive.seek(7)
        val index = ByteArray(indexSize).also { archive.readFully(it) }
        val countOffset = if (version == 2) 4 else 0
        val count = index.readIntLe(countOffset)
        require(count in 1..MAX_ARCHIVE_ENTRIES) { "PFS 文件数异常" }
        var cursor = if (version == 2) 8 else 4
        return List(count) {
            require(cursor + 4 <= index.size) { "PFS 索引内容不完整" }
            val nameSize = index.readIntLe(cursor)
            require(nameSize in 1..MAX_NAME_BYTES) { "PFS 文件名长度异常" }
            val nameStart = cursor + 4
            require(nameStart + nameSize <= index.size) { "PFS 文件名超出索引" }
            val name = normalizeEntryPath(decodePfsName(index, nameStart, nameSize))
            cursor += nameSize + if (version == 2) 16 else 8
            require(cursor + 8 <= index.size) { "PFS 文件索引不完整" }
            val offset = index.readUIntLe(cursor)
            val size = index.readUIntLe(cursor + 4)
            cursor += 8
            requirePlacement(offset, size, archive.length(), name)
            rawEntry(
                unsafeName = name,
                offset = offset,
                size = size,
                unsupportedReason = if (version == 8) "Artemis PFS8 SHA-1 流加密" else null,
            )
        }
    }

    private fun decodePfsName(data: ByteArray, offset: Int, size: Int): String {
        val shiftJis = runCatching { SHIFT_JIS.newDecoder().decode(java.nio.ByteBuffer.wrap(data, offset, size)).toString() }.getOrNull()
        return shiftJis?.takeIf { value -> value.none { it == '\uFFFD' } }
            ?: String(data, offset, size, Charsets.UTF_8)
    }

    private fun parseWillArc(archive: RandomAccessFile, sourceName: String): List<ArchiveEntry> {
        require(archive.length() >= 16L) { "$sourceName 过短，不是 WILL ARC" }
        val extensionCount = archive.readIntLeAt(0)
        require(extensionCount in 1..0xFF) { "$sourceName 不是结构有效的 WILL ARC" }
        val extensions = ArrayList<WillExtension>(extensionCount)
        var headerOffset = 4L
        var totalFiles = 0
        repeat(extensionCount) {
            require(headerOffset + 12 <= archive.length()) { "WILL ARC 扩展名表不完整" }
            val extension = archive.readFixedAscii(headerOffset, 4).lowercase(Locale.ROOT)
            val count = archive.readIntLeAt(headerOffset + 4)
            val directoryOffset = archive.readUIntLeAt(headerOffset + 8)
            require(count in 1..0xFFFF && directoryOffset > headerOffset && directoryOffset < archive.length()) {
                "WILL ARC 索引范围无效"
            }
            totalFiles += count
            require(totalFiles <= MAX_ARCHIVE_ENTRIES) { "WILL ARC 文件条目过多" }
            extensions += WillExtension(extension, count, directoryOffset)
            headerOffset += 12
        }
        return parseWillFileList(archive, extensions, 9)
            ?: parseWillFileList(archive, extensions, 13)
            ?: error("$sourceName 的 WILL ARC 文件表无效")
    }

    private fun parseWillFileList(
        archive: RandomAccessFile,
        extensions: List<WillExtension>,
        nameSize: Int,
    ): List<ArchiveEntry>? = runCatching {
        val entries = mutableListOf<ArchiveEntry>()
        extensions.forEach { extension ->
            var cursor = extension.directoryOffset
            repeat(extension.count) {
                require(cursor + nameSize + 8 <= archive.length())
                val baseName = archive.readFixedName(cursor, nameSize)
                val path = if (extension.extension.isBlank()) baseName else "$baseName.${extension.extension}"
                val size = archive.readUIntLeAt(cursor + nameSize)
                val offset = archive.readUIntLeAt(cursor + nameSize + 4)
                requirePlacement(offset, size, archive.length(), path)
                entries += ArchiveEntry(
                    path = normalizeEntryPath(path.lowercase(Locale.ROOT)),
                    segments = listOf(ArchiveSegment(offset, size)),
                    outputSize = size,
                    encoding = if (extension.extension.equals("scr", true)) {
                        EntryEncoding.ROTATE_RIGHT_2
                    } else {
                        EntryEncoding.RAW
                    },
                )
                cursor += nameSize + 8L
            }
        }
        entries
    }.getOrNull()

    private data class WillExtension(val extension: String, val count: Int, val directoryOffset: Long)

    private fun parseKaguyaLink(archive: RandomAccessFile, sourceName: String): List<ArchiveEntry> {
        require(archive.readAscii(0, 4) == "LINK") { "$sourceName 不是 KaGuYa LINK 档案" }
        archive.seek(4)
        val versionByte = archive.readUnsignedByte()
        val version = versionByte.toChar().digitToIntOrNull()
        return if (version != null && version in 3..6) {
            parseKaguyaLinkModern(archive, version)
        } else {
            parseKaguyaLinkLegacy(archive)
        }
    }

    private fun parseKaguyaLinkLegacy(archive: RandomAccessFile): List<ArchiveEntry> {
        val count = archive.readIntLeAt(4)
        val namesSize = archive.readUIntLeAt(8)
        require(count in 1..MAX_ARCHIVE_ENTRIES && namesSize in 1..MAX_INDEX_BYTES.toLong()) {
            "旧版 LINK 索引范围无效"
        }
        val offsetsStart = safeAdd(12L, namesSize)
        require(offsetsStart + count * 8L <= archive.length()) { "旧版 LINK 索引不完整" }
        archive.seek(12)
        val names = List(count) {
            normalizeEntryPath(String(archive.readCString(offsetsStart), SHIFT_JIS))
        }
        return names.mapIndexed { index, name ->
            val record = offsetsStart + index * 8L
            val offset = archive.readUIntLeAt(record)
            val size = archive.readUIntLeAt(record + 4)
            requirePlacement(offset, size, archive.length(), name)
            rawEntry(name, offset, size)
        }
    }

    private fun parseKaguyaLinkModern(archive: RandomAccessFile, version: Int): List<ArchiveEntry> {
        var cursor = when {
            version < 4 -> 8L
            version < 6 -> 0xAL
            else -> {
                archive.seek(7)
                8L + archive.readUnsignedByte()
            }
        }
        val entries = mutableListOf<ArchiveEntry>()
        while (cursor + 4 < archive.length()) {
            val recordSize = archive.readUIntLeAt(cursor)
            if (recordSize == 0L) break
            require(recordSize >= 0x10 && cursor <= archive.length() - recordSize) { "LINK 记录范围无效" }
            archive.seek(cursor + 4)
            val flags = archive.readUnsignedShortLe()
            archive.seek(cursor + 13)
            val name = if (version < 6) {
                val nameLimit = archive.readUnsignedByte()
                archive.skipBytes(2)
                normalizeEntryPath(String(archive.readCString(minOf(cursor + recordSize, archive.filePointer + nameLimit)), SHIFT_JIS))
            } else {
                val nameBytes = archive.readUnsignedShortLe()
                require(nameBytes in 2..MAX_NAME_BYTES && nameBytes % 2 == 0) { "LINK UTF-16 文件名长度异常" }
                normalizeEntryPath(String(ByteArray(nameBytes).also { archive.readFully(it) }, Charsets.UTF_16LE).trimEnd('\u0000'))
            }
            val offset = archive.filePointer
            val storedSize = recordSize - (offset - cursor)
            requirePlacement(offset, storedSize, archive.length(), name)
            val unsupported = when {
                flags and 4 != 0 -> "KaGuYa LINK 游戏专用加密"
                flags and 3 != 0 -> "KaGuYa LINK BMR 压缩"
                isKaguyaLin2Compressed(archive, offset, storedSize) -> "KaGuYa LINK LIN2 LZSS 压缩"
                else -> null
            }
            entries += rawEntry(name, offset, storedSize, unsupported)
            require(entries.size <= MAX_ARCHIVE_ENTRIES) { "LINK 文件条目过多" }
            cursor += recordSize
        }
        require(entries.isNotEmpty()) { "LINK 索引为空" }
        return entries
    }

    private fun isKaguyaLin2Compressed(archive: RandomAccessFile, offset: Long, size: Long): Boolean {
        if (size <= 8) return false
        archive.seek(offset + 5)
        return archive.readUnsignedShortLe() == 0x4D42
    }

    private fun parseYurisYpfRecognition(archive: RandomAccessFile, sourceName: String): List<ArchiveEntry> {
        require(archive.readHeader(4).startsWith(byteArrayOf('Y'.code.toByte(), 'P'.code.toByte(), 'F'.code.toByte(), 0))) {
            "$sourceName 不是 YU-RIS YPF 档案"
        }
        require(archive.length() >= 0x20L) { "YPF 档案头不完整" }
        val version = archive.readUIntLeAt(4)
        val count = archive.readIntLeAt(8)
        val directorySize = archive.readUIntLeAt(12)
        require(count in 1..MAX_ARCHIVE_ENTRIES && directorySize >= count * 0x17L && 0x20L + directorySize <= archive.length()) {
            "YPF 索引范围无效"
        }
        return listOf(
            ArchiveEntry(
                path = "${sourceName.substringBeforeLast('.').ifBlank { "archive" }}_YPF_v${version.toString(16)}.unsupported",
                segments = emptyList(),
                outputSize = 0,
                unsupportedReason = "YU-RIS YPF 文件名方案、压缩或脚本密钥依赖具体游戏",
            )
        )
    }

    private fun parseNekoPack4(archive: RandomAccessFile, sourceName: String): List<ArchiveEntry> {
        require(archive.readAscii(0, 9) == "NEKOPACK4") { "$sourceName 不是 NekoSDK NEKOPACK4 档案" }
        archive.seek(9)
        val version = archive.readUnsignedByte().toChar()
        val indexStart: Long
        val indexEnd: Long
        when (version) {
            'A' -> {
                indexStart = 0xEL
                indexEnd = safeAdd(indexStart, archive.readUIntLeAt(0xA))
            }

            'S' -> {
                indexStart = 0xAL
                indexEnd = safeAdd(indexStart + 12L, archive.readUIntLeAt(0xA))
            }

            else -> error("暂不支持 NEKOPACK4$version")
        }
        require(indexEnd in (indexStart + 12)..archive.length()) { "NEKOPACK4 索引范围无效" }
        val entries = mutableListOf<ArchiveEntry>()
        var cursor = indexStart
        while (cursor < indexEnd) {
            require(cursor + 4 <= indexEnd) { "NEKOPACK4 索引不完整" }
            val nameSize = archive.readUIntLeAt(cursor).toIntChecked("NEKOPACK4 文件名")
            cursor += 4
            if (nameSize == 0) break
            require(nameSize in 1..0x100 && cursor + nameSize + 8L <= indexEnd) { "NEKOPACK4 文件名长度异常" }
            archive.seek(cursor)
            val rawName = ByteArray(nameSize).also { archive.readFully(it) }
            cursor += nameSize
            var nameKey = 0
            rawName.forEach { byte -> nameKey += byte.toInt() }
            val nameEnd = rawName.indexOf(0).let { if (it < 0) rawName.size else it }
            val name = normalizeEntryPath(String(rawName, 0, nameEnd, SHIFT_JIS))
            val offset = archive.readUIntLeAt(cursor) xor (nameKey.toLong() and 0xFFFF_FFFFL)
            val storedSize = archive.readUIntLeAt(cursor + 4) xor (nameKey.toLong() and 0xFFFF_FFFFL)
            cursor += 8
            requirePlacement(offset, storedSize, archive.length(), name)
            require(storedSize >= 8) { "$name 的 NEKOPACK4 压缩数据过短" }
            val outputSize = archive.readUIntLeAt(offset + storedSize - 4)
            require(outputSize <= MAX_OUTPUT_BYTES) { "$name 的解压长度异常" }
            entries += ArchiveEntry(
                path = name,
                segments = listOf(ArchiveSegment(offset, storedSize)),
                outputSize = outputSize,
                encoding = EntryEncoding.NEKOPACK_ZLIB,
            )
            require(entries.size <= MAX_ARCHIVE_ENTRIES) { "NEKOPACK4 文件条目过多" }
        }
        require(entries.isNotEmpty()) { "NEKOPACK4 索引为空" }
        return entries
    }

    private fun parseQlieRecognition(archive: RandomAccessFile, sourceName: String): List<ArchiveEntry> {
        val signature = archive.readAscii(0, minOf(16L, archive.length()).toInt())
        require(signature.startsWith("FilePackVer")) { "$sourceName 不是 QLIE FilePack 档案" }
        return recognitionOnlyEntry(sourceName, "QLIE $signature", "QLIE FilePack 索引与数据解密依赖版本及游戏密钥")
    }

    private fun parseEntisRecognition(archive: RandomAccessFile, sourceName: String): List<ArchiveEntry> {
        val header = archive.readHeader(12)
        require(header.startsWithAscii("Entis\u001A") || header.startsWithAscii("VIST\u001A")) {
            "$sourceName 不是 EntisGLS NOA 档案"
        }
        require(archive.length() >= 0x40L && archive.readUIntLeAt(8) == 0x02000400L) { "EntisGLS NOA 档案头无效" }
        return recognitionOnlyEntry(sourceName, "EntisGLS NOA", "NOA 可能使用 ERISA 压缩、BSHF 或口令加密")
    }

    private fun parseCmvsRecognition(archive: RandomAccessFile, sourceName: String): List<ArchiveEntry> {
        val signature = archive.readAscii(0, 4)
        require(signature.startsWith("CPZ") && signature.last() in setOf('1', '2', '3', '5', '6', '7')) {
            "$sourceName 不是 CMVS CPZ 档案"
        }
        return recognitionOnlyEntry(sourceName, "CMVS $signature", "CMVS CPZ 索引及资源使用版本相关加密")
    }

    private fun recognitionOnlyEntry(sourceName: String, label: String, reason: String): List<ArchiveEntry> = listOf(
        ArchiveEntry(
            path = "${sourceName.substringBeforeLast('.').ifBlank { "archive" }}_${label.replace(Regex("[^A-Za-z0-9._-]"), "_")}.unsupported",
            segments = emptyList(),
            outputSize = 0,
            unsupportedReason = reason,
        )
    )

    private fun xorRpaNumber(value: Any?, key: Long): Long {
        val number = (value as? Number)?.toLong() ?: error("RPA 索引中的偏移或长度不是整数")
        return (number and 0xFFFF_FFFFL) xor key
    }

    private fun pickleString(value: Any?): String = when (value) {
        is PickleBytes -> String(value.bytes, Charsets.UTF_8)
        is String -> value
        else -> error("RPA 文件名类型不受支持")
    }

    private fun rawEntry(
        unsafeName: String,
        offset: Long,
        size: Long,
        unsupportedReason: String? = null,
    ): ArchiveEntry = ArchiveEntry(
        path = normalizeEntryPath(unsafeName),
        segments = listOf(ArchiveSegment(offset, size)),
        outputSize = size,
        unsupportedReason = unsupportedReason,
    )

    private fun decodeNullTerminatedNames(data: ByteArray, expectedCount: Int): List<String> {
        val names = ArrayList<String>(expectedCount)
        var start = 0
        while (names.size < expectedCount) {
            var end = start
            while (end < data.size && data[end] != 0.toByte()) end += 1
            require(end < data.size) { "Majiro 文件名表不完整" }
            names += normalizeEntryPath(String(data, start, end - start, SHIFT_JIS))
            start = end + 1
        }
        return names
    }

    private fun normalizeEntryPath(value: String): String {
        val normalized = value.replace('\\', '/').trim().trimStart('/')
        val parts = normalized.split('/').filter(String::isNotBlank)
        require(parts.isNotEmpty() && parts.none { it == "." || it == ".." }) { "档案内文件路径不安全" }
        require(parts.size <= MAX_PATH_COMPONENTS) { "档案内文件路径层级过深" }
        return parts.joinToString("/")
    }

    private fun isCandidateName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in CANDIDATE_EXTENSIONS

    private fun queryNameAndSize(uri: Uri): Pair<String, Long> = queryNameAndSizeCompat(context, uri)

    private fun writableTree(uri: Uri): DocumentFile =
        directoryDocumentFile(context, uri)?.takeIf { it.isDirectory && it.canWrite() }
            ?: error("所选输出目录不可写，请重新选择")

    private fun createOutputFile(root: DocumentFile, unsafePath: String): DocumentFile {
        val parts = normalizeEntryPath(unsafePath).split('/').map(::safeName)
        var directory = root
        parts.dropLast(1).forEach { part ->
            directory = directory.findFile(part)?.takeIf(DocumentFile::isDirectory)
                ?: directory.createDirectory(part)
                ?: error("无法创建目录 $part")
        }
        val fileName = parts.last()
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
        error("无法分配 Visual Novel 输出文件名")
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
        "bmp" -> "image/bmp"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "json" -> "application/json"
        "txt", "ks", "rpy" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun copyExactly(source: InputStream, destination: java.io.OutputStream, expected: Long): Long {
        var remaining = expected
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0L) {
            val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) break
            destination.write(buffer, 0, count)
            remaining -= count
        }
        return expected - remaining
    }

    private fun copyTransformExactly(
        source: InputStream,
        destination: java.io.OutputStream,
        expected: Long,
        transform: (Int) -> Int,
    ): Long {
        var remaining = expected
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0L) {
            val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) break
            for (index in 0 until count) buffer[index] = transform(buffer[index].toInt() and 0xFF).toByte()
            destination.write(buffer, 0, count)
            remaining -= count
        }
        return expected - remaining
    }

    private fun requirePlacement(offset: Long, size: Long, limit: Long, name: String) {
        require(offset >= 0 && size >= 0 && offset <= limit - size) { "$name 的数据范围超出档案" }
    }

    private fun safeAdd(left: Long, right: Long): Long {
        require(right >= 0 && left <= Long.MAX_VALUE - right) { "Visual Novel 档案大小溢出" }
        return left + right
    }

    private fun checkedMultiply(left: Long, right: Long, label: String): Long {
        require(left >= 0 && right >= 0 && (left == 0L || right <= Long.MAX_VALUE / left)) { "$label 大小溢出" }
        return left * right
    }

    private fun Long.toIntChecked(label: String): Int {
        require(this in 0..Int.MAX_VALUE.toLong()) { "$label 超出可处理范围" }
        return toInt()
    }

    private fun ByteArray.startsWithAscii(value: String): Boolean {
        val expected = value.toByteArray(Charsets.US_ASCII)
        return size >= expected.size && expected.indices.all { this[it] == expected[it] }
    }

    private fun ByteArray.startsWith(expected: ByteArray): Boolean =
        size >= expected.size && expected.indices.all { this[it] == expected[it] }

    private fun ByteArray.readIntLe(offset: Int): Int {
        require(offset >= 0 && offset <= size - 4) { "索引整数读取越界" }
        return (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun ByteArray.readUIntLe(offset: Int): Long = readIntLe(offset).toLong() and 0xFFFF_FFFFL

    private fun RandomAccessFile.readHeader(limit: Int): ByteArray {
        seek(0)
        val size = minOf(length(), limit.toLong()).toInt()
        return ByteArray(size).also { readFully(it) }
    }

    private fun RandomAccessFile.readAscii(offset: Long, size: Int): String {
        if (size <= 0) return ""
        require(offset >= 0 && offset <= length() - size) { "读取范围超出档案" }
        seek(offset)
        return String(ByteArray(size).also { readFully(it) }, Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readAsciiLine(limit: Int): String {
        seek(0)
        val bytes = mutableListOf<Byte>()
        while (bytes.size < limit) {
            val value = read()
            require(value >= 0) { "档案头不完整" }
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        require(bytes.size < limit) { "档案头过长" }
        return String(bytes.toByteArray(), Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readFixedName(offset: Long, size: Int): String {
        seek(offset)
        val data = ByteArray(size).also { readFully(it) }
        val end = data.indexOf(0).let { if (it < 0) data.size else it }
        require(end > 0) { "档案索引包含空文件名" }
        return normalizeEntryPath(String(data, 0, end, SHIFT_JIS))
    }

    private fun RandomAccessFile.readCString(limitOffset: Long): ByteArray {
        val output = ArrayList<Byte>()
        while (filePointer < limitOffset && output.size <= MAX_NAME_BYTES) {
            val value = read()
            require(value >= 0) { "档案文件名不完整" }
            if (value == 0) return output.toByteArray()
            output += value.toByte()
        }
        error("档案文件名过长或缺少结束符")
    }

    private fun RandomAccessFile.readFixedAscii(offset: Long, size: Int): String {
        seek(offset)
        val data = ByteArray(size).also { readFully(it) }
        val end = data.indexOf(0).let { if (it < 0) data.size else it }
        return String(data, 0, end, Charsets.US_ASCII).trim()
    }

    private fun RandomAccessFile.readIntLeAt(offset: Long): Int {
        seek(offset)
        return Integer.reverseBytes(readInt())
    }

    private fun RandomAccessFile.readUIntLeAt(offset: Long): Long =
        readIntLeAt(offset).toLong() and 0xFFFF_FFFFL

    private fun RandomAccessFile.readUIntBe(): Long = readInt().toLong() and 0xFFFF_FFFFL

    private fun RandomAccessFile.readUnsignedShortLe(): Int = java.lang.Short.reverseBytes(readShort()).toInt() and 0xFFFF

    private class RegionInputStream(
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

    private class SharedRafRegionInputStream(
        private val archive: RandomAccessFile,
        offset: Long,
        private var remaining: Long,
    ) : InputStream() {
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

        override fun close() = Unit
    }

    private class LimitedInputStream(
        private val source: InputStream,
        private val limit: Long,
    ) : InputStream() {
        private var consumed = 0L

        override fun read(): Int {
            require(consumed < limit) { "RPA 解压索引超过 64 MiB 安全上限" }
            return source.read().also { if (it >= 0) consumed += 1 }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            require(consumed < limit) { "RPA 解压索引超过 64 MiB 安全上限" }
            val allowed = minOf(length.toLong(), limit - consumed).toInt()
            return source.read(buffer, offset, allowed).also { if (it > 0) consumed += it }
        }

        override fun close() = source.close()
    }

    private companion object {
        val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")
        val CANDIDATE_EXTENSIONS = setOf(
            "arc", "rpa", "sar", "nsa", "ns2", "npa", "pfs", "ypf", "pak", "pack", "noa", "cpz",
            "000", "001", "002", "003", "004", "005", "010",
        )
        val IMAGE_EXTENSIONS = setOf("bmp", "dib", "jpg", "jpeg", "png", "gif", "tga", "webp")
        const val MIN_ARCHIVE_BYTES = 6L
        const val MAX_ARCHIVE_ENTRIES = 100_000
        const val MAX_DIRECTORY_ENTRIES = 100_000
        const val MAX_INDEX_BYTES = 64 * 1024 * 1024
        const val MAX_NAME_BYTES = 16 * 1024
        const val MAX_PATH_COMPONENTS = 64
        const val MAX_PREVIEW_ITEMS = 24
        const val MAX_RPA_HEADER_BYTES = 128
        const val MAX_RPA_SEGMENTS = 1_024
        const val MAX_OUTPUT_BYTES = 8L * 1024L * 1024L * 1024L
    }
}

private class PickleBytes(val bytes: ByteArray)
private object PickleNone

/** A non-executing subset of Python pickle protocol 0-2 sufficient for RPA-3.0 indices. */
private class SafeRpaPickleReader(private val input: InputStream) {
    private val stack = mutableListOf<Any?>()
    private val marks = ArrayDeque<Int>()
    private val memo = mutableMapOf<Int, Any?>()
    private var operations = 0

    fun read(): Any? {
        while (true) {
            operations += 1
            require(operations <= MAX_OPERATIONS) { "RPA pickle 操作数过多" }
            when (val opcode = input.read()) {
                0x80 -> require(readByte() in 0..2) { "仅支持 RPA pickle protocol 0-2" }
                '}'.code -> push(linkedMapOf<Any?, Any?>())
                ']'.code -> push(mutableListOf<Any?>())
                ')'.code -> push(mutableListOf<Any?>())
                'q'.code -> memo[readByte()] = peek()
                'r'.code -> memo[readIntLe()] = peek()
                'h'.code -> push(memo[readByte()] ?: error("RPA pickle 引用了未知 memo"))
                'j'.code -> push(memo[readIntLe()] ?: error("RPA pickle 引用了未知 memo"))
                '('.code -> marks.addLast(stack.size)
                'U'.code -> push(PickleBytes(readBytes(readByte())))
                'T'.code -> push(PickleBytes(readBytes(readLength())))
                'X'.code -> push(String(readBytes(readLength()), Charsets.UTF_8))
                'J'.code -> push(readIntLe().toLong())
                'K'.code -> push(readByte().toLong())
                'M'.code -> push(readUShortLe().toLong())
                'I'.code -> push(readAsciiLine().removeSuffix("L").toLong())
                0x8A -> push(readSignedLittle(readByte()))
                0x8B -> push(readSignedLittle(readLength()))
                0x86 -> pushTuple(2)
                0x87 -> pushTuple(3)
                't'.code -> pushMarkedTuple()
                'a'.code -> appendOne()
                'e'.code -> appendMarked()
                's'.code -> setOne()
                'u'.code -> setMarked()
                'N'.code -> push(PickleNone)
                '.'.code -> return peek()
                -1 -> error("RPA pickle 意外结束")
                else -> error("RPA pickle 包含不支持的操作码 0x${opcode.toString(16)}")
            }
        }
    }

    private fun push(value: Any?) {
        require(stack.size < MAX_STACK_ITEMS) { "RPA pickle 栈过大" }
        stack += value
    }

    private fun peek(): Any? = stack.lastOrNull() ?: error("RPA pickle 栈为空")

    private fun pop(): Any? = stack.removeAt(stack.lastIndex)

    private fun pushTuple(count: Int) {
        require(stack.size >= count) { "RPA pickle tuple 栈下溢" }
        val tuple = stack.takeLast(count).toMutableList()
        repeat(count) { pop() }
        push(tuple)
    }

    private fun pushMarkedTuple() {
        val mark = takeMark()
        val tuple = stack.subList(mark, stack.size).toMutableList()
        truncate(mark)
        push(tuple)
    }

    private fun appendOne() {
        val value = pop()
        val list = peek() as? MutableList<Any?> ?: error("RPA pickle APPEND 目标不是列表")
        list += value
    }

    private fun appendMarked() {
        val mark = takeMark()
        require(mark > 0) { "RPA pickle APPENDS 缺少列表" }
        val values = stack.subList(mark, stack.size).toList()
        val list = stack[mark - 1] as? MutableList<Any?> ?: error("RPA pickle APPENDS 目标不是列表")
        truncate(mark)
        list.addAll(values)
    }

    private fun setOne() {
        val value = pop()
        val key = pop()
        val dictionary = peek() as? MutableMap<Any?, Any?> ?: error("RPA pickle SETITEM 目标不是字典")
        dictionary[key] = value
    }

    private fun setMarked() {
        val mark = takeMark()
        require(mark > 0 && (stack.size - mark) % 2 == 0) { "RPA pickle SETITEMS 数据无效" }
        val dictionary = stack[mark - 1] as? MutableMap<Any?, Any?>
            ?: error("RPA pickle SETITEMS 目标不是字典")
        var index = mark
        while (index < stack.size) {
            dictionary[stack[index]] = stack[index + 1]
            index += 2
        }
        truncate(mark)
    }

    private fun takeMark(): Int = if (marks.isEmpty()) error("RPA pickle 缺少 MARK") else marks.removeLast()

    private fun truncate(size: Int) {
        while (stack.size > size) stack.removeAt(stack.lastIndex)
    }

    private fun readByte(): Int = input.read().also { require(it >= 0) { "RPA pickle 数据不完整" } }

    private fun readLength(): Int {
        val value = readIntLe()
        require(value in 0..MAX_OBJECT_BYTES) { "RPA pickle 对象长度异常" }
        return value
    }

    private fun readUShortLe(): Int = readByte() or (readByte() shl 8)

    private fun readIntLe(): Int = readByte() or (readByte() shl 8) or (readByte() shl 16) or (readByte() shl 24)

    private fun readBytes(count: Int): ByteArray {
        require(count in 0..MAX_OBJECT_BYTES) { "RPA pickle 对象过大" }
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            require(read > 0) { "RPA pickle 数据不完整" }
            offset += read
        }
        return result
    }

    private fun readSignedLittle(count: Int): Long {
        require(count in 0..8) { "RPA pickle 长整数超出 64 位" }
        if (count == 0) return 0L
        val bytes = readBytes(count)
        var value = 0L
        bytes.forEachIndexed { index, byte -> value = value or ((byte.toLong() and 0xFF) shl (index * 8)) }
        if (bytes.last().toInt() and 0x80 != 0 && count < 8) {
            value = value or (-1L shl (count * 8))
        }
        return value
    }

    private fun readAsciiLine(): String {
        val bytes = mutableListOf<Byte>()
        while (bytes.size <= MAX_NUMBER_CHARS) {
            val value = readByte()
            if (value == '\n'.code) return String(bytes.toByteArray(), Charsets.US_ASCII)
            bytes += value.toByte()
        }
        error("RPA pickle 整数字段过长")
    }

    private companion object {
        const val MAX_OPERATIONS = 2_000_000
        const val MAX_STACK_ITEMS = 500_000
        const val MAX_OBJECT_BYTES = 1024 * 1024
        const val MAX_NUMBER_CHARS = 64
    }
}
