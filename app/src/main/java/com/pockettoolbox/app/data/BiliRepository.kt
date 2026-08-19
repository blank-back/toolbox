package com.pockettoolbox.app.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.DocumentsContract
import com.pockettoolbox.app.model.BiliId
import com.pockettoolbox.app.model.BiliMediaInfo
import com.pockettoolbox.app.model.MediaKind
import com.pockettoolbox.app.model.StreamSource
import com.pockettoolbox.app.model.VideoStreams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

class BiliRepository(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun lookup(input: String): BiliMediaInfo = withContext(Dispatchers.IO) {
        val id = BiliId.parse(input)
        when (id.kind) {
            MediaKind.VIDEO -> lookupVideo(id)
            MediaKind.AUDIO -> lookupAudio(id)
        }
    }

    suspend fun createDestination(treeUri: Uri, mimeType: String, fileName: String): Uri =
        withContext(Dispatchers.IO) {
            val documentId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                mimeType,
                fileName,
            ) ?: throw IOException("无法在保存目录中创建文件，请重新选择目录。")
        }

    suspend fun saveCover(info: BiliMediaInfo, destination: Uri) = withContext(Dispatchers.IO) {
        try {
            writeBytes(destination, info.coverBytes)
        } catch (error: Exception) {
            deleteFailedDestination(destination)
            throw error
        }
    }

    suspend fun saveAudio(info: BiliMediaInfo, destination: Uri, progress: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            try {
                val temp = File.createTempFile("toolbox-audio-", ".m4a", context.cacheDir)
                try {
                    val source = when (info.id.kind) {
                        MediaKind.AUDIO -> getAuStream(info.id)
                        MediaKind.VIDEO -> getVideoStreams(info).audio
                    }
                    downloadToTemp(source, temp, progress)
                    progress("正在写入你选择的位置…")
                    copyFileToUri(temp, destination)
                } finally {
                    temp.delete()
                }
            } catch (error: Exception) {
                deleteFailedDestination(destination)
                throw error
            }
        }

    suspend fun saveVideo(info: BiliMediaInfo, destination: Uri, progress: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            try {
                require(info.id.kind == MediaKind.VIDEO) { "AU 音频不包含视频。" }
                val streams = getVideoStreams(info)
                val videoPart = File.createTempFile("toolbox-video-", ".m4s", context.cacheDir)
                val audioPart = File.createTempFile("toolbox-audio-", ".m4s", context.cacheDir)
                val merged = File.createTempFile("toolbox-merged-", ".mp4", context.cacheDir)
                try {
                    downloadToTemp(streams.video, videoPart) { progress("视频流：$it") }
                    downloadToTemp(streams.audio, audioPart) { progress("音频流：$it") }
                    progress("正在合并音视频流…")
                    muxMp4(videoPart, audioPart, merged)
                    progress("正在写入你选择的位置…")
                    copyFileToUri(merged, destination)
                } finally {
                    videoPart.delete()
                    audioPart.delete()
                    merged.delete()
                }
            } catch (error: Exception) {
                deleteFailedDestination(destination)
                throw error
            }
        }

    private fun lookupVideo(id: BiliId): BiliMediaInfo {
        val root = getJson(
            "https://api.bilibili.com/x/web-interface/view?bvid=${id.value}",
        )
        checkApi(root, "未找到该视频。")
        val data = root.requireObject("data")
        val bvid = data.optString("bvid").ifBlank { id.value }
        val cover = fetchImage(data.requireString("pic"))
        return BiliMediaInfo(
            id = BiliId(bvid, MediaKind.VIDEO),
            title = data.requireString("title"),
            author = data.optJSONObject("owner")?.optString("name").orEmpty().ifBlank { "未知作者" },
            durationSeconds = data.optLong("duration").takeIf { it > 0 },
            coverBytes = cover.bytes,
            coverMimeType = cover.mimeType,
            cid = data.optLong("cid").takeIf { it > 0 },
        )
    }

    private fun lookupAudio(id: BiliId): BiliMediaInfo {
        val sid = id.value.drop(2)
        val root = getJson(
            "https://www.bilibili.com/audio/music-service-c/web/song/info?sid=$sid",
        )
        checkApi(root, "未找到该音频，或该接口需要登录。")
        val data = root.requireObject("data")
        val actualId = data.optLong("id").takeIf { it > 0 }?.toString() ?: sid
        val cover = fetchImage(data.requireString("cover"))
        return BiliMediaInfo(
            id = BiliId("au$actualId", MediaKind.AUDIO),
            title = data.requireString("title"),
            author = data.optString("uname").ifBlank { data.optString("author") }.ifBlank { "未知作者" },
            durationSeconds = data.optLong("duration").takeIf { it > 0 },
            coverBytes = cover.bytes,
            coverMimeType = cover.mimeType,
        )
    }

    private fun getVideoStreams(info: BiliMediaInfo): VideoStreams {
        val cid = requireNotNull(info.cid) { "视频缺少分P标识，无法下载。" }
        val root = getJson(
            "https://api.bilibili.com/x/player/playurl" +
                "?bvid=${info.id.value}&cid=$cid&qn=80&fnver=0&fnval=16&fourk=1",
        )
        checkApi(root, "无法取得视频流；内容可能需要登录或会员权限。")
        val dash = root.requireObject("data").optJSONObject("dash")
            ?: throw IOException("该视频没有可用的 DASH 媒体流。")
        val videos = dash.optJSONArray("video") ?: throw IOException("没有可用的视频流。")
        val audios = dash.optJSONArray("audio") ?: throw IOException("没有可用的音频流。")

        val videoCandidates = (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }
        val audioCandidates = (0 until audios.length()).mapNotNull { audios.optJSONObject(it) }
        val mp4Videos = videoCandidates
            .filter { it.optString("mimeType").contains("mp4", ignoreCase = true) }
        val video = mp4Videos
            .filter { it.optString("codecs").startsWith("avc", ignoreCase = true) }
            .maxByOrNull { it.optInt("height") * 10_000L + it.optLong("bandwidth") }
            ?: mp4Videos.maxByOrNull { it.optInt("height") * 10_000L + it.optLong("bandwidth") }
            ?: videoCandidates.firstOrNull()
            ?: throw IOException("没有可用的视频流。")
        val audio = audioCandidates
            .filter { it.optString("mimeType").contains("mp4", ignoreCase = true) }
            .maxByOrNull { it.optLong("bandwidth") }
            ?: audioCandidates.firstOrNull()
            ?: throw IOException("没有可用的音频流。")
        return VideoStreams(video.toSource(), audio.toSource())
    }

    private fun getAuStream(id: BiliId): StreamSource {
        val sid = id.value.drop(2)
        val root = getJson("https://www.bilibili.com/audio/music-service-c/web/url?sid=$sid")
        checkApi(root, "无法取得该音频的下载地址。")
        val cdns = root.requireObject("data").optJSONArray("cdns")
            ?: throw IOException("没有可用的音频流。")
        val urls = (0 until cdns.length()).mapNotNull { cdns.optString(it).takeIf(String::isNotBlank) }
        return StreamSource(
            url = urls.firstOrNull() ?: throw IOException("没有可用的音频流。"),
            backupUrls = urls.drop(1),
        )
    }

    private data class ImagePayload(val bytes: ByteArray, val mimeType: String)

    private fun fetchImage(rawUrl: String): ImagePayload {
        val url = normalizeHttps(rawUrl)
        val host = Uri.parse(url).host.orEmpty()
        require(isAllowedImageHost(host)) { "封面地址不受信任。" }
        execute(url).use { response ->
            if (!isAllowedImageHost(response.request.url.host)) {
                throw IOException("封面被重定向到了不受信任的地址。")
            }
            val body = response.body ?: throw IOException("封面响应为空。")
            val bytes = body.source().readLimitedBytes(MAX_COVER_BYTES, "封面图片过大。")
            val mimeType = body.contentType()?.toString()?.substringBefore(';') ?: "image/jpeg"
            if (!mimeType.startsWith("image/")) throw IOException("封面响应不是图片。")
            return ImagePayload(bytes, mimeType)
        }
    }

    private fun getJson(url: String): JSONObject {
        execute(url).use { response ->
            val body = response.body ?: throw IOException("接口响应为空。")
            val contentLength = body.contentLength().coerceAtLeast(0)
            if (contentLength > MAX_JSON_BYTES) {
                throw IOException("接口响应过大。")
            }
            val bytes = body.source().readLimitedBytes(MAX_JSON_BYTES, "接口响应过大。")
            return JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }

    private fun execute(url: String): Response {
        val request = Request.Builder()
            .url(normalizeHttps(url))
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.bilibili.com/")
            .header("Accept", "*/*")
            .build()
        val response = client.newCall(request).execute()
        if (!response.request.url.isHttps) {
            response.close()
            throw IOException("服务器跳转到了不安全的地址。")
        }
        if (!response.isSuccessful) {
            response.close()
            throw IOException("网络请求失败（HTTP ${response.code}）。")
        }
        return response
    }

    private suspend fun downloadToTemp(
        source: StreamSource,
        destination: File,
        progress: (String) -> Unit,
    ) {
        var lastError: Exception? = null
        for (url in listOf(source.url) + source.backupUrls) {
            try {
                execute(url).use { response ->
                    val body = response.body ?: throw IOException("下载响应为空。")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(destination, false).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                            var written = 0L
                            var lastPercent = -1
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                if (total > 0) {
                                    val percent = (written * 100 / total).toInt().coerceIn(0, 100)
                                    if (percent != lastPercent) {
                                        progress("$percent%")
                                        lastPercent = percent
                                    }
                                } else {
                                    progress("已下载 ${written / 1024 / 1024} MB")
                                }
                            }
                        }
                    }
                }
                return
            } catch (error: CancellationException) {
                destination.delete()
                throw error
            } catch (error: Exception) {
                lastError = error
                destination.delete()
            }
        }
        throw lastError ?: IOException("所有下载线路均不可用。")
    }

    private fun copyFileToUri(source: File, destination: Uri) {
        context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 4) }
        } ?: throw IOException("无法写入所选位置。")
    }

    private fun writeBytes(destination: Uri, bytes: ByteArray) {
        context.contentResolver.openOutputStream(destination, "wt")?.use { it.write(bytes) }
            ?: throw IOException("无法写入所选位置。")
    }

    private fun deleteFailedDestination(destination: Uri) {
        runCatching { context.contentResolver.delete(destination, null, null) }
    }

    private fun muxMp4(videoFile: File, audioFile: File, outputFile: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            val videoInputTrack = videoExtractor.findTrack("video/")
            val audioInputTrack = audioExtractor.findTrack("audio/")
            val videoFormat = videoExtractor.getTrackFormat(videoInputTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioInputTrack)

            videoExtractor.selectTrack(videoInputTrack)
            audioExtractor.selectTrack(audioInputTrack)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoOutputTrack = muxer.addTrack(videoFormat)
            val audioOutputTrack = muxer.addTrack(audioFormat)
            muxer.start()
            copyTrack(videoExtractor, muxer, videoOutputTrack)
            copyTrack(audioExtractor, muxer, audioOutputTrack)
            muxer.stop()
            muxer.release()
            muxer = null
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun MediaExtractor.findTrack(prefix: String): Int =
        (0 until trackCount).firstOrNull { index ->
            getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
        } ?: throw IOException("媒体流中缺少 ${if (prefix.startsWith("video")) "视频" else "音频"}轨道。")

    private fun copyTrack(extractor: MediaExtractor, muxer: MediaMuxer, outputTrack: Int) {
        val buffer = ByteBuffer.allocateDirect(16 * 1024 * 1024)
        val info = android.media.MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outputTrack, buffer, info)
            extractor.advance()
        }
    }

    private fun JSONObject.toSource(): StreamSource {
        val primary = optString("baseUrl").ifBlank { optString("base_url") }
            .ifBlank { optString("url") }
        if (primary.isBlank()) throw IOException("媒体流地址为空。")
        val backups = optJSONArray("backupUrl") ?: optJSONArray("backup_url")
        val backupUrls = if (backups == null) emptyList() else {
            (0 until backups.length()).mapNotNull { backups.optString(it).takeIf(String::isNotBlank) }
        }
        return StreamSource(normalizeHttps(primary), backupUrls.map(::normalizeHttps))
    }

    private fun checkApi(root: JSONObject, fallback: String) {
        if (root.optInt("code", -1) != 0) {
            throw IOException(root.optString("message").ifBlank { root.optString("msg") }.ifBlank { fallback })
        }
    }

    private fun JSONObject.requireObject(key: String): JSONObject =
        optJSONObject(key) ?: throw IOException("接口数据缺少 $key。")

    private fun JSONObject.requireString(key: String): String =
        optString(key).takeIf(String::isNotBlank) ?: throw IOException("接口数据缺少 $key。")

    private fun normalizeHttps(url: String): String {
        val normalized = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://", ignoreCase = true) -> "https://${url.drop(7)}"
            else -> url
        }
        val uri = Uri.parse(normalized)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "只允许安全的 HTTPS 地址。" }
        return normalized
    }

    private fun isAllowedImageHost(host: String): Boolean {
        val normalized = host.lowercase()
        return IMAGE_HOST_SUFFIXES.any { normalized == it || normalized.endsWith(".$it") }
    }

    private fun BufferedSource.readLimitedBytes(limit: Long, errorMessage: String): ByteArray {
        val buffer = okio.Buffer()
        var total = 0L
        while (true) {
            val read = read(buffer, minOf(64 * 1024L, limit + 1 - total))
            if (read < 0) break
            total += read
            if (total > limit) throw IOException(errorMessage)
        }
        return buffer.readByteArray()
    }

    private companion object {
        const val MAX_JSON_BYTES = 4L * 1024 * 1024
        const val MAX_COVER_BYTES = 15L * 1024 * 1024
        val IMAGE_HOST_SUFFIXES = listOf("biliimg.com", "hdslb.com")
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
