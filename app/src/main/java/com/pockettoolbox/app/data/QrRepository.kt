package com.pockettoolbox.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.pockettoolbox.app.model.QrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI

class QrRepository(private val context: Context) {
    data class ScanOutput(
        val preview: Bitmap,
        val results: List<QrResult>,
    )

    suspend fun scan(uri: Uri): ScanOutput {
        val bitmap = withContext(Dispatchers.IO) { loadBitmap(uri) }
        return withContext(Dispatchers.Default) {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            val hints = mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8",
            )
            val texts = try {
                GenericMultipleBarcodeReader(MultiFormatReader())
                    .decodeMultiple(binary, hints)
                    .map { it.text }
                    .filter(String::isNotBlank)
                    .distinct()
            } catch (_: NotFoundException) {
                emptyList()
            }
            ScanOutput(
                preview = bitmap,
                results = texts.map { text -> QrResult(text, parseWebUrl(text)) },
            )
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap {
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val size = info.size
                val scale = minOf(1f, MAX_DIMENSION.toFloat() / maxOf(size.width, size.height))
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (size.width * scale).toInt().coerceAtLeast(1),
                        (size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        } else {
            decodeLegacy(uri)
        }
        if (decoded.width <= 0 || decoded.height <= 0) throw IOException("无法读取图片。")
        return decoded
    }

    private fun decodeLegacy(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IOException("无法打开图片。")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("不支持该图片格式。")
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("无法解码图片。")
        val orientation = context.contentResolver.openInputStream(uri)?.use {
            runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        return applyOrientation(bitmap, orientation)
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    private fun parseWebUrl(text: String): String? = runCatching {
        val uri = URI(text)
        if ((uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()) {
            uri.toASCIIString()
        } else {
            null
        }
    }.getOrNull()

    private companion object {
        // ZXing also creates an IntArray with one entry per pixel. Capping both
        // dimensions keeps the combined peak well below typical Android heaps.
        const val MAX_DIMENSION = 2560
    }
}
