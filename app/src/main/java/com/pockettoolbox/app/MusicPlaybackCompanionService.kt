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
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
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

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        notificationManager = getSystemService(NotificationManager::class.java)
        windowManager = getSystemService(WindowManager::class.java)
        mediaSession = MediaSession(this, "PocketToolboxMusic").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    if (!this@MusicPlaybackCompanionService.isPlaying) MusicPlaybackCommandBus.toggle()
                }

                override fun onPause() {
                    if (this@MusicPlaybackCompanionService.isPlaying) MusicPlaybackCommandBus.toggle()
                }

                override fun onStop() {
                    MusicPlaybackCommandBus.stop()
                }

                override fun onSkipToPrevious() {
                    MusicPlaybackCommandBus.previous()
                }

                override fun onSkipToNext() {
                    MusicPlaybackCommandBus.next()
                }

                override fun onSeekTo(pos: Long) {
                    MusicPlaybackCommandBus.seekTo(pos)
                }
            })
            isActive = true
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> MusicPlaybackCommandBus.toggle()
            ACTION_PREVIOUS -> MusicPlaybackCommandBus.previous()
            ACTION_NEXT -> MusicPlaybackCommandBus.next()
            ACTION_STOP_PLAYBACK -> {
                MusicPlaybackCommandBus.stop()
                stopAndRemoveUi()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_DESKTOP_LYRICS -> {
                val requestedEnabled = !desktopLyricsEnabled
                if (!requestedEnabled || Settings.canDrawOverlays(this)) {
                    desktopLyricsEnabled = requestedEnabled
                    if (!requestedEnabled) removeOverlay()
                }
                MusicPlaybackCommandBus.setDesktopLyricsEnabled(requestedEnabled)
            }
            ACTION_UPDATE -> intent?.let(::readSnapshot)
        }

        updateMediaSession()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        updateOverlay()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
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
            "音乐播放",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示当前歌曲、歌词和播放控制"
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
            "上一首",
            previousIntent,
        ).build()
        val nextAction = Notification.Action.Builder(
            R.drawable.ic_notification_next,
            "下一首",
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
        val details = listOf(artist, album.takeIf(String::isNotBlank)).filterNotNull().joinToString(" · ")
        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_music)
            .setContentTitle(title)
            .setContentText(details)
            .setSubText(lyric.replace('\n', ' ').take(MAX_NOTIFICATION_LYRIC_LENGTH))
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setColor(Color.rgb(231, 111, 60))
            .apply {
                if (hasPlaylistControls) addAction(previousAction)
                addAction(toggleAction)
                if (hasPlaylistControls) addAction(nextAction)
                addAction(desktopLyricsAction)
                addAction(stopAction)
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
        if (hasPlaylistControls) {
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

        private const val ACTION_UPDATE = "com.pockettoolbox.app.music.UPDATE"
        private const val ACTION_TOGGLE = "com.pockettoolbox.app.music.TOGGLE"
        private const val ACTION_PREVIOUS = "com.pockettoolbox.app.music.PREVIOUS"
        private const val ACTION_NEXT = "com.pockettoolbox.app.music.NEXT"
        private const val ACTION_STOP_PLAYBACK = "com.pockettoolbox.app.music.STOP"
        private const val ACTION_TOGGLE_DESKTOP_LYRICS = "com.pockettoolbox.app.music.TOGGLE_DESKTOP_LYRICS"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ARTIST = "artist"
        private const val EXTRA_ALBUM = "album"
        private const val EXTRA_LYRIC = "lyric"
        private const val EXTRA_IS_PLAYING = "is_playing"
        private const val EXTRA_DESKTOP_LYRICS = "desktop_lyrics"
        private const val EXTRA_HAS_PLAYLIST_CONTROLS = "has_playlist_controls"
        private const val EXTRA_POSITION = "position"
        private const val EXTRA_DURATION = "duration"

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
            runCatching { context.stopService(Intent(context, MusicPlaybackCompanionService::class.java)) }
        }
    }
}
