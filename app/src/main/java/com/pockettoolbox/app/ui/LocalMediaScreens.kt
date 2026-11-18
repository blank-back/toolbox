@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.pockettoolbox.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.pockettoolbox.app.LocalMediaPlaybackBus
import com.pockettoolbox.app.MusicPlaybackCompanionService
import com.pockettoolbox.app.data.LocalMediaRepository
import com.pockettoolbox.app.data.LocalPreferences
import com.pockettoolbox.app.data.SupportedMediaFormats
import com.pockettoolbox.app.model.LocalMediaItem
import com.pockettoolbox.app.ui.theme.Blue
import com.pockettoolbox.app.ui.theme.BluePaper
import com.pockettoolbox.app.ui.theme.Ink
import com.pockettoolbox.app.ui.theme.Line
import com.pockettoolbox.app.ui.theme.Muted
import com.pockettoolbox.app.ui.theme.Orange
import com.pockettoolbox.app.ui.theme.Paper
import com.pockettoolbox.app.ui.theme.SurfacePaper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Stable
class LocalMediaFeatureState(context: Context) {
    private val appContext = context.applicationContext
    private val repository = LocalMediaRepository(appContext)
    private val preferences = LocalPreferences(appContext)

    var items by mutableStateOf<List<LocalMediaItem>>(emptyList())
        private set
    var treeUri by mutableStateOf(preferences.loadMediaScanTreeUri())
        private set
    var enabledExtensions by mutableStateOf(
        preferences.loadMediaExtensions(SupportedMediaFormats.defaultExtensions),
    )
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var isError by mutableStateOf(false)
        private set

    fun setTreeUri(uri: Uri) {
        treeUri = uri.toString()
        preferences.saveMediaScanTreeUri(uri.toString())
    }

    fun setExtensions(value: Set<String>) {
        enabledExtensions = value
        preferences.saveMediaExtensions(value)
        items = items.filter { it.extension in value }
    }

    suspend fun scan() {
        if (enabledExtensions.isEmpty()) {
            items = emptyList()
            message = "当前未选择任何媒体格式"
            isError = false
            return
        }
        val uri = treeUri?.let(Uri::parse) ?: run {
            showError("请先选择要扫描的目录")
            return
        }
        busy = true
        message = "正在扫描当前目录…"
        isError = false
        try {
            items = repository.scanDirectory(uri, enabledExtensions)
            message = if (items.isEmpty()) "当前目录没有符合所选格式的媒体文件" else "已找到 ${items.size} 个媒体文件"
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (error: Throwable) {
            showError(error.message ?: "目录扫描失败")
        } finally {
            busy = false
        }
    }

    suspend fun addPickedFile(uri: Uri): LocalMediaItem? {
        busy = true
        message = "正在读取媒体信息…"
        isError = false
        return try {
            val item = repository.inspect(uri)
            when {
                item == null -> {
                    showError("无法识别该文件为音频或视频")
                    null
                }
                item.extension !in enabledExtensions -> {
                    showError(".${item.extension.ifBlank { "未知" }} 未在格式过滤器中启用")
                    null
                }
                else -> {
                    items = listOf(item) + items.filterNot { it.uri == item.uri }
                    message = "已载入 ${item.name}"
                    item
                }
            }
        } catch (error: Throwable) {
            showError(error.message ?: "无法读取媒体文件")
            null
        } finally {
            busy = false
        }
    }

    private fun showError(value: String) {
        message = value
        isError = true
    }
}

@Composable
fun rememberLocalMediaFeatureState(): LocalMediaFeatureState {
    val context = LocalContext.current
    return remember(context) { LocalMediaFeatureState(context) }
}

@Composable
fun LocalMediaLibraryScreen(
    state: LocalMediaFeatureState,
    onPlay: (LocalMediaItem) -> Unit,
    onOpenPlayer: () -> Unit,
    onEdit: (LocalMediaItem) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playback by LocalMediaPlaybackBus.state.collectAsStateWithLifecycle()
    var showFormats by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        takeReadPermission(context, uri)
        scope.launch { state.addPickedFile(uri)?.let(onPlay) }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        takeReadPermission(context, uri)
        state.setTreeUri(uri)
        scope.launch { state.scan() }
    }

