package io.github.nofuturekid.nova.ui.screens.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import io.github.nofuturekid.nova.BuildConfig
import io.github.nofuturekid.nova.data.model.UpdateInfo
import io.github.nofuturekid.nova.ui.components.BtnVariant
import io.github.nofuturekid.nova.ui.components.Pill
import io.github.nofuturekid.nova.ui.components.Tone
import io.github.nofuturekid.nova.ui.components.UC
import io.github.nofuturekid.nova.ui.components.UnraidButton
import io.github.nofuturekid.nova.ui.components.UnraidIconButton
import io.github.nofuturekid.nova.ui.theme.JetBrainsMono
import io.github.nofuturekid.nova.ui.theme.UnraidAlpha
import io.github.nofuturekid.nova.ui.theme.UnraidTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    onDismiss: () -> Unit,
) {
    val t = UnraidTheme.colors
    val context = LocalContext.current
    var linkError by remember { mutableStateOf(false) }
    fun open(url: String) {
        linkError = !openInBrowser(context, url)
    }
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
                        .background(t.muted.copy(alpha = UnraidAlpha.grabber)),
                )
            }
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Update available",
                            color = t.text,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (info.isPrerelease) Pill("BETA", tone = Tone.Warn)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}  →  v${info.version}" +
                            (info.apk?.let { "  ·  ${formatBytes(it.sizeBytes)}" } ?: ""),
                        color = t.muted,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                    )
                }
                UnraidIconButton(icon = { UC.X(20.dp, t.text) }, onClick = onDismiss, contentDescription = "Close")
            }

            if (info.releaseNotes.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Release notes",
                    color = t.muted,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.3.sp),
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightConstrainedAtMost(220.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(t.muted.copy(alpha = UnraidAlpha.softFill))
                        .padding(12.dp),
                ) {
                    // Default Markdown typography maps headers to MaterialTheme
                    // display/headline sizes (28-57 sp) which are sized for
                    // Markdown styles derive from the app's M3 type scale
                    // so the changelog matches the rest of the UI: clear
                    // heading↔body hierarchy, links render at body size.
                    // Colour comes from markdownColor / UnraidTheme.
                    Markdown(
                        content = tidyReleaseNotes(info.releaseNotes),
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        colors = markdownColor(
                            text = t.text,
                        ),
                        typography = markdownTypography(
                            h1 = MaterialTheme.typography.titleMedium,
                            h2 = MaterialTheme.typography.titleSmall,
                            h3 = MaterialTheme.typography.labelLarge,
                            h4 = MaterialTheme.typography.labelLarge,
                            h5 = MaterialTheme.typography.labelMedium,
                            h6 = MaterialTheme.typography.labelMedium,
                            text = MaterialTheme.typography.bodyMedium,
                            paragraph = MaterialTheme.typography.bodyMedium,
                            ordered = MaterialTheme.typography.bodyMedium,
                            bullet = MaterialTheme.typography.bodyMedium,
                            list = MaterialTheme.typography.bodyMedium,
                            code = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono),
                            inlineCode = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono),
                            quote = MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                        ),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // NOVA does not install anything itself (ADR-0046): the file is
            // downloaded and opened by the browser and Android's installer.
            val apk = info.apk
            Text(
                text = if (apk != null) {
                    "NOVA does not install updates itself. Download lets your browser download " +
                        "the file; open the downloaded file to install it. Android may ask you to " +
                        "allow your browser or file manager to install apps, and Play Protect may " +
                        "ask to scan the new version first."
                } else {
                    "This release has no APK file attached. Open the release page to see " +
                        "what is available."
                },
                color = t.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            apk?.sha256?.let { sha ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "SHA-256 of the file",
                    color = t.muted,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.3.sp),
                )
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        text = sha,
                        color = t.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                    )
                }
            }
            if (linkError) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "No app on this phone can open the link.",
                    color = t.danger,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(14.dp))
            // "Release page" sits on its own row: three buttons side by side
            // do not fit a 360 dp screen at large font scales.
            if (apk != null) {
                UnraidButton(
                    onClick = { open(info.releaseUrl) },
                    label = "Release page",
                    variant = BtnVariant.Text,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(Modifier.weight(1f))
                UnraidButton(
                    onClick = onDismiss,
                    label = "Later",
                    variant = BtnVariant.Text,
                    tone = Tone.Neutral,
                )
                UnraidButton(
                    onClick = { open(info.downloadUrl) },
                    label = if (apk != null) "Download" else "Release page",
                    variant = BtnVariant.Filled,
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Sends [url] to the browser; false if no app on the phone can open it. */
private fun openInBrowser(context: Context, url: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
}

private fun Modifier.heightConstrainedAtMost(max: androidx.compose.ui.unit.Dp): Modifier =
    this.heightIn(max = max)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000     -> "%.0f KB".format(bytes / 1_000.0)
    else               -> "$bytes B"
}

/**
 * GitHub auto-generated release notes inline full PR/compare URLs, which
 * render as huge, unruly link blocks. Compact them to short link text
 * before handing the markdown to the renderer.
 */
private fun tidyReleaseNotes(raw: String): String {
    var s = raw.trim()
    s = Regex("""https://github\.com/[\w.-]+/[\w.-]+/pull/(\d+)""")
        .replace(s) { m -> "[#${m.groupValues[1]}](${m.value})" }
    s = Regex("""\*\*Full Changelog\*\*:\s*(\S+)""")
        .replace(s) { m -> "[Full changelog](${m.groupValues[1]})" }
    return s
}
