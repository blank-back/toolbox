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
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.pockettoolbox.app.data.GameExtractionRepository
import com.pockettoolbox.app.data.GameExtractionFailure
import com.pockettoolbox.app.data.GameInputFile
import com.pockettoolbox.app.data.GameInputKind
import com.pockettoolbox.app.data.KirikiriArchiveInput
import com.pockettoolbox.app.data.KirikiriInspection
import com.pockettoolbox.app.data.KirikiriRepository
import com.pockettoolbox.app.data.UnityExtractionSelection
import com.pockettoolbox.app.data.VisualNovelArchiveFormat
import com.pockettoolbox.app.data.VisualNovelArchiveInput
import com.pockettoolbox.app.data.VisualNovelArchiveInspection
import com.pockettoolbox.app.data.VisualNovelArchiveRepository
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GameExtractionJobPhase { IDLE, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class GameExtractionJobState(
    val taskId: String? = null,
    val title: String = "",
    val message: String = "",
    val phase: GameExtractionJobPhase = GameExtractionJobPhase.IDLE,
    val skippedCount: Int = 0,
    val failureRecords: List<GameExtractionFailure> = emptyList(),
) {
    val isRunning: Boolean get() = phase == GameExtractionJobPhase.RUNNING
    val isFinished: Boolean
        get() = phase == GameExtractionJobPhase.SUCCEEDED ||
            phase == GameExtractionJobPhase.FAILED ||
            phase == GameExtractionJobPhase.CANCELLED
}

object GameExtractionJobBus {
    private val mutableState = MutableStateFlow(GameExtractionJobState())
    val state: StateFlow<GameExtractionJobState> = mutableState.asStateFlow()

    internal fun started(taskId: String, title: String) {
        mutableState.value = GameExtractionJobState(taskId, title, "正在准备后台任务…", GameExtractionJobPhase.RUNNING)
    }

    internal fun progress(taskId: String, title: String, message: String) {
        if (mutableState.value.taskId != taskId) return
        mutableState.value = GameExtractionJobState(taskId, title, message, GameExtractionJobPhase.RUNNING)
    }

    internal fun finished(
        taskId: String,
        title: String,
        message: String,
        phase: GameExtractionJobPhase,
        skippedCount: Int = 0,
        failureRecords: List<GameExtractionFailure> = emptyList(),
    ) {
        if (mutableState.value.taskId != taskId) return
        mutableState.value = GameExtractionJobState(
            taskId = taskId,
            title = title,
            message = message,
            phase = phase,
            skippedCount = skippedCount,
            failureRecords = failureRecords,
        )
    }

    fun dismissFinished() {
        if (mutableState.value.isFinished) mutableState.value = GameExtractionJobState()
    }
}

class GameExtractionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: NotificationManager
    private var activeJob: Job? = null
    private var cancellationMessage: String? = null
    private var activeTaskId: String? = null
    private var activeTitle: String = "游戏资源处理"
    private var lastNotificationUpdate = 0L
    private var taskFinished = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelActiveTask("用户已取消任务")
            return START_NOT_STICKY
        }
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (activeJob?.isActive == true) return START_NOT_STICKY

        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: UUID.randomUUID().toString()
        val title = when (intent.action) {
            ACTION_EXTRACT_RPG -> "RPG Maker 资源解包"
            ACTION_EXTRACT_UNITY -> "Unity 资源导出"
            ACTION_EXTRACT_KIRIKIRI -> "KRKR/吉里吉里 XP3 解包"
            ACTION_EXTRACT_VISUAL_NOVEL -> "Visual Novel 资源解包"
            else -> "游戏资源处理"
        }
        activeTaskId = taskId
        activeTitle = title
        cancellationMessage = null
        lastNotificationUpdate = 0L
        taskFinished = false
        GameExtractionJobBus.started(taskId, title)
        startForegroundCompat(buildNotification(title, "正在准备后台任务…", running = true))
        acquireWakeLock()

        activeJob = serviceScope.launch {
            try {
                val summary = when (intent.action) {
                    ACTION_EXTRACT_RPG -> runRpgExtraction(intent, taskId, title)
                    ACTION_EXTRACT_UNITY -> runUnityExtraction(intent, taskId, title)
                    ACTION_EXTRACT_KIRIKIRI -> runKirikiriExtraction(intent, taskId, title)
                    ACTION_EXTRACT_VISUAL_NOVEL -> runVisualNovelExtraction(intent, taskId, title)
                    else -> error("未知的游戏资源处理任务")
                }
                val message = buildString {
                    append("已输出 ${summary.filesWritten} 个文件（${formatBytes(summary.bytesWritten)}）到 ${summary.outputDirectoryName}")
                    if (summary.existingFilesSkipped > 0) {
                        append("；已跳过 ${summary.existingFilesSkipped} 个同路径同名文件")
                    }
                    if (summary.skippedCount > 0) append("；已跳过 ${summary.skippedCount} 个失败条目")
                }
                val phase = if (summary.filesWritten == 0 && summary.skippedCount > 0) {
                    GameExtractionJobPhase.FAILED
                } else {
                    GameExtractionJobPhase.SUCCEEDED
                }
                finishTask(
                    taskId = taskId,
                    title = title,
                    message = message,
                    phase = phase,
                    skippedCount = summary.skippedCount,
                    failureRecords = summary.failureRecords,
                )
            } catch (cancelled: CancellationException) {
                val message = cancellationMessage ?: "任务已取消"
                finishTask(taskId, title, message, GameExtractionJobPhase.CANCELLED)
            } catch (error: Throwable) {
                finishTask(
                    taskId,
                    title,
                    error.message ?: "游戏资源处理失败",
                    GameExtractionJobPhase.FAILED,
                )
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancellationMessage = "已达到 Android 允许的后台处理时限，任务已停止"
        activeJob?.cancel()
        val taskId = activeTaskId
        if (taskId != null) {
            finishTask(taskId, activeTitle, requireNotNull(cancellationMessage), GameExtractionJobPhase.FAILED)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    override fun onDestroy() {
        activeJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun runRpgExtraction(intent: Intent, taskId: String, title: String) =
        GameExtractionRepository(applicationContext).let { repository ->
            val outputUri = requiredUri(intent, EXTRA_OUTPUT_URI)
            val installedPackageName = intent.getStringExtra(EXTRA_SOURCE_PACKAGE)
            if (installedPackageName != null) {
                publishProgress(taskId, title, "正在重新扫描已安装应用的 RPG Maker 资源…")
                return@let repository.extractRpgMakerInstalledApp(
                    packageName = installedPackageName,
                    outputTreeUri = outputUri,
                    keyHex = intent.getStringExtra(EXTRA_RPG_KEY).orEmpty(),
                ) { message -> publishProgress(taskId, title, message) }
            }
            val directoryUri = intent.getStringExtra(EXTRA_SOURCE_DIRECTORY_URI)?.let(Uri::parse)
            val inputs = if (directoryUri != null) {
                publishProgress(taskId, title, "正在重新扫描 RPG Maker 游戏目录…")
                repository.scanRpgMakerDirectory(directoryUri).files
            } else {
                restoreRpgInputs(intent)
            }
            repository.extractRpgMaker(
                inputs = inputs,
                outputTreeUri = outputUri,
                keyHex = intent.getStringExtra(EXTRA_RPG_KEY).orEmpty(),
            ) { message -> publishProgress(taskId, title, message) }
        }

    private suspend fun runUnityExtraction(intent: Intent, taskId: String, title: String) =
        GameExtractionRepository(applicationContext).let { repository ->
            val installedPackageName = intent.getStringExtra(EXTRA_SOURCE_PACKAGE)
            publishProgress(taskId, title, "正在重新确认 Unity 文件结构…")
            val inspection = when {
                installedPackageName != null -> repository.inspectInstalledUnityApp(installedPackageName)
                intent.getBooleanExtra(EXTRA_SOURCE_IS_DIRECTORY, false) ->
                    repository.inspectUnityDirectory(requiredUri(intent, EXTRA_SOURCE_URI))
                else -> repository.inspectUnity(requiredUri(intent, EXTRA_SOURCE_URI))
            }
            val selection = UnityExtractionSelection(
                monoAssemblies = intent.getBooleanExtra(EXTRA_UNITY_MONO, true),
                il2CppFiles = intent.getBooleanExtra(EXTRA_UNITY_IL2CPP, true),
                assets = intent.getBooleanExtra(EXTRA_UNITY_ASSETS, false),
                configs = intent.getBooleanExtra(EXTRA_UNITY_CONFIGS, true),
                decodedImages = intent.getBooleanExtra(EXTRA_UNITY_DECODED_IMAGES, true),
                decodedAudio = intent.getBooleanExtra(EXTRA_UNITY_DECODED_AUDIO, true),
                decodedVideo = intent.getBooleanExtra(EXTRA_UNITY_DECODED_VIDEO, true),
                decodedText = intent.getBooleanExtra(EXTRA_UNITY_DECODED_TEXT, true),
            )
            repository.extractUnity(
                inspection = inspection,
                outputTreeUri = requiredUri(intent, EXTRA_OUTPUT_URI),
                selection = selection,
            ) { message -> publishProgress(taskId, title, message) }
        }

    private suspend fun runKirikiriExtraction(intent: Intent, taskId: String, title: String) =
        KirikiriRepository(applicationContext).let { repository ->
            val selectedEntryKeys = readKirikiriSelection(intent)
            require(selectedEntryKeys.isNotEmpty()) { "后台任务没有收到需要导出的 XP3 条目" }
            val directoryUri = intent.getStringExtra(EXTRA_SOURCE_DIRECTORY_URI)?.let(Uri::parse)
            val serializedUris = intent.getStringArrayListExtra(EXTRA_KIRIKIRI_URIS).orEmpty()
            val archives = if (serializedUris.isNotEmpty()) {
                restoreKirikiriInputs(intent)
            } else if (directoryUri != null) {
                publishProgress(taskId, title, "正在重新扫描 KRKR/吉里吉里目录…")
                repository.inspectDirectory(directoryUri).archives
            } else {
                restoreKirikiriInputs(intent)
            }
            repository.extract(
                archives = archives,
                outputTreeUri = requiredUri(intent, EXTRA_OUTPUT_URI),
                selectedEntryKeys = selectedEntryKeys,
            ) { message -> publishProgress(taskId, title, message) }
        }

    private fun readKirikiriSelection(intent: Intent): Set<String> {
        val storedPath = intent.getStringExtra(EXTRA_KIRIKIRI_SELECTION_FILE)
            ?: error("后台任务缺少 XP3 条目选择清单")
        val selectionDirectory = File(cacheDir, KIRIKIRI_SELECTION_DIRECTORY).canonicalFile
        val selectionFile = File(storedPath).canonicalFile
        require(selectionFile.parentFile == selectionDirectory && selectionFile.name.endsWith(KIRIKIRI_SELECTION_SUFFIX)) {
            "XP3 条目选择清单路径无效"
        }
        require(selectionFile.isFile && selectionFile.length() <= MAX_KIRIKIRI_SELECTION_FILE_BYTES) {
            "XP3 条目选择清单不存在或大小异常"
        }
        return try {
            DataInputStream(selectionFile.inputStream().buffered()).use { input ->
                val count = input.readInt()
                require(count in 1..MAX_KIRIKIRI_SELECTION_COUNT) { "XP3 条目选择数量异常" }
                buildSet(count) {
                    repeat(count) {
                        val byteCount = input.readInt()
                        require(byteCount in 1..MAX_KIRIKIRI_SELECTION_KEY_BYTES) {
                            "XP3 条目选择标识长度异常"
                        }
                        val bytes = ByteArray(byteCount)
                        input.readFully(bytes)
                        add(String(bytes, Charsets.UTF_8))
                    }
                }.also { keys ->
                    require(keys.size == count) { "XP3 条目选择清单包含重复项" }
                    require(input.read() == -1) { "XP3 条目选择清单包含无效尾部数据" }
                }
            }
        } finally {
            selectionFile.delete()
        }
    }

    private fun restoreKirikiriInputs(intent: Intent): List<KirikiriArchiveInput> {
        val uris = intent.getStringArrayListExtra(EXTRA_KIRIKIRI_URIS).orEmpty()
        val names = intent.getStringArrayListExtra(EXTRA_KIRIKIRI_NAMES).orEmpty()
        val relativePaths = intent.getStringArrayListExtra(EXTRA_KIRIKIRI_RELATIVE_PATHS).orEmpty()
        val sizes = intent.getLongArrayExtra(EXTRA_KIRIKIRI_SIZES) ?: LongArray(0)
        require(uris.isNotEmpty()) { "后台任务缺少 XP3 输入文件" }
        return uris.mapIndexed { index, uri ->
            KirikiriArchiveInput(
                uri = uri,
                name = names.getOrNull(index) ?: Uri.parse(uri).lastPathSegment ?: "archive.xp3",
                sizeBytes = sizes.getOrElse(index) { -1L },
                relativePath = relativePaths.getOrNull(index) ?: names.getOrNull(index) ?: "archive.xp3",
            )
        }
    }

    private suspend fun runVisualNovelExtraction(intent: Intent, taskId: String, title: String) =
        VisualNovelArchiveRepository(applicationContext).let { repository ->
            val directoryUri = intent.getStringExtra(EXTRA_SOURCE_DIRECTORY_URI)?.let(Uri::parse)
            val archives = if (directoryUri != null) {
                publishProgress(taskId, title, "正在重新扫描 Visual Novel 游戏目录…")
                repository.inspectDirectory(directoryUri).archives
            } else {
                restoreVisualNovelInputs(intent)
            }
            repository.extract(
                archives = archives,
                outputTreeUri = requiredUri(intent, EXTRA_OUTPUT_URI),
            ) { message -> publishProgress(taskId, title, message) }
        }

    private fun restoreVisualNovelInputs(intent: Intent): List<VisualNovelArchiveInput> {
        val uris = intent.getStringArrayListExtra(EXTRA_VN_URIS).orEmpty()
        val names = intent.getStringArrayListExtra(EXTRA_VN_NAMES).orEmpty()
        val relativePaths = intent.getStringArrayListExtra(EXTRA_VN_RELATIVE_PATHS).orEmpty()
        val formats = intent.getStringArrayListExtra(EXTRA_VN_FORMATS).orEmpty()
        val sizes = intent.getLongArrayExtra(EXTRA_VN_SIZES) ?: LongArray(0)
        require(uris.isNotEmpty()) { "后台任务缺少 Visual Novel 档案" }
        return uris.mapIndexed { index, uri ->
            val formatName = formats.getOrNull(index) ?: error("后台任务缺少 Visual Novel 格式信息")
            VisualNovelArchiveInput(
                uri = uri,
                name = names.getOrNull(index) ?: Uri.parse(uri).lastPathSegment ?: "archive.bin",
                sizeBytes = sizes.getOrElse(index) { -1L },
                relativePath = relativePaths.getOrNull(index) ?: names.getOrNull(index) ?: "archive.bin",
                format = runCatching { VisualNovelArchiveFormat.valueOf(formatName) }
                    .getOrElse { error("后台任务包含未知 Visual Novel 格式") },
            )
        }
    }

    private fun restoreRpgInputs(intent: Intent): List<GameInputFile> {
        val uris = intent.getStringArrayListExtra(EXTRA_INPUT_URIS).orEmpty()
        val names = intent.getStringArrayListExtra(EXTRA_INPUT_NAMES).orEmpty()
        val kinds = intent.getStringArrayListExtra(EXTRA_INPUT_KINDS).orEmpty()
        val relativePaths = intent.getStringArrayListExtra(EXTRA_INPUT_RELATIVE_PATHS).orEmpty()
        val sizes = intent.getLongArrayExtra(EXTRA_INPUT_SIZES) ?: LongArray(0)
        require(uris.isNotEmpty()) { "没有可供后台处理的 RPG Maker 文件" }
        return uris.mapIndexed { index, uri ->
            GameInputFile(
                uri = uri,
                name = names.getOrNull(index) ?: Uri.parse(uri).lastPathSegment.orEmpty().ifBlank { "game_file" },
                sizeBytes = sizes.getOrElse(index) { -1L },
                kind = kinds.getOrNull(index)?.let { runCatching { GameInputKind.valueOf(it) }.getOrNull() }
                    ?: GameInputKind.UNKNOWN,
                relativePath = relativePaths.getOrNull(index) ?: names.getOrNull(index).orEmpty(),
            )
        }
    }

    private fun requiredUri(intent: Intent, key: String): Uri =
        intent.getStringExtra(key)?.let(Uri::parse) ?: error("后台任务缺少必要目录授权")

    private fun publishProgress(taskId: String, title: String, message: String) {
        GameExtractionJobBus.progress(taskId, title, message)
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdate >= NOTIFICATION_UPDATE_INTERVAL_MILLIS) {
            lastNotificationUpdate = now
            notificationManager.notify(NOTIFICATION_ID, buildNotification(title, message, running = true))
        }
    }

    @Synchronized
    private fun finishTask(
        taskId: String,
        title: String,
        message: String,
        phase: GameExtractionJobPhase,
        skippedCount: Int = 0,
        failureRecords: List<GameExtractionFailure> = emptyList(),
    ) {
        if (taskFinished) return
        taskFinished = true
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, message, running = false))
        stopForeground(STOP_FOREGROUND_DETACH)
        releaseWakeLock()
        activeJob = null
        activeTaskId = null
        stopSelf()
        GameExtractionJobBus.finished(taskId, title, message, phase, skippedCount, failureRecords)
    }

    private fun cancelActiveTask(message: String) {
        cancellationMessage = message
        activeJob?.cancel() ?: run {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "游戏资源处理",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示游戏资源解包、解密和导出的后台进度"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:game-extraction",
        ).apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
    }

    private fun buildNotification(title: String, message: String, running: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_extract)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openAppIntent)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(running)
            .setOngoing(running)
            .setAutoCancel(!running)
            .setShowWhen(!running)
            .setColor(Color.rgb(70, 102, 164))
        if (running && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (running) {
            val cancelIntent = PendingIntent.getService(
                this,
                REQUEST_CANCEL,
                Intent(this, GameExtractionService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setProgress(0, 0, true)
            builder.addAction(
                Notification.Action.Builder(R.drawable.ic_notification_close, "取消", cancelIntent).build(),
            )
        }
        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val ACTION_EXTRACT_RPG = "com.pockettoolbox.app.action.EXTRACT_RPG"
        private const val ACTION_EXTRACT_UNITY = "com.pockettoolbox.app.action.EXTRACT_UNITY"
        private const val ACTION_EXTRACT_KIRIKIRI = "com.pockettoolbox.app.action.EXTRACT_KIRIKIRI"
        private const val ACTION_EXTRACT_VISUAL_NOVEL = "com.pockettoolbox.app.action.EXTRACT_VISUAL_NOVEL"
        private const val ACTION_CANCEL = "com.pockettoolbox.app.action.CANCEL_GAME_EXTRACTION"
        private const val EXTRA_TASK_ID = "game_task_id"
        private const val EXTRA_OUTPUT_URI = "game_output_uri"
        private const val EXTRA_SOURCE_URI = "game_source_uri"
        private const val EXTRA_SOURCE_PACKAGE = "game_source_package"
        private const val EXTRA_SOURCE_DIRECTORY_URI = "game_source_directory_uri"
        private const val EXTRA_SOURCE_IS_DIRECTORY = "game_source_is_directory"
        private const val EXTRA_RPG_KEY = "game_rpg_key"
        private const val EXTRA_INPUT_URIS = "game_input_uris"
        private const val EXTRA_INPUT_NAMES = "game_input_names"
        private const val EXTRA_INPUT_SIZES = "game_input_sizes"
        private const val EXTRA_INPUT_KINDS = "game_input_kinds"
        private const val EXTRA_INPUT_RELATIVE_PATHS = "game_input_relative_paths"
        private const val EXTRA_UNITY_MONO = "game_unity_mono"
        private const val EXTRA_UNITY_IL2CPP = "game_unity_il2cpp"
        private const val EXTRA_UNITY_ASSETS = "game_unity_assets"
        private const val EXTRA_UNITY_CONFIGS = "game_unity_configs"
        private const val EXTRA_UNITY_DECODED_IMAGES = "game_unity_decoded_images"
        private const val EXTRA_UNITY_DECODED_AUDIO = "game_unity_decoded_audio"
        private const val EXTRA_UNITY_DECODED_VIDEO = "game_unity_decoded_video"
        private const val EXTRA_UNITY_DECODED_TEXT = "game_unity_decoded_text"
        private const val EXTRA_KIRIKIRI_URIS = "game_kirikiri_uris"
        private const val EXTRA_KIRIKIRI_NAMES = "game_kirikiri_names"
        private const val EXTRA_KIRIKIRI_SIZES = "game_kirikiri_sizes"
        private const val EXTRA_KIRIKIRI_RELATIVE_PATHS = "game_kirikiri_relative_paths"
        private const val EXTRA_KIRIKIRI_SELECTION_FILE = "game_kirikiri_selection_file"
        private const val EXTRA_VN_URIS = "game_vn_uris"
        private const val EXTRA_VN_NAMES = "game_vn_names"
        private const val EXTRA_VN_SIZES = "game_vn_sizes"
        private const val EXTRA_VN_RELATIVE_PATHS = "game_vn_relative_paths"
        private const val EXTRA_VN_FORMATS = "game_vn_formats"
        private const val NOTIFICATION_CHANNEL_ID = "game_extraction"
        private const val NOTIFICATION_ID = 1202
        private const val REQUEST_OPEN_APP = 12020
        private const val REQUEST_CANCEL = 12021
        private const val NOTIFICATION_UPDATE_INTERVAL_MILLIS = 750L
        private const val MAX_WAKE_LOCK_MILLIS = 6L * 60L * 60L * 1000L
        private const val KIRIKIRI_SELECTION_DIRECTORY = "game_extraction_jobs"
        private const val KIRIKIRI_SELECTION_SUFFIX = ".xp3-selection"
        private const val MAX_KIRIKIRI_SELECTION_COUNT = 1_000_000
        private const val MAX_KIRIKIRI_SELECTION_KEY_BYTES = 128 * 1024
        private const val MAX_KIRIKIRI_SELECTION_FILE_BYTES = 128L * 1024L * 1024L

        fun canShowProgressNotifications(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return false
            val channel = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
            return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
        }

        fun startRpg(
            context: Context,
            inputs: List<GameInputFile>,
            sourceDirectoryUri: String?,
            outputTreeUri: String,
            keyHex: String,
            installedPackageName: String? = null,
        ): Result<Unit> = runCatching {
            require(installedPackageName != null || sourceDirectoryUri != null || inputs.isNotEmpty()) {
                "请先选择 RPG Maker 文件、目录或已安装应用"
            }
            val serializedInputs = if (sourceDirectoryUri == null && installedPackageName == null) inputs else emptyList()
            val intent = Intent(context, GameExtractionService::class.java).apply {
                action = ACTION_EXTRACT_RPG
                putExtra(EXTRA_TASK_ID, UUID.randomUUID().toString())
                putExtra(EXTRA_OUTPUT_URI, outputTreeUri)
                if (sourceDirectoryUri != null) putExtra(EXTRA_SOURCE_DIRECTORY_URI, sourceDirectoryUri)
                if (installedPackageName != null) putExtra(EXTRA_SOURCE_PACKAGE, installedPackageName)
                putExtra(EXTRA_RPG_KEY, keyHex)
                putStringArrayListExtra(EXTRA_INPUT_URIS, ArrayList(serializedInputs.map { it.uri }))
                putStringArrayListExtra(EXTRA_INPUT_NAMES, ArrayList(serializedInputs.map { it.name }))
                putExtra(EXTRA_INPUT_SIZES, serializedInputs.map { it.sizeBytes }.toLongArray())
                putStringArrayListExtra(EXTRA_INPUT_KINDS, ArrayList(serializedInputs.map { it.kind.name }))
                putStringArrayListExtra(EXTRA_INPUT_RELATIVE_PATHS, ArrayList(serializedInputs.map { it.relativePath }))
            }
            context.startForegroundService(intent)
        }

        fun startUnity(
            context: Context,
            sourceUri: String,
            sourceIsDirectory: Boolean,
            outputTreeUri: String,
            selection: UnityExtractionSelection,
            installedPackageName: String? = null,
        ): Result<Unit> = runCatching {
            require(selection.hasAny) { "请至少勾选一类要导出的文件" }
            val intent = Intent(context, GameExtractionService::class.java).apply {
                action = ACTION_EXTRACT_UNITY
                putExtra(EXTRA_TASK_ID, UUID.randomUUID().toString())
                putExtra(EXTRA_SOURCE_URI, sourceUri)
                if (installedPackageName != null) putExtra(EXTRA_SOURCE_PACKAGE, installedPackageName)
                putExtra(EXTRA_SOURCE_IS_DIRECTORY, sourceIsDirectory)
                putExtra(EXTRA_OUTPUT_URI, outputTreeUri)
                putExtra(EXTRA_UNITY_MONO, selection.monoAssemblies)
                putExtra(EXTRA_UNITY_IL2CPP, selection.il2CppFiles)
                putExtra(EXTRA_UNITY_ASSETS, selection.assets)
                putExtra(EXTRA_UNITY_CONFIGS, selection.configs)
                putExtra(EXTRA_UNITY_DECODED_IMAGES, selection.decodedImages)
                putExtra(EXTRA_UNITY_DECODED_AUDIO, selection.decodedAudio)
                putExtra(EXTRA_UNITY_DECODED_VIDEO, selection.decodedVideo)
                putExtra(EXTRA_UNITY_DECODED_TEXT, selection.decodedText)
            }
            context.startForegroundService(intent)
        }

        fun startKirikiri(
            context: Context,
            inspection: KirikiriInspection,
            outputTreeUri: String,
            selectedEntryKeys: Set<String>,
        ): Result<Unit> = runCatching {
            val extractableKeys = inspection.entries
                .asSequence()
                .filter { it.canExtract }
                .map { it.selectionKey }
                .toSet()
            require(selectedEntryKeys.isNotEmpty()) { "请至少选择一个可导出的 XP3 条目" }
            require(selectedEntryKeys.all(extractableKeys::contains)) { "所选内容包含无法可靠导出的 XP3 条目" }
            require(selectedEntryKeys.size <= MAX_KIRIKIRI_SELECTION_COUNT) { "所选 XP3 条目数量过多" }
            val taskId = UUID.randomUUID().toString()
            val selectionDirectory = File(context.cacheDir, KIRIKIRI_SELECTION_DIRECTORY).apply {
                require(isDirectory || mkdirs()) { "无法创建 XP3 后台任务目录" }
            }
            val selectionFile = File(selectionDirectory, taskId + KIRIKIRI_SELECTION_SUFFIX)
            try {
                DataOutputStream(selectionFile.outputStream().buffered()).use { output ->
                    output.writeInt(selectedEntryKeys.size)
                    selectedEntryKeys.forEach { key ->
                        val bytes = key.toByteArray(Charsets.UTF_8)
                        require(bytes.isNotEmpty() && bytes.size <= MAX_KIRIKIRI_SELECTION_KEY_BYTES) {
                            "XP3 条目选择标识长度异常"
                        }
                        output.writeInt(bytes.size)
                        output.write(bytes)
                    }
                }
                require(selectionFile.length() <= MAX_KIRIKIRI_SELECTION_FILE_BYTES) {
                    "XP3 条目选择清单过大"
                }
                val serialized = inspection.archives
                val intent = Intent(context, GameExtractionService::class.java).apply {
                    action = ACTION_EXTRACT_KIRIKIRI
                    putExtra(EXTRA_TASK_ID, taskId)
                    putExtra(EXTRA_OUTPUT_URI, outputTreeUri)
                    inspection.sourceDirectoryUri?.let { putExtra(EXTRA_SOURCE_DIRECTORY_URI, it) }
                    putStringArrayListExtra(EXTRA_KIRIKIRI_URIS, ArrayList(serialized.map { it.uri }))
                    putStringArrayListExtra(EXTRA_KIRIKIRI_NAMES, ArrayList(serialized.map { it.name }))
                    putExtra(EXTRA_KIRIKIRI_SIZES, serialized.map { it.sizeBytes }.toLongArray())
                    putExtra(EXTRA_KIRIKIRI_SELECTION_FILE, selectionFile.absolutePath)
                    putStringArrayListExtra(
                        EXTRA_KIRIKIRI_RELATIVE_PATHS,
                        ArrayList(serialized.map { it.relativePath }),
                    )
                }
                context.startForegroundService(intent)
            } catch (error: Throwable) {
                selectionFile.delete()
                throw error
            }
        }

        fun startVisualNovel(
            context: Context,
            inspection: VisualNovelArchiveInspection,
            outputTreeUri: String,
        ): Result<Unit> = runCatching {
            require(inspection.canExtract) {
                "所选档案中没有可可靠导出的条目"
            }
            val serialized = if (inspection.sourceDirectoryUri == null) inspection.archives else emptyList()
            val intent = Intent(context, GameExtractionService::class.java).apply {
                action = ACTION_EXTRACT_VISUAL_NOVEL
                putExtra(EXTRA_TASK_ID, UUID.randomUUID().toString())
                putExtra(EXTRA_OUTPUT_URI, outputTreeUri)
                inspection.sourceDirectoryUri?.let { putExtra(EXTRA_SOURCE_DIRECTORY_URI, it) }
                putStringArrayListExtra(EXTRA_VN_URIS, ArrayList(serialized.map { it.uri }))
                putStringArrayListExtra(EXTRA_VN_NAMES, ArrayList(serialized.map { it.name }))
                putExtra(EXTRA_VN_SIZES, serialized.map { it.sizeBytes }.toLongArray())
                putStringArrayListExtra(EXTRA_VN_RELATIVE_PATHS, ArrayList(serialized.map { it.relativePath }))
                putStringArrayListExtra(EXTRA_VN_FORMATS, ArrayList(serialized.map { it.format.name }))
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, GameExtractionService::class.java).setAction(ACTION_CANCEL))
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes < 0L -> "未知大小"
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> "%.1f KiB".format(bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
