package com.pockettoolbox.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettoolbox.app.GameExtractionJobBus
import com.pockettoolbox.app.GameExtractionJobPhase
import com.pockettoolbox.app.GameExtractionJobState
import com.pockettoolbox.app.GameExtractionService
import com.pockettoolbox.app.data.GameExtractionRepository
import com.pockettoolbox.app.data.GameInputFile
import com.pockettoolbox.app.data.GameInputKind
import com.pockettoolbox.app.data.InstalledGameApp
import com.pockettoolbox.app.data.KirikiriInspection
import com.pockettoolbox.app.data.KirikiriRepository
import com.pockettoolbox.app.data.LocalPreferences
import com.pockettoolbox.app.data.RpgMakerDirectoryScan
import com.pockettoolbox.app.data.RpgAudioVolumeInjector
import com.pockettoolbox.app.data.RpgAudioVolumeEngine
import com.pockettoolbox.app.data.RpgAudioVolumeSettings
import com.pockettoolbox.app.data.UnityBackend
import com.pockettoolbox.app.data.UnityExtractionSelection
import com.pockettoolbox.app.data.UnityInspection
import com.pockettoolbox.app.data.VisualNovelArchiveInspection
import com.pockettoolbox.app.data.VisualNovelArchiveRepository
import com.pockettoolbox.app.ui.theme.Blue
import com.pockettoolbox.app.ui.theme.BluePaper
import com.pockettoolbox.app.ui.theme.Green
import com.pockettoolbox.app.ui.theme.Ink
import com.pockettoolbox.app.ui.theme.Line
import com.pockettoolbox.app.ui.theme.Muted
import com.pockettoolbox.app.ui.theme.Orange
import com.pockettoolbox.app.ui.theme.Sage
import com.pockettoolbox.app.ui.theme.SurfacePaper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class DetectedGameType { RPG_MAKER, UNITY, KIRIKIRI, VISUAL_NOVEL_ARCHIVE }

private const val KIRIKIRI_PAGE_SIZE = 10
private const val KIRIKIRI_COLLAPSED_ENTRY_COUNT = 4

@Stable
class GameExtractionState(context: Context) {
    private val appContext = context.applicationContext
    private val repository = GameExtractionRepository(appContext)
    private val kirikiriRepository = KirikiriRepository(appContext)
    private val visualNovelRepository = VisualNovelArchiveRepository(appContext)
    private val rpgAudioVolumeInjector = RpgAudioVolumeInjector(appContext)
    private val preferences = LocalPreferences(appContext)

    var outputTreeUri by mutableStateOf(preferences.loadGameExtractionTreeUri())
        private set
    var gameExtractionDisclaimerAccepted by mutableStateOf(
        preferences.loadGameExtractionDisclaimerAccepted(),
    )
        private set
    var rpgFiles by mutableStateOf<List<GameInputFile>>(emptyList())
        private set
    var rpgInputLabel by mutableStateOf("尚未选择")
        private set
    var rpgDirectoryUri by mutableStateOf<String?>(null)
        private set
    var rpgInstalledPackageName by mutableStateOf<String?>(null)
        private set
    var rpgKeyHex by mutableStateOf("")
        private set
    var rpgKeySource by mutableStateOf<String?>(null)
        private set
    var rpgBgmVolume by mutableStateOf("100")
        private set
    var rpgBgsVolume by mutableStateOf("100")
        private set
    var rpgSeVolume by mutableStateOf("100")
        private set
    var rpgMeVolume by mutableStateOf("100")
        private set
    var rpgSkipStartupSplash by mutableStateOf(false)
        private set
    var rpgAudioVolumeEngine by mutableStateOf<RpgAudioVolumeEngine?>(null)
        private set
    var unityInspection by mutableStateOf<UnityInspection?>(null)
        private set
    var installedGameApps by mutableStateOf<List<InstalledGameApp>>(emptyList())
        private set
    var unitySelection by mutableStateOf(UnityExtractionSelection())
        private set
    var kirikiriInspection by mutableStateOf<KirikiriInspection?>(null)
        private set
    var selectedKirikiriEntryKeys by mutableStateOf<Set<String>>(emptySet())
        private set
    var visualNovelInspection by mutableStateOf<VisualNovelArchiveInspection?>(null)
        private set
    var detectedGameType by mutableStateOf<DetectedGameType?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var isError by mutableStateOf(false)
        private set

    val sourceOutputTreeUri: String?
        get() = when (detectedGameType) {
            DetectedGameType.RPG_MAKER -> rpgDirectoryUri
            DetectedGameType.UNITY -> unityInspection?.takeIf { it.isDirectory }?.input?.uri
            DetectedGameType.KIRIKIRI -> kirikiriInspection?.sourceDirectoryUri
            DetectedGameType.VISUAL_NOVEL_ARCHIVE -> visualNovelInspection?.sourceDirectoryUri
            null -> null
        }

    val effectiveOutputTreeUri: String?
        get() = outputTreeUri ?: sourceOutputTreeUri

    val usesSourceDirectoryAsOutput: Boolean
        get() = outputTreeUri == null && sourceOutputTreeUri != null

    val outputPickerInitialUri: Uri?
        get() {
            effectiveOutputTreeUri?.let { return Uri.parse(it) }
            val sourceUri = when (detectedGameType) {
                DetectedGameType.RPG_MAKER -> rpgFiles.firstOrNull()?.uri
                DetectedGameType.UNITY -> unityInspection?.takeIf { it.installedPackageName == null }?.input?.uri
                DetectedGameType.KIRIKIRI -> kirikiriInspection?.archives?.firstOrNull()?.uri
                DetectedGameType.VISUAL_NOVEL_ARCHIVE -> visualNovelInspection?.archives?.firstOrNull()?.uri
                null -> null
            }
            return sourceUri?.let(Uri::parse)
        }

    fun setOutputTree(uri: Uri) {
        outputTreeUri = uri.toString()
        preferences.saveGameExtractionTreeUri(uri.toString())
        showMessage("已记住输出目录", false)
    }

    fun acceptGameExtractionDisclaimer() {
        if (gameExtractionDisclaimerAccepted) return
        gameExtractionDisclaimerAccepted = true
        preferences.saveGameExtractionDisclaimerAccepted(true)
    }

    fun setRpgKey(value: String) {
        rpgKeyHex = value.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(32)
        rpgKeySource = if (rpgKeyHex.isBlank()) null else "手动输入"
    }

    fun updateRpgBgmVolume(value: String) {
        rpgBgmVolume = sanitizeVolumeInput(value)
    }

    fun updateRpgBgsVolume(value: String) {
        rpgBgsVolume = sanitizeVolumeInput(value)
    }

    fun updateRpgSeVolume(value: String) {
        rpgSeVolume = sanitizeVolumeInput(value)
    }

    fun updateRpgMeVolume(value: String) {
        rpgMeVolume = sanitizeVolumeInput(value)
    }

    fun updateRpgSkipStartupSplash(value: Boolean) {
        rpgSkipStartupSplash = value
    }

    val hasValidRpgAudioVolumes: Boolean
        get() = listOf(rpgBgmVolume, rpgBgsVolume, rpgSeVolume, rpgMeVolume)
            .all { value -> value.toIntOrNull()?.let { it in 0..100 } == true }

