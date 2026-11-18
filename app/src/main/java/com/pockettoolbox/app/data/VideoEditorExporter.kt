package com.pockettoolbox.app.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.pockettoolbox.app.model.EditorAudioTrack
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

data class EditorMediaSegment(
    val id: String,
    val startMillis: Long,
    val endMillis: Long,
) {
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0L)
}

data class VideoExportSpec(
    val videoUri: String,
    val sourceIsVideo: Boolean,
    val segments: List<EditorMediaSegment>,
    val muteOriginalAudio: Boolean,
    val originalAudioDb: Float,
    val addedAudioTracks: List<EditorAudioTrack>,
)

@androidx.annotation.OptIn(UnstableApi::class)
class VideoEditorExporter(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transformer: Transformer? = null
    private var tempFile: File? = null
    private var progressRunnable: Runnable? = null

    fun export(
        spec: VideoExportSpec,
        destination: Uri,
        onProgress: (Int) -> Unit,
        onCompleted: () -> Unit,
        onError: (String) -> Unit,
    ) {
        cancel()
        val sourceSegments = spec.segments.filter { it.endMillis > it.startMillis }
        if (sourceSegments.isEmpty()) {
            onError("当前没有可导出的媒体片段")
            return
        }
        val outputDuration = sourceSegments.sumOf(EditorMediaSegment::durationMillis).coerceAtLeast(1L)

        val sequences = mutableListOf<EditedMediaItemSequence>()
        if (spec.sourceIsVideo || !spec.muteOriginalAudio) {
            val mainSequence = EditedMediaItemSequence.Builder()
            sourceSegments.forEach { segment ->
                val mainItemBuilder = EditedMediaItem.Builder(
                    clippedMediaItem(spec.videoUri, segment.startMillis, segment.endMillis),
                ).setRemoveAudio(spec.muteOriginalAudio)
                if (!spec.muteOriginalAudio && kotlin.math.abs(spec.originalAudioDb) >= 0.01f) {
                    val gainFactor = 10.0.pow(spec.originalAudioDb.coerceIn(-80f, 12f).toDouble() / 20.0).toFloat()
                    mainItemBuilder.setEffects(
                        Effects(listOf(ConstantGainAudioProcessor(gainFactor)), emptyList()),
                    )
                }
                mainSequence.addItem(mainItemBuilder.build())
            }
            sequences += mainSequence.build()
        }
        spec.addedAudioTracks.forEach { track ->
            val available = (outputDuration - track.offsetMillis).coerceAtLeast(0L)
            val sourceDuration = (track.trimEndMillis - track.trimStartMillis).coerceAtLeast(0L)
            val usedDuration = minOf(available, sourceDuration)
            if (usedDuration <= 0L) return@forEach
            val audioItem = EditedMediaItem.Builder(
                clippedMediaItem(track.uri, track.trimStartMillis, track.trimStartMillis + usedDuration),
            ).setRemoveVideo(true).build()
            val builder = EditedMediaItemSequence.Builder(audioItem)
            if (track.offsetMillis > 0L) {
                builder.experimentalSetForceAudioTrack(true)
                val delayedBuilder = EditedMediaItemSequence.Builder()
                    .experimentalSetForceAudioTrack(true)
                    .addGap(track.offsetMillis * 1_000L)
                    .addItem(audioItem)
                sequences += delayedBuilder.build()
            } else {
                sequences += builder.build()
            }
        }
        if (sequences.isEmpty()) {
            onError("当前保留窗口内没有可导出的音频内容")
            return
        }

        val composition = Composition.Builder(sequences).build()
        val outputExtension = if (spec.sourceIsVideo) "mp4" else "m4a"
        val output = File(context.cacheDir, "media_edit_${UUID.randomUUID()}.$outputExtension")
        tempFile = output
        val needsAudioEncoding = !spec.sourceIsVideo ||
            spec.addedAudioTracks.isNotEmpty() ||
            (!spec.muteOriginalAudio && kotlin.math.abs(spec.originalAudioDb) >= 0.01f)
        val builder = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopProgressPolling()
                    ioScope.launch {
                        runCatching {
                            context.contentResolver.openOutputStream(destination, "w")?.use { destinationStream ->
                                output.inputStream().use { source -> source.copyTo(destinationStream) }
                            } ?: error("无法打开目标文件")
                        }.onSuccess {
                            output.delete()
                            mainHandler.post(onCompleted)
                        }.onFailure { error ->
                            output.delete()
                            mainHandler.post { onError(error.message ?: "无法保存导出文件") }
                        }
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    stopProgressPolling()
                    output.delete()
                    onError(exportException.message ?: "媒体导出失败")
                }
            })
        if (needsAudioEncoding) builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
        transformer = builder.build().also { it.start(composition, output.absolutePath) }
        startProgressPolling(onProgress)
    }

    fun cancel() {
        stopProgressPolling()
        runCatching { transformer?.cancel() }
        transformer = null
        tempFile?.delete()
        tempFile = null
    }

    fun release() {
        cancel()
        ioScope.cancel()
    }

    private fun clippedMediaItem(uri: String, startMillis: Long, endMillis: Long): MediaItem =
        MediaItem.Builder()
            .setUri(Uri.parse(uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMillis.coerceAtLeast(0L))
                    .setEndPositionMs(endMillis.coerceAtLeast(startMillis + 1L))
                    .build(),
            )
            .build()

    private fun startProgressPolling(onProgress: (Int) -> Unit) {
        val runnable = object : Runnable {
            override fun run() {
                val active = transformer ?: return
                val holder = ProgressHolder()
                if (active.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress.coerceIn(0, 100))
                }
                mainHandler.postDelayed(this, 500L)
            }
        }
        progressRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopProgressPolling() {
        progressRunnable?.let(mainHandler::removeCallbacks)
        progressRunnable = null
    }

    private class ConstantGainAudioProcessor(private val gain: Float) : BaseAudioProcessor() {
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (
                inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
                inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
            ) {
                throw AudioProcessor.UnhandledAudioFormatException(
                    "仅支持 16-bit 或 float PCM 音量处理",
                    inputAudioFormat,
                )
            }
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val output = replaceOutputBuffer(inputBuffer.remaining())
            when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_16BIT -> while (inputBuffer.remaining() >= Short.SIZE_BYTES) {
                    val sample = inputBuffer.short.toInt()
                    output.putShort((sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                }
                C.ENCODING_PCM_FLOAT -> while (inputBuffer.remaining() >= Float.SIZE_BYTES) {
                    output.putFloat((inputBuffer.float * gain).coerceIn(-1f, 1f))
                }
            }
            inputBuffer.position(inputBuffer.limit())
            output.flip()
        }
    }
}