    if (showFormats) {
        MediaFormatDialog(
            selected = state.enabledExtensions,
            onDismiss = { showFormats = false },
            onConfirm = {
                state.setExtensions(it)
                showFormats = false
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(28.dp))
            Text("04 / 本地媒体", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("播放与剪辑", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        if (playback.uri != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPlayer),
                    colors = CardDefaults.cardColors(containerColor = BluePaper),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Blue)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("当前播放", color = Muted, fontSize = 11.sp)
                            Text(playback.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                        }
                        Text("查看", color = Blue, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { filePicker.launch(arrayOf("audio/*", "video/*", "application/ogg", "application/octet-stream")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("选择并播放")
                }
                OutlinedButton(onClick = { showFormats = true }) {
                    Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("格式")
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = SurfacePaper), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FolderOpen, null, tint = Orange)
                        Spacer(Modifier.width(9.dp))
                        Text("目录自动扫描", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(readableDirectoryLocation(state.treeUri), color = Muted, fontSize = 12.sp, maxLines = 3)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                treePicker.launch(
                                    createDirectoryPickerIntent(
                                        initialUri = primaryStorageRootDocumentUri(),
                                        allowWrite = false,
                                        showAppChooser = true,
                                        chooserTitle = "选择媒体扫描目录",
                                    ),
                                )
                            },
                        ) { Text("文件管理器选择") }
                        Button(onClick = { scope.launch { state.scan() } }, enabled = !state.busy && state.treeUri != null) {
                            Text("自动扫描")
                        }
                    }
                }
            }
        }
        state.message?.let { message ->
            item {
                Text(
                    message,
                    color = if (state.isError) Color(0xFFB3261E) else Muted,
                    modifier = Modifier.fillMaxWidth().background(SurfacePaper, RoundedCornerShape(12.dp)).padding(12.dp),
                )
            }
        }
        if (state.busy) item { Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        if (!state.busy && state.items.isEmpty()) {
            item { Text("选择单个文件，或选择目录后自动扫描。", color = Muted, modifier = Modifier.padding(vertical = 24.dp)) }
        }
        items(state.items, key = LocalMediaItem::uri) { item ->
            LocalMediaRow(item = item, onPlay = { onPlay(item) }, onEdit = { onEdit(item) })
        }
    }
}

@Composable
private fun LocalMediaRow(item: LocalMediaItem, onPlay: () -> Unit, onEdit: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfacePaper), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).background(if (item.isVideo) BluePaper else Color(0xFFE8DFEA), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(if (item.isVideo) Icons.Rounded.Movie else Icons.Rounded.LibraryMusic, null, tint = if (item.isVideo) Blue else Color(0xFF76507B))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${item.extension.uppercase()} · ${formatLocalDuration(item.durationMillis)} · ${formatFileSize(item.sizeBytes)}", color = Muted, fontSize = 11.sp)
            }
            IconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayArrow, "播放") }
            IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "剪辑") }
        }
    }
}

