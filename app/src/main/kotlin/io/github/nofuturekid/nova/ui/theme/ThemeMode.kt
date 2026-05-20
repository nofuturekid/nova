package io.github.nofuturekid.nova.ui.theme

/**
 * User theme preference. `System` follows the OS dark/light setting
 * (resolved via isSystemInDarkTheme() inside UnraidTheme); `Light` /
 * `Dark` force the respective scheme.
 */
enum class ThemeMode { System, Light, Dark }
