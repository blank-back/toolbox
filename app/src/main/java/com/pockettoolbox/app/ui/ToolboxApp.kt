package com.pockettoolbox.app.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image as ImageIcon
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettoolbox.app.ToolboxViewModel
import com.pockettoolbox.app.VirusTotalUiState
import com.pockettoolbox.app.model.BiliHistoryEntry
import com.pockettoolbox.app.model.BiliMediaInfo
import com.pockettoolbox.app.model.DownloadKind
import com.pockettoolbox.app.model.MediaKind
import com.pockettoolbox.app.model.MusicHistoryEntry
import com.pockettoolbox.app.model.MusicLyricLine
import com.pockettoolbox.app.model.MusicPlaylist
import com.pockettoolbox.app.model.MusicTrack
import com.pockettoolbox.app.model.PlaylistPlaybackMode
import com.pockettoolbox.app.model.QrHistoryEntry
import com.pockettoolbox.app.model.QrSecurityStatus
import com.pockettoolbox.app.ui.theme.Blue
import com.pockettoolbox.app.ui.theme.BluePaper
import com.pockettoolbox.app.ui.theme.Green
import com.pockettoolbox.app.ui.theme.Ink
import com.pockettoolbox.app.ui.theme.Line
import com.pockettoolbox.app.ui.theme.Muted
import com.pockettoolbox.app.ui.theme.Orange
import com.pockettoolbox.app.ui.theme.Paper
import com.pockettoolbox.app.ui.theme.Sage
import com.pockettoolbox.app.ui.theme.SurfacePaper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private enum class Screen {
    HOME,
    QR,
    QR_HISTORY,
    QR_SECURITY,
    BILI,
    BILI_HISTORY,
    MUSIC,
    MUSIC_HISTORY,
    MUSIC_PLAYLISTS,
}

private data class MusicSourceOption(val value: String, val label: String)

private val MusicSourceOptions = listOf(
    MusicSourceOption("netease", "网易云音乐"),
    MusicSourceOption("tencent", "QQ音乐"),
    MusicSourceOption("tidal", "Tidal"),
    MusicSourceOption("spotify", "Spotify"),
    MusicSourceOption("ytmusic", "YouTube Music"),
    MusicSourceOption("qobuz", "Qobuz"),
    MusicSourceOption("joox", "JOOX"),
    MusicSourceOption("deezer", "Deezer"),
    MusicSourceOption("kugou", "酷狗音乐"),
    MusicSourceOption("kuwo", "酷我音乐"),
    MusicSourceOption("apple", "Apple Music"),
    MusicSourceOption("bilibili", "Bilibili"),
)

private const val COLLAPSED_QR_TEXT_LENGTH = 120
private const val HISTORY_PAGE_SIZE = 10
private const val MUSIC_RESULT_PAGE_SIZE = 10
private const val MUSIC_SECTION_SEARCH = 0
private const val MUSIC_SECTION_PLAYER = 1

@Composable
fun ToolboxApp(viewModel: ToolboxViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val requestMusicNotificationPermission: () -> Unit = {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val goBack = {
        screen = when (screen) {
            Screen.QR_HISTORY -> Screen.QR
            Screen.QR_SECURITY -> Screen.QR
            Screen.BILI_HISTORY -> Screen.BILI
            Screen.MUSIC_HISTORY -> Screen.MUSIC
            Screen.MUSIC_PLAYLISTS -> Screen.MUSIC
            Screen.QR, Screen.BILI, Screen.MUSIC -> Screen.HOME
            Screen.HOME -> Screen.HOME
        }
    }
    BackHandler(enabled = screen != Screen.HOME, onBack = goBack)

    Scaffold(
        containerColor = Paper,
        modifier = Modifier.fillMaxSize(),
        topBar = {
            AppHeader(
                canGoBack = screen != Screen.HOME,
                onHome = { screen = Screen.HOME },
                onBack = goBack,
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = screen,
            label = "screen",
            modifier = Modifier.padding(padding),
        ) { target ->
            when (target) {
                Screen.HOME -> HomeScreen(
                    onQr = { screen = Screen.QR },
                    onBili = { screen = Screen.BILI },
                    onMusic = { screen = Screen.MUSIC },
                )
                Screen.QR -> QrScreen(
                    viewModel = viewModel,
                    onHistory = { screen = Screen.QR_HISTORY },
                    onSecurity = { screen = Screen.QR_SECURITY },
                )
                Screen.QR_HISTORY -> QrHistoryScreen(viewModel)
                Screen.QR_SECURITY -> VirusTotalSettingsScreen(viewModel)
                Screen.BILI -> BiliScreen(viewModel, onHistory = { screen = Screen.BILI_HISTORY })
                Screen.BILI_HISTORY -> BiliHistoryScreen(viewModel)
                Screen.MUSIC -> MusicScreen(
                    viewModel = viewModel,
                    onHistory = { screen = Screen.MUSIC_HISTORY },
                    onPlaylists = { screen = Screen.MUSIC_PLAYLISTS },
                    onPlaybackRequested = requestMusicNotificationPermission,
                )
                Screen.MUSIC_HISTORY -> MusicHistoryScreen(
                    viewModel = viewModel,
                    onSelect = { track ->
                        requestMusicNotificationPermission()
                        viewModel.selectMusic(track)
                        screen = Screen.MUSIC
                    },
                )
                Screen.MUSIC_PLAYLISTS -> MusicPlaylistsScreen(
                    viewModel = viewModel,
                    onStartPlayback = {
                        requestMusicNotificationPermission()
                        screen = Screen.MUSIC
                    },
                )
            }
        }
    }
}

@Composable
private fun AppHeader(canGoBack: Boolean, onHome: () -> Unit, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper)
            .statusBarsPadding()
            .height(68.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.clickable(onClick = onHome),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp, 11.dp, 11.dp, 3.dp))
                    .background(Ink),
                contentAlignment = Alignment.Center,
            ) {
                Text("工", color = Paper, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("随身工具箱", fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp)
                Text("简单 · 克制 · 本地优先", color = Muted, fontSize = 9.sp)
            }
        }
        if (canGoBack) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("返回")
            }
        }
    }
    HorizontalDivider(color = Line.copy(alpha = .7f))
}

@Composable
private fun HomeScreen(onQr: () -> Unit, onBili: () -> Unit, onMusic: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 44.dp),
    ) {
        Eyebrow("POCKET UTILITIES")
        Text(
            "需要时，\n刚好在手边。",
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = 32.sp,
            lineHeight = 36.sp,
            letterSpacing = (-1.5).sp,
        )
        Spacer(Modifier.height(38.dp))
        ToolCard(
            number = "01",
            title = "二维码提取",
            subtitle = "从图片识别二维码，复制或按需打开地址",
            background = Sage,
            icon = { Icon(Icons.Rounded.QrCode2, null, tint = Green, modifier = Modifier.size(64.dp)) },
            onClick = onQr,
        )
        Spacer(Modifier.height(14.dp))
        ToolCard(
            number = "02",
            title = "Bilibili 媒体",
            subtitle = "查询 BV / AU 信息并保存封面、视频或音频",
            background = BluePaper,
            icon = {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Blue),
                    contentAlignment = Alignment.Center,
                ) { Text("B", color = Color.White, fontFamily = FontFamily.Serif, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
            },
            onClick = onBili,
        )
        Spacer(Modifier.height(14.dp))
        ToolCard(
            number = "03",
            title = "歌曲搜索",
            subtitle = "搜索、播放并缓存或下载歌曲",
            background = Color(0xFFE8DFEA),
            icon = { Icon(Icons.Rounded.LibraryMusic, null, tint = Color(0xFF76507B), modifier = Modifier.size(64.dp)) },
            onClick = onMusic,
        )
    }
}

