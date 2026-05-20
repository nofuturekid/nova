package io.github.nofuturekid.nova.data.model

import io.github.nofuturekid.nova.ui.theme.Density
import io.github.nofuturekid.nova.ui.theme.ThemeMode

data class AppSettings(
    val accentHex: Long = 0xFF22D3A4,
    val themeMode: ThemeMode = ThemeMode.System,
    val density: Density = Density.Balanced,
)
