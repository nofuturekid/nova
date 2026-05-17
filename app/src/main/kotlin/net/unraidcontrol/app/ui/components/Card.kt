package net.unraidcontrol.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.unraidcontrol.app.ui.theme.UnraidTheme

@Composable
fun UnraidCard(
    modifier: Modifier = Modifier,
    padding: Dp? = null,
    background: Color? = null,
    borderColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val t = UnraidTheme.colors
    val tokens = UnraidTheme.tokens
    val shape = RoundedCornerShape(tokens.rad)
    val interaction = remember { MutableInteractionSource() }
    val clickModifier = if (onClick != null) {
        modifier.clickable(
            onClick = onClick,
            role = Role.Button,
            interactionSource = interaction,
            indication = ripple(),
        )
    } else {
        modifier
    }
    Surface(
        modifier = clickModifier,
        shape = shape,
        color = background ?: t.surface,
        border = BorderStroke(1.dp, borderColor ?: t.border),
    ) {
        Box(modifier = Modifier.padding(padding ?: tokens.pad)) {
            content()
        }
    }
}