    suspend fun injectRpgAudioVolumeControl() = runOperation("正在注入 AudioVolumeControl…") {
        val directoryUri = rpgDirectoryUri?.let(Uri::parse)
            ?: error("音量补丁只能注入可写的已解压 RPG Maker 游戏目录")
        val settings = rpgAudioVolumeSettings()
            ?: error("BGM、BGS、SE、ME 音量都必须是 0 到 100 的整数")
        val result = rpgAudioVolumeInjector.inject(directoryUri, settings)
        val action = if (result.replacedExisting) "已覆盖同名补丁" else "已新增补丁"
        val backup = result.backupFiles.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "；已创建备份：")
            .orEmpty()
        val splash = if (rpgSkipStartupSplash) "开启跳过开场 Logo" else "保留开场 Logo"
        showMessage("${result.engine.displayName}：$action；$splash$backup", false)
    }

    private fun rpgAudioVolumeSettings(): RpgAudioVolumeSettings? {
        val values = listOf(rpgBgmVolume, rpgBgsVolume, rpgSeVolume, rpgMeVolume).map { it.toIntOrNull() }
        if (values.any { value -> value?.let { it in 0..100 } != true }) return null
        return RpgAudioVolumeSettings(
            bgm = requireNotNull(values[0]),
            bgs = requireNotNull(values[1]),
            se = requireNotNull(values[2]),
            me = requireNotNull(values[3]),
            skipStartupSplash = rpgSkipStartupSplash,
        )
    }

    private fun sanitizeVolumeInput(value: String): String = value.filter(Char::isDigit).take(3)

    fun updateUnitySelection(value: UnityExtractionSelection) {
        unitySelection = value
    }

    fun setKirikiriEntrySelected(selectionKey: String, selected: Boolean) {
        val entry = kirikiriInspection?.entries?.firstOrNull { it.selectionKey == selectionKey } ?: return
        if (!entry.canExtract) return
        selectedKirikiriEntryKeys = if (selected) {
            selectedKirikiriEntryKeys + selectionKey
        } else {
            selectedKirikiriEntryKeys - selectionKey
        }
    }

    fun selectAllKirikiriEntries(selected: Boolean) {
        selectedKirikiriEntryKeys = if (selected) {
            kirikiriInspection?.entries
                ?.asSequence()
                ?.filter { it.canExtract }
                ?.map { it.selectionKey }
                ?.toSet()
                .orEmpty()
        } else {
            emptySet()
        }
    }

    suspend fun inspectGameFiles(uris: List<Uri>) = runOperation("正在自动识别所选游戏文件…") {
        require(uris.isNotEmpty()) { "请至少选择一个文件" }
        resetDetection()
        detectOrNull { kirikiriRepository.inspectFiles(uris) }?.let { inspection ->
            applyKirikiriInspection(inspection)
            detectedGameType = DetectedGameType.KIRIKIRI
            showMessage(kirikiriInspectionMessage(inspection), !inspection.canExtract)
            return@runOperation
        }

        val described = uris.map { uri -> repository.describe(uri) }
        val rpgCandidates = described.filter {
            it.kind == GameInputKind.RPG_ARCHIVE ||
                it.kind == GameInputKind.RPG_ENCRYPTED_RESOURCE ||
                it.kind == GameInputKind.RPG_SYSTEM_JSON
        }
        if (rpgCandidates.isNotEmpty() && rpgCandidates.size == described.size) {
            setRpgFileInput(rpgCandidates)
            detectedGameType = DetectedGameType.RPG_MAKER
            showMessage("已识别为 RPG Maker：${rpgFiles.size} 个可处理文件", false)
            return@runOperation
        }

        detectOrNull { visualNovelRepository.inspectFiles(uris) }?.let { inspection ->
            visualNovelInspection = inspection
            detectedGameType = DetectedGameType.VISUAL_NOVEL_ARCHIVE
            showMessage(
                "已识别 Visual Novel 档案：${inspection.archives.size} 个封包、${inspection.entryCount} 个条目",
                false,
            )
            return@runOperation
        }

        if (uris.size == 1) {
            detectOrNull { repository.inspectUnity(uris.first()) }?.let { inspection ->
                unityInspection = inspection
                detectedGameType = DetectedGameType.UNITY
                showMessage("已识别为 Unity：发现 ${inspection.artifactCount} 个相关文件", false)
                return@runOperation
            }
        }
        error("无法识别所选文件的受支持游戏类型，未执行解析")
    }

    suspend fun inspectGameDirectory(uri: Uri) = runOperation("正在自动识别游戏目录…") {
        resetDetection()
        var rpgScanFailure: Throwable? = null
        detectOrNull { kirikiriRepository.inspectDirectory(uri) }?.let { inspection ->
            applyKirikiriInspection(inspection)
            detectedGameType = DetectedGameType.KIRIKIRI
            showMessage(kirikiriInspectionMessage(inspection), !inspection.canExtract)
            return@runOperation
        }
        val rpgScan = try {
            repository.scanRpgMakerDirectory(uri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            rpgScanFailure = error
            null
        }
        rpgScan?.let { scan ->
            applyRpgScan(scan, sourceDirectoryUri = uri.toString(), installedPackageName = null)
            detectedGameType = DetectedGameType.RPG_MAKER
            showMessage("已识别为 RPG Maker：${scan.files.size} 个可处理文件", false)
            return@runOperation
        }
        detectOrNull { visualNovelRepository.inspectDirectory(uri) }?.let { inspection ->
            visualNovelInspection = inspection
            detectedGameType = DetectedGameType.VISUAL_NOVEL_ARCHIVE
            showMessage(
                "已识别 Visual Novel 档案：${inspection.archives.size} 个封包、${inspection.entryCount} 个条目",
                false,
            )
            return@runOperation
        }
        detectOrNull { repository.inspectUnityDirectory(uri) }?.let { inspection ->
            unityInspection = inspection
            detectedGameType = DetectedGameType.UNITY
            showMessage("已识别为 Unity：发现 ${inspection.artifactCount} 个相关文件", false)
            return@runOperation
        }
        val rpgDetail = rpgScanFailure?.message?.takeIf(String::isNotBlank)
        error(
            buildString {
                append("目录内未发现受支持的 RPG Maker 项目、Unity、KRKR/吉里吉里或 Visual Novel 档案，未执行解析")
                if (rpgDetail != null) append("；RPG Maker 扫描：$rpgDetail")
            },
        )
    }

    suspend fun inspectInstalledGameAppAutomatically(app: InstalledGameApp) =
        runOperation("正在自动识别 ${app.label} 的游戏类型…") {
            resetDetection()
            detectOrNull { repository.scanRpgMakerInstalledApp(app.packageName) }?.let { scan ->
                applyRpgScan(scan, sourceDirectoryUri = null, installedPackageName = app.packageName)
                detectedGameType = DetectedGameType.RPG_MAKER
                showMessage("已识别为 RPG Maker 安卓移植：${scan.files.size} 个资源", false)
                return@runOperation
            }
            detectOrNull { repository.inspectInstalledUnityApp(app.packageName) }?.let { inspection ->
                unityInspection = inspection
                detectedGameType = DetectedGameType.UNITY
                showMessage("已识别为 Unity：发现 ${inspection.artifactCount} 个相关文件", false)
                return@runOperation
            }
            error("该应用的安装包中未发现受支持的 RPG Maker 或 Unity 结构，未执行解析")
        }

    fun startDetectedExtraction() {
        if (!ensureGameExtractionDisclaimerAccepted()) return
        when (detectedGameType) {
            DetectedGameType.RPG_MAKER -> startRpgExtraction()
            DetectedGameType.UNITY -> startUnityExtraction()
            DetectedGameType.KIRIKIRI -> startKirikiriExtraction()
            DetectedGameType.VISUAL_NOVEL_ARCHIVE -> startVisualNovelExtraction()
            null -> showMessage("请先选择文件、目录或已安装应用并完成自动识别", true)
        }
    }

    suspend fun loadRpgFiles(uris: List<Uri>) = runOperation("正在读取所选文件…") {
        rpgFiles = emptyList()
        rpgInputLabel = "尚未选择"
        rpgDirectoryUri = null
        rpgInstalledPackageName = null
        rpgAudioVolumeEngine = null
        rpgFiles = uris.map { uri -> repository.describe(uri) }
        rpgInputLabel = "已选择 ${rpgFiles.size} 个文件"
        rpgKeyHex = ""
        rpgKeySource = null
        val keyCandidate = rpgFiles.firstOrNull { it.kind == GameInputKind.RPG_SYSTEM_JSON }
            ?: rpgFiles.firstOrNull {
                val lower = it.name.lowercase()
                lower.endsWith(".rpgmvp") || lower.endsWith(".png_")
            }
        if (keyCandidate != null) {
            try {
                val (key, source) = repository.readRpgMakerKey(Uri.parse(keyCandidate.uri))
                rpgKeyHex = key
                rpgKeySource = source
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The file list is still useful even when automatic key detection fails.
            }
        }
        val supported = rpgFiles.count {
            it.kind == GameInputKind.RPG_ARCHIVE || it.kind == GameInputKind.RPG_ENCRYPTED_RESOURCE
        }
        showMessage("识别到 $supported 个可处理文件", supported == 0)
    }

    suspend fun loadRpgDirectory(uri: Uri) = runOperation("正在递归扫描 RPG Maker 目录…") {
        rpgFiles = emptyList()
        rpgInputLabel = "尚未选择"
        rpgDirectoryUri = null
        rpgInstalledPackageName = null
        val scan = repository.scanRpgMakerDirectory(uri)
        rpgFiles = scan.files
        rpgDirectoryUri = uri.toString()
        rpgAudioVolumeEngine = scan.audioVolumeEngine
        rpgInputLabel = "目录：${scan.directoryName}（${scan.files.size} 个可处理文件）"
        rpgKeyHex = scan.detectedKey.orEmpty()
        rpgKeySource = scan.keySource
        showMessage(
            if (scan.detectedKey != null) "目录扫描完成，并已自动找到 MV/MZ 密钥" else "目录扫描完成",
            false,
        )
    }

    suspend fun detectRpgKey(uri: Uri) = runOperation("正在读取 RPG Maker 密钥…") {
        val (key, source) = repository.readRpgMakerKey(uri)
        rpgKeyHex = key
        rpgKeySource = source
        showMessage("已从 $source 获取密钥", false)
    }

    suspend fun inspectInstalledRpgMakerApp(app: InstalledGameApp) =
        runOperation("正在扫描 ${app.label} 的 RPG Maker 资源…") {
            rpgFiles = emptyList()
            rpgInputLabel = "尚未选择"
            rpgDirectoryUri = null
            rpgInstalledPackageName = null
            val scan = repository.scanRpgMakerInstalledApp(app.packageName)
            rpgFiles = scan.files
            rpgInstalledPackageName = app.packageName
            rpgInputLabel = "已安装应用：${scan.directoryName}（${scan.files.size} 个可处理文件）"
            rpgKeyHex = scan.detectedKey.orEmpty()
            rpgKeySource = scan.keySource
            showMessage(
                if (scan.detectedKey != null) "应用扫描完成，并已自动找到 MV/MZ 密钥" else "应用扫描完成",
                false,
            )
        }

    fun startRpgExtraction() {
        runCatching {
            val output = effectiveOutputTreeUri ?: error("无法获得源文件父目录的写权限，请先选择输出目录")
            GameExtractionService.startRpg(
                context = appContext,
                inputs = rpgFiles,
                sourceDirectoryUri = rpgDirectoryUri,
                outputTreeUri = output,
                keyHex = rpgKeyHex,
                installedPackageName = rpgInstalledPackageName,
            ).getOrThrow()
        }.onSuccess {
            showMessage("已转入后台处理，可以离开本页、切换到后台或锁屏", false)
        }.onFailure { error ->
            showMessage(error.message ?: "无法启动后台任务", true)
        }
    }

    suspend fun inspectUnityFile(uri: Uri) = runOperation("正在分析 Unity 文件…") {
        unityInspection = null
        unityInspection = repository.inspectUnity(uri)
        val inspection = requireNotNull(unityInspection)
        showMessage("分析完成：发现 ${inspection.artifactCount} 个相关文件", false)
    }

    suspend fun inspectUnityDirectory(uri: Uri) = runOperation("正在递归扫描 Unity 目录…") {
        unityInspection = null
        unityInspection = repository.inspectUnityDirectory(uri)
        val inspection = requireNotNull(unityInspection)
        showMessage("目录分析完成：发现 ${inspection.artifactCount} 个相关文件", false)
    }

    suspend fun loadInstalledGameApps(): Boolean {
        var loaded = false
        runOperation("正在读取可选择的已安装应用…") {
            installedGameApps = repository.listInstalledGameApps()
            require(installedGameApps.isNotEmpty()) { "没有发现带桌面启动入口的其它应用" }
            showMessage("已找到 ${installedGameApps.size} 个可选择应用", false)
            loaded = true
        }
        return loaded
    }

    suspend fun inspectInstalledGameApp(app: InstalledGameApp) = runOperation("正在分析 ${app.label} 的安装包…") {
        unityInspection = null
        unityInspection = repository.inspectInstalledUnityApp(app.packageName)
        val inspection = requireNotNull(unityInspection)
        showMessage(
            "已扫描 ${inspection.installedApkCount} 个安装 APK，发现 ${inspection.artifactCount} 个 Unity 相关文件",
            false,
        )
    }

    fun startUnityExtraction() {
        runCatching {
            val inspection = unityInspection ?: error("请先选择并分析 Unity 游戏文件或目录")
            val output = effectiveOutputTreeUri ?: error("无法获得源文件父目录的写权限，请先选择输出目录")
            GameExtractionService.startUnity(
                context = appContext,
                sourceUri = inspection.input.uri,
                sourceIsDirectory = inspection.isDirectory,
                outputTreeUri = output,
                selection = unitySelection,
                installedPackageName = inspection.installedPackageName,
            ).getOrThrow()
        }.onSuccess {
            showMessage("已转入后台处理，可以离开本页、切换到后台或锁屏", false)
        }.onFailure { error ->
            showMessage(error.message ?: "无法启动后台任务", true)
        }
    }

    private fun startKirikiriExtraction() {
        runCatching {
            val inspection = kirikiriInspection ?: error("请先选择并识别 KRKR/吉里吉里 XP3 资源")
            require(inspection.canExtract) {
                "解析结果中没有可可靠导出的 XP3 条目"
            }
            require(selectedKirikiriEntryKeys.isNotEmpty()) { "请至少勾选一个可导出的 XP3 条目" }
            val output = effectiveOutputTreeUri ?: error("无法获得源文件父目录的写权限，请先选择输出目录")
            GameExtractionService.startKirikiri(
                context = appContext,
                inspection = inspection,
                outputTreeUri = output,
                selectedEntryKeys = selectedKirikiriEntryKeys,
            ).getOrThrow()
        }.onSuccess {
            showMessage("已转入后台处理，可以离开本页、切换到后台或锁屏", false)
        }.onFailure { error ->
            showMessage(error.message ?: "无法启动后台任务", true)
        }
    }

    private fun startVisualNovelExtraction() {
        runCatching {
            val inspection = visualNovelInspection ?: error("请先选择并识别 Visual Novel 档案")
            require(inspection.canExtract) {
                "所选档案中没有可可靠导出的条目"
            }
            val output = effectiveOutputTreeUri ?: error("无法获得源文件父目录的写权限，请先选择输出目录")
            GameExtractionService.startVisualNovel(
                context = appContext,
                inspection = inspection,
                outputTreeUri = output,
            ).getOrThrow()
        }.onSuccess {
            showMessage("已转入后台处理，可以离开本页、切换到后台或锁屏", false)
        }.onFailure { error ->
            showMessage(error.message ?: "无法启动后台任务", true)
        }
    }

    private suspend fun setRpgFileInput(files: List<GameInputFile>) {
        rpgFiles = files
        rpgInputLabel = "已选择 ${files.size} 个文件"
        rpgDirectoryUri = null
        rpgInstalledPackageName = null
        rpgAudioVolumeEngine = null
        rpgKeyHex = ""
        rpgKeySource = null
        val keyCandidate = files.firstOrNull { it.kind == GameInputKind.RPG_SYSTEM_JSON }
            ?: files.firstOrNull {
                val lower = it.name.lowercase()
                lower.endsWith(".rpgmvp") || lower.endsWith(".png_")
            }
        if (keyCandidate != null) {
            detectOrNull { repository.readRpgMakerKey(Uri.parse(keyCandidate.uri)) }?.let { (key, source) ->
                rpgKeyHex = key
                rpgKeySource = source
            }
        }
        rpgFiles = files.filter { it.kind != GameInputKind.RPG_SYSTEM_JSON }
        require(rpgFiles.isNotEmpty()) { "所选 RPG Maker 文件中没有可解析资源" }
    }

    private fun applyRpgScan(
        scan: RpgMakerDirectoryScan,
        sourceDirectoryUri: String?,
        installedPackageName: String?,
    ) {
        rpgFiles = scan.files
        rpgInputLabel = when {
            installedPackageName != null -> "已安装应用：${scan.directoryName}（${scan.files.size} 个资源）"
            sourceDirectoryUri != null -> "目录：${scan.directoryName}（${scan.files.size} 个资源）"
            else -> "${scan.files.size} 个资源"
        }
        rpgDirectoryUri = sourceDirectoryUri
        rpgInstalledPackageName = installedPackageName
        rpgAudioVolumeEngine = scan.audioVolumeEngine.takeIf { sourceDirectoryUri != null }
        rpgKeyHex = scan.detectedKey.orEmpty()
        rpgKeySource = scan.keySource
    }

    private fun applyKirikiriInspection(inspection: KirikiriInspection) {
        kirikiriInspection = inspection
        selectedKirikiriEntryKeys = inspection.entries
            .asSequence()
            .filter { it.canExtract }
            .map { it.selectionKey }
            .toSet()
    }

    private fun kirikiriInspectionMessage(inspection: KirikiriInspection): String = when {
        inspection.canExtract -> "已识别为 KRKR/吉里吉里：${inspection.archives.size} 个 XP3 档案"
        inspection.parseFailureCount > 0 -> "已识别 XP3 输入，但 ${inspection.parseFailureCount} 个档案均解析失败；请查看记录"
        else -> "已识别 XP3 输入，但没有可导出的资源条目"
    }

    private fun resetDetection() {
        detectedGameType = null
        kirikiriInspection = null
        selectedKirikiriEntryKeys = emptySet()
        visualNovelInspection = null
        unityInspection = null
        rpgFiles = emptyList()
        rpgInputLabel = "尚未选择"
        rpgDirectoryUri = null
        rpgInstalledPackageName = null
        rpgAudioVolumeEngine = null
        rpgKeyHex = ""
        rpgKeySource = null
    }

    private suspend fun <T> detectOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private suspend fun runOperation(initialMessage: String, operation: suspend () -> Unit) {
        if (!ensureGameExtractionDisclaimerAccepted()) return
        busy = true
        showMessage(initialMessage, false)
        try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            showMessage(error.message ?: "处理失败", true)
        } finally {
            busy = false
        }
    }

    private fun ensureGameExtractionDisclaimerAccepted(): Boolean {
        if (gameExtractionDisclaimerAccepted) return true
        showMessage("请先阅读免责声明，再使用游戏资源解析功能", true)
        return false
    }

    private fun showMessage(value: String, error: Boolean) {
        message = value
        isError = error
    }

    fun reportNotificationUnavailable() {
        showMessage(
            "后台任务已启动，但系统通知权限或“游戏资源处理”通知渠道已关闭；请在系统应用通知设置中开启。页面内仍会显示进度。",
            true,
        )
    }
}

