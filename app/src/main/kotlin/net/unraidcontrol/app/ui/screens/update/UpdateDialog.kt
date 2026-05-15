package net.unraidcontrol.app.ui.screens.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import net.unraidcontrol.app.BuildConfig
import net.unraidcontrol.app.data.model.InstallState
import net.unraidcontrol.app.data.model.UpdateInfo
import net.unraidcontrol.app.ui.components.BtnVariant
import net.unraidcontrol.app.ui.components.Pill
import net.unraidcontrol.app.ui.components.Tone
import net.unraidcontrol.app.ui.components.UC
import net.unraidcontrol.app.ui.components.UnraidButton
import net.unraidcontrol.app.ui.components.UnraidIconButton
import net.unraidcontrol.app.ui.components.UnraidProgress
import net.unraidcontrol.app.ui.theme.JetBrainsMono
import net.unraidcontrol.app.ui.theme.UnraidTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    install: InstallState,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    onGrantPermission: (InstallState.NeedsPermission) -> Unit,
) {
    val t = UnraidTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = t.surface2,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // drag handle
            Box(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.18f)),
                )
            }
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Update available",
                            color = t.text,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (info.isPrerelease) Pill("BETA", tone = Tone.Warn)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}  →  v${info.version}  ·  ${formatBytes(info.sizeBytes)}",
                        color = t.muted,
                        fontSize = 12.sp,
                        fontFamily = JetBrainsMono,
                    )
                }
                UnraidIconButton(icon = { UC.X(20.dp, t.text) }, onClick = onDismiss)
            }

            if (info.releaseNotes.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Release notes",
                    color = t.muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.3.sp,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightConstrainedAtMost(220.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(12.dp),
                ) {
                    // Default Markdown typography maps headers to MaterialTheme
                    // display/headline sizes (28-57 sp) which are sized for
                    // full-screen layouts and overwhelm a bottom-sheet. Override
                    // with dialog-appropriate sizes; colours come from our
                    // UnraidTheme so links + body match the surrounding UI.
                    val bodyStyle = androidx.compose.ui.text.TextStyle(color = t.text, fontSize = 12.sp)
                    Markdown(
                        content = info.releaseNotes.trim(),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        colors = markdownColor(
                            text = t.text,
                            linkText = t.accent,
                        ),
                        typography = markdownTypography(
                            h1 = bodyStyle.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                            h2 = bodyStyle.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            h3 = bodyStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                            h4 = bodyStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                            h5 = bodyStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            h6 = bodyStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            text = bodyStyle,
                            paragraph = bodyStyle,
                            ordered = bodyStyle,
                            bullet = bodyStyle,
                            list = bodyStyle,
                            code = bodyStyle.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            inlineCode = bodyStyle.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            quote = bodyStyle.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                            link = bodyStyle.copy(color = t.accent, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                        ),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // Status / progress / actions
            when (install) {
                is InstallState.Idle -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.weight(1f))
                        UnraidButton(
                            onClick = onDismiss,
                            label = "Later",
                            variant = BtnVariant.Text,
                            tone = Tone.Neutral,
                        )
                        UnraidButton(
                            onClick = onInstall,
                            label = "Install",
                            variant = BtnVariant.Filled,
                        )
                    }
                }
                is InstallState.Downloading -> {
                    Text(
                        text = "Downloading… ${(install.progress * 100).toInt()}%",
                        color = t.muted,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    UnraidProgress(install.progress, color = t.accent, height = 6.dp)
                }
                is InstallState.Installing -> {
                    Text(
                        text = "Waiting for Android install confirm…",
                        color = t.muted,
                        fontSize = 12.sp,
                    )
                }
                is InstallState.NeedsPermission -> {
                    Text(
                        text = "Android needs you to allow this app to install other apps. Tap below to open the system settings.",
                        color = t.warn,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Spacer(Modifier.weight(1f))
                        UnraidButton(
                            onClick = { onGrantPermission(install) },
                            label = "Open settings",
                            variant = BtnVariant.Filled,
                            tone = Tone.Warn,
                        )
                    }
                }
                is InstallState.Failed -> {
                    Text(
                        text = "Install failed: ${install.message}",
                        color = t.danger,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.weight(1f))
                        UnraidButton(
                            onClick = onDismiss,
                            label = "Close",
                            variant = BtnVariant.Text,
                            tone = Tone.Neutral,
                        )
                        UnraidButton(
                            onClick = onInstall,
                            label = "Retry",
                            variant = BtnVariant.Tonal,
                            tone = Tone.Danger,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun Modifier.heightConstrainedAtMost(max: androidx.compose.ui.unit.Dp): Modifier =
    this.heightIn(max = max)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000     -> "%.0f KB".format(bytes / 1_000.0)
    else               -> "$bytes B"
}
