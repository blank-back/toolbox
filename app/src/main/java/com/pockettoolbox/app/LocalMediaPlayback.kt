package com.pockettoolbox.app

import android.content.Intent
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackTarget { MUSIC, LOCAL_MEDIA }

data class LocalPlaybackState(
    val uri: String? = null,
    val title: String = "未选择媒体",
    val isVideo: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val isPlaying: Boolean = false,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    val speed: Float = 1f,
    val error: String? = null,
)

object LocalMediaPlaybackBus {
    private val _state = MutableStateFlow(LocalPlaybackState())
    val state: StateFlow<LocalPlaybackState> = _state.asStateFlow()

    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    internal fun publishPlayer(player: Player?) {
        _player.value = player
    }

    internal fun publishState(state: LocalPlaybackState) {
        _state.value = state
    }
}

object PlaybackNavigationBus {
    const val EXTRA_TARGET = "com.pockettoolbox.app.extra.PLAYBACK_TARGET"
    private val _target = MutableStateFlow<PlaybackTarget?>(null)
    val target: StateFlow<PlaybackTarget?> = _target.asStateFlow()

    fun readIntent(intent: Intent?) {
        val value = intent?.getStringExtra(EXTRA_TARGET) ?: return
        _target.value = runCatching { PlaybackTarget.valueOf(value) }.getOrNull()
    }

    fun consume(target: PlaybackTarget) {
        if (_target.value == target) _target.value = null
    }
}
