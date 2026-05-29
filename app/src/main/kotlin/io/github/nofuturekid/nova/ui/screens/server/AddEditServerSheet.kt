package io.github.nofuturekid.nova.ui.screens.server

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.github.nofuturekid.nova.data.model.EndpointUrl
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.repository.ServerRepository
import io.github.nofuturekid.nova.data.repository.UnraidRepository
import io.github.nofuturekid.nova.ui.components.BtnVariant
import io.github.nofuturekid.nova.ui.components.Tone
import io.github.nofuturekid.nova.ui.components.UC
import io.github.nofuturekid.nova.ui.components.UnraidButton
import io.github.nofuturekid.nova.ui.components.UnraidField
import io.github.nofuturekid.nova.ui.components.UnraidIconButton
import io.github.nofuturekid.nova.ui.theme.UnraidAlpha
import io.github.nofuturekid.nova.ui.theme.UnraidTheme
import javax.inject.Inject

enum class TestState { Idle, Testing, Ok, Fail }

data class AddEditUiState(
    val name: String = "",
    val localHost: String = "",
    val localSsl: Boolean = false,
    val remoteHost: String = "",
    val remoteSsl: Boolean = true,
    val trustSelfSignedLocal: Boolean = false,
    val hasStoredPin: Boolean = false,
    val apiKey: String = "",
    val showKey: Boolean = false,
    val testState: TestState = TestState.Idle,
    val testMessage: String? = null,
)

