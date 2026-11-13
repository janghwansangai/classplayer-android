package com.classplayer.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 밝은 교실형 디자인: 흰 바탕, 둥근 파란 버튼. */
val Blue = Color(0xFF2F6FED)
val BlueDark = Color(0xFF1E55C9)
val Ink = Color(0xFF1F2937)
val Muted = Color(0xFF6B7280)
val Line = Color(0xFFE5E7EB)
val Panel = Color(0xFFF5F7FB)

private val Scheme = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3ECFF),
    onPrimaryContainer = BlueDark,
    secondary = BlueDark,
    background = Color.White,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Panel,
    onSurfaceVariant = Muted,
    outline = Line,
    error = Color(0xFFD9534F),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ClassPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, shapes = AppShapes, content = content)
}
