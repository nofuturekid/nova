package net.unraidcontrol.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// The prototype uses Inter + JetBrains Mono. To ship without depending on
// Google Play Services or a font asset bundle, we fall back to the platform
// default (Roboto on Android) and platform monospace. To match the prototype
// exactly: drop Inter*.ttf and JetBrainsMono*.ttf into res/font/ and switch
// these FontFamily references.
val Inter: FontFamily = FontFamily.Default
val JetBrainsMono: FontFamily = FontFamily.Monospace

// Material 3 type scale, calibrated for a dense mobile dashboard.
//
// Migration rule (replacing ad-hoc fontSize across the UI):
//   - body*  = readable content / prose. M3-exact (16/14/12) — this is
//     the readability floor, never go smaller for content text.
//   - label* = chips, pills, buttons, captions, overlines, mono specs.
//   - title*/headline*/display* = section + screen headers (kept more
//     compact than raw M3 defaults so the dashboard stays dense, but the
//     role hierarchy is intact).
// Every Text() picks a role via MaterialTheme.typography.* instead of
// hardcoding fontSize/fontWeight. Mono text = role.copy(fontFamily =
// JetBrainsMono); colours stay per-call from UnraidTheme.
val UnraidTypography = Typography(
    displayLarge   = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.02f).em),
    displayMedium  = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.02f).em),
    displaySmall   = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.01f).em),
    headlineLarge  = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.02f).em),
    headlineMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.02f).em),
    headlineSmall  = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.01f).em),
    titleLarge     = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium    = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall     = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge      = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal,   fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium     = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal,   fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall      = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal,   fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge     = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium    = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium,   fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.04f.em),
    labelSmall     = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.10f.em),
)
