package com.pockettoolbox.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import com.pockettoolbox.app.ui.ToolboxApp
import com.pockettoolbox.app.ui.theme.ToolboxTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ToolboxViewModel by viewModels()
    private var isInPipMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlaybackNavigationBus.readIntent(intent)
        enableEdgeToEdge()
        setContent {
            ToolboxTheme {
                ToolboxApp(
                    viewModel = viewModel,
                    isInPictureInPictureMode = isInPipMode,
                    onEnterPictureInPicture = ::enterPlaybackPictureInPicture,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PlaybackNavigationBus.readIntent(intent)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
    }

    private fun enterPlaybackPictureInPicture(width: Int, height: Int) {
        val safeWidth = width.takeIf { it > 0 } ?: 16
        val safeHeight = height.takeIf { it > 0 } ?: 9
        val aspectRatio = (safeWidth.toDouble() / safeHeight.toDouble()).coerceIn(0.42, 2.39)
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((aspectRatio * 1_000).roundToInt(), 1_000))
            .build()
        enterPictureInPictureMode(params)
    }
}
