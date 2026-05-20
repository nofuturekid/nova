package io.github.nofuturekid.nova.ui.screens.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import io.github.nofuturekid.nova.data.model.Plugin
import io.github.nofuturekid.nova.data.model.PluginInstallOperation
import io.github.nofuturekid.nova.data.repository.DomainState
import io.github.nofuturekid.nova.data.repository.UnraidRepository
import javax.inject.Inject

/**
 * Screen-scoped ViewModel for the read-only Server-plugins screen.
 *
 * Polls only while the screen is open: the upstream
 * [UnraidRepository.pluginsStream] / [UnraidRepository.pluginOperationsStream]
 * Flows are cold and gated by `WhileSubscribed(5_000)`, so when the user
 * navigates away the poll loop suspends automatically. Active-server
 * resolution + key handling is done inside the repository stream — this VM
 * just exposes the resulting [DomainState] as StateFlows for the composable.
 */
@HiltViewModel
class PluginsViewModel @Inject constructor(
    unraid: UnraidRepository,
) : ViewModel() {
    val plugins: StateFlow<DomainState<List<Plugin>>> =
        unraid.pluginsStream()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DomainState.Loading)

    val operations: StateFlow<DomainState<List<PluginInstallOperation>>> =
        unraid.pluginOperationsStream()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DomainState.Loading)
}
