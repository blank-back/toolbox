package com.pockettoolbox.app.data

import java.io.File
import org.json.JSONObject

data class UnityNativeExportReport(
    val exportedCount: Int,
    val imageCount: Int,
    val audioCount: Int,
    val videoCount: Int,
    val textCount: Int,
    val existingSkippedCount: Int,
    val manifestWritten: Boolean,
    val failedCount: Int,
    val unsupportedCount: Int,
    val serializedFileCount: Int,
    val objectCount: Int,
    val resourceCount: Int,
    val diagnosticCount: Int,
    val failureMessages: List<String>,
)

object UnityNativeAssetBridge {
    private val libraryLoadResult: Result<Unit> by lazy {
        runCatching { System.loadLibrary("pockettoolbox_unity") }
    }

    fun exportMedia(
        input: File,
        outputDirectory: File,
        existingOutputIndex: File?,
        exportImages: Boolean,
        exportAudio: Boolean,
        exportVideo: Boolean,
        exportText: Boolean,
        maximumOutputBytes: Long,
    ): UnityNativeExportReport {
        require(input.exists()) { "Unity 本地暂存输入不存在" }
        require(outputDirectory.isDirectory || outputDirectory.mkdirs()) { "无法创建 Unity 对象解析暂存目录" }
        require(exportImages || exportAudio || exportVideo || exportText) {
            "请至少选择图片、音频、视频或文本对象"
        }
        libraryLoadResult.getOrElse { cause ->
            throw IllegalStateException(
                "Unity 对象解析组件未能加载；请确认 APK 已包含当前设备架构的 Rust 原生库",
                cause,
            )
        }
        val rawResult = nativeExportMedia(
            input.absolutePath,
            outputDirectory.absolutePath,
            existingOutputIndex?.absolutePath.orEmpty(),
            exportImages,
            exportAudio,
            exportVideo,
            exportText,
            maximumOutputBytes,
        )
        val json = JSONObject(rawResult)
        require(json.optBoolean("ok", false)) {
            json.optString("error").ifBlank { "Unity 原生解析返回未知错误" }
        }
        val failures = json.optJSONArray("failureMessages")
        return UnityNativeExportReport(
            exportedCount = json.optInt("exported"),
            imageCount = json.optInt("images"),
            audioCount = json.optInt("audio"),
            videoCount = json.optInt("video"),
            textCount = json.optInt("text"),
            existingSkippedCount = json.optInt("existingSkipped"),
            manifestWritten = json.optBoolean("manifestWritten", false),
            failedCount = json.optInt("failed"),
            unsupportedCount = json.optInt("unsupported"),
            serializedFileCount = json.optInt("serializedFiles"),
            objectCount = json.optInt("objects"),
            resourceCount = json.optInt("resources"),
            diagnosticCount = json.optInt("diagnostics"),
            failureMessages = buildList {
                if (failures != null) {
                    for (index in 0 until failures.length()) {
                        failures.optString(index).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            },
        )
    }

    @JvmStatic
    private external fun nativeExportMedia(
        inputPath: String,
        outputPath: String,
        existingOutputIndexPath: String,
        exportImages: Boolean,
        exportAudio: Boolean,
        exportVideo: Boolean,
        exportText: Boolean,
        maximumOutputBytes: Long,
    ): String
}
