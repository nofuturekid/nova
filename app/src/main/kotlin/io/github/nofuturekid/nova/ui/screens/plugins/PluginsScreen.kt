package io.github.nofuturekid.nova.ui.screens.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.nofuturekid.nova.data.model.Plugin
import io.github.nofuturekid.nova.data.model.PluginInstallOperation
import io.github.nofuturekid.nova.data.model.PluginInstallStatus
import io.github.nofuturekid.nova.data.repository.DomainState
import io.github.nofuturekid.nova.ui.components.Pill
import io.github.nofuturekid.nova.ui.components.SectionLabel
import io.github.nofuturekid.nova.ui.components.Tone
import io.github.nofuturekid.nova.ui.components.UC
import io.github.nofuturekid.nova.ui.components.UnraidCard
import io.github.nofuturekid.nova.ui.components.UnraidIconButton
import io.github.nofuturekid.nova.ui.theme.UnraidTheme

/** Read-only inventory of installed plugins + install-ops history. */
@Composable
fun PluginsScreen(
    onBack: () -> Unit,
    vm: PluginsViewModel = hiltViewModel(),
) {
    val t = UnraidTheme.colors
    val d = UnraidTheme.tokens
    val installedState by vm.installedUnraidPlugins.collectAsState()
    val pluginsState by vm.plugins.collectAsState()
    val opsState by vm.operations.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(t.bg)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UnraidIconButton(icon = { UC.ChevL(20.dp, t.text) }, onClick = onBack, contentDescription = "Back")
            Text("Server plugins", color = t.text, style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = d.screenPad, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(d.gap),
        ) {
            SectionLabel("Installed plugins")
            UnraidCard(padding = UnraidTheme.tokens.pad) {
                InstalledUnraidPluginsSection(installedState)
            }

            SectionLabel("Unraid API modules")
            UnraidCard(padding = UnraidTheme.tokens.pad) {
                PluginsSection(pluginsState)
            }

            SectionLabel("Recent operations")
            UnraidCard(padding = UnraidTheme.tokens.pad) {
                OperationsSection(opsState)
            }
        }
    }
}

@Composable
private fun InstalledUnraidPluginsSection(state: DomainState<List<String>>) {
    val t = UnraidTheme.colors
    when (state) {
        is DomainState.Loading, is DomainState.NoServer ->
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        is DomainState.Error ->
            Text(state.message, color = t.danger, style = MaterialTheme.typography.bodyMedium)
        is DomainState.Content<List<String>> -> {
            val names = state.value
                .map { it.removeSuffix(".plg") }
                .sortedBy { it.lowercase() }
            if (names.isEmpty()) {
                Text("No plugins installed", color = t.muted, style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    names.forEach { name ->
                        Text(name, color = t.text, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginsSection(state: DomainState<List<Plugin>>) {
    val t = UnraidTheme.colors
    when (state) {
        is DomainState.Loading, is DomainState.NoServer ->
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        is DomainState.Error ->
            Text(state.message, color = t.danger, style = MaterialTheme.typography.bodyMedium)
        is DomainState.Content<List<Plugin>> -> {
            val plugins = state.value.sortedBy { it.name.lowercase() }
            if (plugins.isEmpty()) {
                Text("No API modules", color = t.muted, style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    plugins.forEach { PluginRow(it) }
                }
            }
        }
    }
}

@Composable
private fun PluginRow(plugin: Plugin) {
    val t = UnraidTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(plugin.name, color = t.text, style = MaterialTheme.typography.labelLarge)
            Text(
                text = "v${plugin.version}",
                color = t.muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (plugin.hasApiModule == true) Pill("API", tone = Tone.Accent)
        if (plugin.hasCliModule == true) Pill("CLI", tone = Tone.Neutral)
    }
}

@Composable
private fun OperationsSection(state: DomainState<List<PluginInstallOperation>>) {
    val t = UnraidTheme.colors
    when (state) {
        is DomainState.Loading, is DomainState.NoServer ->
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        is DomainState.Error ->
            Text(state.message, color = t.danger, style = MaterialTheme.typography.bodyMedium)
        is DomainState.Content<List<PluginInstallOperation>> -> {
            val all = state.value.sortedByDescending { it.createdAt }
            val ops = all.take(20)
            if (ops.isEmpty()) {
                Text("No operations recorded", color = t.muted, style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ops.forEach { OperationRow(it) }
                    if (all.size > 20) {
                        Text(
                            text = "Showing 20 most recent",
                            color = t.muted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationRow(op: PluginInstallOperation) {
    val t = UnraidTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val displayTitle = op.name ?: lastUrlSegment(op.url)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = displayTitle,
                color = t.text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Pill(op.status.label(), tone = op.status.tone())
        }
        Text(
            text = op.createdAt,
            color = t.muted,
            style = MaterialTheme.typography.labelSmall,
        )
        if (expanded) {
            val lines = op.output
            val preview = lines.take(20)
            Text(
                text = preview.joinToString("\n"),
                color = t.muted,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            if (lines.size > 20) {
                Text(
                    text = "… ${lines.size - 20} more line(s)",
                    color = t.muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun lastUrlSegment(url: String): String =
    url.substringAfterLast('/').ifBlank { url }

private fun PluginInstallStatus.label(): String = when (this) {
    PluginInstallStatus.Failed    -> "FAILED"
    PluginInstallStatus.Queued    -> "QUEUED"
    PluginInstallStatus.Running   -> "RUNNING"
    PluginInstallStatus.Succeeded -> "SUCCEEDED"
}

private fun PluginInstallStatus.tone(): Tone = when (this) {
    PluginInstallStatus.Failed    -> Tone.Danger
    PluginInstallStatus.Queued    -> Tone.Neutral
    PluginInstallStatus.Running   -> Tone.Info
    PluginInstallStatus.Succeeded -> Tone.Accent
}
