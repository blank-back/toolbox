package com.pockettoolbox.app

import android.app.Application
import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.pockettoolbox.app.data.BiliRepository
import com.pockettoolbox.app.data.LocalPreferences
import com.pockettoolbox.app.data.MusicRepository
import com.pockettoolbox.app.data.QrRepository
import com.pockettoolbox.app.data.VirusTotalApiKeyStore
import com.pockettoolbox.app.data.VirusTotalReport
import com.pockettoolbox.app.data.VirusTotalRepository
import com.pockettoolbox.app.model.BiliHistoryEntry
import com.pockettoolbox.app.model.BiliMediaInfo
import com.pockettoolbox.app.model.DownloadKind
import com.pockettoolbox.app.model.MusicHistoryEntry
import com.pockettoolbox.app.model.MusicLyricLine
import com.pockettoolbox.app.model.MusicPlaylist
import com.pockettoolbox.app.model.MusicTrack
import com.pockettoolbox.app.model.PlaylistPlaybackMode
import com.pockettoolbox.app.model.QrHistoryEntry
import com.pockettoolbox.app.model.QrResult
import com.pockettoolbox.app.model.QrSecurityHistory
import com.pockettoolbox.app.model.QrSecurityStatus
import com.pockettoolbox.app.model.downloadSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.Locale

data class QrUiState(
    val preview: Bitmap? = null,
    val results: List<QrResult> = emptyList(),
    val historyParsedAtEpochMillis: Long? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

data class VirusTotalUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val report: VirusTotalReport? = null,
    val analysisId: String? = null,
)