@Composable
fun rememberGameExtractionState(): GameExtractionState {
    val context = LocalContext.current
    return remember(context) { GameExtractionState(context) }
}

@Composable
fun GameExtractionScreen(state: GameExtractionState) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val extractionJob by GameExtractionJobBus.state.collectAsStateWithLifecycle()
    val interactionBusy = state.busy || extractionJob.isRunning
    var showDisclaimer by rememberSaveable { mutableStateOf(false) }
    var showInstalledAppPicker by rememberSaveable { mutableStateOf(false) }
    var showRpgAudioVolumeConfirm by rememberSaveable { mutableStateOf(false) }
    var pendingExtractionStart by rememberSaveable { mutableStateOf(false) }
    var directPickerTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDirectPickerTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var allFilesAccessRefresh by remember { mutableStateOf(0) }
    val allFilesAccessSupported = remember { supportsAllFilesAccess() }
    val allFilesAccessGranted = remember(allFilesAccessRefresh) {
        !allFilesAccessSupported || hasAllFilesAccess()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (pendingExtractionStart) {
            pendingExtractionStart = false
            state.startDetectedExtraction()
            if (!granted || !GameExtractionService.canShowProgressNotifications(context)) {
                state.reportNotificationUnavailable()
            }
        }
    }
    val startExtractionWithNotification: () -> Unit = {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingExtractionStart = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            state.startDetectedExtraction()
            if (!GameExtractionService.canShowProgressNotifications(context)) {
                state.reportNotificationUnavailable()
            }
        }
    }

    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        takeGameUriPermission(context, uri, write = true)
        state.setOutputTree(uri)
    }
    val gameFilesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { takeGameUriPermission(context, it, write = false) }
        scope.launch { state.inspectGameFiles(uris) }
    }
    val gameDirectoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        takeGameUriPermission(context, uri, write = true)
        scope.launch { state.inspectGameDirectory(uri) }
    }
    val allFilesAccessSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        allFilesAccessRefresh += 1
        if (hasAllFilesAccess()) {
            directPickerTarget = pendingDirectPickerTarget
        }
        pendingDirectPickerTarget = null
    }
    val rpgKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        takeGameUriPermission(context, uri, write = false)
        scope.launch { state.detectRpgKey(uri) }
    }
    val requestDirectPicker: (String, () -> Unit) -> Unit = { target, fallback ->
        when {
            !supportsAllFilesAccess() -> fallback()
            hasAllFilesAccess() -> directPickerTarget = target
            else -> {
                pendingDirectPickerTarget = target
                allFilesAccessSettingsLauncher.launch(createAllFilesAccessSettingsIntent(context))
            }
        }
    }
    val launchSystemGameDirectoryPicker = {
        gameDirectoryPicker.launch(
            createDirectoryPickerIntent(
                initialUri = primaryStorageRootDocumentUri(),
                allowWrite = true,
                showAppChooser = true,
                chooserTitle = "选择游戏资源目录",
            ),
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text("05 / 游戏资源", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text("解密与逆向导出", fontFamily = FontFamily.Serif, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))

        OutlinedButton(
            onClick = { showDisclaimer = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(

                    "使用前请先阅读"
            )
        }
        Spacer(Modifier.height(14.dp))

        UnifiedGameInputCard(
            busy = interactionBusy,
            disclaimerAccepted = state.gameExtractionDisclaimerAccepted,
            storageAccessSupported = allFilesAccessSupported,
            storageAccessGranted = allFilesAccessGranted,
            onOpenStorageSettings = {
                pendingDirectPickerTarget = null
                allFilesAccessSettingsLauncher.launch(createAllFilesAccessSettingsIntent(context))
            },
            onPickFiles = {
                requestDirectPicker("input_file") { gameFilesPicker.launch(arrayOf("*/*")) }
            },
            onPickDirectory = {
                requestDirectPicker("input_directory", launchSystemGameDirectoryPicker)
            },
            onPickInstalledApp = {
                scope.launch {
                    if (state.loadInstalledGameApps()) showInstalledAppPicker = true
                }
            },
        )
        if (state.detectedGameType != null) {
            Spacer(Modifier.height(14.dp))
            OutputDirectoryCard(
                available = state.effectiveOutputTreeUri != null,
                usingSourceDefault = state.usesSourceDirectoryAsOutput,
                enabled = !interactionBusy,
                onBrowseStorage = {
                    requestDirectPicker("output_directory") {
                        outputPicker.launch(
                            createDirectoryPickerIntent(
                                initialUri = state.outputPickerInitialUri
                                    ?.takeIf { it.scheme.equals("content", ignoreCase = true) }
                                    ?: primaryStorageRootDocumentUri(),
                                allowWrite = true,
                                showAppChooser = false,
                                chooserTitle = "选择游戏资源输出目录",
                            ),
                        )
                    }
                },
            )
        }
        Spacer(Modifier.height(16.dp))

        when (state.detectedGameType) {
            DetectedGameType.RPG_MAKER -> RpgMakerPanel(
                state = state,
                busy = interactionBusy,
                showInputActions = false,
                onPickFiles = {},
                onPickDirectory = {},
                onPickKey = { rpgKeyPicker.launch(arrayOf("application/json", "image/*", "application/octet-stream")) },
                onPickInstalledApp = {},
                onExtract = startExtractionWithNotification,
                onInjectAudioVolumes = { showRpgAudioVolumeConfirm = true },
                onOpenSource = { runCatching { uriHandler.openUri("https://github.com/uuksu/RPGMakerDecrypter") } },
            )

            DetectedGameType.UNITY -> UnityPanel(
                state = state,
                busy = interactionBusy,
                showInputActions = false,
                onPickFile = {},
                onPickDirectory = {},
                onPickInstalledApp = {},
                onExtract = startExtractionWithNotification,
                onOpenSource = { runCatching { uriHandler.openUri("https://github.com/AssetRipper/AssetRipper") } },
            )

            DetectedGameType.KIRIKIRI -> KirikiriPanel(
                inspection = requireNotNull(state.kirikiriInspection),
                selectedEntryKeys = state.selectedKirikiriEntryKeys,
                busy = interactionBusy,
                outputReady = state.effectiveOutputTreeUri != null,
                onEntrySelectionChange = state::setKirikiriEntrySelected,
                onSelectAll = state::selectAllKirikiriEntries,
                onExtract = startExtractionWithNotification,
                onOpenSource = { runCatching { uriHandler.openUri("https://github.com/morkt/GARbro/blob/master/ArcFormats/KiriKiri/CryptAlgorithms.cs") } },
            )

            DetectedGameType.VISUAL_NOVEL_ARCHIVE -> VisualNovelArchivePanel(
                inspection = requireNotNull(state.visualNovelInspection),
                busy = interactionBusy,
                outputReady = state.effectiveOutputTreeUri != null,
                onExtract = startExtractionWithNotification,
                onOpenSource = { runCatching { uriHandler.openUri("https://github.com/morkt/GARbro") } },
            )

            null -> Unit
        }

        if (extractionJob.phase != GameExtractionJobPhase.IDLE) {
            Spacer(Modifier.height(14.dp))
            GameExtractionJobCard(
                job = extractionJob,
                onCancel = { GameExtractionService.cancel(context) },
                onDismiss = GameExtractionJobBus::dismissFinished,
            )
        }

        if (state.busy) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("正在读取或分析，完成前请保持页面打开", color = Muted)
            }
        }
        state.message?.let { message ->
            Text(
                message,
                color = if (state.isError) Color(0xFFB3261E) else Ink,
                modifier = Modifier.fillMaxWidth().background(
                    if (state.isError) Color(0xFFFFEDEA) else SurfacePaper,
                    RoundedCornerShape(12.dp),
                ).padding(12.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
    }

    if (showDisclaimer) {
        GameExtractionDisclaimerDialog(
            accepted = state.gameExtractionDisclaimerAccepted,
            onAccept = {
                state.acceptGameExtractionDisclaimer()
                showDisclaimer = false
            },
            onDismiss = { showDisclaimer = false },
        )
    }

    if (showInstalledAppPicker) {
        InstalledAppPickerDialog(
            apps = state.installedGameApps,
            onDismiss = { showInstalledAppPicker = false },
            onSelect = { app ->
                showInstalledAppPicker = false
                scope.launch { state.inspectInstalledGameAppAutomatically(app) }
            },
        )
    }

    if (showRpgAudioVolumeConfirm) {
        AlertDialog(
            onDismissRequest = { showRpgAudioVolumeConfirm = false },
            title = { Text("应用音量与启动设置？") },
            text = {
                val splashNote = if (state.rpgSkipStartupSplash) {
                    "已开启跳过开场 Logo；第三方自定义剧情动画不保证能够识别。"
                } else {
                    "当前保留开场 Logo，并恢复此前由工具箱停用的已知 Logo 插件。"
                }
                Text(
                    "该操作会直接修改已选择游戏目录中的插件注册文件或 Scripts.rvdata2。" +
                        "首次修改会在原目录创建备份；已有同名补丁会被当前音量和开场 Logo 设置覆盖。$splashNote",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRpgAudioVolumeConfirm = false
                        scope.launch { state.injectRpgAudioVolumeControl() }
                    },
                ) {
                    Text("继续注入")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRpgAudioVolumeConfirm = false }) { Text("取消") }
            },
        )
    }

    directPickerTarget?.let { target ->
        DirectStoragePickerDialog(
            title = when (target) {
                "input_file" -> "选择游戏资源文件"
                "input_directory" -> "选择游戏资源目录"
                else -> "选择游戏资源输出目录"
            },
            mode = if (target == "input_file") {
                DirectStorageSelectionMode.FILE
            } else {
                DirectStorageSelectionMode.DIRECTORY
            },
            initialDirectory = if (target == "output_directory") {
                directDirectoryFromUri(state.outputPickerInitialUri) ?: directStorageRoot()
            } else {
                directStorageRoot()
            },
            onDismiss = { directPickerTarget = null },
            onSelected = { selected ->
                directPickerTarget = null
                when (target) {
                    "input_file" -> scope.launch { state.inspectGameFiles(selected.map { Uri.fromFile(it) }) }
                    "input_directory" -> selected.firstOrNull()?.let { directory ->
                        scope.launch { state.inspectGameDirectory(Uri.fromFile(directory)) }
                    }
                    "output_directory" -> selected.firstOrNull()?.let { directory ->
                        state.setOutputTree(Uri.fromFile(directory))
                    }
                }
            },
        )
    }
}

