package io.github.nofuturekid.nova.ui.screens.network

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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.nofuturekid.nova.data.model.NetworkInterface
import io.github.nofuturekid.nova.data.repository.DomainState
import io.github.nofuturekid.nova.ui.components.Pill
import io.github.nofuturekid.nova.ui.components.SectionLabel
import io.github.nofuturekid.nova.ui.components.Tone
import io.github.nofuturekid.nova.ui.components.UC
import io.github.nofuturekid.nova.ui.components.UnraidCard
import io.github.nofuturekid.nova.ui.components.UnraidIconButton
import io.github.nofuturekid.nova.ui.theme.UnraidTheme

/** Read-only inventory of server network interfaces. */
@Composable
fun NetworkScreen(
    onBack: () -> Unit,
    vm: NetworkViewModel = hiltViewModel(),
) {
    val t = UnraidTheme.colors
    val d = UnraidTheme.tokens
    val state by vm.interfaces.collectAsState()

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
            Text("Network interfaces", color = t.text, style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = d.screenPad, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(d.gap),
        ) {
            SectionLabel("Interfaces")
            UnraidCard(padding = UnraidTheme.tokens.pad) {
                InterfacesSection(state)
            }
        }
    }
}

@Composable
private fun InterfacesSection(state: DomainState<List<NetworkInterface>>) {
    val t = UnraidTheme.colors
    when (state) {
        is DomainState.Loading, is DomainState.NoServer ->
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        is DomainState.Error ->
            Text(state.message, color = t.danger, style = MaterialTheme.typography.bodyMedium)
        is DomainState.Content<List<NetworkInterface>> -> {
            // Sort primary first, then alphabetical by name.
            val ifaces = state.value.sortedWith(
                compareByDescending<NetworkInterface> { it.isPrimary }
                    .thenBy { it.name.lowercase() },
            )
            if (ifaces.isEmpty()) {
                Text("No interfaces reported", color = t.muted, style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ifaces.forEach { InterfaceRow(it) }
                }
            }
        }
    }
}

@Composable
private fun InterfaceRow(iface: NetworkInterface) {
    val t = UnraidTheme.colors
    var expanded by remember { mutableStateOf(false) }

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
                text = iface.name,
                color = t.text,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            if (iface.isPrimary) Pill("Primary", tone = Tone.Accent)
            iface.status?.takeIf { it.isNotBlank() }?.let { s ->
                Pill(s.uppercase(), tone = s.statusTone())
            }
        }
        iface.ipAddress?.takeIf { it.isNotBlank() }?.let { ip ->
            Text(ip, color = t.muted, style = MaterialTheme.typography.labelSmall)
        }

        if (expanded) {
            val hasIpv4 = iface.ipAddress != null || iface.netmask != null ||
                iface.gateway != null || iface.useDhcp != null
            val hasIpv6 = iface.ipv6Address != null || iface.ipv6Netmask != null ||
                iface.ipv6Gateway != null || iface.useDhcp6 != null
            val hasHw = iface.macAddress != null || iface.vendor != null ||
                iface.model != null || iface.speed != null || iface.virtual != null

            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                iface.description?.let { DetailRow("Description", it) }
                iface.protocol?.let { DetailRow("Protocol", it) }

                if (hasIpv4) {
                    SubHeading("IPv4")
                    iface.ipAddress?.let { DetailRow("Address", it) }
                    iface.netmask?.let { DetailRow("Netmask", it) }
                    iface.gateway?.let { DetailRow("Gateway", it) }
                    iface.useDhcp?.let { DetailRow("DHCP", if (it) "Yes" else "No") }
                }
                if (hasIpv6) {
                    SubHeading("IPv6")
                    iface.ipv6Address?.let { DetailRow("Address", it) }
                    iface.ipv6Netmask?.let { DetailRow("Netmask", it) }
                    iface.ipv6Gateway?.let { DetailRow("Gateway", it) }
                    iface.useDhcp6?.let { DetailRow("DHCP", if (it) "Yes" else "No") }
                }
                if (hasHw) {
                    SubHeading("Hardware")
                    iface.macAddress?.let { DetailRow("MAC", it) }
                    iface.vendor?.let { DetailRow("Vendor", it) }
                    iface.model?.let { DetailRow("Model", it) }
                    iface.speed?.let { DetailRow("Link speed", it) }
                    iface.virtual?.let { DetailRow("Virtual", if (it) "Yes" else "No") }
                }
            }
        }
    }
}

@Composable
private fun SubHeading(text: String) {
    val t = UnraidTheme.colors
    Text(
        text = text,
        color = t.accent,
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    val t = UnraidTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            color = t.muted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = t.text,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Map an Unraid-reported status string to a pill tone. Values seen on
 *  the live server include `up` / `down` / `connected` / `disconnected`
 *  (case-insensitive). Anything else falls through to neutral. */
private fun String.statusTone(): Tone = when (lowercase()) {
    "up", "connected"       -> Tone.Accent
    "down", "disconnected"  -> Tone.Danger
    else                    -> Tone.Neutral
}
