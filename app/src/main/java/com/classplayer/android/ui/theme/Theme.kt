package com.classplayer.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 시안 B: 다크 뮤직형. 어두운 남색 바탕, 밝은 파란 포인트, 둥근 버튼. */
val Bg = Color(0xFF0F172A)
val Surface = Color(0xFF1E293B)
val Line = Color(0xFF334155)
val Accent = Color(0xFF60A5FA)
val AccentDeep = Color(0xFF1D4ED8)
val Ink = Color(0xFFF1F5F9)
val Muted = Color(0xFF94A3B8)

/** 썸네일이 없을 때 쓰는 커버 색. */
val CoverColors = listOf(
    Color(0xFF1D4ED8), Color(0xFF9A3412), Color(0xFF166534),
    Color(0xFF6B21A8), Color(0xFFA16207), Color(0xFF9F1239),
)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Bg,
    primaryContainer = AccentDeep,
    onPrimaryContainer = Ink,
    background = Bg,
    onBackground = Ink,
    surface = Bg,
    onSurface = Ink,
    surfaceVariant = Surface,
    onSurfaceVariant = Muted,
    outline = Line,
    error = Color(0xFFF87171),
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
