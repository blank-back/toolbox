package com.pockettoolbox.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.pockettoolbox.app.ui.ToolboxApp
import com.pockettoolbox.app.ui.theme.ToolboxTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ToolboxViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ToolboxTheme {
                ToolboxApp(viewModel)
            }
        }
    }
}
