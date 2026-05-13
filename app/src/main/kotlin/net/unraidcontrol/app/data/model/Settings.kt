package net.unraidcontrol.app.data.model

import net.unraidcontrol.app.ui.theme.Density

data class AppSettings(
    val accentHex: Long = 0xFF22D3A4,
    val isDark: Boolean = true,
    val density: Density = Density.Balanced,
)
