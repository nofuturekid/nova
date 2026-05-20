package io.github.nofuturekid.nova.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class UnraidColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentDim: Color,
    val warn: Color,
    val danger: Color,
    val info: Color,
)

object AccentSwatches {
    val Mint = Color(0xFF22D3A4)
    val Blue = Color(0xFF3B82F6)
    val Purple = Color(0xFFA78BFA)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFEF4444)
    val all = listOf(Mint, Blue, Purple, Amber, Red)
}

fun darkColors(accent: Color): UnraidColors = UnraidColors(
    bg = Color(0xFF0A0E0D),
    surface = Color(0xFF131817),
    surface2 = Color(0xFF1C2322),
    border = Color(0x12FFFFFF),
    text = Color(0xFFE6EDEB),
    muted = Color(0xFF8A9693),
    accent = accent,
    accentDim = accent.copy(alpha = UnraidAlpha.tonalFill),
    warn = Color(0xFFF59E0B),
    danger = Color(0xFFEF4444),
    info = Color(0xFF3B82F6),
)

fun lightColors(accent: Color): UnraidColors = UnraidColors(
    bg = Color(0xFFF4F7F5),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFFAFCFA),
    border = Color(0x14000000),
    text = Color(0xFF0E1413),
    muted = Color(0xFF5B6764),
    accent = accent,
    accentDim = accent.copy(alpha = UnraidAlpha.tonalFill),
    warn = Color(0xFFF59E0B),
    danger = Color(0xFFEF4444),
    info = Color(0xFF3B82F6),
)
