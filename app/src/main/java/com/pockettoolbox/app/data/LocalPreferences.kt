package com.pockettoolbox.app.data

import android.content.Context
import com.pockettoolbox.app.model.BiliHistoryEntry
import com.pockettoolbox.app.model.BiliId
import com.pockettoolbox.app.model.MediaKind
import com.pockettoolbox.app.model.MusicHistoryEntry
import com.pockettoolbox.app.model.MusicPlaylist
import com.pockettoolbox.app.model.MusicTrack
import com.pockettoolbox.app.model.QrHistoryEntry
import com.pockettoolbox.app.model.QrResult
import com.pockettoolbox.app.model.QrSecurityHistory
import com.pockettoolbox.app.model.QrSecurityStatus
import org.json.JSONArray
import org.json.JSONObject

class LocalPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadQrHistory(): List<QrHistoryEntry> = parseArray(KEY_QR_HISTORY) { json ->
        QrHistoryEntry(
            parsedAtEpochMillis = json.getLong("parsedAt"),
            text = json.getString("text"),
            webUrl = if (json.isNull("webUrl")) null else json.getString("webUrl"),
            security = json.optJSONObject("security")?.toQrSecurityHistory() ?: QrSecurityHistory(),
        )
    }

    @Synchronized
    fun addQrHistory(results: List<QrResult>, parsedAtEpochMillis: Long): List<QrHistoryEntry> {
        if (results.isEmpty()) return loadQrHistory()
        val updated = (
            results.map { QrHistoryEntry(parsedAtEpochMillis, it.text, it.webUrl) } + loadQrHistory()
        ).take(MAX_HISTORY_ITEMS)
        saveQrHistory(updated)
        return updated
    }

    @Synchronized
    fun updateQrSecurityHistory(
        parsedAtEpochMillis: Long,
        webUrl: String,
        security: QrSecurityHistory,
    ): List<QrHistoryEntry> {
        var updatedOne = false
        val updated = loadQrHistory().map { entry ->
            if (!updatedOne && entry.parsedAtEpochMillis == parsedAtEpochMillis && entry.webUrl == webUrl) {
                updatedOne = true
                entry.copy(security = security)
            } else {
                entry
            }
        }
        if (updatedOne) saveQrHistory(updated)
        return updated
    }

    fun clearQrHistory() {
        preferences.edit().remove(KEY_QR_HISTORY).apply()
    }

    fun loadBiliHistory(): List<BiliHistoryEntry> = parseArray(KEY_BILI_HISTORY) { json ->
        BiliHistoryEntry(
            parsedAtEpochMillis = json.getLong("parsedAt"),
            id = BiliId(
                value = json.getString("id"),
                kind = MediaKind.valueOf(json.getString("kind")),
            ),
            title = json.getString("title"),
        )
    }

    @Synchronized
    fun addBiliHistory(entry: BiliHistoryEntry): List<BiliHistoryEntry> {
        val previous = loadBiliHistory().filterNot { item ->
            item.id == entry.id && item.title == entry.title
        }
        val updated = (listOf(entry) + previous).take(MAX_HISTORY_ITEMS)
        saveArray(KEY_BILI_HISTORY, updated) { item ->
            JSONObject()
                .put("parsedAt", item.parsedAtEpochMillis)
                .put("id", item.id.value)
                .put("kind", item.id.kind.name)
                .put("title", item.title)
        }
        return updated
    }

    fun clearBiliHistory() {
        preferences.edit().remove(KEY_BILI_HISTORY).apply()
    }

    fun loadMusicHistory(): List<MusicHistoryEntry> = parseArray(KEY_MUSIC_HISTORY) { json ->
        MusicHistoryEntry(
            searchedAtEpochMillis = json.getLong("searchedAt"),
            track = json.toMusicTrack(),
        )
    }

    @Synchronized
    fun addMusicHistory(entry: MusicHistoryEntry): List<MusicHistoryEntry> {
        val previous = loadMusicHistory().filterNot { it.track.cacheKey == entry.track.cacheKey }
        val updated = (listOf(entry) + previous).take(MAX_HISTORY_ITEMS)
        saveArray(KEY_MUSIC_HISTORY, updated) { item ->
            item.track.toJson().put("searchedAt", item.searchedAtEpochMillis)
        }
        return updated
    }

    fun clearMusicHistory() {
        preferences.edit().remove(KEY_MUSIC_HISTORY).apply()
    }

    fun loadMusicPlaylists(): List<MusicPlaylist> = parseArray(KEY_MUSIC_PLAYLISTS) { json ->
        val tracksJson = json.optJSONArray("tracks") ?: JSONArray()
        MusicPlaylist(
            id = json.getString("id"),
            name = json.getString("name"),
            createdAtEpochMillis = json.optLong("createdAt", 0L),
            tracks = (0 until tracksJson.length()).mapNotNull { index ->
                runCatching { tracksJson.getJSONObject(index).toMusicTrack() }.getOrNull()
            }.distinctBy(MusicTrack::cacheKey),
        )
    }

    @Synchronized
    fun saveMusicPlaylists(playlists: List<MusicPlaylist>) {
        saveArray(KEY_MUSIC_PLAYLISTS, playlists.take(MAX_MUSIC_PLAYLISTS)) { playlist ->
            JSONObject()
                .put("id", playlist.id)
                .put("name", playlist.name)
                .put("createdAt", playlist.createdAtEpochMillis)
                .put(
                    "tracks",
                    JSONArray().apply {
                        playlist.tracks.take(MAX_TRACKS_PER_PLAYLIST).forEach { put(it.toJson()) }
                    },
                )
        }
    }

    fun loadDownloadTreeUri(): String? = preferences.getString(KEY_DOWNLOAD_TREE_URI, null)

    fun saveDownloadTreeUri(uri: String) {
        preferences.edit().putString(KEY_DOWNLOAD_TREE_URI, uri).apply()
    }

    fun clearDownloadTreeUri() {
        preferences.edit().remove(KEY_DOWNLOAD_TREE_URI).apply()
    }

    fun loadDesktopLyricsEnabled(): Boolean =
        preferences.getBoolean(KEY_DESKTOP_LYRICS_ENABLED, false)

    fun saveDesktopLyricsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_DESKTOP_LYRICS_ENABLED, enabled).apply()
    }

    fun loadMusicSearchSource(): String =
        preferences.getString(KEY_MUSIC_SEARCH_SOURCE, DEFAULT_MUSIC_SEARCH_SOURCE)
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotBlank)
            ?: DEFAULT_MUSIC_SEARCH_SOURCE

    fun saveMusicSearchSource(source: String) {
        preferences.edit().putString(KEY_MUSIC_SEARCH_SOURCE, source).apply()
    }

    private fun <T> parseArray(key: String, transform: (JSONObject) -> T): List<T> {
        val raw = preferences.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                runCatching { transform(array.getJSONObject(index)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun <T> saveArray(key: String, items: List<T>, transform: (T) -> JSONObject) {
        val array = JSONArray()
        items.forEach { array.put(transform(it)) }
        preferences.edit().putString(key, array.toString()).apply()
    }

    private fun saveQrHistory(items: List<QrHistoryEntry>) {
        saveArray(KEY_QR_HISTORY, items) { entry ->
            JSONObject()
                .put("parsedAt", entry.parsedAtEpochMillis)
                .put("text", entry.text)
                .put("webUrl", entry.webUrl ?: JSONObject.NULL)
                .put("security", entry.security.toJson())
        }
    }

    private fun JSONObject.toQrSecurityHistory(): QrSecurityHistory = QrSecurityHistory(
        status = runCatching {
            QrSecurityStatus.valueOf(optString("status", QrSecurityStatus.NOT_CHECKED.name))
        }.getOrDefault(QrSecurityStatus.NOT_CHECKED),
        checkedAtEpochMillis = optLong("checkedAt").takeIf { it > 0L },
        malicious = optInt("malicious").coerceAtLeast(0),
        suspicious = optInt("suspicious").coerceAtLeast(0),
        harmless = optInt("harmless").coerceAtLeast(0),
        undetected = optInt("undetected").coerceAtLeast(0),
    )

    private fun QrSecurityHistory.toJson(): JSONObject = JSONObject()
        .put("status", status.name)
        .put("checkedAt", checkedAtEpochMillis ?: JSONObject.NULL)
        .put("malicious", malicious)
        .put("suspicious", suspicious)
        .put("harmless", harmless)
        .put("undetected", undetected)

    private fun JSONObject.toMusicTrack(): MusicTrack = MusicTrack(
        id = getString("id"),
        source = getString("source"),
        name = getString("name"),
        artists = optJSONArray("artists")?.let { artists ->
            (0 until artists.length()).mapNotNull { index ->
                artists.optString(index).takeIf(String::isNotBlank)
            }
        }.orEmpty(),
        album = optString("album"),
        trackId = optString("trackId").takeIf(String::isNotBlank),
        lyricId = optString("lyricId").takeIf(String::isNotBlank),
        pictureId = optString("pictureId").takeIf(String::isNotBlank),
    )

    private fun MusicTrack.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("source", source)
        .put("name", name)
        .put("artists", JSONArray(artists))
        .put("album", album)
        .put("trackId", trackId ?: "")
        .put("lyricId", lyricId ?: "")
        .put("pictureId", pictureId ?: "")

    private companion object {
        const val PREFERENCES_NAME = "pocket_toolbox_local"
        const val KEY_QR_HISTORY = "qr_history_v1"
        const val KEY_BILI_HISTORY = "bili_history_v1"
        const val KEY_DOWNLOAD_TREE_URI = "download_tree_uri_v1"
        const val KEY_MUSIC_HISTORY = "music_history_v1"
        const val KEY_MUSIC_PLAYLISTS = "music_playlists_v1"
        const val KEY_DESKTOP_LYRICS_ENABLED = "desktop_lyrics_enabled_v1"
        const val KEY_MUSIC_SEARCH_SOURCE = "music_search_source_v1"
        const val DEFAULT_MUSIC_SEARCH_SOURCE = "netease"
        const val MAX_HISTORY_ITEMS = 100
        const val MAX_MUSIC_PLAYLISTS = 50
        const val MAX_TRACKS_PER_PLAYLIST = 500
    }
}
