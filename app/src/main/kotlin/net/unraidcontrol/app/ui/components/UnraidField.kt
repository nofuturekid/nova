package net.unraidcontrol.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import net.unraidcontrol.app.ui.theme.UnraidAlpha
import net.unraidcontrol.app.ui.theme.UnraidDims
import net.unraidcontrol.app.ui.theme.UnraidTheme

@Composable
fun UnraidField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    helper: String? = null,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    obscured: Boolean = false,
) {
    val t = UnraidTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = when {
        isError -> t.danger
        focused -> t.accent
        else    -> t.border
    }
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = t.muted.copy(alpha = UnraidAlpha.softFill),
                    shape = RoundedCornerShape(UnraidDims.radField),
                )
                .border(1.dp, borderColor, RoundedCornerShape(UnraidDims.radField))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (leadingIcon != null) Box { leadingIcon() }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = if (focused) t.accent else t.muted,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(2.dp))
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = label },
                    interactionSource = interaction,
                    singleLine = true,
                    cursorBrush = SolidColor(t.accent),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = t.text),
                    visualTransformation = if (obscured) PasswordVisualTransformation() else visualTransformation,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty() && placeholder.isNotEmpty()) {
                                Text(placeholder, color = t.muted.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyLarge)
                            }
                            inner()
                        }
                    },
                )
            }
            if (trailing != null) trailing()
        }
        if (helper != null) {
            Text(
                text = helper,
                color = if (isError) t.danger else t.muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 14.dp, top = 4.dp),
            )
        }
    }
}
