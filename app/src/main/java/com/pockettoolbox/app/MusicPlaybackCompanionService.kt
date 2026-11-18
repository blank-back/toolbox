@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.pockettoolbox.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.pockettoolbox.app.model.LocalMediaItem
import kotlin.math.roundToInt

object MusicPlaybackCommandBus {
    private var owner: Any? = null
    private var toggleCallback: (() -> Unit)? = null
    private var stopCallback: (() -> Unit)? = null
    private var previousCallback: (() -> Unit)? = null
    private var nextCallback: (() -> Unit)? = null
    private var seekCallback: ((Long) -> Unit)? = null
    private var setDesktopLyricsEnabledCallback: ((Boolean) -> Unit)? = null

    @Synchronized
    fun register(
        owner: Any,
        onToggle: () -> Unit,
        onStop: () -> Unit,
        onPrevious: () -> Unit,
        onNext: () -> Unit,
        onSeek: (Long) -> Unit,
        onSetDesktopLyricsEnabled: (Boolean) -> Unit,
    ) {
        this.owner = owner
        toggleCallback = onToggle
        stopCallback = onStop
        previousCallback = onPrevious
        nextCallback = onNext
        seekCallback = onSeek
        setDesktopLyricsEnabledCallback = onSetDesktopLyricsEnabled
    }

    @Synchronized
    fun unregister(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        toggleCallback = null
        stopCallback = null
        previousCallback = null
        nextCallback = null
        seekCallback = null
        setDesktopLyricsEnabledCallback = null
    }

    fun toggle() {
        synchronized(this) { toggleCallback }?.invoke()
    }

    fun stop() {
        synchronized(this) { stopCallback }?.invoke()
    }

    fun previous() {
        synchronized(this) { previousCallback }?.invoke()
    }

    fun next() {
        synchronized(this) { nextCallback }?.invoke()
    }

    fun seekTo(positionMillis: Long) {
        synchronized(this) { seekCallback }?.invoke(positionMillis)
    }

    fun setDesktopLyricsEnabled(enabled: Boolean) {
        synchronized(this) { setDesktopLyricsEnabledCallback }?.invoke(enabled)
    }
}

class MusicPlaybackCompanionService : Service() {
    private lateinit var notificationManager: NotificationManager
    private lateinit var windowManager: WindowManager
    private lateinit var mediaSession: MediaSession
    private var overlayView: View? = null
    private var overlayText: TextView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null

