package com.pockettoolbox.app.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.pockettoolbox.app.model.MusicLyricLine
import com.pockettoolbox.app.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

@OptIn(UnstableApi::class)
class MusicRepository(private val context: Context) {
    data class PlayableAudio(
        val uri: Uri,
    )

    private data class TimedSearch(
        val savedAtElapsedMillis: Long,
        val tracks: List<MusicTrack>,
    )

    private data class TimedUrl(
        val savedAtElapsedMillis: Long,
        val address: AudioAddress,
    )

    private data class TimedLyrics(
        val savedAtElapsedMillis: Long,
        val lines: List<MusicLyricLine>,
    )

    private data class AudioAddress(
        val url: String,
        val extension: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val searchCache = object : LinkedHashMap<String, TimedSearch>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TimedSearch>?): Boolean =
            size > MAX_SEARCH_CACHE_ITEMS
    }
    private val urlCache = object : LinkedHashMap<String, TimedUrl>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TimedUrl>?): Boolean =
            size > MAX_URL_CACHE_ITEMS
    }
    private val lyricCache = object : LinkedHashMap<String, TimedLyrics>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TimedLyrics>?): Boolean =
            size > MAX_LYRIC_CACHE_ITEMS
    }
    private val ratePreferences = context.getSharedPreferences(RATE_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val requestTimes = ArrayDeque<Long>().apply {
        val now = System.currentTimeMillis()
        ratePreferences.getString(KEY_REQUEST_TIMES, null)
            ?.split(',')
            ?.mapNotNull(String::toLongOrNull)
            ?.filter { timestamp -> now - timestamp in 0L until RATE_WINDOW_MILLIS }
            ?.forEach { timestamp -> addLast(timestamp) }
    }
    private val audioCacheDirectory = File(context.cacheDir, "gd-music").apply {
        mkdirs()
        listFiles()?.filter { it.name.endsWith(PARTIAL_SUFFIX) }?.forEach(File::delete)
    }
    private val playbackCacheDelegate = lazy {
        SimpleCache(
            File(context.cacheDir, "gd-music-playback"),
            LeastRecentlyUsedCacheEvictor(MAX_AUDIO_CACHE_BYTES),
            StandaloneDatabaseProvider(context),
        )
    }
    private val playbackCache by playbackCacheDelegate
    private val playbackDataSourceFactory: CacheDataSource.Factory by lazy {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setDefaultRequestProperties(mapOf("Referer" to "https://music.gdstudio.xyz/"))
        CacheDataSource.Factory()
            .setCache(playbackCache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, httpFactory))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    suspend fun search(keyword: String, source: String): List<MusicTrack> = withContext(Dispatchers.IO) {
        val normalized = keyword.trim()
        val normalizedSource = source.trim().lowercase()
        require(normalized.isNotBlank()) { "请输入歌曲名或歌手名。" }
        require(normalized.length <= 100) { "搜索关键词过长。" }
        require(normalizedSource in SUPPORTED_SEARCH_SOURCES) { "不支持所选音乐源。" }
        val cacheKey = "$normalizedSource:${normalized.lowercase()}"
        synchronized(searchCache) {
            searchCache[cacheKey]?.takeIf { !it.isExpired(SEARCH_CACHE_TTL_MILLIS) }?.tracks
        }?.let { return@withContext it }

        val endpoint = API_BASE.toHttpUrl().newBuilder()
            .addQueryParameter("types", "search")
            .addQueryParameter("source", normalizedSource)
            .addQueryParameter("name", normalized)
            .addQueryParameter("count", SEARCH_RESULT_COUNT.toString())
            .addQueryParameter("pages", "1")
            .build()
        val array = getJsonArray(endpoint.toString())
        val tracks = (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.toTrack(defaultSource = normalizedSource)
        }.distinctBy(MusicTrack::cacheKey)
        synchronized(searchCache) {
            searchCache[cacheKey] = TimedSearch(SystemClock.elapsedRealtime(), tracks)
        }
        tracks
    }

    suspend fun prepareForPlayback(track: MusicTrack, progress: (String) -> Unit): PlayableAudio =
        withContext(Dispatchers.IO) {
            findCachedAudio(track)?.let { cached ->
                cached.setLastModified(System.currentTimeMillis())
                return@withContext PlayableAudio(Uri.fromFile(cached))
            }

            val address = resolveAudioAddress(track)
            progress("正在连接音频；播放时会同步写入缓存…")
            PlayableAudio(Uri.parse(address.url))
        }

    suspend fun fetchLyrics(track: MusicTrack): List<MusicLyricLine> = withContext(Dispatchers.IO) {
        synchronized(lyricCache) {
            lyricCache[track.cacheKey]
                ?.takeIf { !it.isExpired(LYRIC_CACHE_TTL_MILLIS) }
                ?.lines
        }?.let { return@withContext it }

        val endpoint = API_BASE.toHttpUrl().newBuilder()
            .addQueryParameter("types", "lyric")
            .addQueryParameter("source", track.source)
            .addQueryParameter("id", track.lyricId ?: track.id)
            .build()
        val lines = parseLyrics(getJsonObject(endpoint.toString()))
        synchronized(lyricCache) {
            lyricCache[track.cacheKey] = TimedLyrics(SystemClock.elapsedRealtime(), lines)
        }
        lines
    }

    fun createPlaybackPlayer(): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(playbackDataSourceFactory))
        .build()

    fun playbackCacheKey(track: MusicTrack): String = "$PLAYBACK_CACHE_KEY_PREFIX${track.cacheKey}"

    suspend fun discardFailedPlaybackCache(track: MusicTrack) = withContext(Dispatchers.IO) {
        synchronized(urlCache) { urlCache.remove(track.cacheKey) }

        if (playbackCacheDelegate.isInitialized()) {
            synchronized(playbackCache) {
                playbackCache.removeResource(playbackCacheKey(track))
            }
        }

        val filePrefix = cacheFilePrefix(track)
        audioCacheDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(filePrefix) && !file.delete()) {
                throw IOException("无法清理未完成的本地音频缓存。")
            }
        }
    }

    suspend fun clearAudioCache(): Long = withContext(Dispatchers.IO) {
        var clearedBytes = 0L
        synchronized(searchCache) { searchCache.clear() }
        synchronized(urlCache) { urlCache.clear() }
        synchronized(lyricCache) { lyricCache.clear() }
        synchronized(playbackCache) {
            clearedBytes += playbackCache.cacheSpace
            playbackCache.keys.toList().forEach { key -> playbackCache.removeResource(key) }
        }
        audioCacheDirectory.listFiles()?.forEach { file ->
            if (file.isFile) {
                val fileBytes = file.length()
                if (file.delete()) clearedBytes += fileBytes
            }
        }
        clearedBytes
    }

    fun release() {
        if (playbackCacheDelegate.isInitialized()) playbackCache.release()
    }

    suspend fun saveTrack(
        track: MusicTrack,
        treeUri: Uri,
        progress: (String) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val cached = findCachedAudio(track)
        val source = cached ?: run {
            val address = resolveAudioAddress(track)
            downloadIntoCache(track, address, progress)
        }
        val extension = source.extension.ifBlank { "mp3" }
        val mimeType = when (extension.lowercase()) {
            "flac" -> "audio/flac"
            "m4a", "mp4" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "aac" -> "audio/aac"
            else -> "audio/mpeg"
        }
        val fileName = safeMusicFileName("${track.name} - ${track.artistText}") + ".$extension"
        val destination = createDestination(treeUri, mimeType, fileName)
        try {
            context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 4) }
            } ?: throw IOException("无法写入所选目录。")
            destination
        } catch (error: Exception) {
            runCatching { context.contentResolver.delete(destination, null, null) }
            throw error
        } finally {
            if (cached == null) source.delete()
        }
    }

    private fun resolveAudioAddress(track: MusicTrack): AudioAddress {
        synchronized(urlCache) {
            urlCache[track.cacheKey]
                ?.takeIf { !it.isExpired(URL_CACHE_TTL_MILLIS) }
                ?.address
                ?.let { return it }
        }
        val endpoint = API_BASE.toHttpUrl().newBuilder()
            .addQueryParameter("types", "url")
            .addQueryParameter("source", track.source)
            .addQueryParameter("id", track.id)
            .addQueryParameter("br", DEFAULT_QUALITY)
            .build()
        val json = getJsonObject(endpoint.toString())
        val url = json.optString("url").takeIf(String::isNotBlank)
            ?: throw IOException(
                buildString {
                    append("gd音乐台暂未返回该歌曲的可用地址（id=${track.id}")
                    track.trackId?.let { append("，track_id=$it") }
                    append("）。")
                },
            )
        val secureUrl = normalizeHttps(url)
        val extension = json.optString("type")
            .lowercase()
            .takeIf { it in SUPPORTED_AUDIO_EXTENSIONS }
            ?: extensionFromUrl(secureUrl)
        synchronized(urlCache) {
            urlCache[track.cacheKey] = TimedUrl(
                savedAtElapsedMillis = SystemClock.elapsedRealtime(),
                address = AudioAddress(secureUrl, extension),
            )
        }
        return AudioAddress(secureUrl, extension)
    }

    private fun getJsonArray(url: String): JSONArray {
        executeApi(url).use { response ->
            val body = response.body ?: throw IOException("API 响应为空。")
            return JSONArray(body.source().readLimitedUtf8(MAX_JSON_BYTES))
        }
    }

    private fun getJsonObject(url: String): JSONObject {
        executeApi(url).use { response ->
            val body = response.body ?: throw IOException("API 响应为空。")
            return JSONObject(body.source().readLimitedUtf8(MAX_JSON_BYTES))
        }
    }

    private fun executeApi(url: String): Response {
        acquireApiPermit()
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build(),
        ).execute()
        if (!response.request.url.isHttps || response.request.url.host != API_HOST) {
            response.close()
            throw IOException("API 跳转到了不受信任的地址。")
        }
        if (!response.isSuccessful) {
            response.close()
            throw IOException("gd音乐台请求失败（HTTP ${response.code}）。")
        }
        return response
    }

    private fun acquireApiPermit() {
        val now = System.currentTimeMillis()
        synchronized(requestTimes) {
            while (requestTimes.isNotEmpty() && now - requestTimes.first() !in 0L until RATE_WINDOW_MILLIS) {
                requestTimes.removeFirst()
            }
            if (requestTimes.size >= MAX_REQUESTS_PER_WINDOW) {
                val seconds = ((RATE_WINDOW_MILLIS - (now - requestTimes.first())) / 1_000L + 1L)
                throw IOException("请求较频繁，请约 $seconds 秒后再试。")
            }
            requestTimes.addLast(now)
            ratePreferences.edit()
                .putString(KEY_REQUEST_TIMES, requestTimes.joinToString(","))
                .commit()
        }
    }

    private suspend fun download(url: String, destination: File, progress: (String) -> Unit) {
        val secureUrl = normalizeHttps(url)
        val request = Request.Builder()
            .url(secureUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://music.gdstudio.xyz/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.request.url.isHttps) throw IOException("音频地址跳转到了非 HTTPS 地址。")
            if (!response.isSuccessful) throw IOException("音频下载失败（HTTP ${response.code}）。")
            val body = response.body ?: throw IOException("音频响应为空。")
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_SINGLE_AUDIO_BYTES) throw IOException("音频文件超过缓存上限。")
            var written = 0L
            body.byteStream().use { input ->
                FileOutputStream(destination, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    var lastPercent = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > MAX_SINGLE_AUDIO_BYTES) throw IOException("音频文件超过缓存上限。")
                        output.write(buffer, 0, read)
                        if (declaredLength > 0) {
                            val percent = (written * 100 / declaredLength).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                progress("正在缓存音频：$percent%")
                                lastPercent = percent
                            }
                        }
                    }
                }
            }
            if (declaredLength >= 0L && written != declaredLength) {
                throw IOException("音频缓存不完整（应接收 $declaredLength 字节，实际接收 $written 字节）。")
            }
        }
    }

    private suspend fun downloadIntoCache(
        track: MusicTrack,
        address: AudioAddress,
        progress: (String) -> Unit,
    ): File {
        val destination = cacheFile(track, address.extension)
        val partial = File(destination.parentFile, destination.name + PARTIAL_SUFFIX)
        audioCacheDirectory.mkdirs()
        partial.delete()
        try {
            download(address.url, partial, progress)
            if (!partial.isFile || partial.length() == 0L) throw IOException("音频响应为空。")
            if (destination.exists() && !destination.delete()) {
                throw IOException("无法替换旧的音频缓存。")
            }
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            destination.setLastModified(System.currentTimeMillis())
            trimAudioCache(except = destination)
            return destination
        } catch (error: Exception) {
            partial.delete()
            destination.delete()
            throw error
        }
    }

    private fun createDestination(treeUri: Uri, mimeType: String, fileName: String): Uri {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return DocumentsContract.createDocument(context.contentResolver, parentUri, mimeType, fileName)
            ?: throw IOException("无法在保存目录中创建文件，请重新选择目录。")
    }

    private fun findCachedAudio(track: MusicTrack): File? {
        val prefix = cacheFilePrefix(track)
        return audioCacheDirectory.listFiles()
            ?.firstOrNull {
                it.isFile &&
                    it.name.startsWith(prefix) &&
                    !it.name.endsWith(PARTIAL_SUFFIX) &&
                    it.length() > 0
            }
    }

    private fun cacheFile(track: MusicTrack, extension: String): File =
        File(audioCacheDirectory, cacheFilePrefix(track) + "." + extension.lowercase())

    private fun cacheFilePrefix(track: MusicTrack): String = "audio-${sha256(track.cacheKey).take(24)}"

    private fun trimAudioCache(except: File) {
        val files = audioCacheDirectory.listFiles()
            ?.filter { it.isFile && it != except }
            ?.sortedBy { it.lastModified() }
            ?.toMutableList()
            ?: return
        var totalBytes = files.sumOf(File::length) + except.length()
        while (files.size + 1 > MAX_AUDIO_CACHE_FILES || totalBytes > MAX_AUDIO_CACHE_BYTES) {
            val oldest = files.removeFirstOrNull() ?: break
            totalBytes -= oldest.length()
            oldest.delete()
        }
    }

    private fun JSONObject.toTrack(defaultSource: String): MusicTrack? {
        val trackId = optionalString("track_id")
        val id = optionalString("id") ?: trackId ?: return null
        val source = optString("source").ifBlank { defaultSource }
        val name = optionalString("name") ?: optionalString("title") ?: return null
        val artistValue = opt("artist")
        val artists = when (artistValue) {
            is JSONArray -> (0 until artistValue.length()).mapNotNull { index ->
                artistValue.optString(index).takeIf(String::isNotBlank)
            }
            is String -> artistValue.split('/', ',', '、').map(String::trim).filter(String::isNotBlank)
            else -> optionalString("author")?.let(::listOf).orEmpty()
        }
        return MusicTrack(
            id = id,
            source = source,
            name = name,
            artists = artists,
            album = optString("album").ifBlank { "未知专辑" },
            trackId = trackId,
            lyricId = optionalString("lyric_id"),
            pictureId = optionalString("pic_id"),
        )
    }

    private fun JSONObject.optionalString(key: String): String? = opt(key)
        ?.takeUnless { it == JSONObject.NULL }
        ?.toString()
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun TimedSearch.isExpired(ttl: Long): Boolean =
        SystemClock.elapsedRealtime() - savedAtElapsedMillis >= ttl

    private fun TimedUrl.isExpired(ttl: Long): Boolean =
        SystemClock.elapsedRealtime() - savedAtElapsedMillis >= ttl

    private fun TimedLyrics.isExpired(ttl: Long): Boolean =
        SystemClock.elapsedRealtime() - savedAtElapsedMillis >= ttl

    private fun parseLyrics(json: JSONObject): List<MusicLyricLine> {
        val primary = parseLrc(json.optString("lyric"))
        val translated = parseLrc(json.optString("tlyric"))
        return (primary.keys + translated.keys)
            .distinct()
            .sorted()
            .mapNotNull { startTimeMillis ->
                val primaryText = primary[startTimeMillis]
                val translatedText = translated[startTimeMillis]
                val text = primaryText ?: translatedText ?: return@mapNotNull null
                MusicLyricLine(
                    startTimeMillis = startTimeMillis,
                    text = text,
                    translation = translatedText?.takeIf { primaryText != null && it != primaryText },
                )
            }
    }

    private fun parseLrc(value: String): Map<Long, String> {
        if (value.isBlank()) return emptyMap()
        val offsetMillis = LRC_OFFSET_PATTERN.find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: 0L
        val lines = linkedMapOf<Long, String>()
        value.lineSequence().forEach lineLoop@ { rawLine ->
            val timestamps = LRC_TIMESTAMP_PATTERN.findAll(rawLine).toList()
            if (timestamps.isEmpty()) return@lineLoop
            val text = rawLine.substring(timestamps.last().range.last + 1).trim()
            if (text.isBlank()) return@lineLoop
            timestamps.forEach timestampLoop@ { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@timestampLoop
                val seconds = match.groupValues[2].toLongOrNull() ?: return@timestampLoop
                val fraction = match.groupValues[3]
                    .takeIf(String::isNotBlank)
                    ?.padEnd(3, '0')
                    ?.take(3)
                    ?.toLongOrNull()
                    ?: 0L
                val timestamp = ((minutes * 60L + seconds) * 1_000L + fraction + offsetMillis)
                    .coerceAtLeast(0L)
                val previous = lines[timestamp]
                lines[timestamp] = if (previous.isNullOrBlank() || previous == text) {
                    text
                } else {
                    "$previous\n$text"
                }
            }
        }
        return lines
    }

    private fun extensionFromUrl(url: String): String {
        val extension = Uri.parse(url).lastPathSegment.orEmpty()
            .substringAfterLast('.', "mp3")
            .substringBefore('?')
            .lowercase()
        return extension.takeIf { it in SUPPORTED_AUDIO_EXTENSIONS }
            ?: "mp3"
    }

    private fun normalizeHttps(url: String): String {
        val normalized = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://", ignoreCase = true) -> "https://${url.drop(7)}"
            else -> url
        }
        val parsed = Uri.parse(normalized)
        require(parsed.scheme.equals("https", true) && !parsed.host.isNullOrBlank()) {
            "只允许安全的 HTTPS 音频地址。"
        }
        return normalized
    }

    private fun BufferedSource.readLimitedUtf8(limit: Long): String {
        val buffer = okio.Buffer()
        var total = 0L
        while (true) {
            val read = read(buffer, minOf(64 * 1024L, limit + 1 - total))
            if (read < 0) break
            total += read
            if (total > limit) throw IOException("API 响应过大。")
        }
        return buffer.readUtf8()
    }

    private fun safeMusicFileName(value: String): String = value
        .replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]"), "_")
        .trim()
        .trimEnd('.', ' ')
        .ifBlank { "music" }
        .take(100)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val API_BASE = "https://music-api.gdstudio.xyz/api.php"
        const val API_HOST = "music-api.gdstudio.xyz"
        const val PLAYBACK_CACHE_KEY_PREFIX = "gd-music:"
        const val PARTIAL_SUFFIX = ".part"
        const val RATE_PREFERENCES_NAME = "gd_music_rate_limit"
        const val KEY_REQUEST_TIMES = "request_times_v1"
        const val DEFAULT_QUALITY = "320"
        const val SEARCH_RESULT_COUNT = 30
        const val MAX_REQUESTS_PER_WINDOW = 45
        const val RATE_WINDOW_MILLIS = 5 * 60 * 1_000L
        const val SEARCH_CACHE_TTL_MILLIS = 30 * 60 * 1_000L
        const val URL_CACHE_TTL_MILLIS = 15 * 60 * 1_000L
        const val LYRIC_CACHE_TTL_MILLIS = 60 * 60 * 1_000L
        const val MAX_SEARCH_CACHE_ITEMS = 20
        const val MAX_URL_CACHE_ITEMS = 30
        const val MAX_LYRIC_CACHE_ITEMS = 30
        const val MAX_AUDIO_CACHE_FILES = 8
        const val MAX_AUDIO_CACHE_BYTES = 300L * 1024 * 1024
        const val MAX_SINGLE_AUDIO_BYTES = 150L * 1024 * 1024
        const val MAX_JSON_BYTES = 2L * 1024 * 1024
        const val USER_AGENT = "PocketToolbox/0.6 (Android; gd music client)"
        val LRC_OFFSET_PATTERN = Regex("(?im)^\\[offset:([+-]?\\d+)]")
        val LRC_TIMESTAMP_PATTERN = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val SUPPORTED_SEARCH_SOURCES = setOf(
            "netease",
            "tencent",
            "tidal",
            "spotify",
            "ytmusic",
            "qobuz",
            "joox",
            "deezer",
            "kugou",
            "kuwo",
            "apple",
            "bilibili",
        )
        val SUPPORTED_AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "mp4", "ogg", "opus", "aac")
    }
}
