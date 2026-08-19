package com.pockettoolbox.app.model

enum class MediaKind { VIDEO, AUDIO }

enum class DownloadKind { COVER, VIDEO, AUDIO }

data class BiliId(
    val value: String,
    val kind: MediaKind,
) {
    val pageUrl: String
        get() = when (kind) {
            MediaKind.VIDEO -> "https://www.bilibili.com/video/$value"
            MediaKind.AUDIO -> "https://www.bilibili.com/audio/$value"
        }

    companion object {
        private val bvPattern = Regex("(?<![0-9A-Za-z])BV[0-9A-Za-z]{10}(?![0-9A-Za-z])", RegexOption.IGNORE_CASE)
        private val auPattern = Regex("(?<![0-9A-Za-z])AU\\s*[-_:]?\\s*(\\d+)(?!\\d)", RegexOption.IGNORE_CASE)

        fun parse(input: String): BiliId {
            val trimmed = input.trim()
            require(trimmed.length <= 500) { "输入内容过长。" }

            bvPattern.find(trimmed)?.value?.let { match ->
                return BiliId("BV${match.drop(2)}", MediaKind.VIDEO)
            }
            auPattern.find(trimmed)?.groupValues?.get(1)?.let { sid ->
                return BiliId("au$sid", MediaKind.AUDIO)
            }
            throw IllegalArgumentException("请输入有效的 BV 号或 AU 号。")
        }
    }
}

data class BiliMediaInfo(
    val id: BiliId,
    val title: String,
    val author: String,
    val durationSeconds: Long?,
    val coverBytes: ByteArray,
    val coverMimeType: String,
    val cid: Long? = null,
)

data class StreamSource(
    val url: String,
    val backupUrls: List<String> = emptyList(),
)

data class VideoStreams(
    val video: StreamSource,
    val audio: StreamSource,
)

data class QrResult(
    val text: String,
    val webUrl: String?,
)

enum class QrSecurityStatus { NOT_CHECKED, PENDING, COMPLETED, FAILED }

data class QrSecurityHistory(
    val status: QrSecurityStatus = QrSecurityStatus.NOT_CHECKED,
    val checkedAtEpochMillis: Long? = null,
    val malicious: Int = 0,
    val suspicious: Int = 0,
    val harmless: Int = 0,
    val undetected: Int = 0,
)

data class QrHistoryEntry(
    val parsedAtEpochMillis: Long,
    val text: String,
    val webUrl: String?,
    val security: QrSecurityHistory = QrSecurityHistory(),
)

data class BiliHistoryEntry(
    val parsedAtEpochMillis: Long,
    val id: BiliId,
    val title: String,
) {
    val pageUrl: String
        get() = id.pageUrl
}

data class DownloadSpec(
    val mimeType: String,
    val fileName: String,
)

data class MusicTrack(
    val id: String,
    val source: String,
    val name: String,
    val artists: List<String>,
    val album: String,
    val trackId: String? = null,
    val lyricId: String? = null,
    val pictureId: String? = null,
) {
    val artistText: String
        get() = artists.filter(String::isNotBlank).joinToString(" / ").ifBlank { "未知歌手" }

    val cacheKey: String
        get() = "$source:$id"
}

data class MusicHistoryEntry(
    val searchedAtEpochMillis: Long,
    val track: MusicTrack,
)

data class MusicLyricLine(
    val startTimeMillis: Long,
    val text: String,
    val translation: String? = null,
) {
    val displayText: String
        get() = listOfNotNull(text, translation?.takeIf { it != text }).joinToString("\n")
}

enum class PlaylistPlaybackMode { LIST_LOOP, SHUFFLE }

data class MusicPlaylist(
    val id: String,
    val name: String,
    val createdAtEpochMillis: Long,
    val tracks: List<MusicTrack>,
)

fun BiliMediaInfo.downloadSpec(kind: DownloadKind): DownloadSpec {
    val base = safeFileName(title)
    return when (kind) {
        DownloadKind.COVER -> {
            val extension = when {
                coverMimeType.contains("png", ignoreCase = true) -> "png"
                coverMimeType.contains("webp", ignoreCase = true) -> "webp"
                else -> "jpg"
            }
            DownloadSpec(coverMimeType, "$base.$extension")
        }
        DownloadKind.VIDEO -> DownloadSpec("video/mp4", "$base.mp4")
        DownloadKind.AUDIO -> DownloadSpec("audio/mp4", "$base.m4a")
    }
}

fun safeFileName(value: String): String {
    val cleaned = value
        .replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]"), "_")
        .trim()
        .trimEnd('.', ' ')
        .ifBlank { "bilibili" }
    return cleaned.take(80)
}