    private var title: String = "正在播放"
    private var artist: String = "未知歌手"
    private var album: String = ""
    private var lyric: String = "暂无歌词"
    private var isPlaying: Boolean = false
    private var desktopLyricsEnabled: Boolean = false
    private var hasPlaylistControls: Boolean = false
    private var positionMillis: Int = 0
    private var durationMillis: Int = 0
    private var playbackMode = PlaybackTarget.MUSIC
    private var localPlayer: ExoPlayer? = null
    private var localUri: String? = null
    private var localIsVideo = false
    private var localWidth = 0
    private var localHeight = 0
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            if (playbackMode == PlaybackTarget.LOCAL_MEDIA && localPlayer != null) {
                syncLocalPlayerState(refreshNotification = true)
                progressHandler.postDelayed(this, LOCAL_PROGRESS_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        notificationManager = getSystemService(NotificationManager::class.java)
        windowManager = getSystemService(WindowManager::class.java)
        mediaSession = MediaSession(this, "PocketToolboxMusic").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    togglePlayback(playOnly = true)
                }

                override fun onPause() {
                    pausePlayback()
                }

                override fun onStop() {
                    stopCurrentPlayback()
                }

                override fun onSkipToPrevious() {
                    if (playbackMode == PlaybackTarget.LOCAL_MEDIA) seekLocalBy(-LOCAL_SEEK_MILLIS)
                    else MusicPlaybackCommandBus.previous()
                }

                override fun onSkipToNext() {
                    if (playbackMode == PlaybackTarget.LOCAL_MEDIA) seekLocalBy(LOCAL_SEEK_MILLIS)
                    else MusicPlaybackCommandBus.next()
                }

                override fun onSeekTo(pos: Long) {
                    if (playbackMode == PlaybackTarget.LOCAL_MEDIA) localPlayer?.seekTo(pos)
                    else MusicPlaybackCommandBus.seekTo(pos)
                }
            })
            isActive = true
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && localPlayer == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_TOGGLE -> togglePlayback()
            ACTION_PREVIOUS -> if (playbackMode == PlaybackTarget.LOCAL_MEDIA) {
                seekLocalBy(-LOCAL_SEEK_MILLIS)
            } else MusicPlaybackCommandBus.previous()
            ACTION_NEXT -> if (playbackMode == PlaybackTarget.LOCAL_MEDIA) {
                seekLocalBy(LOCAL_SEEK_MILLIS)
            } else MusicPlaybackCommandBus.next()
            ACTION_PLAY_LOCAL -> readLocalRequest(intent)
            ACTION_REPLAY_LOCAL -> localPlayer?.let { player -> player.seekTo(0); player.play() }
            ACTION_SET_LOCAL_SPEED -> setLocalSpeed(intent.getFloatExtra(EXTRA_SPEED, 1f))
            ACTION_SEEK_LOCAL_TO -> localPlayer?.seekTo(intent.getLongExtra(EXTRA_SEEK_POSITION, 0L))
            ACTION_STOP_MUSIC -> {
                if (playbackMode == PlaybackTarget.MUSIC) {
                    stopAndRemoveUi()
                    return START_NOT_STICKY
                }
            }
            ACTION_STOP_LOCAL -> {
                if (playbackMode == PlaybackTarget.LOCAL_MEDIA) {
                    stopLocalPlayer(publishEmptyState = true)
                    stopAndRemoveUi()
                    return START_NOT_STICKY
                }
            }
            ACTION_STOP_PLAYBACK -> {
                stopCurrentPlayback()
                stopAndRemoveUi()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_DESKTOP_LYRICS -> {
                if (playbackMode == PlaybackTarget.LOCAL_MEDIA) return START_STICKY
                val requestedEnabled = !desktopLyricsEnabled
                if (!requestedEnabled || Settings.canDrawOverlays(this)) {
                    desktopLyricsEnabled = requestedEnabled
                    if (!requestedEnabled) removeOverlay()
                }
                MusicPlaybackCommandBus.setDesktopLyricsEnabled(requestedEnabled)
            }
            ACTION_UPDATE -> intent?.let(::readSnapshot)
        }

        refreshForeground()
        return if (playbackMode == PlaybackTarget.LOCAL_MEDIA) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        stopLocalPlayer(publishEmptyState = false)
        LocalMediaPlaybackBus.publishState(LocalPlaybackState())
        removeOverlay()
        mediaSession.isActive = false
        mediaSession.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun readSnapshot(intent: Intent) {
        stopLocalPlayer(publishEmptyState = true)
        playbackMode = PlaybackTarget.MUSIC
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "正在播放" }
        artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty().ifBlank { "未知歌手" }
        album = intent.getStringExtra(EXTRA_ALBUM).orEmpty()
        lyric = intent.getStringExtra(EXTRA_LYRIC).orEmpty().ifBlank { "暂无歌词" }
        isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
        desktopLyricsEnabled = intent.getBooleanExtra(EXTRA_DESKTOP_LYRICS, false)
        hasPlaylistControls = intent.getBooleanExtra(EXTRA_HAS_PLAYLIST_CONTROLS, false)
        positionMillis = intent.getIntExtra(EXTRA_POSITION, 0).coerceAtLeast(0)
        durationMillis = intent.getIntExtra(EXTRA_DURATION, 0).coerceAtLeast(0)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "媒体播放",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示在线音乐或本地媒体的播放状态与控制"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(PlaybackNavigationBus.EXTRA_TARGET, playbackMode.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleIntent = PendingIntent.getService(
            this,
            REQUEST_TOGGLE,
            Intent(this, MusicPlaybackCompanionService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val previousIntent = PendingIntent.getService(
            this,
            REQUEST_PREVIOUS,
            Intent(this, MusicPlaybackCompanionService::class.java).setAction(ACTION_PREVIOUS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nextIntent = PendingIntent.getService(
            this,
            REQUEST_NEXT,
            Intent(this, MusicPlaybackCompanionService::class.java).setAction(ACTION_NEXT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, MusicPlaybackCompanionService::class.java).setAction(ACTION_STOP_PLAYBACK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val desktopLyricsIntent = PendingIntent.getService(
            this,
            REQUEST_TOGGLE_DESKTOP_LYRICS,
            Intent(this, MusicPlaybackCompanionService::class.java).setAction(ACTION_TOGGLE_DESKTOP_LYRICS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleAction = Notification.Action.Builder(
            if (isPlaying) R.drawable.ic_notification_pause else R.drawable.ic_notification_play,
            if (isPlaying) "暂停" else "播放",
            toggleIntent,
        ).build()
        val previousAction = Notification.Action.Builder(
            R.drawable.ic_notification_previous,
            if (playbackMode == PlaybackTarget.LOCAL_MEDIA) "后退 10 秒" else "上一首",
            previousIntent,
        ).build()
        val nextAction = Notification.Action.Builder(
            R.drawable.ic_notification_next,
            if (playbackMode == PlaybackTarget.LOCAL_MEDIA) "快进 10 秒" else "下一首",
            nextIntent,
        ).build()
        val desktopLyricsAction = Notification.Action.Builder(
            if (desktopLyricsEnabled) {
                R.drawable.ic_notification_lyrics_on
            } else {
                R.drawable.ic_notification_lyrics_off
            },
            if (desktopLyricsEnabled) "关闭桌面歌词" else "开启桌面歌词",
            desktopLyricsIntent,
        ).build()
        val stopAction = Notification.Action.Builder(
            R.drawable.ic_notification_close,
            "停止",
            stopIntent,
        ).build()
        val details = if (playbackMode == PlaybackTarget.LOCAL_MEDIA) {
            listOf(if (localIsVideo) "本地视频" else "本地音频", artist).filter(String::isNotBlank).joinToString(" · ")
        } else {
            listOf(artist, album.takeIf(String::isNotBlank)).filterNotNull().joinToString(" · ")
        }
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_music)
            .setContentTitle(title)
            .setContentText(details)
            .setSubText(
                if (playbackMode == PlaybackTarget.LOCAL_MEDIA) {
                    "${formatClock(positionMillis)} / ${formatClock(durationMillis)}"
                } else lyric.replace('\n', ' ').take(MAX_NOTIFICATION_LYRIC_LENGTH),
            )
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setColor(Color.rgb(231, 111, 60))
            .apply {
                if (playbackMode == PlaybackTarget.LOCAL_MEDIA) {
                    addAction(previousAction)
                    addAction(toggleAction)
                    addAction(nextAction)
                    addAction(stopAction)
                } else {
                    if (hasPlaylistControls) addAction(previousAction)
                    addAction(toggleAction)
                    if (hasPlaylistControls) addAction(nextAction)
                    addAction(desktopLyricsAction)
                    addAction(stopAction)
                }
                setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(mediaSession.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2),
                )
                if (durationMillis > 0) setProgress(durationMillis, positionMillis.coerceAtMost(durationMillis), false)
            }
            .build()
    }

    private fun updateMediaSession() {
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMillis.toLong())
                .build(),
        )
        var actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO
        if (hasPlaylistControls || playbackMode == PlaybackTarget.LOCAL_MEDIA) {
            actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT
        }
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    positionMillis.toLong(),
                    if (isPlaying) 1f else 0f,
                )
                .build(),
        )
    }

    private fun readLocalRequest(intent: Intent) {
        val uriText = intent.getStringExtra(EXTRA_LOCAL_URI).orEmpty()
        if (uriText.isBlank()) return
        MusicPlaybackCommandBus.stop()
        stopLocalPlayer(publishEmptyState = false)
        playbackMode = PlaybackTarget.LOCAL_MEDIA
        localUri = uriText
        localIsVideo = intent.getBooleanExtra(EXTRA_LOCAL_IS_VIDEO, false)
        localWidth = intent.getIntExtra(EXTRA_LOCAL_WIDTH, 0).coerceAtLeast(0)
        localHeight = intent.getIntExtra(EXTRA_LOCAL_HEIGHT, 0).coerceAtLeast(0)
        title = intent.getStringExtra(EXTRA_LOCAL_TITLE).orEmpty().ifBlank { "本地媒体" }
        artist = "1.00×"
        album = ""
        lyric = ""
        desktopLyricsEnabled = false
        hasPlaylistControls = false
        removeOverlay()

        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(if (localIsVideo) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    syncLocalPlayerState(refreshNotification = true)
                }

                override fun onPlayerError(error: PlaybackException) {
                    syncLocalPlayerState(refreshNotification = true, error = error.message ?: "无法播放此媒体")
                }
            })
            val metadata = androidx.media3.common.MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(if (localIsVideo) "本地视频" else "本地音频")
                .build()
            setMediaItem(MediaItem.Builder().setUri(Uri.parse(uriText)).setMediaMetadata(metadata).build())
            prepare()
            playWhenReady = true
        }
        localPlayer = player
        LocalMediaPlaybackBus.publishPlayer(player)
        progressHandler.removeCallbacks(progressTicker)
        progressHandler.post(progressTicker)
        syncLocalPlayerState(refreshNotification = false)
    }

    private fun togglePlayback(playOnly: Boolean = false) {
        if (playbackMode == PlaybackTarget.LOCAL_MEDIA) {
            localPlayer?.let { player ->
                if (playOnly || !player.isPlaying) player.play() else player.pause()
            }
        } else if (!playOnly || !isPlaying) {
            MusicPlaybackCommandBus.toggle()
        }
    }

    private fun pausePlayback() {
        if (playbackMode == PlaybackTarget.LOCAL_MEDIA) localPlayer?.pause()
        else if (isPlaying) MusicPlaybackCommandBus.toggle()
    }

    private fun stopCurrentPlayback() {
        if (playbackMode == PlaybackTarget.LOCAL_MEDIA) stopLocalPlayer(publishEmptyState = true)
        else MusicPlaybackCommandBus.stop()
    }

    private fun seekLocalBy(deltaMillis: Long) {
        localPlayer?.let { player ->
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: Long.MAX_VALUE
            player.seekTo((player.currentPosition + deltaMillis).coerceIn(0L, duration))
        }
    }

    private fun setLocalSpeed(speed: Float) {
        localPlayer?.setPlaybackSpeed(speed.coerceIn(0.25f, 3f))
        syncLocalPlayerState(refreshNotification = true)
    }

    private fun syncLocalPlayerState(refreshNotification: Boolean, error: String? = null) {
        val player = localPlayer ?: return
        val safeDuration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val speed = player.playbackParameters.speed
        isPlaying = player.isPlaying
        positionMillis = player.currentPosition.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        durationMillis = safeDuration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        artist = "${"%.2f".format(speed)}×"
        LocalMediaPlaybackBus.publishState(
            LocalPlaybackState(
                uri = localUri,
                title = title,
                isVideo = localIsVideo,
                width = localWidth,
                height = localHeight,
                isPlaying = isPlaying,
                positionMillis = player.currentPosition.coerceAtLeast(0L),
                durationMillis = safeDuration,
                speed = speed,
                error = error,
            ),
        )
        if (refreshNotification && ::notificationManager.isInitialized) {
            updateMediaSession()
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun stopLocalPlayer(publishEmptyState: Boolean) {
        progressHandler.removeCallbacks(progressTicker)
        val player = localPlayer
        localPlayer = null
        player?.release()
        localUri = null
        LocalMediaPlaybackBus.publishPlayer(null)
        if (publishEmptyState) LocalMediaPlaybackBus.publishState(LocalPlaybackState())
    }

    private fun refreshForeground() {
        updateMediaSession()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (playbackMode == PlaybackTarget.MUSIC) updateOverlay() else removeOverlay()
    }

    private fun formatClock(milliseconds: Int): String {
        val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun updateOverlay() {
        if (!desktopLyricsEnabled || !Settings.canDrawOverlays(this)) {
            removeOverlay()
            return
        }
        val existing = overlayView
        if (existing == null) {
            addOverlay()
        }
        overlayText?.text = lyric
    }

    private fun addOverlay() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp, 9.dp, 8.dp, 9.dp)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16.dp.toFloat()
                setColor(Color.argb(205, 28, 28, 25))
                setStroke(1.dp, Color.argb(150, 255, 255, 255))
            }
        }
        val lyricTextView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            maxLines = 3
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(this).apply {
            this.text = "×"
            contentDescription = "关闭桌面歌词"
            setTextColor(Color.LTGRAY)
            textSize = 21f
            gravity = Gravity.CENTER
            setPadding(12.dp, 2.dp, 8.dp, 2.dp)
            setOnClickListener {
                desktopLyricsEnabled = false
                removeOverlay()
                MusicPlaybackCommandBus.setDesktopLyricsEnabled(false)
            }
        }
        root.addView(lyricTextView)
        root.addView(close)

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.9f).roundToInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels * 0.05f).roundToInt()
            y = 96.dp
        }
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        lyricTextView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).roundToInt()
                    params.y = startY + (event.rawY - touchY).roundToInt()
                    runCatching { windowManager.updateViewLayout(root, params) }
                    true
                }
                else -> false
            }
        }
        runCatching { windowManager.addView(root, params) }
            .onSuccess {
                overlayView = root
                overlayText = lyricTextView
                overlayLayoutParams = params
            }
    }

    private fun removeOverlay() {
        overlayView?.let { view -> runCatching { windowManager.removeView(view) } }
        overlayView = null
        overlayText = null
        overlayLayoutParams = null
    }

    private fun stopAndRemoveUi() {
        removeOverlay()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "music_playback"
        private const val NOTIFICATION_ID = 3201
        private const val REQUEST_OPEN_APP = 3202
        private const val REQUEST_TOGGLE = 3203
        private const val REQUEST_STOP = 3204
        private const val REQUEST_TOGGLE_DESKTOP_LYRICS = 3205
        private const val REQUEST_PREVIOUS = 3206
        private const val REQUEST_NEXT = 3207
        private const val MAX_NOTIFICATION_LYRIC_LENGTH = 80
        private const val LOCAL_SEEK_MILLIS = 10_000L
        private const val LOCAL_PROGRESS_INTERVAL_MS = 500L

        private const val ACTION_UPDATE = "com.pockettoolbox.app.music.UPDATE"
        private const val ACTION_TOGGLE = "com.pockettoolbox.app.music.TOGGLE"
        private const val ACTION_PREVIOUS = "com.pockettoolbox.app.music.PREVIOUS"
        private const val ACTION_NEXT = "com.pockettoolbox.app.music.NEXT"
        private const val ACTION_STOP_PLAYBACK = "com.pockettoolbox.app.music.STOP"
        private const val ACTION_TOGGLE_DESKTOP_LYRICS = "com.pockettoolbox.app.music.TOGGLE_DESKTOP_LYRICS"
        private const val ACTION_PLAY_LOCAL = "com.pockettoolbox.app.local.PLAY"
        private const val ACTION_REPLAY_LOCAL = "com.pockettoolbox.app.local.REPLAY"
        private const val ACTION_SET_LOCAL_SPEED = "com.pockettoolbox.app.local.SET_SPEED"
        private const val ACTION_SEEK_LOCAL_TO = "com.pockettoolbox.app.local.SEEK_TO"
        private const val ACTION_STOP_LOCAL = "com.pockettoolbox.app.local.STOP"
        private const val ACTION_STOP_MUSIC = "com.pockettoolbox.app.music.STOP_MODE"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ARTIST = "artist"
        private const val EXTRA_ALBUM = "album"
        private const val EXTRA_LYRIC = "lyric"
        private const val EXTRA_IS_PLAYING = "is_playing"
        private const val EXTRA_DESKTOP_LYRICS = "desktop_lyrics"
        private const val EXTRA_HAS_PLAYLIST_CONTROLS = "has_playlist_controls"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_LOCAL_URI = "local_uri"
        private const val EXTRA_LOCAL_TITLE = "local_title"
        private const val EXTRA_LOCAL_IS_VIDEO = "local_is_video"
        private const val EXTRA_LOCAL_WIDTH = "local_width"
        private const val EXTRA_LOCAL_HEIGHT = "local_height"
        private const val EXTRA_SPEED = "speed"
        private const val EXTRA_SEEK_POSITION = "seek_position"

        @Volatile
        private var isRunning = false

        fun update(
            context: Context,
            title: String,
            artist: String,
            album: String,
            lyric: String,
            isPlaying: Boolean,
            desktopLyricsEnabled: Boolean,
            hasPlaylistControls: Boolean,
            positionMillis: Int,
            durationMillis: Int,
        ) {
            val intent = Intent(context, MusicPlaybackCompanionService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_ARTIST, artist)
                .putExtra(EXTRA_ALBUM, album)
                .putExtra(EXTRA_LYRIC, lyric)
                .putExtra(EXTRA_IS_PLAYING, isPlaying)
                .putExtra(EXTRA_DESKTOP_LYRICS, desktopLyricsEnabled)
                .putExtra(EXTRA_HAS_PLAYLIST_CONTROLS, hasPlaylistControls)
                .putExtra(EXTRA_POSITION, positionMillis)
                .putExtra(EXTRA_DURATION, durationMillis)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isRunning) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, MusicPlaybackCompanionService::class.java).setAction(ACTION_STOP_MUSIC),
                )
            }
        }

        fun playLocal(context: Context, item: LocalMediaItem) {
            val intent = Intent(context, MusicPlaybackCompanionService::class.java)
                .setAction(ACTION_PLAY_LOCAL)
                .putExtra(EXTRA_LOCAL_URI, item.uri)
                .putExtra(EXTRA_LOCAL_TITLE, item.name)
                .putExtra(EXTRA_LOCAL_IS_VIDEO, item.isVideo)
                .putExtra(EXTRA_LOCAL_WIDTH, item.width)
                .putExtra(EXTRA_LOCAL_HEIGHT, item.height)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isRunning) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun toggleLocal(context: Context) = sendLocalCommand(context, ACTION_TOGGLE)

        fun replayLocal(context: Context) = sendLocalCommand(context, ACTION_REPLAY_LOCAL)

        fun seekLocalTo(context: Context, positionMillis: Long) {
            sendLocalCommand(
                context,
                ACTION_SEEK_LOCAL_TO,
                Intent().putExtra(EXTRA_SEEK_POSITION, positionMillis.coerceAtLeast(0L)),
            )
        }

        fun setLocalSpeed(context: Context, speed: Float) {
            sendLocalCommand(
                context,
                ACTION_SET_LOCAL_SPEED,
                Intent().putExtra(EXTRA_SPEED, speed.coerceIn(0.25f, 3f)),
            )
        }

        fun seekLocalBy(context: Context, forward: Boolean) {
            sendLocalCommand(context, if (forward) ACTION_NEXT else ACTION_PREVIOUS)
        }

        fun stopLocal(context: Context) = sendLocalCommand(context, ACTION_STOP_LOCAL)

        private fun sendLocalCommand(context: Context, action: String, extras: Intent? = null) {
            if (!isRunning) return
            val intent = Intent(context, MusicPlaybackCompanionService::class.java).setAction(action)
            extras?.extras?.let(intent::putExtras)
            runCatching { context.startService(intent) }
        }
    }
}
