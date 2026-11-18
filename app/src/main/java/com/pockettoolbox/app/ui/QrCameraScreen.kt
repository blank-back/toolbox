package com.pockettoolbox.app.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Image as ImageIcon
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pockettoolbox.app.ToolboxViewModel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unified QR entry: the camera opens first and the gallery remains available
 * from the bottom of the same screen, like a conventional scanner app.
 */
@Composable
internal fun QrCameraScreen(
    viewModel: ToolboxViewModel,
    onFinished: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var requestedAutomatically by rememberSaveable { mutableStateOf(false) }
    var cameraError by rememberSaveable { mutableStateOf<String?>(null) }
    val screenActive = remember { AtomicBoolean(true) }
    val acceptCameraFrames = remember { AtomicBoolean(true) }

    DisposableEffect(Unit) {
        screenActive.set(true)
        acceptCameraFrames.set(true)
        onDispose {
            screenActive.set(false)
            acceptCameraFrames.set(false)
        }
    }

    DisposableEffect(view) {
        val window = (context as? Activity)?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousLightStatusBars = insetsController?.isAppearanceLightStatusBars
        val previousLightNavigationBars = insetsController?.isAppearanceLightNavigationBars
        insetsController?.isAppearanceLightStatusBars = false
        insetsController?.isAppearanceLightNavigationBars = false
        onDispose {
            if (insetsController != null) {
                previousLightStatusBars?.let { insetsController.isAppearanceLightStatusBars = it }
                previousLightNavigationBars?.let { insetsController.isAppearanceLightNavigationBars = it }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (granted) cameraError = null
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.scanQr(uri)
            onFinished()
        } else {
            acceptCameraFrames.set(true)
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted && !requestedAutomatically) {
            requestedAutomatically = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (permissionGranted) {
            CameraPreview(
                onBitmap = { bitmap, resumeAnalysis ->
                    if (acceptCameraFrames.get()) {
                        viewModel.scanQrCameraFrame(bitmap) { found ->
                            if (found) {
                                if (screenActive.get()) onFinished()
                            } else {
                                resumeAnalysis()
                            }
                        }
                    } else {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        resumeAnalysis()
                    }
                },
                onError = { cameraError = it },
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(.76f)
                    .aspectRatio(1f)
                    .border(3.dp, Color.White, RoundedCornerShape(24.dp)),
            )
            Text(
                text = "将二维码放入框内",
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 300.dp),
            )
        } else {
            CameraPermissionContent(
                onRequestPermission = {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = .56f))
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.background(Color.Black.copy(alpha = .38f), CircleShape),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Color.White)
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text("扫描二维码", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }

        cameraError?.let { message ->
            Text(
                text = message,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 84.dp, start = 24.dp, end = 24.dp)
                    .background(Color(0xCC8B2D21), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = .68f))
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                Button(
                    onClick = {
                        acceptCameraFrames.set(false)
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                ) {
                    Icon(Icons.Rounded.ImageIcon, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("相册")
                }
            }
            Text(
                "选择图片后返回结果页",
                color = Color.White.copy(alpha = .65f),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun CameraPermissionContent(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.QrCode2, null, tint = Color.White, modifier = Modifier.size(70.dp))
        Text(
            "需要相机权限",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            "相机画面只用于本地二维码识别，不会上传或保存。你也可以直接使用下方的相册入口。",
            color = Color.White.copy(alpha = .72f),
            textAlign = TextAlign.Center,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 10.dp, bottom = 18.dp),
        )
        Button(onClick = onRequestPermission) {
            Text("允许使用相机")
        }
    }
}

@Composable
private fun CameraPreview(
    onBitmap: (Bitmap, resumeAnalysis: () -> Unit) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBitmap by rememberUpdatedState(onBitmap)
    val currentOnError by rememberUpdatedState(onError)
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(
            analysisExecutor,
            CameraFrameAnalyzer { bitmap, resume -> currentOnBitmap(bitmap, resume) },
        )

        var provider: ProcessCameraProvider? = null
        var disposed = false
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                if (!disposed) {
                    runCatching {
                        provider = providerFuture.get()
                        provider?.unbindAll()
                        provider?.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }.onFailure {
                        currentOnError("无法启动相机：${it.message ?: "设备相机不可用"}")
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            analysisExecutor.shutdownNow()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}

private class CameraFrameAnalyzer(
    private val onBitmap: (Bitmap, resumeAnalysis: () -> Unit) -> Unit,
) : ImageAnalysis.Analyzer {
    private val frameInFlight = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (!frameInFlight.compareAndSet(false, true)) {
            image.close()
            return
        }

        var bitmap: Bitmap? = null
        try {
            val source = image.toBitmap()
            bitmap = rotateBitmap(source, image.imageInfo.rotationDegrees)
            onBitmap(bitmap) { frameInFlight.set(false) }
        } catch (_: Exception) {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            frameInFlight.set(false)
        } finally {
            image.close()
        }
    }

    private fun rotateBitmap(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return source
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true,
        ).also { rotated ->
            if (rotated !== source) source.recycle()
        }
    }
}
