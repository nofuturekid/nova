package io.github.nofuturekid.nova.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nofuturekid.nova.data.repository.ErrorReason
import io.github.nofuturekid.nova.ui.components.BtnVariant
import io.github.nofuturekid.nova.ui.components.Tone
import io.github.nofuturekid.nova.ui.components.UC
import io.github.nofuturekid.nova.ui.components.UnraidButton
import io.github.nofuturekid.nova.ui.screens.demo.LocalDemoEntry
import io.github.nofuturekid.nova.ui.theme.UnraidTheme

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    val t = UnraidTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = t.accent)
        Spacer(Modifier.height(16.dp))
        Text("Connecting to server…", color = t.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * What a tab shows, for the error texts that name it: the noun in "can't read
 * {noun}" and the permission resource the server checks for it.
 */
enum class ErrorSubject(val noun: String, val resource: String) {
    Array("the array", "ARRAY"),
    Docker("containers", "DOCKER"),
    Vms("virtual machines", "VMS"),
}

/** The title and the explaining text of an error screen. */
data class ErrorTexts(val title: String, val body: String)

/**
 * The texts for [reason]. Pure, so a test can pin every wording. [message] is the
 * server's own text and the body only for [ErrorReason.Unreachable] and
 * [ErrorReason.ServerError] (for [ErrorReason.Redirected] it is the app's own sentence naming the
 * target); the other reasons explain what to do instead.
 * [subject] is null where a screen has none (generic wording).
 */
fun errorTexts(reason: ErrorReason, subject: ErrorSubject?, message: String): ErrorTexts = when (reason) {
    ErrorReason.Unreachable -> ErrorTexts("Can't reach the server", message)
    ErrorReason.KeyRejected -> ErrorTexts(
        "The server didn't accept the API key",
        "Check the key in this server's settings in NOVA, or create a new key in the Unraid web UI.",
    )
    ErrorReason.Forbidden -> ErrorTexts(
        "This API key can't read ${subject?.noun ?: "this"}",
        (if (subject != null) "It needs the permission ${subject.resource}:READ_ANY. " else "") +
            "A key with the VIEWER role can read everything NOVA shows.",
    )
    ErrorReason.ApiTooOld -> ErrorTexts(
        "This server's Unraid API is too old",
        "This screen needs Unraid API 4.30.0 or newer. Update the server to Unraid 7.2.5 or newer.",
    )
    ErrorReason.ServiceOff -> when (subject) {
        ErrorSubject.Docker -> ErrorTexts(
            "Docker is turned off",
            "Turn it on in the Unraid web UI under Settings → Docker.",
        )
        ErrorSubject.Vms -> ErrorTexts(
            "Virtual machines are turned off",
            "Turn them on in the Unraid web UI under Settings → VM Manager.",
        )
        // Only Docker and VMs can be switched off; anything else is the server's own error.
        else -> ErrorTexts("The server couldn't load ${subject?.noun ?: "this"}", message)
    }
    // [message] names the target ("The server redirects to https://...") and is already the advice.
    ErrorReason.Redirected -> ErrorTexts("The address needs to change", message)
    ErrorReason.ServerError -> ErrorTexts("The server couldn't load ${subject?.noun ?: "this"}", message)
}

@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Raw technical text behind a "Show details" toggle; null when [message] is already the whole story. */
    detail: String? = null,
    /** Why the load failed; the default is the original "Can't reach the server" screen. */
    reason: ErrorReason = ErrorReason.Unreachable,
    /** What the tab shows; names it in the texts of the reasons that need it. */
    subject: ErrorSubject? = null,
) {
    val t = UnraidTheme.colors
    val texts = errorTexts(reason, subject, message)
    var showDetail by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        UC.Alert(36.dp, t.danger)
        Spacer(Modifier.height(8.dp))
        Text(
            text = texts.title,
            color = t.text,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = texts.body,
            color = t.muted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            UnraidButton(
                onClick = { showDetail = !showDetail },
                label = if (showDetail) "Hide details" else "Show details",
                variant = BtnVariant.Text,
                tone = Tone.Accent,
            )
            if (showDetail) {
                Text(
                    text = detail,
                    color = t.muted,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            UnraidButton(
                onClick = onRetry,
                label = "Try again",
                variant = BtnVariant.Tonal,
                tone = Tone.Accent,
                leadingIcon = { UC.Refresh(14.dp, t.accent) },
            )
        }
    }
}

@Composable
fun NoServerState(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val t = UnraidTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        UC.Cloud(36.dp, t.muted)
        Spacer(Modifier.height(8.dp))
        Text("No server configured", color = t.text, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Add an Unraid server to get started.",
            color = t.muted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        UnraidButton(
            onClick = onAdd,
            label = "Add server",
            variant = BtnVariant.Filled,
            leadingIcon = { UC.Plus(14.dp, androidx.compose.ui.graphics.Color(0xFF06120E)) },
        )
        // The demo (ADR-0050): a second, quieter choice for whoever has no server to add.
        LocalDemoEntry.current?.let { tryDemo ->
            Spacer(Modifier.height(8.dp))
            UnraidButton(onClick = tryDemo, label = "Try the demo", variant = BtnVariant.Text)
            Text(
                text = "Look around with sample data. No server needed.",
                color = t.muted,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun OfflineEmpty(label: String, modifier: Modifier = Modifier) {
    val t = UnraidTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(60.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        UC.Cloud(36.dp, t.muted)
        Spacer(Modifier.height(8.dp))
        Text("Server offline", color = t.text, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "$label are unavailable while the server can't be reached.",
            color = t.muted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
