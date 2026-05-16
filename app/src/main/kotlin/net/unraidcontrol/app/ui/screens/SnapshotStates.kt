package net.unraidcontrol.app.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.unraidcontrol.app.ui.components.BtnVariant
import net.unraidcontrol.app.ui.components.Tone
import net.unraidcontrol.app.ui.components.UC
import net.unraidcontrol.app.ui.components.UnraidButton
import net.unraidcontrol.app.ui.theme.UnraidTheme

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

@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val t = UnraidTheme.colors
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
            text = "Can't reach the server",
            color = t.text,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            color = t.muted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
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
