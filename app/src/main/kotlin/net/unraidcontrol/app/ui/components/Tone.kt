package net.unraidcontrol.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import net.unraidcontrol.app.ui.theme.UnraidTheme

enum class Tone { Neutral, Accent, Warn, Danger, Info }

data class ToneColors(val bg: Color, val fg: Color, val dot: Color)

@Composable
fun Tone.colors(): ToneColors {
    val t = UnraidTheme.colors
    return when (this) {
        Tone.Neutral -> ToneColors(bg = t.muted.copy(alpha = 0.12f), fg = t.muted, dot = t.muted)
        Tone.Accent  -> ToneColors(bg = t.accentDim, fg = t.accent, dot = t.accent)
        Tone.Warn    -> ToneColors(bg = t.warn.copy(alpha = 0.16f), fg = t.warn, dot = t.warn)
        Tone.Danger  -> ToneColors(bg = t.danger.copy(alpha = 0.16f), fg = t.danger, dot = t.danger)
        Tone.Info    -> ToneColors(bg = t.info.copy(alpha = 0.16f), fg = t.info, dot = t.info)
    }
}