data class BiliUiState(
    val media: BiliMediaInfo? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

data class MusicUiState(
    val results: List<MusicTrack> = emptyList(),
    val currentTrack: MusicTrack? = null,
    val busy: Boolean = false,
    val isPlaying: Boolean = false,
    val durationMillis: Int = 0,
    val positionMillis: Int = 0,
    val activePlaylistId: String? = null,
    val playlistPlaybackMode: PlaylistPlaybackMode? = null,
    val playbackRetryAvailable: Boolean = false,
    val lyrics: List<MusicLyricLine> = emptyList(),
    val lyricsLoading: Boolean = false,
    val lyricsTrackKey: String? = null,
    val currentLyricIndex: Int = -1,
    val desktopLyricsEnabled: Boolean = false,
    val searchSource: String = "netease",
    val message: String? = null,
    val isError: Boolean = false,
)

@OptIn(UnstableApi::class)
class ToolboxViewModel(application: Application) : AndroidViewModel(application) {
    private val qrRepository = QrRepository(application)
    private val biliRepository = BiliRepository(application)
    private val musicRepository = MusicRepository(application)
    private val localPreferences = LocalPreferences(application)
    private val virusTotalApiKeyStore = VirusTotalApiKeyStore(application)
    private val virusTotalRepository = VirusTotalRepository()

    private val _qrState = MutableStateFlow(QrUiState())
    val qrState: StateFlow<QrUiState> = _qrState.asStateFlow()

    private val _hasVirusTotalApiKey = MutableStateFlow(virusTotalApiKeyStore.hasApiKey())
    val hasVirusTotalApiKey: StateFlow<Boolean> = _hasVirusTotalApiKey.asStateFlow()

    private val _virusTotalStates = MutableStateFlow<Map<String, VirusTotalUiState>>(emptyMap())
    val virusTotalStates: StateFlow<Map<String, VirusTotalUiState>> = _virusTotalStates.asStateFlow()

    private val _biliState = MutableStateFlow(BiliUiState())
    val biliState: StateFlow<BiliUiState> = _biliState.asStateFlow()

    private val _qrHistory = MutableStateFlow(localPreferences.loadQrHistory())
    val qrHistory: StateFlow<List<QrHistoryEntry>> = _qrHistory.asStateFlow()

    private val _biliHistory = MutableStateFlow(localPreferences.loadBiliHistory())
    val biliHistory: StateFlow<List<BiliHistoryEntry>> = _biliHistory.asStateFlow()

    private val _downloadTreeUri = MutableStateFlow(localPreferences.loadDownloadTreeUri())
    val downloadTreeUri: StateFlow<String?> = _downloadTreeUri.asStateFlow()

    private val _musicState = MutableStateFlow(
        MusicUiState(
            desktopLyricsEnabled = localPreferences.loadDesktopLyricsEnabled() &&
                Settings.canDrawOverlays(application),
            searchSource = localPreferences.loadMusicSearchSource()
                .takeIf { it in SUPPORTED_MUSIC_SEARCH_SOURCES }
                ?: DEFAULT_MUSIC_SEARCH_SOURCE,
        ),
    )
    val musicState: StateFlow<MusicUiState> = _musicState.asStateFlow()

    private val _musicHistory = MutableStateFlow(localPreferences.loadMusicHistory())
    val musicHistory: StateFlow<List<MusicHistoryEntry>> = _musicHistory.asStateFlow()

    private val _musicPlaylists = MutableStateFlow(localPreferences.loadMusicPlaylists())
    val musicPlaylists: StateFlow<List<MusicPlaylist>> = _musicPlaylists.asStateFlow()

    private var mediaPlayer: ExoPlayer? = null
    private var musicPreparationJob: Job? = null
    private var musicLyricsJob: Job? = null
    private var playbackTicker: Job? = null
    private var playlistQueue: List<MusicTrack> = emptyList()
    private var playlistQueueIndex = -1
    private val musicTracksNeedingCacheReset = mutableSetOf<String>()
    private val musicRetryPositionsMillis = mutableMapOf<String, Long>()

    init {
        MusicPlaybackCommandBus.register(
            owner = this,
            onToggle = ::toggleMusicPlayback,
            onStop = ::stopMusicPlayback,
            onPrevious = ::playPreviousPlaylistTrack,
            onNext = ::playNextPlaylistTrack,
            onSeek = { positionMillis ->
                seekMusic(positionMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            },
            onSetDesktopLyricsEnabled = ::setDesktopLyricsEnabled,
        )
    }

    fun scanQr(uri: Uri) {
        _virusTotalStates.value = emptyMap()
        _qrState.value = QrUiState(busy = true, message = "正在本地识别二维码…")
        viewModelScope.launch {
            runCatching { qrRepository.scan(uri) }
                .onSuccess { output ->
                    val parsedAtEpochMillis = System.currentTimeMillis()
                    if (output.results.isNotEmpty()) {
                        _qrHistory.value = localPreferences.addQrHistory(
                            results = output.results,
                            parsedAtEpochMillis = parsedAtEpochMillis,
                        )
                    }
                    _qrState.value = QrUiState(
                        preview = output.preview,
                        results = output.results,
                        historyParsedAtEpochMillis = parsedAtEpochMillis.takeIf {
                            output.results.isNotEmpty()
                        },
                        message = if (output.results.isEmpty()) {
                            "没有识别到二维码。可尝试更清晰、边缘留白更多的原图。"
                        } else {
                            "识别到 ${output.results.size} 个二维码"
                        },
                        isError = output.results.isEmpty(),
                    )
                }
                .onFailure { error ->
                    _qrState.value = QrUiState(
                        message = error.readableMessage("无法识别这张图片。"),
                        isError = true,
                    )
                }
        }
    }

    fun saveVirusTotalApiKey(value: String): Boolean {
        val apiKey = value.trim()
        if (apiKey.isBlank() || apiKey.length > 512 || apiKey.any(Char::isWhitespace)) {
            _qrState.update {
                it.copy(message = "API key 格式无效，请检查是否为空、过长或包含空格。", isError = true)
            }
            return false
        }
        return runCatching {
            virusTotalApiKeyStore.saveApiKey(apiKey)
            _hasVirusTotalApiKey.value = true
            _virusTotalStates.value = emptyMap()
            _qrState.update { it.copy(message = "VirusTotal API key 已加密保存。", isError = false) }
        }.fold(
            onSuccess = { true },
            onFailure = { error ->
                _hasVirusTotalApiKey.value = virusTotalApiKeyStore.hasApiKey()
                _qrState.update {
                    it.copy(
                        message = error.readableMessage("无法使用 Android Keystore 保存 API key。"),
                        isError = true,
                    )
                }
                false
            },
        )
    }

    fun removeVirusTotalApiKey() {
        runCatching { virusTotalApiKeyStore.clearApiKey() }
        _hasVirusTotalApiKey.value = false
        _virusTotalStates.value = emptyMap()
        _qrState.update { it.copy(message = "已移除 VirusTotal API key。", isError = false) }
    }

    fun checkVirusTotal(url: String) {
        if (_virusTotalStates.value[url]?.busy == true) return
        val previousState = _virusTotalStates.value[url]
        val historyParsedAtEpochMillis = _qrState.value.historyParsedAtEpochMillis
        if (previousState?.report == null) {
            updateQrSecurityHistory(
                parsedAtEpochMillis = historyParsedAtEpochMillis,
                webUrl = url,
                status = QrSecurityStatus.PENDING,
            )
        }
        updateVirusTotalState(url) {
            it.copy(busy = true, message = "正在查询 VirusTotal…", isError = false)
        }
        viewModelScope.launch {
            runCatching {
                val apiKey = virusTotalApiKeyStore.getApiKey()
                    ?: throw IllegalStateException("请先设置 VirusTotal API key。")
                val report = virusTotalRepository.lookupUrl(url, apiKey)
                if (report != null) {
                    report to null
                } else {
                    null to virusTotalRepository.submitUrl(url, apiKey)
                }
            }.onSuccess { (report, analysisId) ->
                updateQrSecurityHistory(
                    parsedAtEpochMillis = historyParsedAtEpochMillis,
                    webUrl = url,
                    status = if (report == null) QrSecurityStatus.PENDING else QrSecurityStatus.COMPLETED,
                    report = report,
                )
                updateVirusTotalState(url) { previous ->
                    if (report != null) {
                        VirusTotalUiState(
                            report = report,
                            message = virusTotalVerdict(report),
                            isError = report.stats.malicious > 0 || report.stats.suspicious > 0,
                        )
                    } else {
                        previous.copy(
                            busy = false,
                            message = "没有找到已有报告，URL 已提交扫描。请稍后手动刷新结果。",
                            isError = false,
                            report = null,
                            analysisId = analysisId,
                        )
                    }
                }
            }.onFailure { error ->
                if (previousState?.report == null) {
                    updateQrSecurityHistory(
                        parsedAtEpochMillis = historyParsedAtEpochMillis,
                        webUrl = url,
                        status = QrSecurityStatus.FAILED,
                    )
                }
                updateVirusTotalState(url) {
                    it.copy(
                        busy = false,
                        message = error.readableMessage("VirusTotal 检测失败。"),
                        isError = true,
                    )
                }
            }
        }
    }

    fun refreshVirusTotal(url: String) {
        val existing = _virusTotalStates.value[url] ?: VirusTotalUiState()
        if (existing.busy) return
        val historyParsedAtEpochMillis = _qrState.value.historyParsedAtEpochMillis
        if (existing.report == null) {
            updateQrSecurityHistory(
                parsedAtEpochMillis = historyParsedAtEpochMillis,
                webUrl = url,
                status = QrSecurityStatus.PENDING,
            )
        }
        updateVirusTotalState(url) {
            it.copy(busy = true, message = "正在刷新 VirusTotal 报告…", isError = false)
        }
        viewModelScope.launch {
            runCatching {
                val apiKey = virusTotalApiKeyStore.getApiKey()
                    ?: throw IllegalStateException("请先设置 VirusTotal API key。")
                val analysisId = existing.analysisId
                if (analysisId != null) {
                    val analysis = virusTotalRepository.getAnalysis(url, analysisId, apiKey)
                    if (analysis.status == "completed" && analysis.stats != null) {
                        VirusTotalUiState(
                            report = VirusTotalReport(
                                stats = analysis.stats,
                                analysisEpochSeconds = analysis.analysisEpochSeconds,
                                reportUrl = analysis.reportUrl,
                            ),
                        )
                    } else {
                        VirusTotalUiState(
                            message = when (analysis.status) {
                                "queued" -> "扫描仍在队列中，请稍后再刷新。"
                                "in-progress" -> "VirusTotal 正在分析，请稍后再刷新。"
                                else -> "分析状态：${analysis.status}。请稍后再刷新。"
                            },
                            analysisId = analysisId,
                        )
                    }
                } else {
                    val report = virusTotalRepository.lookupUrl(url, apiKey)
                    if (report == null) {
                        VirusTotalUiState(message = "报告尚未生成；本次刷新不会重复提交 URL。")
                    } else {
                        VirusTotalUiState(report = report)
                    }
                }
            }.onSuccess { refreshed ->
                val report = refreshed.report
                updateQrSecurityHistory(
                    parsedAtEpochMillis = historyParsedAtEpochMillis,
                    webUrl = url,
                    status = if (report == null) QrSecurityStatus.PENDING else QrSecurityStatus.COMPLETED,
                    report = report,
                )
                updateVirusTotalState(url) {
                    if (report == null) {
                        refreshed.copy(busy = false)
                    } else {
                        refreshed.copy(
                            busy = false,
                            message = virusTotalVerdict(report),
                            isError = report.stats.malicious > 0 || report.stats.suspicious > 0,
                        )
                    }
                }
            }.onFailure { error ->
                if (existing.report == null) {
                    updateQrSecurityHistory(
                        parsedAtEpochMillis = historyParsedAtEpochMillis,
                        webUrl = url,
                        status = QrSecurityStatus.FAILED,
                    )
                }
                updateVirusTotalState(url) {
                    existing.copy(
                        busy = false,
                        message = error.readableMessage("无法刷新 VirusTotal 报告。"),
                        isError = true,
                    )
                }
            }
        }
    }

    private fun updateVirusTotalState(
        url: String,
        transform: (VirusTotalUiState) -> VirusTotalUiState,
    ) {
        _virusTotalStates.update { states ->
            states + (url to transform(states[url] ?: VirusTotalUiState()))
        }
    }

    private fun updateQrSecurityHistory(
        parsedAtEpochMillis: Long?,
        webUrl: String,
        status: QrSecurityStatus,
        report: VirusTotalReport? = null,
    ) {
        if (parsedAtEpochMillis == null) return
        val stats = report?.stats
        _qrHistory.value = localPreferences.updateQrSecurityHistory(
            parsedAtEpochMillis = parsedAtEpochMillis,
            webUrl = webUrl,
            security = QrSecurityHistory(
                status = status,
                checkedAtEpochMillis = System.currentTimeMillis(),
                malicious = stats?.malicious ?: 0,
                suspicious = stats?.suspicious ?: 0,
                harmless = stats?.harmless ?: 0,
                undetected = stats?.undetected ?: 0,
            ),
        )
    }

    private fun virusTotalVerdict(report: VirusTotalReport): String = when {
        report.stats.malicious > 0 ->
            "${report.stats.malicious} 个检测引擎标记为恶意，请谨慎处理。"
        report.stats.suspicious > 0 ->
            "${report.stats.suspicious} 个检测引擎标记为可疑，请谨慎处理。"
        else -> "当前报告未发现恶意或可疑标记；这不代表该地址绝对安全。"
    }

    fun lookupBili(input: String) {
        _biliState.value = BiliUiState(busy = true, message = "正在获取公开信息…")
        viewModelScope.launch {
            runCatching { biliRepository.lookup(input) }
                .onSuccess { media ->
                    _biliHistory.value = localPreferences.addBiliHistory(
                        BiliHistoryEntry(
                            parsedAtEpochMillis = System.currentTimeMillis(),
                            id = media.id,
                            title = media.title,
                        ),
                    )
                    _biliState.value = BiliUiState(media = media)
                }
                .onFailure { error ->
                    _biliState.value = BiliUiState(
                        message = error.readableMessage("查询失败。"),
                        isError = true,
                    )
                }
        }
    }

    fun setDownloadDirectory(uri: Uri) {
        _downloadTreeUri.value?.let(Uri::parse)?.takeIf { it != uri }?.let { previous ->
            runCatching {
                getApplication<Application>().contentResolver.releasePersistableUriPermission(
                    previous,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        val value = uri.toString()
        localPreferences.saveDownloadTreeUri(value)
        _downloadTreeUri.value = value
        _biliState.update { it.copy(message = "保存目录已更新。", isError = false) }
        _musicState.update { it.copy(message = "保存目录已更新。", isError = false) }
    }

    fun download(kind: DownloadKind) {
        val media = _biliState.value.media ?: return
        val treeUri = _downloadTreeUri.value?.let(Uri::parse)
        if (treeUri == null) {
            _biliState.update { it.copy(message = "请先选择保存目录。", isError = true) }
            return
        }
        _biliState.update { it.copy(busy = true, message = "正在准备下载…", isError = false) }
        viewModelScope.launch {
            runCatching {
                val spec = media.downloadSpec(kind)
                val destination = biliRepository.createDestination(treeUri, spec.mimeType, spec.fileName)
                when (kind) {
                    DownloadKind.COVER -> biliRepository.saveCover(media, destination)
                    DownloadKind.VIDEO -> biliRepository.saveVideo(media, destination, ::updateDownloadProgress)
                    DownloadKind.AUDIO -> biliRepository.saveAudio(media, destination, ::updateDownloadProgress)
                }
            }.onSuccess {
                _biliState.update { it.copy(busy = false, message = "下载完成，文件已保存到所选位置。") }
            }.onFailure { error ->
                if (error is SecurityException) {
                    localPreferences.clearDownloadTreeUri()
                    _downloadTreeUri.value = null
                }
                _biliState.update {
                    it.copy(
                        busy = false,
                        message = if (error is SecurityException) {
                            "保存目录权限已失效，请重新选择目录。"
                        } else {
                            error.readableMessage("下载失败。")
                        },
                        isError = true,
                    )
                }
            }
        }
    }

    fun clearBiliMessage() {
        _biliState.update { it.copy(message = null, isError = false) }
    }

    fun reportBiliError(message: String) {
        _biliState.update { it.copy(busy = false, message = message, isError = true) }
    }

    fun clearQrHistory() {
        localPreferences.clearQrHistory()
        _qrHistory.value = emptyList()
    }

    fun clearBiliHistory() {
        localPreferences.clearBiliHistory()
        _biliHistory.value = emptyList()
    }

    fun searchMusic(keyword: String, source: String) {
        setMusicSearchSource(source)
        _musicState.update { it.copy(busy = true, message = "正在搜索歌曲…", isError = false) }
        viewModelScope.launch {
            runCatching { musicRepository.search(keyword, source) }
                .onSuccess { tracks ->
                    _musicState.update {
                        it.copy(
                            results = tracks,
                            busy = false,
                            message = if (tracks.isEmpty()) "没有找到相关歌曲。" else "找到 ${tracks.size} 首歌曲",
                            isError = tracks.isEmpty(),
                        )
                    }
                }
                .onFailure { error ->
                    _musicState.update {
                        it.copy(
                            busy = false,
                            message = error.readableMessage("歌曲搜索失败。"),
                            isError = true,
                        )
                    }
                }
        }
    }

    fun setMusicSearchSource(source: String) {
        val normalized = source.trim().lowercase(Locale.ROOT)
        if (normalized !in SUPPORTED_MUSIC_SEARCH_SOURCES) return
        localPreferences.saveMusicSearchSource(normalized)
        _musicState.update { it.copy(searchSource = normalized) }
    }

    fun selectMusic(track: MusicTrack) {
        if (_musicState.value.busy) return
        clearPlaylistPlayback()
        prepareMusicTrack(track)
    }

    fun createMusicPlaylist(name: String): Boolean {
        val normalized = name.trim()
        if (normalized.isBlank()) {
            reportMusicError("请输入歌单名称。")
            return false
        }
        if (normalized.length > 50) {
            reportMusicError("歌单名称不能超过 50 个字符。")
            return false
        }
        if (_musicPlaylists.value.any { it.name.equals(normalized, ignoreCase = true) }) {
            reportMusicError("已经存在同名歌单。")
            return false
        }
        if (_musicPlaylists.value.size >= 50) {
            reportMusicError("最多创建 50 个歌单。")
            return false
        }
        updateMusicPlaylists(
            _musicPlaylists.value + MusicPlaylist(
                id = UUID.randomUUID().toString(),
                name = normalized,
                createdAtEpochMillis = System.currentTimeMillis(),
                tracks = emptyList(),
            ),
        )
        _musicState.update { it.copy(message = "已创建歌单“$normalized”。", isError = false) }
        return true
    }

    fun renameMusicPlaylist(playlistId: String, name: String): Boolean {
        val normalized = name.trim()
        val playlist = _musicPlaylists.value.firstOrNull { it.id == playlistId } ?: return false
        if (normalized.isBlank() || normalized.length > 50) {
            reportMusicError("歌单名称应为 1 至 50 个字符。")
            return false
        }
        if (_musicPlaylists.value.any {
                it.id != playlistId && it.name.equals(normalized, ignoreCase = true)
            }
        ) {
            reportMusicError("已经存在同名歌单。")
            return false
        }
        updateMusicPlaylists(
            _musicPlaylists.value.map { if (it.id == playlistId) it.copy(name = normalized) else it },
        )
        _musicState.update { it.copy(message = "歌单“${playlist.name}”已重命名。", isError = false) }
        return true
    }

    fun deleteMusicPlaylist(playlistId: String) {
        val playlist = _musicPlaylists.value.firstOrNull { it.id == playlistId } ?: return
        updateMusicPlaylists(_musicPlaylists.value.filterNot { it.id == playlistId })
        if (_musicState.value.activePlaylistId == playlistId) clearPlaylistPlayback()
        _musicState.update { it.copy(message = "已删除歌单“${playlist.name}”。", isError = false) }
    }

    fun addTrackToPlaylist(playlistId: String, track: MusicTrack): Boolean {
        val playlist = _musicPlaylists.value.firstOrNull { it.id == playlistId } ?: return false
        if (playlist.tracks.any { it.cacheKey == track.cacheKey }) {
            reportMusicError("这首歌已经在“${playlist.name}”中。")
            return false
        }
        if (playlist.tracks.size >= 500) {
            reportMusicError("单个歌单最多保存 500 首歌曲。")
            return false
        }
        updateMusicPlaylists(
            _musicPlaylists.value.map {
                if (it.id == playlistId) it.copy(tracks = it.tracks + track) else it
            },
        )
        _musicState.update { it.copy(message = "已添加到“${playlist.name}”。", isError = false) }
        return true
    }

    fun removeTrackFromPlaylist(playlistId: String, track: MusicTrack) {
        val playlist = _musicPlaylists.value.firstOrNull { it.id == playlistId } ?: return
        updateMusicPlaylists(
            _musicPlaylists.value.map {
                if (it.id == playlistId) {
                    it.copy(tracks = it.tracks.filterNot { item -> item.cacheKey == track.cacheKey })
                } else {
                    it
                }
            },
        )
        if (_musicState.value.activePlaylistId == playlistId) clearPlaylistPlayback()
        _musicState.update { it.copy(message = "已从“${playlist.name}”移除 ${track.name}。", isError = false) }
    }

    fun playMusicPlaylist(playlistId: String, mode: PlaylistPlaybackMode, startTrack: MusicTrack? = null) {
        if (_musicState.value.busy) return
        val playlist = _musicPlaylists.value.firstOrNull { it.id == playlistId } ?: return
        if (playlist.tracks.isEmpty()) {
            reportMusicError("该歌单还没有歌曲。")
            return
        }
        playlistQueue = when (mode) {
            PlaylistPlaybackMode.LIST_LOOP -> playlist.tracks
            PlaylistPlaybackMode.SHUFFLE -> playlist.tracks.shuffled().let { shuffled ->
                if (startTrack == null) shuffled else listOf(startTrack) + shuffled.filterNot {
                    it.cacheKey == startTrack.cacheKey
                }
            }
        }
        playlistQueueIndex = startTrack?.let { selected ->
            playlistQueue.indexOfFirst { it.cacheKey == selected.cacheKey }
        }?.takeIf { it >= 0 } ?: 0
        _musicState.update {
            it.copy(activePlaylistId = playlistId, playlistPlaybackMode = mode)
        }
        prepareMusicTrack(playlistQueue[playlistQueueIndex])
    }

    private fun prepareMusicTrack(track: MusicTrack) {
        val previousState = _musicState.value
        val retryPositionMillis = musicRetryPositionsMillis[track.cacheKey]?.coerceAtLeast(0L) ?: 0L
        val isRetryingCurrentTrack = previousState.currentTrack?.cacheKey == track.cacheKey && retryPositionMillis > 0L
        musicPreparationJob?.cancel()
        musicPreparationJob = null
        stopPlaybackTicker()
        releaseMusicPlayer()
        _musicState.update {
            it.copy(
                currentTrack = track,
                busy = true,
                isPlaying = false,
                durationMillis = if (isRetryingCurrentTrack) previousState.durationMillis else 0,
                positionMillis = if (isRetryingCurrentTrack) retryPositionMillis.toUiMillis() else 0,
                playbackRetryAvailable = false,
                message = if (retryPositionMillis > 0L) {
                    "正在准备从中断进度继续播放 ${track.name}…"
                } else {
                    "正在准备 ${track.name}…"
                },
                isError = false,
            )
        }
        loadMusicLyrics(track)
        updateMusicCompanion()
        musicPreparationJob = viewModelScope.launch {
            try {
                if (track.cacheKey in musicTracksNeedingCacheReset) {
                    updateMusicProgress("正在清理未完成的音频缓存…")
                    musicRepository.discardFailedPlaybackCache(track)
                    musicTracksNeedingCacheReset.remove(track.cacheKey)
                }
                val playable = musicRepository.prepareForPlayback(track, ::updateMusicProgress)
                prepareMediaPlayer(track, playable.uri, retryPositionMillis)
            } catch (_: CancellationException) {
                // A newer track selection or an explicit cache clear superseded this request.
            } catch (error: Throwable) {
                _musicState.update {
                    it.copy(
                        busy = false,
                        playbackRetryAvailable = true,
                        message = error.readableMessage("无法播放这首歌曲。"),
                        isError = true,
                    )
                }
                updateMusicCompanion()
            }
        }
    }

    fun toggleMusicPlayback() {
        val player = mediaPlayer
        if (player == null) {
            val state = _musicState.value
            val track = state.currentTrack ?: return
            if (!state.busy) prepareMusicTrack(track)
            return
        }
        runCatching {
            if (player.isPlaying) {
                player.pause()
                stopPlaybackTicker()
                _musicState.update {
                    it.copy(isPlaying = false, positionMillis = player.currentPosition.toUiMillis())
                }
                updateMusicCompanion()
            } else {
                if (player.playbackState == Player.STATE_ENDED) {
                    player.seekTo(0L)
                }
                player.play()
                _musicState.update { it.copy(isPlaying = true, message = null, isError = false) }
                updateMusicCompanion()
                startPlaybackTicker()
            }
        }.onFailure { reportMusicError("播放器状态异常，请重新选择歌曲。") }
    }

    fun seekMusic(positionMillis: Int) {
        val player = mediaPlayer ?: return
        val bounded = positionMillis.coerceIn(0, _musicState.value.durationMillis.coerceAtLeast(0))
        runCatching {
            player.seekTo(bounded.toLong())
            val previousLyricIndex = _musicState.value.currentLyricIndex
            _musicState.update {
                it.copy(
                    positionMillis = bounded,
                    currentLyricIndex = lyricIndexAt(it.lyrics, bounded.toLong()),
                )
            }
            if (_musicState.value.currentLyricIndex != previousLyricIndex) updateMusicCompanion()
        }
    }

    fun setDesktopLyricsEnabled(enabled: Boolean) {
        val application = getApplication<Application>()
        if (enabled && !Settings.canDrawOverlays(application)) {
            reportMusicError("请先授予“显示在其他应用上层”权限。")
            return
        }
        localPreferences.saveDesktopLyricsEnabled(enabled)
        _musicState.update { it.copy(desktopLyricsEnabled = enabled) }
        updateMusicCompanion()
    }

    fun stopMusicPlayback() {
        musicPreparationJob?.cancel()
        musicPreparationJob = null
        stopPlaybackTicker()
        releaseMusicPlayer()
        _musicState.update {
            it.copy(
                busy = false,
                isPlaying = false,
                playbackRetryAvailable = it.currentTrack != null,
                message = "播放已停止。",
                isError = false,
            )
        }
        MusicPlaybackCompanionService.stop(getApplication<Application>())
    }

    fun downloadMusic() {
        val track = _musicState.value.currentTrack ?: return
        val treeUri = _downloadTreeUri.value?.let(Uri::parse)
        if (treeUri == null) {
            reportMusicError("请先选择保存目录。")
            return
        }
        _musicState.update { it.copy(busy = true, message = "正在准备下载…", isError = false) }
        viewModelScope.launch {
            runCatching {
                musicRepository.saveTrack(track, treeUri, ::updateMusicProgress)
            }.onSuccess {
                _musicState.update {
                    it.copy(busy = false, message = "歌曲已保存到所选目录。", isError = false)
                }
            }.onFailure { error ->
                if (error is SecurityException) {
                    localPreferences.clearDownloadTreeUri()
                    _downloadTreeUri.value = null
                }
                _musicState.update {
                    it.copy(
                        busy = false,
                        message = if (error is SecurityException) {
                            "保存目录权限已失效，请重新选择目录。"
                        } else {
                            error.readableMessage("歌曲下载失败。")
                        },
                        isError = true,
                    )
                }
            }
        }
    }

    fun clearMusicCache() {
        musicPreparationJob?.cancel()
        musicPreparationJob = null
        musicLyricsJob?.cancel()
        musicLyricsJob = null
        stopPlaybackTicker()
        releaseMusicPlayer()
        playlistQueue = emptyList()
        playlistQueueIndex = -1
        musicTracksNeedingCacheReset.clear()
        musicRetryPositionsMillis.clear()
        _musicState.update {
            it.copy(
                currentTrack = null,
                busy = true,
                isPlaying = false,
                durationMillis = 0,
                positionMillis = 0,
                activePlaylistId = null,
                playlistPlaybackMode = null,
                playbackRetryAvailable = false,
                lyrics = emptyList(),
                lyricsLoading = false,
                lyricsTrackKey = null,
                currentLyricIndex = -1,
                message = "正在清除音乐缓存…",
                isError = false,
            )
        }
        MusicPlaybackCompanionService.stop(getApplication<Application>())
        viewModelScope.launch {
            runCatching { musicRepository.clearAudioCache() }
                .onSuccess { clearedBytes ->
                    _musicState.update {
                        it.copy(
                            busy = false,
                            message = if (clearedBytes > 0L) {
                                "已清除 ${formatCacheSize(clearedBytes)} 音乐缓存。"
                            } else {
                                "音乐缓存已经是空的。"
                            },
                            isError = false,
                        )
                    }
                }
                .onFailure { error ->
                    _musicState.update {
                        it.copy(
                            busy = false,
                            message = error.readableMessage("无法清除音乐缓存。"),
                            isError = true,
                        )
                    }
                }
        }
    }

    fun clearMusicHistory() {
        localPreferences.clearMusicHistory()
        _musicHistory.value = emptyList()
    }

    fun reportMusicError(message: String) {
        _musicState.update { it.copy(busy = false, message = message, isError = true) }
    }

    private fun prepareMediaPlayer(track: MusicTrack, uri: Uri, resumePositionMillis: Long) {
        val player = musicRepository.createPlaybackPlayer()
        mediaPlayer = player
        var historyRecorded = false
        runCatching {
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (mediaPlayer !== player) return
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            _musicState.update {
                                it.copy(
                                    busy = true,
                                    isPlaying = false,
                                    message = if (resumePositionMillis > 0L) {
                                        "正在从中断进度缓冲并重新缓存音频…"
                                    } else {
                                        "正在缓冲并缓存音频…"
                                    },
                                    isError = false,
                                )
                            }
                            updateMusicCompanion()
                        }
                        Player.STATE_READY -> {
                            musicTracksNeedingCacheReset.remove(track.cacheKey)
                            musicRetryPositionsMillis.remove(track.cacheKey)
                            if (!historyRecorded) {
                                historyRecorded = true
                                _musicHistory.value = localPreferences.addMusicHistory(
                                    MusicHistoryEntry(System.currentTimeMillis(), track),
                                )
                            }
                            _musicState.update {
                                val positionMillis = player.currentPosition.toUiMillis()
                                it.copy(
                                    busy = false,
                                    isPlaying = player.isPlaying,
                                    durationMillis = player.duration.toUiDurationMillis(),
                                    positionMillis = positionMillis,
                                    currentLyricIndex = lyricIndexAt(it.lyrics, positionMillis.toLong()),
                                    playbackRetryAvailable = false,
                                    message = null,
                                    isError = false,
                                )
                            }
                            updateMusicCompanion()
                            if (player.isPlaying) startPlaybackTicker()
                        }
                        Player.STATE_ENDED -> {
                            stopPlaybackTicker()
                            if (_musicState.value.playlistPlaybackMode != null && playlistQueue.isNotEmpty()) {
                                playNextPlaylistTrack()
                            } else {
                                _musicState.update {
                                    it.copy(
                                        busy = false,
                                        isPlaying = false,
                                        positionMillis = player.duration.toUiDurationMillis(),
                                    )
                                }
                                updateMusicCompanion()
                            }
                        }
                        Player.STATE_IDLE -> Unit
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (mediaPlayer !== player) return
                    _musicState.update {
                        it.copy(
                            isPlaying = isPlaying,
                            positionMillis = player.currentPosition.toUiMillis(),
                        )
                    }
                    if (isPlaying) startPlaybackTicker() else stopPlaybackTicker()
                    updateMusicCompanion()
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (mediaPlayer !== player) return
                    val failedPositionMillis = runCatching { player.currentPosition }
                        .getOrNull()
                        ?.takeIf { it != C.TIME_UNSET && it > 0L }
                        ?: _musicState.value.positionMillis.toLong().coerceAtLeast(0L)
                    if (failedPositionMillis > 0L) {
                        musicRetryPositionsMillis[track.cacheKey] = failedPositionMillis
                    } else {
                        musicRetryPositionsMillis.remove(track.cacheKey)
                    }
                    stopPlaybackTicker()
                    mediaPlayer = null
                    runCatching { player.release() }
                    musicTracksNeedingCacheReset += track.cacheKey
                    _musicState.update {
                        it.copy(
                            busy = true,
                            isPlaying = false,
                            positionMillis = failedPositionMillis.toUiMillis(),
                            currentLyricIndex = lyricIndexAt(it.lyrics, failedPositionMillis),
                            playbackRetryAvailable = false,
                            message = "音频播放或缓存中断，正在清理未完成的缓存…",
                            isError = true,
                        )
                    }
                    updateMusicCompanion()
                    musicPreparationJob = viewModelScope.launch {
                        try {
                            musicRepository.discardFailedPlaybackCache(track)
                            musicTracksNeedingCacheReset.remove(track.cacheKey)
                            if (_musicState.value.currentTrack?.cacheKey == track.cacheKey && mediaPlayer == null) {
                                _musicState.update {
                                    it.copy(
                                        busy = false,
                                        playbackRetryAvailable = true,
                                        message = if (failedPositionMillis > 0L) {
                                            "音频播放或缓存中断，未完成的缓存已清理。点击“重试播放”将尝试从当前进度继续。"
                                        } else {
                                            "音频播放或缓存中断，未完成的缓存已清理。点击“重试播放”可重新缓存。"
                                        },
                                        isError = true,
                                    )
                                }
                            }
                        } catch (_: CancellationException) {
                            // A new selection or a full cache clear superseded this cleanup.
                        } catch (cleanupError: Throwable) {
                            if (_musicState.value.currentTrack?.cacheKey == track.cacheKey && mediaPlayer == null) {
                                _musicState.update {
                                    it.copy(
                                        busy = false,
                                        playbackRetryAvailable = true,
                                        message = cleanupError.readableMessage(
                                            "音频播放或缓存中断。点击“重试播放”将再次清理缓存并重试。",
                                        ),
                                        isError = true,
                                    )
                                }
                            }
                        }
                    }
                }
            })
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(uri)
                    .setCustomCacheKey(musicRepository.playbackCacheKey(track))
                    .build(),
            )
            if (resumePositionMillis > 0L) player.seekTo(resumePositionMillis)
            player.prepare()
            player.playWhenReady = true
        }.onFailure { error ->
            runCatching { player.release() }
            if (mediaPlayer === player) mediaPlayer = null
            _musicState.update {
                it.copy(
                    busy = false,
                    isPlaying = false,
                    playbackRetryAvailable = true,
                    message = error.readableMessage("无法初始化播放器。"),
                    isError = true,
                )
            }
            updateMusicCompanion()
        }
    }

    private fun startPlaybackTicker() {
        stopPlaybackTicker()
        playbackTicker = viewModelScope.launch {
            while (true) {
                val player = mediaPlayer ?: break
                val position = runCatching { player.currentPosition }.getOrNull() ?: break
                val previousLyricIndex = _musicState.value.currentLyricIndex
                val positionMillis = position.toUiMillis()
                _musicState.update {
                    it.copy(
                        positionMillis = positionMillis,
                        currentLyricIndex = lyricIndexAt(it.lyrics, position),
                    )
                }
                if (_musicState.value.currentLyricIndex != previousLyricIndex) updateMusicCompanion()
                delay(500)
            }
        }
    }

    private fun stopPlaybackTicker() {
        playbackTicker?.cancel()
        playbackTicker = null
    }

    private fun releaseMusicPlayer() {
        val player = mediaPlayer
        mediaPlayer = null
        runCatching { player?.release() }
    }

    fun playNextPlaylistTrack() {
        val mode = _musicState.value.playlistPlaybackMode ?: return
        if (playlistQueue.isEmpty()) return
        playlistQueueIndex += 1
        if (playlistQueueIndex >= playlistQueue.size) {
            playlistQueueIndex = 0
            if (mode == PlaylistPlaybackMode.SHUFFLE && playlistQueue.size > 1) {
                val lastTrack = playlistQueue.lastOrNull()
                playlistQueue = playlistQueue.shuffled().let { shuffled ->
                    if (shuffled.firstOrNull()?.cacheKey == lastTrack?.cacheKey) {
                        shuffled.drop(1) + shuffled.first()
                    } else {
                        shuffled
                    }
                }
            }
        }
        prepareMusicTrack(playlistQueue[playlistQueueIndex])
    }

    fun playPreviousPlaylistTrack() {
        if (_musicState.value.playlistPlaybackMode == null || playlistQueue.isEmpty()) return
        playlistQueueIndex -= 1
        if (playlistQueueIndex < 0) playlistQueueIndex = playlistQueue.lastIndex
        prepareMusicTrack(playlistQueue[playlistQueueIndex])
    }

    private fun clearPlaylistPlayback() {
        playlistQueue = emptyList()
        playlistQueueIndex = -1
        _musicState.update { it.copy(activePlaylistId = null, playlistPlaybackMode = null) }
        updateMusicCompanion()
    }

    private fun updateMusicPlaylists(playlists: List<MusicPlaylist>) {
        localPreferences.saveMusicPlaylists(playlists)
        _musicPlaylists.value = playlists
    }

    private fun loadMusicLyrics(track: MusicTrack) {
        if (_musicState.value.lyricsTrackKey == track.cacheKey) return
        musicLyricsJob?.cancel()
        _musicState.update {
            it.copy(
                lyrics = emptyList(),
                lyricsLoading = true,
                lyricsTrackKey = track.cacheKey,
                currentLyricIndex = -1,
            )
        }
        musicLyricsJob = viewModelScope.launch {
            try {
                val lines = musicRepository.fetchLyrics(track)
                if (_musicState.value.currentTrack?.cacheKey != track.cacheKey) return@launch
                _musicState.update {
                    it.copy(
                        lyrics = lines,
                        lyricsLoading = false,
                        currentLyricIndex = lyricIndexAt(lines, it.positionMillis.toLong()),
                    )
                }
                updateMusicCompanion()
            } catch (_: CancellationException) {
                // Lyrics for a newer track superseded this request.
            } catch (_: Throwable) {
                if (_musicState.value.currentTrack?.cacheKey != track.cacheKey) return@launch
                _musicState.update {
                    it.copy(
                        lyrics = emptyList(),
                        lyricsLoading = false,
                        currentLyricIndex = -1,
                    )
                }
                updateMusicCompanion()
            }
        }
    }

    private fun lyricIndexAt(lines: List<MusicLyricLine>, positionMillis: Long): Int =
        lines.indexOfLast { it.startTimeMillis <= positionMillis }

    private fun updateMusicCompanion() {
        val state = _musicState.value
        val track = state.currentTrack ?: run {
            MusicPlaybackCompanionService.stop(getApplication<Application>())
            return
        }
        val lyric = when {
            state.lyricsLoading -> "歌词加载中…"
            state.currentLyricIndex in state.lyrics.indices ->
                state.lyrics[state.currentLyricIndex].displayText
            state.lyrics.isEmpty() -> "暂无歌词"
            else -> "♪ ${track.name}"
        }
        MusicPlaybackCompanionService.update(
            context = getApplication<Application>(),
            title = track.name,
            artist = track.artistText,
            album = track.album,
            lyric = lyric,
            isPlaying = state.isPlaying,
            desktopLyricsEnabled = state.desktopLyricsEnabled,
            hasPlaylistControls = state.playlistPlaybackMode != null && playlistQueue.isNotEmpty(),
            positionMillis = state.positionMillis,
            durationMillis = state.durationMillis,
        )
    }

    private fun updateMusicProgress(message: String) {
        _musicState.update { it.copy(message = message, isError = false) }
    }

    override fun onCleared() {
        musicPreparationJob?.cancel()
        musicPreparationJob = null
        musicLyricsJob?.cancel()
        musicLyricsJob = null
        stopPlaybackTicker()
        releaseMusicPlayer()
        MusicPlaybackCommandBus.unregister(this)
        MusicPlaybackCompanionService.stop(getApplication<Application>())
        runCatching { musicRepository.release() }
        super.onCleared()
    }

    private fun updateDownloadProgress(message: String) {
        _biliState.update { it.copy(message = message, isError = false) }
    }

    private fun Throwable.readableMessage(fallback: String): String =
        message?.takeIf(String::isNotBlank) ?: fallback

    private fun Long.toUiMillis(): Int =
        takeIf { it != C.TIME_UNSET && it > 0L }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()
            ?: 0

    private fun Long.toUiDurationMillis(): Int = toUiMillis()

    private fun formatCacheSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private companion object {
        const val DEFAULT_MUSIC_SEARCH_SOURCE = "netease"
        val SUPPORTED_MUSIC_SEARCH_SOURCES = setOf(
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
    }
}