@Composable
private fun ToolCard(
    number: String,
    title: String,
    subtitle: String,
    background: Color,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = background),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.fillMaxWidth().height(150.dp).padding(25.dp)) {
            Icon(Icons.Rounded.ArrowOutward, null, modifier = Modifier.align(Alignment.TopEnd))
            Row(Modifier.align(Alignment.BottomStart), verticalAlignment = Alignment.Bottom) {
                icon()
                Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(number, color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp)
                    Text(title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun PageHeading(number: String, title: String, subtitle: String) {
    Eyebrow("TOOL $number")
    Text(title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 32.sp, letterSpacing = (-1).sp)
    Spacer(Modifier.height(12.dp))
    Text(subtitle, color = Muted, lineHeight = 23.sp)
    Spacer(Modifier.height(28.dp))
    HorizontalDivider(color = Line)
    Spacer(Modifier.height(25.dp))
}

@Composable
private fun Eyebrow(text: String) {
    Text(text, color = Orange, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun QrScreen(
    viewModel: ToolboxViewModel,
    onHistory: () -> Unit,
    onSecurity: () -> Unit,
) {
    val state by viewModel.qrState.collectAsStateWithLifecycle()
    val hasVirusTotalApiKey by viewModel.hasVirusTotalApiKey.collectAsStateWithLifecycle()
    val virusTotalStates by viewModel.virusTotalStates.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var pendingVirusTotalUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::scanQr)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
    ) {
        PageHeading("01", "二维码提取", "图片只在本机内存中解析，不会上传。识别出的地址也不会自动访问。")
        OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.History, null)
            Spacer(Modifier.width(8.dp))
            Text("识别历史")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSecurity, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Link, null)
            Spacer(Modifier.width(8.dp))
            Text("VirusTotal 设置与 API key 教程")
        }
        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = BluePaper.copy(alpha = .7f)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("来源与免责声明", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(5.dp))
                Text(
                    "二维码内容由本地 ZXing 识别；可选的链接安全报告来自 VirusTotal。检测前会再次确认，只有确认后完整 URL 才会发送给 VirusTotal。结果仅供参考，未命中不等于绝对安全，本应用及第三方检测引擎均不对结果作安全保证。请勿提交私密或含敏感信息的 URL。",
                    color = Muted,
                    fontSize = 10.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        if (state.preview == null) {
            Card(
                onClick = { picker.launch(arrayOf("image/*")) },
                colors = CardDefaults.cardColors(containerColor = SurfacePaper.copy(alpha = .65f)),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth().height(290.dp).border(1.dp, Line, RoundedCornerShape(22.dp)),
            ) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.UploadFile, null, tint = Green, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.height(17.dp))
                    Text("选择一张图片", fontFamily = FontFamily.Serif, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(7.dp))
                    Text("支持系统相册、文件和云盘", color = Muted, fontSize = 12.sp)
                }
            }
        } else {
            Image(
                bitmap = state.preview!!.asImageBitmap(),
                contentDescription = "待识别图片预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(18.dp)).background(Line.copy(alpha = .4f)),
            )
            TextButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text("更换图片") }
        }
        StatusBlock(state.busy, state.message, state.isError)
        state.results.forEachIndexed { index, result ->
            HorizontalDivider(color = Line)
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(String.format(Locale.ROOT, "%02d", index + 1), color = Orange, fontSize = 10.sp)
                    Spacer(Modifier.width(12.dp))
                    ExpandableQrText(
                        text = result.text,
                        color = if (result.webUrl != null) Blue else Ink,
                        onClick = result.webUrl?.let { url ->
                            { openWebUrl(context, url) }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        copyText(context, "二维码内容", result.text)
                    }) { Icon(Icons.Rounded.ContentCopy, "复制", Modifier.size(20.dp)) }
                }
                result.webUrl?.let { url ->
                    val virusTotalState = virusTotalStates[url]
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (hasVirusTotalApiKey) pendingVirusTotalUrl = url
                            else showApiKeyDialog = true
                        },
                        enabled = virusTotalState?.busy != true,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (virusTotalState?.busy == true) {
                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (virusTotalState?.report == null) "使用 VirusTotal 检测" else "重新查询 VirusTotal")
                    }
                    virusTotalState?.let { check ->
                        VirusTotalResultBlock(
                            state = check,
                            onRefresh = { viewModel.refreshVirusTotal(url) },
                            onOpenReport = { reportUrl -> openWebUrl(context, reportUrl) },
                        )
                    }
                }
            }
        }
    }

    if (showApiKeyDialog) {
        VirusTotalApiKeyDialog(
            title = if (hasVirusTotalApiKey) "更改 API key" else "首次使用：设置 API key",
            onDismiss = { showApiKeyDialog = false },
            onSave = viewModel::saveVirusTotalApiKey,
        )
    }
    pendingVirusTotalUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingVirusTotalUrl = null },
            title = { Text("发送到 VirusTotal？") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text("继续后，下面的完整 URL 会发送给 VirusTotal 查询公开报告；若没有报告，将提交一次扫描。应用不会自动打开该 URL，也不会自动轮询。")
                    Spacer(Modifier.height(10.dp))
                    Text(url, color = Blue, fontSize = 11.sp, lineHeight = 16.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingVirusTotalUrl = null
                    viewModel.checkVirusTotal(url)
                }) { Text("同意并检测") }
            },
            dismissButton = {
                TextButton(onClick = { pendingVirusTotalUrl = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ExpandableQrText(
    text: String,
    color: Color,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val canExpand = text.length > COLLAPSED_QR_TEXT_LENGTH
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    Column(modifier) {
        Text(
            text = if (canExpand && !expanded) {
                text.take(COLLAPSED_QR_TEXT_LENGTH).trimEnd() + "…"
            } else {
                text
            },
            color = color,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = if (canExpand && !expanded) 3 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().then(
                if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
            ),
        )
        if (canExpand) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.height(34.dp),
            ) {
                Text(if (expanded) "收起" else "展开完整内容", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun VirusTotalResultBlock(
    state: VirusTotalUiState,
    onRefresh: () -> Unit,
    onOpenReport: (String) -> Unit,
) {
    if (state.message != null) StatusBlock(state.busy, state.message, state.isError)
    state.report?.let { report ->
        Spacer(Modifier.height(9.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (state.isError) Color(0xFFF2DDD8) else Color(0xFFDCE9DF),
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(13.dp)) {
                Text("VirusTotal 检测统计", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "恶意 ${report.stats.malicious} · 可疑 ${report.stats.suspicious} · 无害 ${report.stats.harmless} · 未检出 ${report.stats.undetected} · 超时 ${report.stats.timeout} · 失败 ${report.stats.failure} · 不支持 ${report.stats.unsupported}",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                )
                report.analysisEpochSeconds?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("报告时间：${formatHistoryTime(it * 1_000L)}", color = Muted, fontSize = 10.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onRefresh, enabled = !state.busy) { Text("刷新") }
                    TextButton(onClick = { onOpenReport(report.reportUrl) }) {
                        Text("查看来源")
                        Icon(Icons.Rounded.ArrowOutward, null, Modifier.size(15.dp))
                    }
                }
                Text("来源：VirusTotal。统计仅供参考，不构成安全结论。", color = Muted, fontSize = 9.sp)
            }
        }
    } ?: run {
        if (state.analysisId != null) {
            TextButton(onClick = onRefresh, enabled = !state.busy) { Text("手动刷新扫描结果") }
        }
    }
}

@Composable
private fun VirusTotalApiKeyDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Boolean,
) {
    // Secret input must not enter Android SavedState; closing or rotating clears it.
    var input by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("API key 将由 Android Keystore 的 AES-GCM 密钥加密保存，不会以明文写入偏好设置；界面也不会再次显示已保存的 key。")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        if (value.length <= 512) input = value
                        errorMessage = null
                    },
                    label = { Text("VirusTotal API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { message ->
                        { Text(message) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("key 仅在你确认检测时通过 HTTPS 发给 www.virustotal.com。", color = Muted, fontSize = 10.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (onSave(input)) onDismiss()
                else errorMessage = "无法保存，请检查 key 格式和系统密钥库状态。"
            }) { Text("加密保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun VirusTotalSettingsScreen(viewModel: ToolboxViewModel) {
    val hasApiKey by viewModel.hasVirusTotalApiKey.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var confirmRemoval by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(20.dp),
    ) {
        PageHeading("01 · SECURITY", "VirusTotal 设置", "管理 API key，并查看获取方法、数据发送范围和检测免责声明。")
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfacePaper),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(16.dp)),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(if (hasApiKey) "API key 已加密保存" else "尚未设置 API key", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "使用 Android Keystore + AES-GCM。应用只保存密文与随机 IV，不提供已保存 key 的明文查看功能。",
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { showApiKeyDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (hasApiKey) "更改 API key" else "输入 API key")
                }
                if (hasApiKey) {
                    TextButton(onClick = { confirmRemoval = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("移除已保存的 API key")
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Text("如何获取 API key", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "1. 打开 VirusTotal Community 注册页面并创建账户。\n\n2. 登录后进入 My API Key 页面。\n\n3. 复制页面显示的 API key，返回本应用点击“输入 API key”并保存。\n\n4. 回到二维码提取页，识别 HTTP(S) 链接后点击 VirusTotal 检测。每次发送 URL 前都会要求确认。",
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { openWebUrl(context, "https://www.virustotal.com/gui/join-us") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("打开账户注册页面") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { openWebUrl(context, "https://www.virustotal.com/gui/my-apikey") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("打开 My API Key 页面") }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { openWebUrl(context, "https://docs.virustotal.com/docs/please-give-me-an-api-key") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("阅读 VirusTotal 官方 API key 教程") }
        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEEE5CF)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(15.dp)) {
                Text("使用限制、来源与免责声明", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "VirusTotal 公共 API 的默认额度较低（官方文档标注为每分钟 4 次），且受其服务条款和使用限制约束。本应用不会自动高频轮询。完整 URL 可能包含路径、查询参数或敏感令牌，请确认不含隐私信息后再提交。安全统计与分类来源于 VirusTotal 及其第三方检测引擎，仅供参考，可能存在误报、漏报或报告滞后；无命中不代表安全，也不构成任何保证或专业建议。",
                    color = Ink,
                    fontSize = 10.sp,
                    lineHeight = 16.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        TextButton(
            onClick = { openWebUrl(context, "https://docs.virustotal.com/docs/api-overview") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("查看 VirusTotal API 官方说明") }
    }

    if (showApiKeyDialog) {
        VirusTotalApiKeyDialog(
            title = if (hasApiKey) "更改 API key" else "设置 API key",
            onDismiss = { showApiKeyDialog = false },
            onSave = viewModel::saveVirusTotalApiKey,
        )
    }
    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text("移除 API key？") },
            text = { Text("移除后需要重新输入 key 才能使用 VirusTotal 检测。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoval = false
                    viewModel.removeVirusTotalApiKey()
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun QrHistoryScreen(viewModel: ToolboxViewModel) {
    val history by viewModel.qrHistory.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var currentPage by rememberSaveable { mutableStateOf(1) }
    val safePage = currentPage.coerceIn(1, pageCount(history.size, HISTORY_PAGE_SIZE))
    LaunchedEffect(history.size) { currentPage = safePage }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(20.dp),
    ) {
        PageHeading("01 · HISTORY", "识别历史", "记录二维码解析时间、识别内容及简要安全检测结果。只有明确点击链接时才会访问。")
        HistoryHeader(history.size, viewModel::clearQrHistory)
        if (history.isEmpty()) {
            EmptyHistory("还没有二维码识别记录。")
        } else {
            history.pageSlice(safePage, HISTORY_PAGE_SIZE).forEachIndexed { index, entry ->
                QrHistoryCard((safePage - 1) * HISTORY_PAGE_SIZE + index, entry, context)
                Spacer(Modifier.height(10.dp))
            }
            PaginationControls(
                itemCount = history.size,
                pageSize = HISTORY_PAGE_SIZE,
                currentPage = safePage,
                onPageChange = { currentPage = it },
            )
        }
    }
}

@Composable
private fun QrHistoryCard(index: Int, entry: QrHistoryEntry, context: Context) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfacePaper),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(String.format(Locale.ROOT, "%02d", index + 1), color = Orange, fontSize = 10.sp)
                Text(formatHistoryTime(entry.parsedAtEpochMillis), color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(10.dp))
            ExpandableQrText(
                text = entry.text,
                color = if (entry.webUrl != null) Blue else Ink,
                onClick = entry.webUrl?.let { url ->
                    { openWebUrl(context, url) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(11.dp))
            QrSecurityHistoryBlock(entry)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { copyText(context, "二维码内容", entry.text) }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("复制")
                }
                if (entry.webUrl != null) {
                    TextButton(onClick = { openWebUrl(context, entry.webUrl) }) {
                        Icon(Icons.Rounded.ArrowOutward, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("打开链接")
                    }
                }
            }
        }
    }
}

@Composable
private fun QrSecurityHistoryBlock(entry: QrHistoryEntry) {
    val security = entry.security
    val risky = security.malicious > 0 || security.suspicious > 0
    val background = when (security.status) {
        QrSecurityStatus.COMPLETED -> if (risky) Color(0xFFF2DDD8) else Color(0xFFDCE9DF)
        QrSecurityStatus.PENDING -> Color(0xFFEEE5CF)
        QrSecurityStatus.FAILED -> Color(0xFFF2DDD8)
        QrSecurityStatus.NOT_CHECKED -> Line.copy(alpha = .35f)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = background),
        shape = RoundedCornerShape(11.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(11.dp)) {
            when (security.status) {
                QrSecurityStatus.NOT_CHECKED -> {
                    Text(
                        if (entry.webUrl == null) "安全检测：未检测（非 HTTP(S) 链接）" else "安全检测：未检测",
                        color = Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                QrSecurityStatus.PENDING -> {
                    Text("VirusTotal：检测已发起，正在处理或等待报告", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                QrSecurityStatus.FAILED -> {
                    Text("VirusTotal：检测失败，暂无可用结论", color = Color(0xFF7E352B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                QrSecurityStatus.COMPLETED -> {
                    Text(
                        when {
                            security.malicious > 0 -> "VirusTotal：${security.malicious} 个引擎标记为恶意"
                            security.suspicious > 0 -> "VirusTotal：${security.suspicious} 个引擎标记为可疑"
                            else -> "VirusTotal：未发现恶意或可疑标记"
                        },
                        color = if (risky) Color(0xFF7E352B) else Ink,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "恶意 ${security.malicious} · 可疑 ${security.suspicious} · 无害 ${security.harmless} · 未检出 ${security.undetected}",
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
            security.checkedAtEpochMillis?.let { checkedAt ->
                Spacer(Modifier.height(4.dp))
                Text("检测时间：${formatHistoryTime(checkedAt)}", color = Muted, fontSize = 9.sp)
            }
            if (security.status != QrSecurityStatus.NOT_CHECKED) {
                Spacer(Modifier.height(3.dp))
                Text("来源：VirusTotal，结果仅供参考；无命中不代表安全。", color = Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun BiliScreen(viewModel: ToolboxViewModel, onHistory: () -> Unit) {
    val state by viewModel.biliState.collectAsStateWithLifecycle()
    val downloadTreeUri by viewModel.downloadTreeUri.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var pendingKindName by rememberSaveable { mutableStateOf<String?>(null) }
    val directoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val kind = pendingKindName?.let { runCatching { DownloadKind.valueOf(it) }.getOrNull() }
        pendingKindName = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                viewModel.setDownloadDirectory(uri)
            }.onSuccess {
                kind?.let(viewModel::download)
            }.onFailure {
                viewModel.reportBiliError("无法保存该目录的长期访问权限，请选择其他目录。")
            }
        } else if (kind != null) {
            viewModel.clearBiliMessage()
        }
    }
    val selectDirectory: (DownloadKind?) -> Unit = { kind ->
        pendingKindName = kind?.name
        directoryLauncher.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(20.dp),
    ) {
        PageHeading("02", "Bilibili 媒体", "输入 BV / AU 号或包含它的链接。只在点击下载后保存内容。")
        OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.History, null)
            Spacer(Modifier.width(8.dp))
            Text("解析历史")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.take(500) },
            label = { Text("视频 / 音频代码") },
            placeholder = { Text("例如 BV1xx411c7mD 或 au123456") },
            singleLine = true,
            enabled = !state.busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (input.isNotBlank()) viewModel.lookupBili(input) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { viewModel.lookupBili(input) },
            enabled = input.isNotBlank() && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Search, null)
            Spacer(Modifier.width(8.dp))
            Text("查询信息")
        }
        Text("也可直接粘贴 bilibili.com 的视频或音频链接", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
        Spacer(Modifier.height(14.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfacePaper),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.FolderOpen, null, tint = Green)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (downloadTreeUri == null) "尚未选择保存目录" else "保存到 ${readableTreeName(downloadTreeUri)}", fontSize = 12.sp)
                    Text("选择一次后，后续下载会直接保存", color = Muted, fontSize = 10.sp)
                }
                TextButton(onClick = { selectDirectory(null) }, enabled = !state.busy) {
                    Text(if (downloadTreeUri == null) "选择" else "更改")
                }
            }
        }
        StatusBlock(state.busy, state.message, state.isError)
        state.media?.let { media ->
            Spacer(Modifier.height(18.dp))
            MediaCard(
                media = media,
                busy = state.busy,
                onOpenSource = { openWebUrl(context, media.id.pageUrl) },
                onDownload = { kind ->
                    if (downloadTreeUri == null) selectDirectory(kind) else viewModel.download(kind)
                },
            )
        }
    }
}

@Composable
private fun BiliHistoryScreen(viewModel: ToolboxViewModel) {
    val history by viewModel.biliHistory.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var currentPage by rememberSaveable { mutableStateOf(1) }
    val safePage = currentPage.coerceIn(1, pageCount(history.size, HISTORY_PAGE_SIZE))
    LaunchedEffect(history.size) { currentPage = safePage }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(20.dp),
    ) {
        PageHeading("02 · HISTORY", "解析历史", "记录解析时间、BV / AU 号及其对应的 Bilibili 来源链接。")
        HistoryHeader(history.size, viewModel::clearBiliHistory)
        if (history.isEmpty()) {
            EmptyHistory("还没有 Bilibili 解析记录。")
        } else {
            history.pageSlice(safePage, HISTORY_PAGE_SIZE).forEach { entry ->
                BiliHistoryCard(entry, context)
                Spacer(Modifier.height(10.dp))
            }
            PaginationControls(
                itemCount = history.size,
                pageSize = HISTORY_PAGE_SIZE,
                currentPage = safePage,
                onPageChange = { currentPage = it },
            )
        }
    }
}

@Composable
private fun BiliHistoryCard(entry: BiliHistoryEntry, context: Context) {
    Card(
        onClick = { openWebUrl(context, entry.pageUrl) },
        colors = CardDefaults.cardColors(containerColor = SurfacePaper),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(entry.id.value, color = Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(formatHistoryTime(entry.parsedAtEpochMillis), color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(9.dp))
            Text(entry.title, fontFamily = FontFamily.Serif, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Link, null, tint = Blue, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(entry.pageUrl, color = Blue, fontSize = 11.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { copyText(context, "Bilibili 链接", entry.pageUrl) }) {
                    Icon(Icons.Rounded.ContentCopy, "复制链接", Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun HistoryHeader(count: Int, onClear: () -> Unit) {
    var confirmingClear by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("共 $count 条 · 最多保留 100 条", color = Muted, fontSize = 11.sp)
        if (count > 0) TextButton(onClick = { confirmingClear = true }) { Text("清空") }
    }
    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("清空历史记录？") },
            text = { Text("此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    onClear()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun EmptyHistory(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfacePaper.copy(alpha = .6f)),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(message, color = Muted, modifier = Modifier.padding(28.dp))
    }
}

@Composable
private fun PaginationControls(
    itemCount: Int,
    pageSize: Int,
    currentPage: Int,
    onPageChange: (Int) -> Unit,
) {
    if (itemCount <= pageSize) return
    val totalPages = pageCount(itemCount, pageSize)
    var pageInput by rememberSaveable { mutableStateOf(currentPage.toString()) }
    LaunchedEffect(currentPage) { pageInput = currentPage.toString() }
    val jumpPage = pageInput.toIntOrNull()?.coerceIn(1, totalPages)

    Spacer(Modifier.height(10.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfacePaper.copy(alpha = .72f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "第 $currentPage / $totalPages 页 · 每页 $pageSize 条",
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onPageChange(1) },
                    enabled = currentPage > 1,
                    modifier = Modifier.weight(1f),
                ) { Text("首页", fontSize = 11.sp) }
                TextButton(
                    onClick = { onPageChange(currentPage - 1) },
                    enabled = currentPage > 1,
                    modifier = Modifier.weight(1f),
                ) { Text("上一页", fontSize = 11.sp) }
                TextButton(
                    onClick = { onPageChange(currentPage + 1) },
                    enabled = currentPage < totalPages,
                    modifier = Modifier.weight(1f),
                ) { Text("下一页", fontSize = 11.sp) }
                TextButton(
                    onClick = { onPageChange(totalPages) },
                    enabled = currentPage < totalPages,
                    modifier = Modifier.weight(1f),
                ) { Text("末页", fontSize = 11.sp) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = pageInput,
                    onValueChange = { value ->
                        if (value.length <= 6 && value.all(Char::isDigit)) pageInput = value
                    },
                    label = { Text("输入页码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { jumpPage?.let(onPageChange) },
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { jumpPage?.let(onPageChange) },
                    enabled = jumpPage != null,
                ) { Text("跳转") }
            }
        }
    }
}

private fun pageCount(itemCount: Int, pageSize: Int): Int =
    maxOf(1, (itemCount + pageSize - 1) / pageSize)

private fun <T> List<T>.pageSlice(page: Int, pageSize: Int): List<T> {
    val fromIndex = ((page.coerceAtLeast(1) - 1) * pageSize).coerceAtMost(size)
    val toIndex = (fromIndex + pageSize).coerceAtMost(size)
    return subList(fromIndex, toIndex)
}

@Composable
private fun MusicScreen(
    viewModel: ToolboxViewModel,
    onHistory: () -> Unit,
    onPlaylists: () -> Unit,
    onPlaybackRequested: () -> Unit,
) {
    val state by viewModel.musicState.collectAsStateWithLifecycle()
    val playlists by viewModel.musicPlaylists.collectAsStateWithLifecycle()
    val downloadTreeUri by viewModel.downloadTreeUri.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var keyword by rememberSaveable { mutableStateOf("") }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    val sourceMenuScrollState = rememberScrollState()
    var chooseDirectoryThenDownload by rememberSaveable { mutableStateOf(false) }
    var trackToAdd by remember { mutableStateOf<MusicTrack?>(null) }
    var confirmClearCache by rememberSaveable { mutableStateOf(false) }
    var resultPage by rememberSaveable { mutableStateOf(1) }
    val safeResultPage = resultPage.coerceIn(1, pageCount(state.results.size, MUSIC_RESULT_PAGE_SIZE))
    val musicSectionPagerState = rememberPagerState(
        initialPage = if (state.currentTrack == null) MUSIC_SECTION_SEARCH else MUSIC_SECTION_PLAYER,
        pageCount = { 2 },
    )
    val musicSectionScope = rememberCoroutineScope()
    LaunchedEffect(state.results) { resultPage = 1 }
    LaunchedEffect(state.currentTrack?.cacheKey) {
        musicSectionPagerState.animateScrollToPage(
            if (state.currentTrack == null) MUSIC_SECTION_SEARCH else MUSIC_SECTION_PLAYER,
        )
    }
    val directoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                viewModel.setDownloadDirectory(uri)
            }.onSuccess {
                if (chooseDirectoryThenDownload) viewModel.downloadMusic()
            }.onFailure {
                viewModel.reportMusicError("无法保存该目录的长期访问权限，请选择其他目录。")
            }
        }
        chooseDirectoryThenDownload = false
    }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Settings.canDrawOverlays(context)) {
            viewModel.setDesktopLyricsEnabled(true)
        } else {
            viewModel.reportMusicError("未获得悬浮窗权限，桌面歌词未开启。")
        }
    }
    val setDesktopLyricsEnabled: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            viewModel.setDesktopLyricsEnabled(false)
        } else if (Settings.canDrawOverlays(context)) {
            viewModel.setDesktopLyricsEnabled(true)
        } else {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }
    val selectDirectory: (Boolean) -> Unit = { downloadAfterSelection ->
        chooseDirectoryThenDownload = downloadAfterSelection
        directoryLauncher.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .padding(20.dp),
    ) {
        PageHeading("03", "歌曲搜索", "搜索、播放或下载歌曲。")
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (musicSectionPagerState.currentPage == MUSIC_SECTION_SEARCH) {
                Button(
                    onClick = { },
                    modifier = Modifier.weight(1f),
                ) { Text("搜索与结果") }
            } else {
                OutlinedButton(
                    onClick = {
                        musicSectionScope.launch {
                            musicSectionPagerState.animateScrollToPage(MUSIC_SECTION_SEARCH)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("搜索与结果") }
            }
            if (musicSectionPagerState.currentPage == MUSIC_SECTION_PLAYER) {
                Button(
                    onClick = { },
                    modifier = Modifier.weight(1f),
                ) { Text("播放卡片") }
            } else {
                OutlinedButton(
                    onClick = {
                        musicSectionScope.launch {
                            musicSectionPagerState.animateScrollToPage(MUSIC_SECTION_PLAYER)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("播放卡片") }
            }
        }
        Text(
            "可点击上方按钮或左右滑动切换；选择歌曲后会自动进入播放卡片。",
            color = Muted,
            fontSize = 9.sp,
            modifier = Modifier.padding(top = 5.dp, bottom = 7.dp),
        )
        HorizontalPager(
            state = musicSectionPagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            pageSpacing = 12.dp,
        ) { section ->
            when (section) {
                MUSIC_SECTION_SEARCH -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 20.dp),
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8DFEA)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("API 来源：gd音乐台", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                "music.gdstudio.xyz",
                                color = Blue,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable {
                                    openWebUrl(context, "https://music.gdstudio.xyz/")
                                },
                            )
                            Text("应用内限制为 5 分钟最多 45 次请求。", color = Muted, fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onHistory, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.History, null)
                        Spacer(Modifier.width(8.dp))
                        Text("搜索记录")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onPlaylists, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.LibraryMusic, null)
                        Spacer(Modifier.width(8.dp))
                        Text("我的歌单（${playlists.size}）")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { confirmClearCache = true },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("清除音乐缓存")
                    }
                    Text(
                        "播放会在缓冲数据可用后开始，并把同一读取链路写入本地 LRU 缓存。",
                        color = Muted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { sourceMenuExpanded = true },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "音乐源：${MusicSourceOptions.firstOrNull { it.value == state.searchSource }?.label ?: MusicSourceOptions.first().label}",
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.Rounded.ArrowDropDown, "切换音乐源")
                        }
                        DropdownMenu(
                            expanded = sourceMenuExpanded,
                            onDismissRequest = { sourceMenuExpanded = false },
                            modifier = Modifier.heightIn(max = 360.dp),
                            scrollState = sourceMenuScrollState,
                        ) {
                            MusicSourceOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        viewModel.setMusicSearchSource(option.value)
                                        sourceMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it.take(100) },
                        label = { Text("歌曲名 / 歌手") },
                        singleLine = true,
                        enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (keyword.isNotBlank()) viewModel.searchMusic(keyword, state.searchSource)
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.searchMusic(keyword, state.searchSource) },
                        enabled = keyword.isNotBlank() && !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Search, null)
                        Spacer(Modifier.width(8.dp))
                        Text("搜索歌曲")
                    }
                    Text("每次从当前选择的音乐源显示最多 30 条结果。切换音源不会自动发起请求。", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
                    Text("请仅播放或下载你有权使用的内容，并遵守对应音乐平台及 API 服务规则。", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                    StatusBlock(state.busy, state.message, state.isError)

                    if (state.results.isNotEmpty()) {
                        Spacer(Modifier.height(22.dp))
                        Text("搜索结果", fontFamily = FontFamily.Serif, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        state.results.pageSlice(safeResultPage, MUSIC_RESULT_PAGE_SIZE).forEachIndexed { index, track ->
                            MusicResultCard(
                                index = (safeResultPage - 1) * MUSIC_RESULT_PAGE_SIZE + index,
                                track = track,
                                selected = track.cacheKey == state.currentTrack?.cacheKey,
                                enabled = !state.busy,
                                onClick = {
                                    onPlaybackRequested()
                                    viewModel.selectMusic(track)
                                    musicSectionScope.launch {
                                        musicSectionPagerState.animateScrollToPage(MUSIC_SECTION_PLAYER)
                                    }
                                },
                                onAddToPlaylist = { trackToAdd = track },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        PaginationControls(
                            itemCount = state.results.size,
                            pageSize = MUSIC_RESULT_PAGE_SIZE,
                            currentPage = safeResultPage,
                            onPageChange = { resultPage = it },
                        )
                    }
                }
                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 20.dp),
                ) {
                    StatusBlock(state.busy, state.message, state.isError)
                    state.currentTrack?.let { track ->
                        Spacer(Modifier.height(8.dp))
                        MusicPlayerCard(
                            track = track,
                            isPlaying = state.isPlaying,
                            busy = state.busy,
                            playbackRetryAvailable = state.playbackRetryAvailable,
                            positionMillis = state.positionMillis,
                            durationMillis = state.durationMillis,
                            lyrics = state.lyrics,
                            lyricsLoading = state.lyricsLoading,
                            currentLyricIndex = state.currentLyricIndex,
                            desktopLyricsEnabled = state.desktopLyricsEnabled,
                            playlistName = state.activePlaylistId?.let { playlistId ->
                                playlists.firstOrNull { it.id == playlistId }?.name
                            },
                            playlistPlaybackMode = state.playlistPlaybackMode,
                            downloadTreeUri = downloadTreeUri,
                            onToggle = {
                                onPlaybackRequested()
                                viewModel.toggleMusicPlayback()
                            },
                            onPrevious = viewModel::playPreviousPlaylistTrack,
                            onNext = viewModel::playNextPlaylistTrack,
                            onSeek = viewModel::seekMusic,
                            onDesktopLyricsChange = setDesktopLyricsEnabled,
                            onDownload = {
                                if (downloadTreeUri == null) selectDirectory(true) else viewModel.downloadMusic()
                            },
                            onChangeDirectory = { selectDirectory(false) },
                            onAddToPlaylist = { trackToAdd = track },
                        )
                    } ?: EmptyHistory("尚未选择歌曲。请切换到“搜索与结果”并点击一首歌曲。")
                }
            }
        }
    }

    trackToAdd?.let { track ->
        AddTrackToPlaylistDialog(
            track = track,
            playlists = playlists,
            onDismiss = { trackToAdd = null },
            onAdd = { playlistId ->
                if (viewModel.addTrackToPlaylist(playlistId, track)) trackToAdd = null
            },
        )
    }
    if (confirmClearCache) {
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            title = { Text("清除音乐缓存？") },
            text = { Text("正在播放的歌曲会停止。搜索记录、歌单和已下载到自定义目录的文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearCache = false
                    viewModel.clearMusicCache()
                }) { Text("清除缓存") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearCache = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun MusicPlayerCard(
    track: MusicTrack,
    isPlaying: Boolean,
    busy: Boolean,
    playbackRetryAvailable: Boolean,
    positionMillis: Int,
    durationMillis: Int,
    lyrics: List<MusicLyricLine>,
    lyricsLoading: Boolean,
    currentLyricIndex: Int,
    desktopLyricsEnabled: Boolean,
    playlistName: String?,
    playlistPlaybackMode: PlaylistPlaybackMode?,
    downloadTreeUri: String?,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Int) -> Unit,
    onDesktopLyricsChange: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onChangeDirectory: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfacePaper),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(20.dp)),
    ) {
        Column(Modifier.padding(17.dp)) {
            Text("正在播放", color = Orange, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(7.dp))
            Text(track.name, fontFamily = FontFamily.Serif, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("${track.artistText} · ${track.album}", color = Muted, fontSize = 12.sp)
            if (playlistName != null && playlistPlaybackMode != null) {
                Text(
                    "$playlistName · ${if (playlistPlaybackMode == PlaylistPlaybackMode.LIST_LOOP) "列表循环" else "随机播放"}",
                    color = Blue,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Slider(
                value = positionMillis.toFloat().coerceIn(0f, durationMillis.coerceAtLeast(1).toFloat()),
                onValueChange = { onSeek(it.toInt()) },
                valueRange = 0f..durationMillis.coerceAtLeast(1).toFloat(),
                enabled = durationMillis > 0,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatAudioTime(positionMillis), color = Muted, fontSize = 10.sp)
                Text(formatAudioTime(durationMillis), color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (playlistPlaybackMode != null) {
                    IconButton(onClick = onPrevious, enabled = !busy) {
                        Icon(Icons.Rounded.SkipPrevious, "上一首")
                    }
                }
                Button(
                    onClick = onToggle,
                    enabled = !busy && (durationMillis > 0 || playbackRetryAvailable),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            isPlaying -> "暂停"
                            playbackRetryAvailable -> "重试播放"
                            else -> "播放"
                        },
                    )
                }
                if (playlistPlaybackMode != null) {
                    IconButton(onClick = onNext, enabled = !busy) {
                        Icon(Icons.Rounded.SkipNext, "下一首")
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            MusicLyricsPanel(
                lyrics = lyrics,
                loading = lyricsLoading,
                currentIndex = currentLyricIndex,
                onSeek = { line -> onSeek(line.startTimeMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("桌面歌词", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("需要系统悬浮窗权限，可拖动悬浮歌词。", color = Muted, fontSize = 9.sp)
                }
                Switch(
                    checked = desktopLyricsEnabled,
                    onCheckedChange = onDesktopLyricsChange,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onAddToPlaylist, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.LibraryMusic, null)
                Spacer(Modifier.width(8.dp))
                Text("加入歌单")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDownload, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("下载歌曲")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (downloadTreeUri == null) "尚未选择保存目录" else "保存到 ${readableTreeName(downloadTreeUri)}",
                    color = Muted,
                    fontSize = 10.sp,
                )
                TextButton(onClick = onChangeDirectory, enabled = !busy) {
                    Text(if (downloadTreeUri == null) "选择目录" else "更改目录")
                }
            }
        }
    }
}

@Composable
private fun MusicLyricsPanel(
    lyrics: List<MusicLyricLine>,
    loading: Boolean,
    currentIndex: Int,
    onSeek: (MusicLyricLine) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex, lyrics.size) {
        if (currentIndex in lyrics.indices) {
            listState.animateScrollToItem(currentIndex)
        }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1EEE5)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("歌词", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("来源：gd音乐台 API", color = Muted, fontSize = 9.sp)
            }
            HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 8.dp))
            when {
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text("正在获取歌词…", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                lyrics.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("暂无歌词", color = Muted, fontSize = 13.sp)
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().height(190.dp),
                ) {
                    itemsIndexed(
                        items = lyrics,
                        key = { index, line -> "${line.startTimeMillis}:$index" },
                    ) { index, line ->
                        val active = index == currentIndex
                        Text(
                            text = line.displayText,
                            color = if (active) Orange else Muted,
                            fontSize = if (active) 15.sp else 12.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSeek(line) }
                                .padding(horizontal = 5.dp, vertical = if (active) 11.dp else 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicResultCard(
    index: Int,
    track: MusicTrack,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFFE8DFEA) else SurfacePaper),
        shape = RoundedCornerShape(15.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(String.format(Locale.ROOT, "%02d", index + 1), color = Orange, fontSize = 10.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("${track.artistText} · ${track.album}", color = Muted, fontSize = 11.sp)
                Text("当前音乐源：${musicSourceName(track.source)}", color = Blue, fontSize = 9.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onAddToPlaylist, enabled = enabled) {
                    Icon(Icons.Rounded.LibraryMusic, "加入歌单", tint = Blue)
                }
                Icon(Icons.Rounded.PlayArrow, "播放", tint = if (selected) Orange else Ink)
            }
        }
    }
}

@Composable
private fun AddTrackToPlaylistDialog(
    track: MusicTrack,
    playlists: List<MusicPlaylist>,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("将 ${track.name} 加入歌单") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (playlists.isEmpty()) {
                    Text("还没有歌单，请先进入“我的歌单”创建。", color = Muted)
                } else {
                    playlists.forEach { playlist ->
                        val alreadyAdded = playlist.tracks.any { it.cacheKey == track.cacheKey }
                        val isFull = playlist.tracks.size >= 500
                        OutlinedButton(
                            onClick = { onAdd(playlist.id) },
                            enabled = !alreadyAdded && !isFull,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(playlist.name, modifier = Modifier.weight(1f))
                            Text(
                                when {
                                    alreadyAdded -> "已添加"
                                    isFull -> "已满"
                                    else -> "${playlist.tracks.size} 首"
                                },
                                color = Muted,
                                fontSize = 11.sp,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun MusicPlaylistsScreen(
    viewModel: ToolboxViewModel,
    onStartPlayback: () -> Unit,
) {
    val playlists by viewModel.musicPlaylists.collectAsStateWithLifecycle()
    val musicState by viewModel.musicState.collectAsStateWithLifecycle()
    var selectedPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingPlaylist by rememberSaveable { mutableStateOf(false) }
    var renamePlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletePlaylistId by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .padding(20.dp),
    ) {
        PageHeading("03 · PLAYLISTS", "我的歌单", "歌单与歌曲信息只保存在本机。点击歌单可查看、播放或移除歌曲。")
        Button(
            onClick = { creatingPlaylist = true },
            enabled = !musicState.busy && playlists.size < 50,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.LibraryMusic, null)
            Spacer(Modifier.width(8.dp))
            Text("创建歌单")
        }
        Text("共 ${playlists.size} 个歌单 · 最多 50 个", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp, bottom = 14.dp))
        StatusBlock(musicState.busy, musicState.message, musicState.isError)

        if (playlists.isEmpty()) {
            EmptyHistory("还没有歌单。创建后，可从搜索结果或播放器把歌曲加入歌单。")
        } else {
            playlists.forEach { playlist ->
                PlaylistCard(
                    playlist = playlist,
                    active = musicState.activePlaylistId == playlist.id,
                    enabled = !musicState.busy,
                    onOpen = { selectedPlaylistId = playlist.id },
                    onRename = { renamePlaylistId = playlist.id },
                    onDelete = { deletePlaylistId = playlist.id },
                )
                Spacer(Modifier.height(9.dp))
            }
        }
    }

    if (creatingPlaylist) {
        PlaylistNameDialog(
            title = "创建歌单",
            initialName = "",
            confirmLabel = "创建",
            onDismiss = { creatingPlaylist = false },
            onConfirm = { name ->
                viewModel.createMusicPlaylist(name)
                creatingPlaylist = false
            },
        )
    }

    renamePlaylistId?.let { playlistId ->
        playlists.firstOrNull { it.id == playlistId }?.let { playlist ->
            PlaylistNameDialog(
                title = "重命名歌单",
                initialName = playlist.name,
                confirmLabel = "保存",
                onDismiss = { renamePlaylistId = null },
                onConfirm = { name ->
                    viewModel.renameMusicPlaylist(playlist.id, name)
                    renamePlaylistId = null
                },
            )
        }
    }

    deletePlaylistId?.let { playlistId ->
        playlists.firstOrNull { it.id == playlistId }?.let { playlist ->
            AlertDialog(
                onDismissRequest = { deletePlaylistId = null },
                title = { Text("删除歌单？") },
                text = { Text("将删除“${playlist.name}”及其本地歌单记录，不会删除已下载的音频文件。") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteMusicPlaylist(playlist.id)
                        deletePlaylistId = null
                        if (selectedPlaylistId == playlist.id) selectedPlaylistId = null
                    }) { Text("删除") }
                },
                dismissButton = { TextButton(onClick = { deletePlaylistId = null }) { Text("取消") } },
            )
        }
    }

    selectedPlaylistId?.let { playlistId ->
        playlists.firstOrNull { it.id == playlistId }?.let { playlist ->
            PlaylistTracksDialog(
                playlist = playlist,
                busy = musicState.busy,
                onDismiss = { selectedPlaylistId = null },
                onRemove = { track -> viewModel.removeTrackFromPlaylist(playlist.id, track) },
                onPlay = { mode, startTrack ->
                    viewModel.playMusicPlaylist(playlist.id, mode, startTrack)
                    selectedPlaylistId = null
                    onStartPlayback()
                },
            )
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: MusicPlaylist,
    active: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (active) Color(0xFFE8DFEA) else SurfacePaper),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(16.dp)),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen).padding(15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.LibraryMusic, null, tint = if (active) Orange else Blue)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${playlist.tracks.size} 首歌曲${if (active) " · 正在播放" else ""}", color = Muted, fontSize = 11.sp)
                }
                Icon(Icons.Rounded.ArrowOutward, "打开歌单")
            }
            HorizontalDivider(color = Line)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRename, enabled = enabled) { Text("重命名") }
                TextButton(onClick = onDelete, enabled = enabled) { Text("删除") }
            }
        }
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(50) },
                label = { Text("歌单名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PlaylistTracksDialog(
    playlist: MusicPlaylist,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRemove: (MusicTrack) -> Unit,
    onPlay: (PlaylistPlaybackMode, MusicTrack?) -> Unit,
) {
    var mode by rememberSaveable(playlist.id) { mutableStateOf(PlaylistPlaybackMode.LIST_LOOP) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${playlist.name} · ${playlist.tracks.size} 首") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                    if (mode == PlaylistPlaybackMode.LIST_LOOP) {
                        Button(onClick = { mode = PlaylistPlaybackMode.LIST_LOOP }, modifier = Modifier.weight(1f)) { Text("列表循环") }
                    } else {
                        OutlinedButton(onClick = { mode = PlaylistPlaybackMode.LIST_LOOP }, modifier = Modifier.weight(1f)) { Text("列表循环") }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (mode == PlaylistPlaybackMode.SHUFFLE) {
                        Button(onClick = { mode = PlaylistPlaybackMode.SHUFFLE }, modifier = Modifier.weight(1f)) { Text("随机播放") }
                    } else {
                        OutlinedButton(onClick = { mode = PlaylistPlaybackMode.SHUFFLE }, modifier = Modifier.weight(1f)) { Text("随机播放") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onPlay(mode, null) },
                    enabled = playlist.tracks.isNotEmpty() && !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text("播放歌单")
                }
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (playlist.tracks.isEmpty()) {
                        Text("歌单中还没有歌曲。", color = Muted, modifier = Modifier.padding(vertical = 20.dp))
                    } else {
                        playlist.tracks.forEachIndexed { index, track ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy) { onPlay(mode, track) }
                                    .padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${index + 1}", color = Orange, fontSize = 10.sp, modifier = Modifier.width(24.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(track.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("${track.artistText} · ${musicSourceName(track.source)}", color = Muted, fontSize = 10.sp)
                                }
                                TextButton(onClick = { onRemove(track) }, enabled = !busy) { Text("移除") }
                            }
                            HorizontalDivider(color = Line.copy(alpha = .6f))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun MusicHistoryScreen(viewModel: ToolboxViewModel, onSelect: (MusicTrack) -> Unit) {
    val history by viewModel.musicHistory.collectAsStateWithLifecycle()
    var currentPage by rememberSaveable { mutableStateOf(1) }
    val safePage = currentPage.coerceIn(1, pageCount(history.size, HISTORY_PAGE_SIZE))
    LaunchedEffect(history.size) { currentPage = safePage }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(20.dp),
    ) {
        PageHeading("03 · HISTORY", "搜索记录", "保存曾从搜索结果中成功调出的歌曲。点击即可再次播放，并优先复用本地播放缓存；音频地址失效时可能重新请求 API。")
        HistoryHeader(history.size, viewModel::clearMusicHistory)
        if (history.isEmpty()) {
            EmptyHistory("还没有歌曲搜索记录。")
        } else {
            history.pageSlice(safePage, HISTORY_PAGE_SIZE).forEach { entry ->
                MusicHistoryCard(entry, onSelect)
                Spacer(Modifier.height(8.dp))
            }
            PaginationControls(
                itemCount = history.size,
                pageSize = HISTORY_PAGE_SIZE,
                currentPage = safePage,
                onPageChange = { currentPage = it },
            )
        }
    }
}

@Composable
private fun MusicHistoryCard(entry: MusicHistoryEntry, onSelect: (MusicTrack) -> Unit) {
    Card(
        onClick = { onSelect(entry.track) },
        colors = CardDefaults.cardColors(containerColor = SurfacePaper),
        shape = RoundedCornerShape(15.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(15.dp)),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(entry.track.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(formatHistoryTime(entry.searchedAtEpochMillis), color = Muted, fontSize = 9.sp)
                }
                Text("${entry.track.artistText} · ${entry.track.album}", color = Muted, fontSize = 11.sp)
                Text(musicSourceName(entry.track.source), color = Blue, fontSize = 9.sp)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.PlayArrow, "再次播放")
        }
    }
}

@Composable
private fun MediaCard(
    media: BiliMediaInfo,
    busy: Boolean,
    onOpenSource: () -> Unit,
    onDownload: (DownloadKind) -> Unit,
) {
    val bitmap = remember(media.coverBytes) { decodeImagePreview(media.coverBytes) }
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfacePaper),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(22.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "媒体封面",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp)).background(Line.copy(alpha = .35f)),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text("${if (media.id.kind == MediaKind.VIDEO) "视频" else "音频"} · ${media.id.value}", color = Orange, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            Text(media.title, fontFamily = FontFamily.Serif, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 33.sp)
            Spacer(Modifier.height(7.dp))
            Text(
                media.author + (media.durationSeconds?.let { " · ${formatDuration(it)}" } ?: ""),
                color = Muted,
            )
            TextButton(onClick = onOpenSource) {
                Icon(Icons.Rounded.Link, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("在 Bilibili 查看")
                Icon(Icons.Rounded.ArrowOutward, null, Modifier.size(15.dp))
            }
            Spacer(Modifier.height(8.dp))
            DownloadButton("下载封面", Icons.Rounded.ImageIcon, busy) { onDownload(DownloadKind.COVER) }
            if (media.id.kind == MediaKind.VIDEO) {
                Spacer(Modifier.height(8.dp))
                DownloadButton("下载视频", Icons.Rounded.VideoFile, busy, primary = true) { onDownload(DownloadKind.VIDEO) }
            }
            Spacer(Modifier.height(8.dp))
            DownloadButton("下载音频", Icons.Rounded.MusicNote, busy) { onDownload(DownloadKind.AUDIO) }
            Spacer(Modifier.height(12.dp))
            Text("文件会直接保存到上方目录。请只下载你有权保存的内容。付费、会员或登录受限内容不在首版支持范围内。", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun DownloadButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    busy: Boolean,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    if (primary) {
        Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, null)
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, null)
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }
}

@Composable
private fun StatusBlock(busy: Boolean, message: String?, isError: Boolean) {
    if (message == null) return
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isError) Color(0xFFF2DDD8) else if (busy) Color(0xFFEEE5CF) else Color(0xFFDCE9DF))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink)
            Spacer(Modifier.width(10.dp))
        }
        Text(message, color = if (isError) Color(0xFF7E352B) else Ink, fontSize = 13.sp)
    }
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val rest = seconds % 60
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
    else String.format(Locale.ROOT, "%d:%02d", minutes, rest)
}