@Composable
private fun GameExtractionJobCard(
    job: GameExtractionJobState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val containerColor = when (job.phase) {
        GameExtractionJobPhase.RUNNING -> BluePaper
        GameExtractionJobPhase.SUCCEEDED -> Sage
        GameExtractionJobPhase.FAILED, GameExtractionJobPhase.CANCELLED -> Color(0xFFFFEDEA)
        GameExtractionJobPhase.IDLE -> SurfacePaper
    }
    val accentColor = when (job.phase) {
        GameExtractionJobPhase.SUCCEEDED -> Green
        GameExtractionJobPhase.FAILED, GameExtractionJobPhase.CANCELLED -> Color(0xFFB3261E)
        else -> Blue
    }
    Card(colors = CardDefaults.cardColors(containerColor = containerColor), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (job.isRunning) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Blue)
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(job.title, color = accentColor, fontWeight = FontWeight.Bold)
                    Text(job.message, color = Ink, fontSize = 12.sp)
                }
            }
            if (job.isRunning) {
                Text(
                    "任务已由前台服务接管，可以离开本页、切换到后台或锁屏；通知栏会持续显示进度。",
                    color = Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("取消任务") }
            } else {
                if (job.skippedCount > 0) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Line)
                    Text("失败并已跳过：${job.skippedCount} 项", color = Color(0xFFB3261E), fontWeight = FontWeight.Bold)
                    job.failureRecords.forEach { failure ->
                        Text(
                            "• ${failure.item}：${failure.reason}",
                            color = Color(0xFFB3261E),
                            fontSize = 10.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (job.skippedCount > job.failureRecords.size) {
                        Text(
                            "另有 ${job.skippedCount - job.failureRecords.size} 项失败记录未在此处展开。",
                            color = Muted,
                            fontSize = 10.sp,
                        )
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("知道了") }
            }
        }
    }
}

