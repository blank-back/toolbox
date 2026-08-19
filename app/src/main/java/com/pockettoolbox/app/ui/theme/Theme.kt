package com.pockettoolbox.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Paper = Color(0xFFF4F2E9)
val SurfacePaper = Color(0xFFFBFAF5)
val Ink = Color(0xFF202018)
val Muted = Color(0xFF74736B)
val Orange = Color(0xFFE76F3C)
val Sage = Color(0xFFDCE4D6)
val BluePaper = Color(0xFFD9E3E9)
val Green = Color(0xFF496D54)
val Blue = Color(0xFF315A78)
val Line = Color(0xFFD9D6C9)

private val ToolboxColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    secondary = Blue,
    tertiary = Orange,
    background = Paper,
    onBackground = Ink,
    surface = SurfacePaper,
    onSurface = Ink,
    outline = Line,
    error = Color(0xFF8A3C31),
)

@Composable
fun ToolboxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ToolboxColors,
        content = content,
    )
}
