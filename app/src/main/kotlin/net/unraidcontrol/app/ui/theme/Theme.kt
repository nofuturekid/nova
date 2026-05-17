package net.unraidcontrol.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

val LocalUnraidColors = staticCompositionLocalOf<UnraidColors> {
    error("UnraidColors not provided")
}
val LocalDensityTokens = staticCompositionLocalOf<DensityTokens> {
    error("DensityTokens not provided")
}

object UnraidTheme {
    val colors: UnraidColors @Composable get() = LocalUnraidColors.current
    val tokens: DensityTokens @Composable get() = LocalDensityTokens.current
}

@Composable
fun UnraidTheme(
    accent: Color = AccentSwatches.Mint,
    themeMode: ThemeMode = ThemeMode.System,
    density: Density = Density.Balanced,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        ThemeMode.Dark   -> true
        ThemeMode.Light  -> false
        ThemeMode.System -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val colors = if (isDark) darkColors(accent) else lightColors(accent)
    val tokens = tokensFor(density)

    // System status/navigation bar icons must stay readable against the
    // *app* theme, not the OS theme (e.g. app forced Light while the
    // phone is in Dark): light app → dark icons, dark app → light icons.
    // Reactive to themeMode so it follows the Settings choice live.
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as android.app.Activity).window
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
    }

    val m3 = if (isDark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = Color(0xFF06120E),
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.muted,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.muted,
            error = colors.danger,
        )
    }

    CompositionLocalProvider(
        LocalUnraidColors provides colors,
        LocalDensityTokens provides tokens,
    ) {
        MaterialTheme(
            colorScheme = m3,
            typography = UnraidTypography,
            shapes = unraidShapes(tokens),
            content = { ThemedRoot(content) },
        )
    }
}

@Composable
private fun ThemedRoot(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(UnraidTheme.colors.bg),
    ) { content() }
}