@Composable
private fun UnifiedGameInputCard(
    busy: Boolean,
    disclaimerAccepted: Boolean,
    storageAccessSupported: Boolean,
    storageAccessGranted: Boolean,
    onOpenStorageSettings: () -> Unit,
    onPickFiles: () -> Unit,
    onPickDirectory: () -> Unit,
    onPickInstalledApp: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = BluePaper), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自动识别输入", color = Blue, fontWeight = FontWeight.Bold)
                    Text(
                        "选择后自动识别游戏类型",
                        color = Muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    Text(
                        when {
                            !storageAccessSupported -> "当前系统无需单独授予所有文件访问权限"
                            storageAccessGranted -> "存储权限：已授权"
                            else -> "存储权限：未授权"
                        },
                        color = if (storageAccessGranted || !storageAccessSupported) Muted else Color(0xFFB3261E),
                        fontSize = 9.sp,
                    )
                }
                OutlinedButton(
                    onClick = onOpenStorageSettings,
                    enabled = storageAccessSupported,
                ) {
                    Text("管理存储权限", fontSize = 11.sp, maxLines = 1)
                }
            }
            if (!disclaimerAccepted) {
                Text(
                    "请先阅读上方免责声明，再进行使用。",
                    color = Color(0xFFB3261E),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onPickFiles,
                    enabled = !busy && disclaimerAccepted,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("选择文件")
                }
                OutlinedButton(
                    onClick = onPickDirectory,
                    enabled = !busy && disclaimerAccepted,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("选择目录")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onPickInstalledApp,
                enabled = !busy && disclaimerAccepted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Apps, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("选择已安装应用")
            }
            Text(
                "已安装应用仅列出带桌面入口的项目",
                color = Muted,
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun VisualNovelArchivePanel(
    inspection: VisualNovelArchiveInspection,
    busy: Boolean,
    outputReady: Boolean,
    onExtract: () -> Unit,
    onOpenSource: () -> Unit,
) {
    Text("已识别：Visual Novel 资源档案", fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Text(
        "来源：${inspection.sourceLabel} · ${inspection.archives.size} 个档案",
        color = Blue,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(10.dp))
    Card(colors = CardDefaults.cardColors(containerColor = SurfacePaper), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("识别格式", fontWeight = FontWeight.Bold)
            inspection.formatCounts.forEach { (format, count) ->
                Text("• ${format.engineName} / ${format.displayName} × $count", color = Ink, fontSize = 11.sp)
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Line)
            Text("文件条目：${inspection.entryCount}", fontWeight = FontWeight.Bold)
            Text("预计输出：${formatGameBytes(inspection.outputBytes)}", color = Muted, fontSize = 11.sp)
            if (inspection.unsupportedEntryCount > 0) {
                Text(
                    "其中 ${inspection.unsupportedEntryCount} 个条目需要游戏专用密钥、私有压缩或尚未实现的解码器。后台会跳过这些条目，仅导出能够可靠处理的内容；若全部条目均不支持则禁用按钮。",
                    color = Color(0xFFB3261E),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                inspection.unsupportedReasons.take(5).forEach { reason ->
                    Text("• $reason", color = Color(0xFFB3261E), fontSize = 10.sp)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Line)
            inspection.previewPaths.take(12).forEach { path ->
                Text("• $path", color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (inspection.entryCount > inspection.previewPaths.size) {
                Text("仅预览前 ${inspection.previewPaths.size} 项，导出会处理全部可支持条目。", color = Muted, fontSize = 10.sp)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onExtract,
        enabled = !busy && outputReady && inspection.canExtract,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Rounded.Download, null)
        Spacer(Modifier.width(7.dp))
        Text("后台解包资源")
    }
    Text(
        "当前可直接处理：Ren'Py RPA-3.0、Majiro ARC、BGI PackFile/BURIKO ARC20、NScripter SAR/NS2/未压缩 NSA、未加密 Nitroplus NPA、Artemis PFS2/PFS6、WILL ARC、NekoSDK NEKOPACK4A/4S、部分 KaGuYa LINK。另可识别 YU-RIS YPF、QLIE FilePack、EntisGLS NOA、CMVS CPZ、PFS8、加密 NPA/NS2 及部分受保护条目并明确提示限制。",
        color = Muted,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        "设计与格式资料参考 GARbro；本项目使用独立的 Android 流式实现，并不等同于 GARbro 的完整格式覆盖。",
        color = Muted,
        fontSize = 10.sp,
    )
    TextButton(onClick = onOpenSource) { Text("格式设计参考：GARbro（MIT）") }
}

@Composable
private fun KirikiriPanel(
    inspection: KirikiriInspection,
    selectedEntryKeys: Set<String>,
    busy: Boolean,
    outputReady: Boolean,
    onEntrySelectionChange: (String, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onExtract: () -> Unit,
    onOpenSource: () -> Unit,
) {
    val pageCount = maxOf(1, (inspection.entries.size + KIRIKIRI_PAGE_SIZE - 1) / KIRIKIRI_PAGE_SIZE)
    var page by rememberSaveable(inspection.sourceLabel, inspection.entryCount) { mutableStateOf(1) }
    var pageInput by rememberSaveable(inspection.sourceLabel, inspection.entryCount) { mutableStateOf("1") }
    var pageExpanded by rememberSaveable(inspection.sourceLabel, inspection.entryCount) { mutableStateOf(false) }
    val currentPage = page.coerceIn(1, pageCount)
    val pageEntries = inspection.entries
        .drop((currentPage - 1) * KIRIKIRI_PAGE_SIZE)
        .take(KIRIKIRI_PAGE_SIZE)
    val visiblePageEntries = if (pageExpanded) pageEntries else pageEntries.take(KIRIKIRI_COLLAPSED_ENTRY_COUNT)
    val selectedCount = selectedEntryKeys.size
    val unsupportedEntryCount = inspection.entryCount - inspection.extractableEntryCount
    val allExtractableSelected = inspection.extractableEntryCount > 0 &&
        selectedCount == inspection.extractableEntryCount
    val exportingEverything = inspection.extractableEntryCount == inspection.entryCount &&
        selectedCount == inspection.entryCount
    val changePage: (Int) -> Unit = { target ->
        val safePage = target.coerceIn(1, pageCount)
        page = safePage
        pageInput = safePage.toString()
        pageExpanded = false
    }

    Text("已识别：KRKR / 吉里吉里 XP3", fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Text(
        "来源：${inspection.sourceLabel} · ${inspection.archives.size} 个档案",
        color = Blue,
        fontSize = 12.sp,
    )
    Spacer(Modifier.height(10.dp))
    Card(colors = CardDefaults.cardColors(containerColor = SurfacePaper), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("文件条目：${inspection.entryCount}", fontWeight = FontWeight.Bold)
            Text(
                "可导出：${inspection.extractableEntryCount} · 暂不支持：$unsupportedEntryCount · 已选择：$selectedCount",
                color = Blue,
                fontSize = 11.sp,
            )
            Text("含 zlib 压缩条目：${inspection.compressedEntryCount}", color = Muted, fontSize = 11.sp)
            Text("解包后约：${formatGameBytes(inspection.originalBytes)}", color = Muted, fontSize = 11.sp)
            if (inspection.parseFailureCount > 0) {
                Text(
                    "解析阶段已自动跳过 ${inspection.parseFailureCount} 个失败档案：",
                    color = Color(0xFFB3261E),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                inspection.parseFailures.forEach { failure ->
                    Text(
                        "• ${failure.item}：${failure.reason}",
                        color = Color(0xFFB3261E),
                        fontSize = 9.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (inspection.parseFailureCount > inspection.parseFailures.size) {
                    Text(
                        "另有 ${inspection.parseFailureCount - inspection.parseFailures.size} 项未展开。",
                        color = Muted,
                        fontSize = 9.sp,
                    )
                }
            }
            if (inspection.protectedEntryCount > 0) {
                if (inspection.unsupportedProtectedArchiveCount == 0) {
                    Text(
                        "检测到 ${inspection.protectedEntryCount} 个受保护条目；已自动识别并验证：${inspection.detectedProtectionSchemes.joinToString()}。导出时仍会逐文件校验。",
                        color = Green,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Text(
                        "${inspection.unsupportedProtectedArchiveCount} 个档案的受保护内容无法验证；其中可正常解析的未受保护条目仍可选择导出。",
                        color = Orange,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Line)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onSelectAll(true) },
                    enabled = !busy && !allExtractableSelected && inspection.extractableEntryCount > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("全选可导出", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { onSelectAll(false) },
                    enabled = !busy && selectedCount > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("全不选", fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            visiblePageEntries.forEachIndexed { index, entry ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(
                        checked = entry.selectionKey in selectedEntryKeys,
                        onCheckedChange = { checked -> onEntrySelectionChange(entry.selectionKey, checked) },
                        enabled = !busy && entry.canExtract,
                    )
                    Column(Modifier.weight(1f).padding(top = 3.dp)) {
                        Text(
                            entry.path,
                            color = if (entry.canExtract) Ink else Muted,
                            fontSize = 11.sp,
                            fontWeight = if (entry.canExtract) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${entry.archiveName} · ${formatGameBytes(entry.originalSize)}",
                            color = Muted,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            when {
                                !entry.canExtract -> entry.limitation ?: "暂不支持导出"
                                entry.protected -> "已验证过滤器：${entry.protectionScheme ?: "未知"}"
                                else -> "标准 XP3 条目"
                            },
                            color = if (entry.canExtract) Green else Color(0xFFB3261E),
                            fontSize = 9.sp,
                        )
                    }
                }
                if (index != visiblePageEntries.lastIndex) HorizontalDivider(color = Line.copy(alpha = .55f))
            }
            if (pageEntries.size > KIRIKIRI_COLLAPSED_ENTRY_COUNT) {
                TextButton(
                    onClick = { pageExpanded = !pageExpanded },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        if (pageExpanded) "收起本页"
                        else "展开本页另外 ${pageEntries.size - KIRIKIRI_COLLAPSED_ENTRY_COUNT} 项",
                    )
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = Line)
            KirikiriPaginationControls(
                page = currentPage,
                pageCount = pageCount,
                pageInput = pageInput,
                onPageInputChange = { pageInput = it.filter(Char::isDigit).take(6) },
                onPageChange = changePage,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    if (!outputReady) {
        Text(
            "尚未选择输出目录；点击导出后会提示先在页面上方完成目录授权。",
            color = Orange,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
    Button(
        onClick = onExtract,
        enabled = !busy && selectedCount > 0,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Rounded.Download, null)
        Spacer(Modifier.width(7.dp))
        Text(
            if (exportingEverything) "后台解包全部 ${inspection.entryCount} 项"
            else "后台导出已选 $selectedCount 项"
        )
    }
    Text(
        "支持标准 XP3 索引、分段、zlib、adlr 校验，以及一组可自动验证的 GARbro 通用内容过滤器和固定 XOR。依赖游戏专用密钥表、私有索引或运行时提取的方案仍会安全拒绝。",
        color = Muted,
        fontSize = 10.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
    TextButton(onClick = onOpenSource) { Text("过滤算法参考：GARbro（MIT）") }
}

@Composable
private fun KirikiriPaginationControls(
    page: Int,
    pageCount: Int,
    pageInput: String,
    onPageInputChange: (String) -> Unit,
    onPageChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onPageChange(1) }, enabled = page > 1) { Text("首页", fontSize = 10.sp) }
            TextButton(onClick = { onPageChange(page - 1) }, enabled = page > 1) { Text("上一页", fontSize = 10.sp) }
            Text("$page / $pageCount", color = Muted, fontSize = 10.sp)
            TextButton(onClick = { onPageChange(page + 1) }, enabled = page < pageCount) { Text("下一页", fontSize = 10.sp) }
            TextButton(onClick = { onPageChange(pageCount) }, enabled = page < pageCount) { Text("末页", fontSize = 10.sp) }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = pageInput,
                onValueChange = onPageInputChange,
                label = { Text("页码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(100.dp),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onPageChange(pageInput.toIntOrNull() ?: page) }) { Text("跳转") }
        }
    }
}

@Composable
private fun OutputDirectoryCard(
    available: Boolean,
    usingSourceDefault: Boolean,
    enabled: Boolean,
    onBrowseStorage: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfacePaper), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).background(BluePaper, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.FolderOpen, null, tint = Blue)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("资源输出位置", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        when {
                            !available -> "单文件或已安装应用需要授权输出目录"
                            usingSourceDefault -> "默认输出到原始资源目录"
                            else -> "使用已选择并记住的输出目录"
                        },
                        color = Muted,
                        fontSize = 10.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onBrowseStorage, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("浏览存储路径")
            }
        }
    }
}

@Composable
private fun RpgMakerPanel(
    state: GameExtractionState,
    busy: Boolean,
    showInputActions: Boolean = true,
    onPickFiles: () -> Unit,
    onPickDirectory: () -> Unit,
    onPickKey: () -> Unit,
    onPickInstalledApp: () -> Unit,
    onExtract: () -> Unit,
    onInjectAudioVolumes: () -> Unit,
    onOpenSource: () -> Unit,
) {
    Text("RPG Maker XP / VX / VX Ace / MV / MZ", fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Text("目录模式会自动识别普通 MV/MZ 项目、RGSS 档案及加密资源；已安装应用模式还会还原 assets/www 下的安卓移植项目。", color = Muted, fontSize = 12.sp)
    if (showInputActions) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onPickFiles, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("选择文件")
            }
            OutlinedButton(onClick = onPickDirectory, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("选择目录")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPickInstalledApp, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Apps, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("选择已安装应用")
        }
    }
    Text(state.rpgInputLabel, color = Blue, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
    state.rpgFiles.take(8).forEach { file ->
        Text(
            "• ${file.relativePath} · ${rpgKindLabel(file.kind)}",
            color = Muted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (state.rpgFiles.size > 8) Text("另有 ${state.rpgFiles.size - 8} 个文件…", color = Muted, fontSize = 11.sp)

    Spacer(Modifier.height(14.dp))
    Card(colors = CardDefaults.cardColors(containerColor = BluePaper), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = Blue)
                Spacer(Modifier.width(8.dp))
                Text("MV/MZ 资源密钥", fontWeight = FontWeight.Bold)
            }
            OutlinedTextField(
                value = state.rpgKeyHex,
                onValueChange = state::setRpgKey,
                enabled = !busy,
                label = { Text("32 位十六进制 encryptionKey") },
                supportingText = { Text(state.rpgKeySource ?: "目录扫描可自动读取 System.json；也可选择加密 PNG 推导") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = onPickKey, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("选择 System.json 或加密 PNG 获取密钥")
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Card(colors = CardDefaults.cardColors(containerColor = SurfacePaper), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("AudioVolumeControl 音量与启动补丁", fontWeight = FontWeight.Bold)
            Text(
                "四项音量均填写 0–100 整数。MV/MZ 以标准插件注入；VX Ace 以 RGSS3 自定义脚本注入。再次注入会按当前设置覆盖同名补丁。",
                color = Muted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RpgAudioVolumeField(
                    label = "BGM",
                    value = state.rpgBgmVolume,
                    enabled = !busy,
                    onValueChange = state::updateRpgBgmVolume,
                    modifier = Modifier.weight(1f),
                )
                RpgAudioVolumeField(
                    label = "BGS",
                    value = state.rpgBgsVolume,
                    enabled = !busy,
                    onValueChange = state::updateRpgBgsVolume,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RpgAudioVolumeField(
                    label = "SE",
                    value = state.rpgSeVolume,
                    enabled = !busy,
                    onValueChange = state::updateRpgSeVolume,
                    modifier = Modifier.weight(1f),
                )
                RpgAudioVolumeField(
                    label = "ME",
                    value = state.rpgMeVolume,
                    enabled = !busy,
                    onValueChange = state::updateRpgMeVolume,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.rpgSkipStartupSplash,
                    onCheckedChange = state::updateRpgSkipStartupSplash,
                    enabled = !busy,
                )
                Column(Modifier.weight(1f)) {
                    Text("跳过可能存在的开场 Logo / 动画", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(
                        "覆盖 MZ 内置启动 Logo、官方 MadeWithMv/CustomLogo 及部分常见 Splash 插件；不会删除资源文件。",
                        color = Muted,
                        fontSize = 9.sp,
                    )
                }
            }
            Button(
                onClick = onInjectAudioVolumes,
                enabled = !busy &&
                    state.rpgDirectoryUri != null &&
                    state.rpgAudioVolumeEngine != null &&
                    state.hasValidRpgAudioVolumes,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("应用音量与启动设置")
            }
            Text(
                when {
                    state.rpgDirectoryUri == null -> "仅可注入可写的已解压游戏目录；不能直接修改已安装 APK 或单独的加密档案。"
                    state.rpgAudioVolumeEngine == null -> "当前目录未检测到 MV/MZ 插件结构或 VX Ace Scripts.rvdata2。"
                    !state.hasValidRpgAudioVolumes -> "四项音量都必须填写 0 到 100 的整数。"
                    else -> "已识别 ${state.rpgAudioVolumeEngine?.displayName}；补丁将直接写入当前游戏目录，并在首次写入时保留原文件备份。第三方自定义开场不保证可识别。"
                },
                color = Muted,
                fontSize = 9.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onExtract,
        enabled = !busy && state.rpgFiles.isNotEmpty() && state.effectiveOutputTreeUri != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Rounded.Download, null)
        Spacer(Modifier.width(7.dp))
        Text("开始解密 / 提取")
    }
    Text(
        "支持 .rgssad/.rgss2a/.rgss3a、已解压 VX Ace、普通或加密的 MV/MZ 项目，以及已安装安卓移植包内的完整 assets/www 项目。解密/提取不会修改源文件；音量与启动补丁会在确认后修改所选目录并创建备份。",
        color = Muted,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
    TextButton(onClick = onOpenSource) { Text("格式参考：RPGMakerDecrypter（GitHub）") }
}

@Composable
private fun UnityPanel(
    state: GameExtractionState,
    busy: Boolean,
    showInputActions: Boolean = true,
    onPickFile: () -> Unit,
    onPickDirectory: () -> Unit,
    onPickInstalledApp: () -> Unit,
    onExtract: () -> Unit,
    onOpenSource: () -> Unit,
) {
    Text("Unity 游戏文件分析", fontWeight = FontWeight.Bold, fontSize = 18.sp)
    Text(
        "可分析 APK/ZIP、单独的 DLL/SO/DAT/资源文件、已解压游戏目录，也可直接选择设备上已安装的应用。",
        color = Muted,
        fontSize = 12.sp,
    )
    if (showInputActions) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onPickFile, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("选择文件")
            }
            OutlinedButton(onClick = onPickDirectory, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("选择目录")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPickInstalledApp, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Apps, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("选择已安装应用")
        }
        Text(
            "列表仅显示带桌面启动入口的应用；会同时分析 base APK 与 split APK，不申请全量应用列表权限。",
            color = Muted,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    state.unityInspection?.let { inspection ->
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SurfacePaper), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(inspection.input.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "输入：${when {
                        inspection.installedPackageName != null -> "已安装应用（${inspection.installedApkCount} 个 APK）"
                        inspection.isDirectory -> "已解压目录"
                        inspection.isArchive -> "APK/ZIP 包"
                        else -> "单文件"
                    }} · 后端：${unityBackendLabel(inspection.backend)}",
                    color = Blue,
                    fontSize = 12.sp,
                )
                inspection.installedPackageName?.let { packageName ->
                    Text(packageName, color = Muted, fontSize = 10.sp)
                }
                Text("扫描 ${inspection.totalEntries} 个条目，识别 ${inspection.artifactCount} 个相关文件", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                inspection.artifactCounts.entries.sortedBy { it.key.ordinal }.forEach { (kind, count) ->
                    Text("${kind.displayName}：$count", fontSize = 12.sp)
                }
                if (
                    (inspection.backend == UnityBackend.IL2CPP || inspection.backend == UnityBackend.MIXED) &&
                    !inspection.hasCompleteIl2CppPair
                ) {
                    Text(
                        "当前输入未同时找到 IL2CPP 本机库和 global-metadata.dat，请从同一游戏构建补齐。",
                        color = Color(0xFFB3261E),
                        fontSize = 11.sp,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Line)
                inspection.previewArtifacts.take(12).forEach { artifact ->
                    Text(
                        "• ${artifact.path}",
                        color = Muted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (inspection.previewTruncated || inspection.previewArtifacts.size > 12) {
                    Text("列表仅显示前 12 项，导出仍会处理全部已勾选文件。", color = Muted, fontSize = 10.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("导出内容", fontWeight = FontWeight.Bold)
        UnityOptionRow("Mono 程序集（Assembly-CSharp.dll 等）", state.unitySelection.monoAssemblies, !busy) {
            state.updateUnitySelection(state.unitySelection.copy(monoAssemblies = it))
        }
        UnityOptionRow("IL2CPP 二件套（SO/DLL + global-metadata.dat）", state.unitySelection.il2CppFiles, !busy) {
            state.updateUnitySelection(state.unitySelection.copy(il2CppFiles = it))
        }
        UnityOptionRow("解析后的图片（PNG，含 Texture2D/Sprite）", state.unitySelection.decodedImages, !busy) {
            state.updateUnitySelection(state.unitySelection.copy(decodedImages = it))
        }
        UnityOptionRow("解析后的音频（WAV 或原始音频容器）", state.unitySelection.decodedAudio, !busy) {
            state.updateUnitySelection(state.unitySelection.copy(decodedAudio = it))
        }
        UnityOptionRow("解析后的视频（VideoClip/MovieTexture 原始容器）", state.unitySelection.decodedVideo, !busy) {
            state.updateUnitySelection(state.unitySelection.copy(decodedVideo = it))
        }
        UnityOptionRow("文本资源（TextAsset 原始内容）", state.unitySelection.decodedText, !busy) {
            state.updateUnitySelection(state.unitySelection.copy(decodedText = it))
        }
        UnityOptionRow("保留原始 Unity 容器（assets/bundle/resS 等）", state.unitySelection.assets, !busy) {
            state.updateUnitySelection(state.unitySelection.copy(assets = it))
        }
        UnityOptionRow("配置与内嵌 APK", state.unitySelection.configs, !busy) {
            state.updateUnitySelection(state.unitySelection.copy(configs = it))
        }
        Button(
            onClick = onExtract,
            enabled = !busy && state.effectiveOutputTreeUri != null && state.unitySelection.hasAny,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Download, null)
            Spacer(Modifier.width(7.dp))
            Text("解析并导出 Unity 内容")
        }
    }
    TextButton(onClick = onOpenSource) { Text("资源分析参考：AssetRipper（GitHub）") }
}

@Composable
private fun GameExtractionDisclaimerDialog(
    accepted: Boolean,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val disclaimerText = remember(context) {
        runCatching {
            context.assets.open("legal/game_extraction_disclaimer.md")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrElse {
            "免责声明内容加载失败，请稍后重试。"
        }
            .lineSequence()
            .filterNot { it.trim() == "## 免责声明" }
            .joinToString("\n") { line ->
                line.replace("**", "").replace(Regex("^#{1,6}\\s+"), "")
            }
            .trim()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("免责声明") },
        text = {
            Text(
                text = disclaimerText,
                color = Ink,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text(if (accepted) "关闭" else "我已阅读并同意") }
        },
        dismissButton = {
            if (!accepted) {
                TextButton(onClick = onDismiss) { Text("暂不使用") }
            }
        },
    )
}

@Composable
private fun RpgAudioVolumeField(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val valid = value.toIntOrNull()?.let { it in 0..100 } == true
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        isError = !valid,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun InstalledAppPickerDialog(
    apps: List<InstalledGameApp>,
    onDismiss: () -> Unit,
    onSelect: (InstalledGameApp) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filteredApps = remember(apps, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) apps else apps.filter { app ->
            app.label.contains(keyword, ignoreCase = true) ||
                app.packageName.contains(keyword, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择已安装应用") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(120) },
                    label = { Text("搜索名称或包名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (filteredApps.isEmpty()) {
                    Text("没有匹配的应用", color = Muted, modifier = Modifier.padding(vertical = 20.dp))
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(filteredApps, key = InstalledGameApp::packageName) { app ->
                            TextButton(onClick = { onSelect(app) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(app.label, color = Ink, fontWeight = FontWeight.Bold)
                                    Text(
                                        buildString {
                                            append(app.packageName)
                                            app.versionName?.takeIf(String::isNotBlank)?.let { append(" · v$it") }
                                            append(" · ${app.apkCount} APK")
                                        },
                                        color = Muted,
                                        fontSize = 10.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            HorizontalDivider(color = Line.copy(alpha = .6f))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun UnityOptionRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

private fun rpgKindLabel(kind: GameInputKind): String = when (kind) {
    GameInputKind.RPG_ARCHIVE -> "RGSS 档案"
    GameInputKind.RPG_ENCRYPTED_RESOURCE -> "MV/MZ 加密资源"
    GameInputKind.RPG_PACKAGE_RESOURCE -> "项目资源"
    else -> "不支持"
}

private fun unityBackendLabel(value: UnityBackend): String = when (value) {
    UnityBackend.MONO -> "Mono"
    UnityBackend.IL2CPP -> "IL2CPP"
    UnityBackend.MIXED -> "Mono + IL2CPP"
    UnityBackend.UNKNOWN -> "未识别代码后端"
}

private fun formatGameBytes(bytes: Long): String = when {
    bytes < 0L -> "未知大小"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KiB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun takeGameUriPermission(context: Context, uri: Uri, write: Boolean) {
    if (!uri.scheme.equals("content", ignoreCase = true)) return
    runCatching {
        if (write) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } else {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
