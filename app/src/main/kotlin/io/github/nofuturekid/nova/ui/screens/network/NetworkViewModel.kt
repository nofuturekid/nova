package io.github.nofuturekid.nova.ui.screens.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import io.github.nofuturekid.nova.data.model.NetworkInterface
import io.github.nofuturekid.nova.data.repository.DomainState
import io.github.nofuturekid.nova.data.repository.UnraidRepository
import javax.inject.Inject

/**
 * Screen-scoped ViewModel for the read-only Network-interfaces screen.
 *
 * Polls only while the screen is open: the upstream
 * [UnraidRepository.networkInterfacesStream] Flow is cold and gated by
 * `WhileSubscribed(5_000)`, so when the user navigates away the poll
 * loop suspends automatically (ADR-0017 polling pattern, same as
 * [io.github.nofuturekid.nova.ui.screens.plugins.PluginsViewModel]).
 */
@HiltViewModel
class NetworkViewModel @Inject constructor(
    unraid: UnraidRepository,
) : ViewModel() {
    val interfaces: StateFlow<DomainState<List<NetworkInterface>>> =
        unraid.networkInterfacesStream()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DomainState.Loading)
}