@Composable
private fun MediaFormatDialog(selected: Set<String>, onDismiss: () -> Unit, onConfirm: (Set<String>) -> Unit) {
    var draft by remember(selected) { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫描与选择格式") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { draft = SupportedMediaFormats.allExtensions }) { Text("全选") }
                        Text("已选 ${draft.size}/${SupportedMediaFormats.allExtensions.size}", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterVertically))
                        TextButton(onClick = { draft = emptySet() }) { Text("全不选") }
                    }
                }
                item { FormatSection("音频") }
                items(SupportedMediaFormats.audioExtensions.toList()) { extension ->
                    FormatCheckRow(extension, extension in draft) { checked ->
                        draft = if (checked) draft + extension else draft - extension
                    }
                }
                item { FormatSection("视频") }
                items(SupportedMediaFormats.videoExtensions.toList()) { extension ->
                    FormatCheckRow(extension, extension in draft) { checked ->
                        draft = if (checked) draft + extension else draft - extension
                    }
                }
                item { Text("部分旧容器/无损格式只在具备相应系统解码器的设备上可用。", color = Muted, fontSize = 11.sp) }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(draft) }) { Text("保存") } },
        dismissButton = {
            Row {
                TextButton(onClick = { draft = SupportedMediaFormats.defaultExtensions }) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun FormatSection(title: String) {
    Text(title, fontWeight = FontWeight.Bold, color = Orange, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun FormatCheckRow(extension: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(".${extension}")
    }
}

@Composable
fun LocalMediaPlayerScreen(
    isInPictureInPictureMode: Boolean,
    onEnterPictureInPicture: (Int, Int) -> Unit,
    onEdit: () -> Unit,
) {
    val context = LocalContext.current
    val state by LocalMediaPlaybackBus.state.collectAsStateWithLifecycle()
    val player by LocalMediaPlaybackBus.player.collectAsStateWithLifecycle()
    var speedMenu by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var pendingPosition by remember { mutableStateOf(0f) }
    LaunchedEffect(state.positionMillis, seeking) {
        if (!seeking) pendingPosition = state.positionMillis.toFloat()
    }

    val video: @Composable (Modifier) -> Unit = { modifier ->
        if (state.isVideo) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        keepScreenOn = true
                        this.player = player
                    }
                },
                update = { it.player = player },
                modifier = modifier.background(Color.Black),
            )
        } else {
            Box(modifier.background(Ink), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.LibraryMusic, null, tint = Paper, modifier = Modifier.size(72.dp))
            }
        }
    }

    if (isInPictureInPictureMode) {
        video(Modifier.fillMaxSize())
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
    ) {
        Text("当前播放", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(state.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        video(Modifier.fillMaxWidth().aspectRatio(if (state.width > 0 && state.height > 0) state.width.toFloat() / state.height else 16f / 9f))
        state.error?.let { Text(it, color = Color(0xFFB3261E), modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(12.dp))
        Slider(
            value = pendingPosition.coerceIn(0f, state.durationMillis.coerceAtLeast(1L).toFloat()),
            onValueChange = { seeking = true; pendingPosition = it },
            onValueChangeFinished = {
                MusicPlaybackCompanionService.seekLocalTo(context, pendingPosition.toLong())
                seeking = false
            },
            valueRange = 0f..state.durationMillis.coerceAtLeast(1L).toFloat(),
            enabled = state.durationMillis > 0L,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatLocalDuration(pendingPosition.toLong()), color = Muted, fontSize = 11.sp)
            Text(formatLocalDuration(state.durationMillis), color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { MusicPlaybackCompanionService.seekLocalBy(context, false) }) { Text("−10 秒") }
            IconButton(onClick = { MusicPlaybackCompanionService.toggleLocal(context) }) {
                Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.isPlaying) "暂停" else "播放", Modifier.size(34.dp))
            }
            IconButton(onClick = { MusicPlaybackCompanionService.replayLocal(context) }) { Icon(Icons.Rounded.Replay, "重播") }
            TextButton(onClick = { MusicPlaybackCompanionService.seekLocalBy(context, true) }) { Text("+10 秒") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { speedMenu = true }) { Text("倍速 ${"%.2f".format(state.speed)}×") }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f).forEach { speed ->
                        DropdownMenuItem(
                            text = { Text("${"%.2f".format(speed)}×") },
                            onClick = {
                                MusicPlaybackCompanionService.setLocalSpeed(context, speed)
                                speedMenu = false
                            },
                        )
                    }
                }
            }
            if (state.isVideo) {
                OutlinedButton(onClick = { onEnterPictureInPicture(state.width, state.height) }) {
                    Icon(Icons.Rounded.PictureInPictureAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("小窗口")
                }
            }
            OutlinedButton(onClick = onEdit) {
                Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("剪辑")
            }
        }
        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = Line)
        TextButton(onClick = { MusicPlaybackCompanionService.stopLocal(context) }) { Text("停止并关闭通知", color = Color(0xFFB3261E)) }
    }
}

private fun takeReadPermission(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal fun formatLocalDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private fun formatFileSize(bytes: Long): String = when {
    bytes <= 0L -> "未知大小"
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.1f KB".format(bytes / 1_024.0)
}
