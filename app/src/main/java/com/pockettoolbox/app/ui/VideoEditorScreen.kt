@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.pockettoolbox.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.pockettoolbox.app.MusicPlaybackCompanionService
import com.pockettoolbox.app.data.LocalMediaRepository
import com.pockettoolbox.app.data.EditorMediaSegment
import com.pockettoolbox.app.data.VideoEditorExporter
import com.pockettoolbox.app.data.VideoExportSpec
import com.pockettoolbox.app.model.EditorAudioTrack
import com.pockettoolbox.app.model.LocalMediaItem
import com.pockettoolbox.app.model.safeFileName
import com.pockettoolbox.app.ui.theme.Blue
import com.pockettoolbox.app.ui.theme.BluePaper
import com.pockettoolbox.app.ui.theme.Ink
import com.pockettoolbox.app.ui.theme.Line
import com.pockettoolbox.app.ui.theme.Muted
import com.pockettoolbox.app.ui.theme.Orange
import com.pockettoolbox.app.ui.theme.SurfacePaper
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class EditorTool { SPLIT, AUDIO }
private const val AUDIO_TIMELINE_SEGMENTS = 16
private const val MIN_EDITOR_SEGMENT_MILLIS = 100L

@Composable
fun VideoEditorScreen(item: LocalMediaItem) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val duration = item.durationMillis.coerceAtLeast(1_000L)
    val previewPlayer = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(item.uri))
            prepare()
        }
    }
    val exporter = remember { VideoEditorExporter(context.applicationContext) }
    var currentPosition by remember { mutableStateOf(0L) }
    val segments = remember(item.uri, duration) {
        mutableStateListOf(EditorMediaSegment(UUID.randomUUID().toString(), 0L, duration))
    }
    var selectedSegmentId by remember(item.uri) { mutableStateOf(segments.first().id) }
    var previewEndMillis by remember(item.uri) { mutableStateOf<Long?>(null) }
    var timelineZoom by remember { mutableStateOf(1f) }
    var activeTool by remember { mutableStateOf<EditorTool?>(null) }
    var thumbnails by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var keepOriginalAudio by remember { mutableStateOf(true) }
    var originalDbText by remember { mutableStateOf("0") }
    val audioTracks = remember { mutableStateListOf<EditorAudioTrack>() }
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    val selectedSegment = segments.firstOrNull { it.id == selectedSegmentId }
    val projectDuration = segments.sumOf(EditorMediaSegment::durationMillis).coerceAtLeast(1L)
    val projectPosition = projectPositionFor(segments, selectedSegmentId, currentPosition)

    val seekToSegmentPosition: (EditorMediaSegment, Long) -> Unit = { segment, requestedPosition ->
        val wasPlaying = previewPlayer.isPlaying
        val target = requestedPosition.coerceIn(segment.startMillis, segment.endMillis)
        selectedSegmentId = segment.id
        currentPosition = target
        previewEndMillis = if (wasPlaying) segment.endMillis else null
        previewPlayer.seekTo(target)
    }
    val playSelectedSegment: () -> Unit = {
        selectedSegment?.let { segment ->
            val target = currentPosition.takeIf { it >= segment.startMillis && it < segment.endMillis }
                ?: segment.startMillis
            selectedSegmentId = segment.id
            currentPosition = target
            previewEndMillis = segment.endMillis
            previewPlayer.seekTo(target)
            previewPlayer.play()
        }
    }
    val deleteSegment: (String) -> Unit = { segmentId ->
        val index = segments.indexOfFirst { it.id == segmentId }
        if (index >= 0) {
            val deletingSelected = selectedSegmentId == segmentId
            segments.removeAt(index)
            if (deletingSelected) {
                previewPlayer.pause()
                previewEndMillis = null
                val next = segments.getOrNull(index.coerceAtMost(segments.lastIndex))
                selectedSegmentId = next?.id.orEmpty()
                next?.let {
                    currentPosition = it.startMillis
                    previewPlayer.seekTo(it.startMillis)
                }
            }
        }
    }
    val moveSegment: (String, Int) -> Unit = { segmentId, direction ->
        val fromIndex = segments.indexOfFirst { it.id == segmentId }
        if (fromIndex >= 0) {
            val toIndex = (fromIndex + direction).coerceIn(0, segments.lastIndex)
            if (fromIndex != toIndex) {
                val moving = segments.removeAt(fromIndex)
                segments.add(toIndex, moving)
            }
        }
    }

    DisposableEffect(previewPlayer, exporter) {
        MusicPlaybackCompanionService.stopLocal(context)
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                currentPosition = previewPlayer.currentPosition.coerceAtLeast(0L)
            }
        }
        previewPlayer.addListener(listener)
        onDispose {
            previewPlayer.removeListener(listener)
            previewPlayer.release()
            exporter.release()
            thumbnails.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        }
    }
    LaunchedEffect(previewPlayer) {
        while (true) {
            val position = previewPlayer.currentPosition.coerceAtLeast(0L)
            val previewEnd = previewEndMillis
            if (previewEnd != null && previewPlayer.isPlaying && position >= previewEnd) {
                previewPlayer.pause()
                previewPlayer.seekTo(previewEnd)
                currentPosition = previewEnd
                previewEndMillis = null
            } else {
                currentPosition = position
            }
            delay(50L)
        }
    }
    LaunchedEffect(item.uri, duration) {
        thumbnails = if (item.isVideo) {
            loadTimelineThumbnails(context, Uri.parse(item.uri), duration, 16)
        } else {
            emptyList()
        }
    }

    val addAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        takeEditorReadPermission(context, uri)
        scope.launch {
            val picked = LocalMediaRepository(context.applicationContext).inspect(uri)
            if (picked == null || picked.isVideo) {
                status = "请选择可读取的音频文件"
                statusError = true
            } else {
                audioTracks += EditorAudioTrack(
                    id = UUID.randomUUID().toString(),
                    uri = picked.uri,
                    name = picked.name,
                    durationMillis = picked.durationMillis.coerceAtLeast(1_000L),
                    trimEndMillis = picked.durationMillis.coerceAtLeast(1_000L),
                )
                status = "已添加音轨 ${picked.name}"
                statusError = false
            }
        }
    }
    val exportContract = remember(item.isVideo) {
        ActivityResultContracts.CreateDocument(if (item.isVideo) "video/mp4" else "audio/mp4")
    }
    val exportLauncher = rememberLauncherForActivityResult(
        exportContract,
    ) { destination ->
        destination ?: return@rememberLauncherForActivityResult
        val db = originalDbText.toFloatOrNull()
        if (db == null || db !in -80f..12f) {
            status = "原声音量请输入 -80 到 +12 dB"
            statusError = true
            return@rememberLauncherForActivityResult
        }
        if (segments.isEmpty()) {
            status = "请至少保留一个媒体片段"
            statusError = true
            return@rememberLauncherForActivityResult
        }
        if (!item.isVideo && !keepOriginalAudio && audioTracks.isEmpty()) {
            status = "音频项目至少需要保留原声或添加一条音轨"
            statusError = true
            return@rememberLauncherForActivityResult
        }
        previewPlayer.pause()
        exporting = true
        exportProgress = 0
        status = "正在导出，请保持剪辑页面打开…"
        statusError = false
        exporter.export(
            spec = VideoExportSpec(
                videoUri = item.uri,
                sourceIsVideo = item.isVideo,
                segments = segments.toList(),
                muteOriginalAudio = !keepOriginalAudio,
                originalAudioDb = db,
                addedAudioTracks = audioTracks.toList(),
            ),
            destination = destination,
            onProgress = { exportProgress = it },
            onCompleted = {
                exporting = false
                exportProgress = 100
                status = if (item.isVideo) "视频已保存到所选位置" else "音频已保存到所选位置"
                statusError = false
            },
            onError = { error ->
                exporting = false
                status = error
                statusError = true
            },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        Text(if (item.isVideo) "视频剪辑" else "音频剪辑", color = Orange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(item.name, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("所有操作只写入新文件，不修改源媒体。", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        if (item.isVideo) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        player = previewPlayer
                    }
                },
                modifier = Modifier.fillMaxWidth()
                    .aspectRatio(if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else 16f / 9f)
                    .background(Color.Black),
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MusicNote, "音频预览", tint = Color.White, modifier = Modifier.size(76.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (previewPlayer.isPlaying) {
                        previewPlayer.pause()
                        previewEndMillis = null
                    } else {
                        playSelectedSegment()
                    }
                },
                enabled = selectedSegment != null,
            ) {
                Icon(if (previewPlayer.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(30.dp))
            }
            Text("${formatLocalDuration(projectPosition)} / ${formatLocalDuration(projectDuration)}", color = Muted)
        }
        Slider(
            value = projectPosition.coerceIn(0L, projectDuration).toFloat(),
            onValueChange = { requested ->
                projectTargetFor(segments, requested.toLong())?.let { (segment, sourcePosition) ->
                    seekToSegmentPosition(segment, sourcePosition)
                }
            },
            valueRange = 0f..projectDuration.toFloat(),
            enabled = segments.isNotEmpty(),
        )

        EditorSectionTitle("时间轴 · 双指缩放粒度")
        TimelineStrip(
            isVideo = item.isVideo,
            thumbnails = thumbnails,
            sourceDurationMillis = duration,
            segments = segments,
            selectedSegmentId = selectedSegmentId,
            currentPositionMillis = currentPosition,
            zoom = timelineZoom,
            onZoom = { timelineZoom = it },
            onSeek = { segment, position -> seekToSegmentPosition(segment, position) },
        )
        Text(
            "观察粒度 ${"%.1f".format(timelineZoom)}× · ${segments.size} 个片段",
            color = Muted,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { activeTool = if (activeTool == EditorTool.SPLIT) null else EditorTool.SPLIT },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (activeTool == EditorTool.SPLIT) BluePaper else Color.Transparent,
                    contentColor = if (activeTool == EditorTool.SPLIT) Blue else Ink,
                ),
            ) {
                Icon(Icons.Rounded.ContentCut, "分割工具", Modifier.size(19.dp))
                Spacer(Modifier.width(6.dp))
                Text("分割")
            }
            OutlinedButton(
                onClick = { activeTool = if (activeTool == EditorTool.AUDIO) null else EditorTool.AUDIO },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (activeTool == EditorTool.AUDIO) BluePaper else Color.Transparent,
                    contentColor = if (activeTool == EditorTool.AUDIO) Blue else Ink,
                ),
            ) {
                Icon(Icons.Rounded.GraphicEq, "音轨工具", Modifier.size(19.dp))
                Spacer(Modifier.width(6.dp))
                Text("音轨")
            }
        }
        if (activeTool == null) {
            Text("点击工具图标，在时间轴下方展开操作区域。", color = Muted, fontSize = 11.sp)
        }

        AnimatedVisibility(visible = activeTool == EditorTool.SPLIT) {
            Column {
                EditorSectionTitle("分割片段")
                if (selectedSegment == null) {
                    Text("当前没有片段。可恢复完整媒体后继续剪辑。", color = Muted, fontSize = 12.sp)
                    Button(
                        onClick = {
                            val restored = EditorMediaSegment(UUID.randomUUID().toString(), 0L, duration)
                            segments += restored
                            selectedSegmentId = restored.id
                            seekToSegmentPosition(restored, 0L)
                        },
                    ) { Text("恢复完整媒体") }
                } else {
                    Text(
                        "所选片段：${formatLocalDuration(selectedSegment.startMillis)} — ${formatLocalDuration(selectedSegment.endMillis)}",
                        color = Blue,
                        fontSize = 12.sp,
                    )
                    RangeSlider(
                        value = selectedSegment.startMillis.toFloat()..selectedSegment.endMillis.toFloat(),
                        onValueChange = { range ->
                            val index = segments.indexOfFirst { it.id == selectedSegment.id }
                            if (index >= 0) {
                                val start = range.start.toLong().coerceIn(0L, duration - 1L)
                                val end = range.endInclusive.toLong().coerceIn(start + 1L, duration)
                                val updated = selectedSegment.copy(startMillis = start, endMillis = end)
                                segments[index] = updated
                                if (currentPosition !in start..end) {
                                    seekToSegmentPosition(updated, start)
                                } else if (previewPlayer.isPlaying) {
                                    previewEndMillis = end
                                }
                            }
                        },
                        valueRange = 0f..duration.toFloat(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = playSelectedSegment, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("播放片段")
                        }
                        Button(
                            onClick = {
                                val splitAt = currentPosition
                                if (
                                    splitAt <= selectedSegment.startMillis + MIN_EDITOR_SEGMENT_MILLIS ||
                                    splitAt >= selectedSegment.endMillis - MIN_EDITOR_SEGMENT_MILLIS
                                ) {
                                    status = "分割点需位于所选片段内部，并与边缘至少相距 0.1 秒"
                                    statusError = true
                                } else {
                                    val index = segments.indexOfFirst { it.id == selectedSegment.id }
                                    if (index >= 0) {
                                        val left = selectedSegment.copy(endMillis = splitAt)
                                        val right = EditorMediaSegment(UUID.randomUUID().toString(), splitAt, selectedSegment.endMillis)
                                        segments[index] = left
                                        segments.add(index + 1, right)
                                        previewPlayer.pause()
                                        selectedSegmentId = right.id
                                        currentPosition = right.startMillis
                                        previewEndMillis = null
                                        previewPlayer.seekTo(right.startMillis)
                                        status = "已分割为 ${segments.size} 个片段"
                                        statusError = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.ContentCut, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("在浮标处分割")
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                val splitAt = currentPosition.coerceIn(selectedSegment.startMillis, selectedSegment.endMillis - 1L)
                                val index = segments.indexOfFirst { it.id == selectedSegment.id }
                                if (index >= 0) segments[index] = selectedSegment.copy(startMillis = splitAt)
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("删浮标前内容") }
                        TextButton(
                            onClick = {
                                val splitAt = currentPosition.coerceIn(selectedSegment.startMillis + 1L, selectedSegment.endMillis)
                                val index = segments.indexOfFirst { it.id == selectedSegment.id }
                                if (index >= 0) {
                                    segments[index] = selectedSegment.copy(endMillis = splitAt)
                                    if (previewPlayer.isPlaying) previewEndMillis = splitAt
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("删浮标后内容") }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("片段顺序", fontWeight = FontWeight.Bold)
                    TextButton(
                        onClick = {
                            previewPlayer.pause()
                            previewEndMillis = null
                            segments.clear()
                            val restored = EditorMediaSegment(UUID.randomUUID().toString(), 0L, duration)
                            segments += restored
                            selectedSegmentId = restored.id
                            currentPosition = 0L
                            previewPlayer.seekTo(0L)
                        },
                    ) { Text("重置") }
                }
                Text("点按选择；长按右侧拖动柄并上下拖动可调整导出顺序。", color = Muted, fontSize = 11.sp)
                segments.forEachIndexed { index, segment ->
                    Spacer(Modifier.height(8.dp))
                    SegmentEditorCard(
                        index = index,
                        totalCount = segments.size,
                        segment = segment,
                        selected = segment.id == selectedSegmentId,
                        onSelect = { seekToSegmentPosition(segment, segment.startMillis) },
                        onPlay = {
                            seekToSegmentPosition(segment, segment.startMillis)
                            previewEndMillis = segment.endMillis
                            previewPlayer.play()
                        },
                        onDelete = { deleteSegment(segment.id) },
                        onMove = { direction -> moveSegment(segment.id, direction) },
                    )
                }
            }
        }

        AnimatedVisibility(visible = activeTool == EditorTool.AUDIO) {
            Column {
                EditorSectionTitle("原始声音")
                Card(colors = CardDefaults.cardColors(containerColor = SurfacePaper), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("保留原声", fontWeight = FontWeight.Bold)
                                Text(if (keepOriginalAudio) "参与最终混音" else "导出时移除", color = Muted, fontSize = 11.sp)
                            }
                            Switch(checked = keepOriginalAudio, onCheckedChange = { keepOriginalAudio = it })
                        }
                        OutlinedTextField(
                            value = originalDbText,
                            onValueChange = { originalDbText = it },
                            label = { Text("原声音量（dB）") },
                            supportingText = { Text("0 dB 为原音量；可输入 -80 到 +12 dB，增益过高可能削波") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            enabled = keepOriginalAudio,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                EditorSectionTitle("附加音轨")
                Button(onClick = { addAudioLauncher.launch(arrayOf("audio/*", "application/ogg", "application/octet-stream")) }) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("增加音频轨道")
                }
                if (audioTracks.isEmpty()) {
                    Text("尚未增加音轨。每条音轨都有独立保留窗口和起始位置。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
                }
                audioTracks.forEachIndexed { index, track ->
                    Spacer(Modifier.height(10.dp))
                    AddedAudioTrackCard(
                        track = track,
                        projectDuration = projectDuration,
                        playheadMillis = projectPosition,
                        onChange = { audioTracks[index] = it },
                        onDelete = { audioTracks.removeAt(index) },
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        HorizontalDivider(color = Line)
        status?.let {
            Text(it, color = if (statusError) Color(0xFFB3261E) else Muted, modifier = Modifier.padding(vertical = 10.dp))
        }
        if (exporting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(progress = { exportProgress / 100f }, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text("导出 $exportProgress%")
            }
            TextButton(onClick = { exporter.cancel(); exporting = false; status = "已取消导出" }) { Text("取消导出") }
        } else {
            Button(
                onClick = {
                    if (segments.isEmpty()) {
                        status = "请至少保留一个媒体片段"
                        statusError = true
                    } else if (!item.isVideo && !keepOriginalAudio && audioTracks.isEmpty()) {
                        status = "音频项目至少需要保留原声或添加一条音轨"
                        statusError = true
                    } else {
                        val extension = if (item.isVideo) ".mp4" else ".m4a"
                        exportLauncher.launch(safeFileName(item.name.substringBeforeLast('.')) + "_edited" + extension)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Save, null)
                Spacer(Modifier.width(7.dp))
                Text(if (item.isVideo) "选择位置并导出 MP4" else "选择位置并导出 M4A")
            }
        }
        Text(
            if (item.isVideo) {
                "缩略图仅用于近似定位；导出会按片段列表顺序拼接。附加音轨会与保留的原声混合。"
            } else {
                "音频时间轴不包含画面；导出会按片段列表顺序拼接，附加音轨会与保留的原声混合。"
            },
            color = Muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(vertical = 14.dp),
        )
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun TimelineStrip(
    isVideo: Boolean,
    thumbnails: List<Bitmap>,
    sourceDurationMillis: Long,
    segments: List<EditorMediaSegment>,
    selectedSegmentId: String,
    currentPositionMillis: Long,
    zoom: Float,
    onZoom: (Float) -> Unit,
    onSeek: (EditorMediaSegment, Long) -> Unit,
) {
    val scrollState = rememberScrollState()
    val projectDuration = segments.sumOf(EditorMediaSegment::durationMillis).coerceAtLeast(1L)
    val baseWidthValue = 82f * AUDIO_TIMELINE_SEGMENTS * zoom
    val segmentWidths = segments.map { segment ->
        maxOf(58f, baseWidthValue * segment.durationMillis.toFloat() / projectDuration.toFloat())
    }
    val totalWidthValue = segmentWidths.sum().coerceAtLeast(1f)
    val totalWidth = totalWidthValue.dp
    val density = LocalDensity.current
    val totalWidthPx = with(density) { totalWidth.toPx() }.coerceAtLeast(1f)
    val widthPixels = segmentWidths.map { with(density) { it.dp.toPx() } }
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    val latestZoom by rememberUpdatedState(zoom)
    val selectedIndex = segments.indexOfFirst { it.id == selectedSegmentId }
    val selectedSegment = segments.getOrNull(selectedIndex)
    val widthBeforeMarker = segmentWidths.take(selectedIndex.coerceAtLeast(0)).sum()
    val markerLocalFraction = selectedSegment?.let {
        (currentPositionMillis - it.startMillis).toFloat().coerceIn(0f, it.durationMillis.toFloat()) /
            it.durationMillis.coerceAtLeast(1L).toFloat()
    } ?: 0f
    val markerPositionValue = if (selectedIndex >= 0) {
        widthBeforeMarker + segmentWidths[selectedIndex] * markerLocalFraction
    } else {
        0f
    }
    val markerWidth = 24.dp
    val markerOffset = (markerPositionValue - markerWidth.value / 2f)
        .coerceIn(0f, (totalWidth - markerWidth).value.coerceAtLeast(0f)).dp
    val segmentLayoutKey = segments.joinToString("|") { "${it.id}:${it.startMillis}:${it.endMillis}" }
    LaunchedEffect(zoom, totalWidthPx, viewportWidthPx, segmentLayoutKey, selectedSegmentId) {
        if (viewportWidthPx <= 0) return@LaunchedEffect
        withFrameNanos { }
        val markerPositionPx = with(density) { markerPositionValue.dp.toPx() }
        val centeredOffset = (markerPositionPx - viewportWidthPx / 2f).roundToInt()
        scrollState.scrollTo(centeredOffset.coerceIn(0, scrollState.maxValue))
    }
    Box(
        modifier = Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(12.dp)).background(Ink)
            .onSizeChanged { viewportWidthPx = it.width }
            .pointerInput(Unit) {
                awaitEachGesture {
                    var initialDistance = 0f
                    var initialZoom = latestZoom
                    var anyPointerPressed = true
                    while (anyPointerPressed) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val distance = (pressed[0].position - pressed[1].position).getDistance()
                            if (initialDistance <= 0f) {
                                initialDistance = distance.coerceAtLeast(1f)
                                initialZoom = latestZoom
                            } else {
                                onZoom((initialZoom * distance / initialDistance).coerceIn(0.6f, 4f))
                            }
                            pressed.forEach { it.consume() }
                        } else {
                            initialDistance = 0f
                            initialZoom = latestZoom
                        }
                        anyPointerPressed = event.changes.any { it.pressed }
                    }
                }
            }
            .horizontalScroll(scrollState),
    ) {
        if (segments.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有保留的片段", color = Color.White.copy(alpha = .72f))
            }
        } else if (isVideo && thumbnails.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
        } else {
            Box(Modifier.width(totalWidth).height(112.dp)) {
                Row(Modifier.width(totalWidth).height(112.dp).padding(top = 18.dp)) {
                    segments.forEachIndexed { index, segment ->
                        val segmentWidth = segmentWidths[index].dp
                        Box(
                            Modifier.width(segmentWidth).height(92.dp)
                                .border(
                                    width = if (segment.id == selectedSegmentId) 3.dp else 1.dp,
                                    color = if (segment.id == selectedSegmentId) Blue else Color.White.copy(alpha = .35f),
                                )
                                .pointerInput(segment.id, segment.startMillis, segment.endMillis, segmentWidth) {
                                    detectTapGestures { tap ->
                                        val fraction = (tap.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                        val sourcePosition = segment.startMillis +
                                            (segment.durationMillis.toFloat() * fraction).toLong()
                                        onSeek(segment, sourcePosition)
                                    }
                                }
                        ) {
                            val visualCount = (segmentWidths[index] / 82f).roundToInt().coerceAtLeast(1)
                            Row(Modifier.fillMaxSize()) {
                                repeat(visualCount) { visualIndex ->
                                    val cellWidth = (segmentWidths[index] / visualCount).dp
                                    if (isVideo) {
                                        val sourceMillis = segment.startMillis.toFloat() + segment.durationMillis.toFloat() *
                                            ((visualIndex + 0.5f) / visualCount)
                                        val sourceFraction = sourceMillis / sourceDurationMillis.coerceAtLeast(1L).toFloat()
                                        val thumbnailIndex = (sourceFraction * (thumbnails.size - 1))
                                            .roundToInt().coerceIn(0, thumbnails.lastIndex)
                                        Image(
                                            bitmap = thumbnails[thumbnailIndex].asImageBitmap(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.width(cellWidth).height(92.dp),
                                        )
                                    } else {
                                        Box(
                                            Modifier.width(cellWidth).height(92.dp).background(
                                                if ((index + visualIndex) % 2 == 0) Color(0xFF252525) else Color(0xFF303030),
                                            ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                Icons.Rounded.MusicNote,
                                                null,
                                                tint = Color.White.copy(alpha = .72f),
                                                modifier = Modifier.size(26.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                "${index + 1}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = .58f)).padding(4.dp),
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.offset(x = markerOffset).width(markerWidth).height(112.dp)
                        .pointerInput(segmentLayoutKey, totalWidthPx) {
                            var dragPositionPx = 0f
                            detectDragGestures(
                                onDragStart = {
                                    dragPositionPx = with(density) { markerPositionValue.dp.toPx() }
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragPositionPx = (dragPositionPx + dragAmount.x).coerceIn(0f, totalWidthPx)
                                    displayTargetFor(segments, widthPixels, dragPositionPx)?.let { (segment, sourcePosition) ->
                                        onSeek(segment, sourcePosition)
                                    }
                                },
                            )
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(22.dp).background(Orange, CircleShape), contentAlignment = Alignment.Center) {
                        Text("●", color = Color.White, fontSize = 8.sp)
                    }
                    Box(Modifier.width(3.dp).height(90.dp).background(Orange))
                }
            }
        }
    }
}

@Composable
private fun SegmentEditorCard(
    index: Int,
    totalCount: Int,
    segment: EditorMediaSegment,
    selected: Boolean,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val dragThreshold = with(density) { 42.dp.toPx() }
    var accumulatedDrag by remember(segment.id) { mutableStateOf(0f) }
    Card(
        modifier = Modifier.fillMaxWidth()
            .border(if (selected) 2.dp else 0.dp, if (selected) Blue else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = if (selected) BluePaper else SurfacePaper),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("片段 ${index + 1}", fontWeight = FontWeight.Bold, color = if (selected) Blue else Ink)
                Text(
                    "${formatLocalDuration(segment.startMillis)} — ${formatLocalDuration(segment.endMillis)} · ${formatLocalDuration(segment.durationMillis)}",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
            IconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayArrow, "独立播放片段") }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "删除片段", tint = Color(0xFFB3261E)) }
            Box(
                modifier = Modifier.size(44.dp)
                    .pointerInput(segment.id, totalCount) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { accumulatedDrag = 0f },
                            onDragCancel = { accumulatedDrag = 0f },
                            onDragEnd = { accumulatedDrag = 0f },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                accumulatedDrag += dragAmount.y
                                if (accumulatedDrag >= dragThreshold) {
                                    onMove(1)
                                    accumulatedDrag = 0f
                                } else if (accumulatedDrag <= -dragThreshold) {
                                    onMove(-1)
                                    accumulatedDrag = 0f
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("↕", color = Muted, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AddedAudioTrackCard(
    track: EditorAudioTrack,
    projectDuration: Long,
    playheadMillis: Long,
    onChange: (EditorAudioTrack) -> Unit,
    onDelete: () -> Unit,
) {
    val duration = track.durationMillis.coerceAtLeast(1L)
    Card(colors = CardDefaults.cardColors(containerColor = BluePaper), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.GraphicEq, null, tint = Blue)
                Spacer(Modifier.width(8.dp))
                Text(track.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "删除音轨", tint = Color(0xFFB3261E)) }
            }
            Box(Modifier.fillMaxWidth().height(28.dp).background(Ink.copy(alpha = .12f), RoundedCornerShape(7.dp))) {
                val startFraction = (track.offsetMillis.toFloat() / projectDuration).coerceIn(0f, 1f)
                val maxLengthFraction = (1f - startFraction).coerceAtLeast(.001f)
                val lengthFraction = ((track.trimEndMillis - track.trimStartMillis).toFloat() / projectDuration)
                    .coerceIn(.001f, maxLengthFraction)
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(startFraction.coerceAtLeast(.001f)))
                    Box(Modifier.weight(lengthFraction).height(28.dp).background(Blue, RoundedCornerShape(7.dp)))
                    Spacer(Modifier.weight((1f - startFraction - lengthFraction).coerceAtLeast(.001f)))
                }
            }
            Text("音轨保留 ${formatLocalDuration(track.trimStartMillis)} — ${formatLocalDuration(track.trimEndMillis)}", color = Muted, fontSize = 11.sp)
            RangeSlider(
                value = track.trimStartMillis.toFloat()..track.trimEndMillis.toFloat(),
                onValueChange = { onChange(track.copy(trimStartMillis = it.start.toLong(), trimEndMillis = it.endInclusive.toLong())) },
                valueRange = 0f..duration.toFloat(),
            )
            Text("进入时间 ${formatLocalDuration(track.offsetMillis)}", color = Muted, fontSize = 11.sp)
            Slider(
                value = track.offsetMillis.coerceIn(0L, projectDuration).toFloat(),
                onValueChange = { onChange(track.copy(offsetMillis = it.toLong())) },
                valueRange = 0f..projectDuration.coerceAtLeast(1L).toFloat(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val sourceAtPlayhead = (track.trimStartMillis + playheadMillis - track.offsetMillis).coerceIn(0L, duration)
                TextButton(onClick = {
                    val safeEnd = track.trimEndMillis.coerceIn(1L, duration)
                    onChange(track.copy(trimStartMillis = sourceAtPlayhead.coerceIn(0L, safeEnd - 1L)))
                }) { Text("从播放点删前") }
                TextButton(onClick = {
                    val safeStart = track.trimStartMillis.coerceIn(0L, duration - 1L)
                    onChange(track.copy(trimEndMillis = sourceAtPlayhead.coerceIn(safeStart + 1L, duration)))
                }) { Text("从播放点删后") }
            }
        }
    }
}

@Composable
private fun EditorSectionTitle(title: String) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
}

private suspend fun loadTimelineThumbnails(
    context: Context,
    uri: Uri,
    durationMillis: Long,
    count: Int,
): List<Bitmap> = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        (0 until count).mapNotNull { index ->
            val fraction = index.toDouble() / (count - 1).coerceAtLeast(1)
            val timeUs = (durationMillis * 1_000L * fraction).toLong()
            runCatching {
                val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 135)
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { source ->
                        Bitmap.createScaledBitmap(source, 240, 135, true).also { if (it !== source) source.recycle() }
                    }
                }
                frame
            }.getOrNull()
        }
    } finally {
        retriever.release()
    }
}

private fun projectPositionFor(
    segments: List<EditorMediaSegment>,
    selectedSegmentId: String,
    sourcePositionMillis: Long,
): Long {
    var elapsed = 0L
    segments.forEach { segment ->
        if (segment.id == selectedSegmentId) {
            return elapsed + (sourcePositionMillis - segment.startMillis).coerceIn(0L, segment.durationMillis)
        }
        elapsed += segment.durationMillis
    }
    return 0L
}

private fun projectTargetFor(
    segments: List<EditorMediaSegment>,
    projectPositionMillis: Long,
): Pair<EditorMediaSegment, Long>? {
    if (segments.isEmpty()) return null
    val totalDuration = segments.sumOf(EditorMediaSegment::durationMillis)
    var remaining = projectPositionMillis.coerceIn(0L, totalDuration)
    segments.forEachIndexed { index, segment ->
        if (remaining <= segment.durationMillis || index == segments.lastIndex) {
            return segment to (segment.startMillis + remaining.coerceIn(0L, segment.durationMillis))
        }
        remaining -= segment.durationMillis
    }
    return null
}

private fun displayTargetFor(
    segments: List<EditorMediaSegment>,
    segmentWidths: List<Float>,
    displayPosition: Float,
): Pair<EditorMediaSegment, Long>? {
    if (segments.isEmpty() || segmentWidths.size != segments.size) return null
    var remaining = displayPosition.coerceAtLeast(0f)
    segments.forEachIndexed { index, segment ->
        val width = segmentWidths[index].coerceAtLeast(1f)
        if (remaining <= width || index == segments.lastIndex) {
            val fraction = (remaining / width).coerceIn(0f, 1f)
            return segment to (
                segment.startMillis + (segment.durationMillis.toFloat() * fraction).toLong()
                )
        }
        remaining -= width
    }
    return null
}

private fun takeEditorReadPermission(context: Context, uri: Uri) {
    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}