@HiltViewModel
class AddEditServerViewModel @Inject constructor(
    private val servers: ServerRepository,
    private val unraid: UnraidRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AddEditUiState())
    val state: StateFlow<AddEditUiState> = _state.asStateFlow()

    private var existingId: String = ""

    fun load(server: Server?) {
        existingId = server?.id ?: ""
        viewModelScope.launch {
            val local = EndpointUrl.parse(server?.localUrl.orEmpty(), defaultSsl = false)
            val remote = EndpointUrl.parse(server?.remoteUrl.orEmpty(), defaultSsl = true)
            _state.value = AddEditUiState(
                name = server?.name.orEmpty(),
                localHost = local.host,
                localSsl = local.ssl,
                remoteHost = remote.host,
                remoteSsl = remote.ssl,
                trustSelfSignedLocal = server?.trustSelfSignedLocal ?: false,
                hasStoredPin = server?.id?.let { servers.pinFor(it) } != null,
                apiKey = server?.id?.let { servers.apiKeyFor(it) }.orEmpty(),
            )
        }
    }

    fun setName(v: String)      { _state.value = _state.value.copy(name = v, testState = TestState.Idle) }
    fun setLocalHost(v: String) { _state.value = _state.value.copy(localHost = v, testState = TestState.Idle) }
    fun setLocalSsl(v: Boolean) { _state.value = _state.value.copy(localSsl = v, testState = TestState.Idle) }
    fun setRemoteHost(v: String){ _state.value = _state.value.copy(remoteHost = v, testState = TestState.Idle) }
    fun setRemoteSsl(v: Boolean){ _state.value = _state.value.copy(remoteSsl = v, testState = TestState.Idle) }
    fun setTrustSelfSigned(v: Boolean) {
        // Turning trust off forgets any captured cert on save (handled in upsert);
        // here just flip the intent.
        _state.value = _state.value.copy(trustSelfSignedLocal = v, testState = TestState.Idle)
    }
    fun setApiKey(v: String)    { _state.value = _state.value.copy(apiKey = v, testState = TestState.Idle) }
    fun toggleKeyVisible()      { _state.value = _state.value.copy(showKey = !_state.value.showKey) }

    /** Forget the captured certificate for this server (deliberate rotation).
     *  The next local connect re-runs first-use and re-prompts. */
    fun resetCertificate() {
        val id = existingId
        if (id.isBlank()) return
        viewModelScope.launch {
            servers.clearLocalCertPin(id)
            _state.value = _state.value.copy(hasStoredPin = false)
        }
    }

    fun test() {
        val s = _state.value
        val localUrl = EndpointUrl.compose(s.localHost, s.localSsl)
        val remoteUrl = EndpointUrl.compose(s.remoteHost, s.remoteSsl)
        if (localUrl.isBlank() && remoteUrl.isBlank()) return
        if (s.apiKey.isBlank() || s.apiKey.all { it == '•' }) {
            _state.value = s.copy(testState = TestState.Fail, testMessage = "Enter the API key first")
            return
        }
        _state.value = s.copy(testState = TestState.Testing, testMessage = null)
        viewModelScope.launch {
            val url = localUrl.ifBlank { remoteUrl }
            val allowSelfSigned = s.trustSelfSignedLocal && url == localUrl
            val err = unraid.testConnection(url, s.apiKey, allowSelfSigned)
            _state.value = _state.value.copy(
                testState = if (err == null) TestState.Ok else TestState.Fail,
                testMessage = err,
            )
        }
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (s.name.isBlank()) return
        viewModelScope.launch {
            val localUrl = EndpointUrl.compose(s.localHost, s.localSsl)
            val remoteUrl = EndpointUrl.compose(s.remoteHost, s.remoteSsl)
            val hostname = s.localHost.substringBefore('/').ifBlank { s.remoteHost }
            servers.upsert(
                Server(
                    id = existingId,
                    name = s.name.trim(),
                    hostname = hostname,
                    localUrl = localUrl,
                    remoteUrl = remoteUrl,
                    trustSelfSignedLocal = s.trustSelfSignedLocal,
                ),
                apiKey = s.apiKey,
            )
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        if (existingId.isBlank()) { onDone(); return }
        viewModelScope.launch {
            servers.delete(existingId)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditServerSheet(
    server: Server?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    vm: AddEditServerViewModel = hiltViewModel(),
) {
    // Keyed on Unit, not server?.id: the shared (NavGraph-scoped) ViewModel
    // is reused across opens, and an Add sheet's key is always null, so
    // keying on server?.id skips reload on Add→Edit→Add and leaks the prior
    // session's state. The sheet leaves composition on dismiss, so a Unit key
    // re-runs exactly once per open with the freshly-passed server.
    LaunchedEffect(Unit) { vm.load(server) }
    val state by vm.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val t = UnraidTheme.colors

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = t.surface2,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // drag handle
            Box(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .background(t.muted.copy(alpha = UnraidAlpha.grabber), RoundedCornerShape(2.dp)),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (server != null) "Edit server" else "Add server",
                    color = t.text,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                UnraidIconButton(icon = { UC.X(20.dp, t.text) }, onClick = onDismiss, contentDescription = "Close")
            }
            Spacer(Modifier.height(12.dp))

            UnraidField(
                label = "Server name",
                value = state.name,
                onChange = vm::setName,
                placeholder = "Tower",
                leadingIcon = { UC.Server(18.dp, t.muted) },
            )

            // Local endpoint
            UnraidField(
                label = "Local host",
                value = state.localHost,
                onChange = vm::setLocalHost,
                placeholder = "192.168.11.2",
                leadingIcon = { UC.Wifi(18.dp, t.muted) },
                helper = "Used on home network · add :port if non-default",
                keyboardType = KeyboardType.Uri,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(
                    "Use SSL (HTTPS)",
                    color = t.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.localSsl, onCheckedChange = vm::setLocalSsl)
            }
            if (state.localSsl) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Trust self-signed certificate",
                            color = t.text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Local connection only · you confirm the certificate once",
                            color = t.muted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Switch(checked = state.trustSelfSignedLocal, onCheckedChange = vm::setTrustSelfSigned)
                }
                if (state.trustSelfSignedLocal && state.hasStoredPin) {
                    UnraidButton(
                        onClick = vm::resetCertificate,
                        label = "Reset certificate",
                        variant = BtnVariant.Text,
                        tone = Tone.Neutral,
                        leadingIcon = { UC.Refresh(14.dp, t.muted) },
                    )
                }
            }

            // Remote endpoint
            UnraidField(
                label = "Remote host (Unraid Connect)",
                value = state.remoteHost,
                onChange = vm::setRemoteHost,
                placeholder = "your-server.unraid.net",
                leadingIcon = { UC.Cloud(18.dp, t.muted) },
                helper = "Optional · used when away from home",
                keyboardType = KeyboardType.Uri,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(
                    "Use SSL (HTTPS)",
                    color = t.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = state.remoteSsl, onCheckedChange = vm::setRemoteSsl)
            }

            UnraidField(
                label = "API Key",
                value = state.apiKey,
                onChange = vm::setApiKey,
                leadingIcon = { UC.Lock(18.dp, t.muted) },
                obscured = !state.showKey,
                trailing = {
                    UnraidIconButton(
                        icon = { if (state.showKey) UC.EyeOff(18.dp, t.muted) else UC.Eye(18.dp, t.muted) },
                        onClick = vm::toggleKeyVisible,
                        size = 32.dp,
                        contentDescription = if (state.showKey) "Hide API key" else "Show API key",
                    )
                },
            )

            TestConnectionPanel(
                state = state.testState,
                onTest = vm::test,
                message = state.testMessage,
            )

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (server != null) {
                    UnraidButton(
                        onClick = { vm.delete(onDeleted) },
                        label = "Delete",
                        variant = BtnVariant.Tonal,
                        tone = Tone.Danger,
                        leadingIcon = { UC.Trash(14.dp, t.danger) },
                    )
                }
                Spacer(Modifier.weight(1f))
                UnraidButton(
                    onClick = onDismiss,
                    label = "Cancel",
                    variant = BtnVariant.Text,
                    tone = Tone.Neutral,
                )
                Spacer(Modifier.width(4.dp))
                val canSave = state.name.isNotBlank() &&
                    (state.localHost.isNotBlank() || state.remoteHost.isNotBlank()) &&
                    state.apiKey.isNotBlank()
                UnraidButton(
                    onClick = { vm.save(onSaved) },
                    label = "Save",
                    variant = BtnVariant.Filled,
                    enabled = canSave,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TestConnectionPanel(state: TestState, onTest: () -> Unit, message: String?) {
    val t = UnraidTheme.colors
    val bg = when (state) {
        TestState.Ok   -> t.accent.copy(alpha = UnraidAlpha.softFill)
        TestState.Fail -> t.danger.copy(alpha = UnraidAlpha.softFill)
        else           -> t.muted.copy(alpha = UnraidAlpha.softFill)
    }
    val border = when (state) {
        TestState.Ok   -> t.accent.copy(alpha = UnraidAlpha.testStateBorder)
        TestState.Fail -> t.danger.copy(alpha = UnraidAlpha.testStateBorder)
        else           -> t.border
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(14.dp))
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (state) {
                    TestState.Idle    -> "Test connection"
                    TestState.Testing -> "Connecting…"
                    TestState.Ok      -> "Connected"
                    TestState.Fail    -> "Failed to connect"
                },
                color = t.text,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = when (state) {
                    TestState.Idle    -> "Verify URL + API key before saving"
                    TestState.Testing -> "Talking to GraphQL endpoint…"
                    TestState.Ok      -> "Server responded · ready to save"
                    TestState.Fail    -> message ?: "Check URL and API key"
                },
                color = t.muted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        when (state) {
            TestState.Ok      -> UC.Check(20.dp, t.accent)
            TestState.Testing -> UC.Refresh(18.dp, t.accent)
            TestState.Fail, TestState.Idle -> UnraidButton(
                onClick = onTest,
                label = "Test",
                variant = BtnVariant.Text,
            )
        }
    }
}
