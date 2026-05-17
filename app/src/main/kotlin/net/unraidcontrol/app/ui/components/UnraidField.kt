package net.unraidcontrol.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import net.unraidcontrol.app.ui.theme.UnraidDims

/**
 * Material 3 [OutlinedTextField] (ADR-0030 P7), signature unchanged so
 * all 4 call sites compile untouched (the ADR's "single call site" is
 * wrong — AddEditServerSheet.kt uses it ×4).
 *
 * Rule 13 — M3-idiomatic appearance is ACCEPTED here over strict
 * zero-visual: the field now has the standard M3 floating/animating
 * label, M3 outline focus motion and M3 supporting-text/error slot.
 * Colours route EXCLUSIVELY through the P1 helper [unraidTextFieldColors]
 * (same softFill container + accent/border/danger roles as the bespoke).
 * The container corner is kept at [UnraidDims.radField] via the M3 shape.
 */
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
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .semantics { contentDescription = label },
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) {
            { Text(placeholder) }
        } else null,
        leadingIcon = leadingIcon?.let { { Box { it() } } },
        trailingIcon = trailing,
        supportingText = helper?.let { { Text(it) } },
        isError = isError,
        singleLine = true,
        shape = RoundedCornerShape(UnraidDims.radField),
        colors = unraidTextFieldColors(),
        visualTransformation = if (obscured) PasswordVisualTransformation() else visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}
