package net.unraidcontrol.app.ui.screens.server

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.unraidcontrol.app.data.model.Server
import net.unraidcontrol.app.data.repository.ServerRepository
import net.unraidcontrol.app.ui.components.Pill
import net.unraidcontrol.app.ui.components.Tone
import net.unraidcontrol.app.ui.components.UC
import net.unraidcontrol.app.ui.components.UnraidCard
import net.unraidcontrol.app.ui.components.UnraidIconButton
import net.unraidcontrol.app.ui.theme.JetBrainsMono
import net.unraidcontrol.app.ui.theme.UnraidAlpha
import net.unraidcontrol.app.ui.theme.UnraidTheme
import javax.inject.Inject

data class ServerListUi(
    val servers: List<Server>,
    val activeId: String?,
)

@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val repo: ServerRepository,
) : ViewModel() {

    val state: StateFlow<ServerListUi> = combine(repo.servers, repo.activeServer) { all, active ->
        ServerListUi(all, active?.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerListUi(emptyList(), null))

    fun pick(id: String) = viewModelScope.launch { repo.setActive(id) }
}

@Composable
fun ServerListScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Server) -> Unit,
    vm: ServerListViewModel = hiltViewModel(),
) {
    val t = UnraidTheme.colors
    val ui by vm.state.collectAsState()
    val scope = rememberCoroutineScope()

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
            Text(
                text = "Servers",
                color = t.text,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            UnraidIconButton(icon = { UC.Plus(20.dp, t.accent) }, onClick = onAdd, tone = Tone.Accent, contentDescription = "Add server")
        }

        val d = UnraidTheme.tokens
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = d.screenPad, end = d.screenPad, top = 8.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(d.gap),
        ) {
            items(ui.servers, key = { it.id }) { s ->
                val active = s.id == ui.activeId
                UnraidCard(
                    onClick = {
                        scope.launch { vm.pick(s.id) }
                        onBack()
                    },
                    background = if (active) t.accent.copy(alpha = UnraidAlpha.selectedRowFill) else t.surface,
                    borderColor = if (active) t.accent else t.border,
                    padding = 14.dp,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) t.accentDim else t.muted.copy(alpha = UnraidAlpha.tonalFill)),
                            contentAlignment = Alignment.Center,
                        ) {
                            UC.Server(20.dp, if (active) t.accent else t.muted)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(s.name, color = t.text, style = MaterialTheme.typography.titleSmall)
                                if (active) Pill("active", tone = Tone.Accent, dot = true)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = s.localUrl,
                                color = t.muted,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                            )
                        }
                        UnraidIconButton(
                            icon = { UC.Edit(16.dp, t.muted) },
                            onClick = { onEdit(s) },
                            size = 32.dp,
                            contentDescription = "Edit ${s.name}",
                        )
                    }
                }
            }
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(UnraidTheme.tokens.rad))
                        .border(1.dp, t.border, RoundedCornerShape(UnraidTheme.tokens.rad))
                        .clickable(onClick = onAdd)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        UC.Plus(18.dp, t.muted)
                        Text("Add another server", color = t.muted, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
