package net.unraidcontrol.app.data.model

import net.unraidcontrol.app.ui.theme.Density
import net.unraidcontrol.app.ui.theme.ThemeMode

data class AppSettings(
    val accentHex: Long = 0xFF22D3A4,
    val themeMode: ThemeMode = ThemeMode.System,
    val density: Density = Density.Balanced,
)