private fun formatAudioTime(milliseconds: Int): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

private fun musicSourceName(source: String): String = when (source.lowercase()) {
    "netease" -> "网易云音乐"
    "tencent" -> "QQ音乐"
    "tidal" -> "Tidal"
    "spotify" -> "Spotify"
    "ytmusic" -> "YouTube Music"
    "qobuz" -> "Qobuz"
    "joox" -> "JOOX"
    "deezer" -> "Deezer"
    "kugou" -> "酷狗音乐"
    "kuwo" -> "酷我音乐"
    "apple" -> "Apple Music"
    "bilibili" -> "Bilibili"
    else -> source
}

private fun readableTreeName(value: String?): String {
    if (value == null) return "未选择"
    return runCatching {
        Uri.decode(Uri.parse(value).lastPathSegment.orEmpty())
            .substringAfter(':')
            .ifBlank { "已选择目录" }
    }.getOrDefault("已选择目录")
}

private fun formatHistoryTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))

private fun copyText(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "复制成功", Toast.LENGTH_SHORT).show()
}

private fun decodeImagePreview(bytes: ByteArray): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 2048) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

private fun openWebUrl(context: Context, url: String) {
    runCatching {
        val uri = Uri.parse(url)
        require(uri.scheme == "http" || uri.scheme == "https")
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}
