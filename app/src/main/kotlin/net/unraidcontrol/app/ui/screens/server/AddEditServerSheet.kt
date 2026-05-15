package net.unraidcontrol.app.ui.screens.server

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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.unraidcontrol.app.data.model.Server
import net.unraidcontrol.app.data.repository.ServerRepository
import net.unraidcontrol.app.data.repository.UnraidRepository
import net.unraidcontrol.app.ui.components.BtnVariant
import net.unraidcontrol.app.ui.components.Tone
import net.unraidcontrol.app.ui.components.UC
import net.unraidcontrol.app.ui.components.UnraidButton
import net.unraidcontrol.app.ui.components.UnraidField
import net.unraidcontrol.app.ui.components.UnraidIconButton
import net.unraidcontrol.app.ui.theme.UnraidTheme
import javax.inject.Inject

enum class TestState { Idle, Testing, Ok, Fail }

data class AddEditUiState(
    val name: String = "",
    val localUrl: String = "",
    val remoteUrl: String = "",
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
        // apiKeyFor is suspend (DataStore + Tink, ADR-0024); resolve the
        // stored key off-thread and publish the state when it's ready.
        viewModelScope.launch {
            _state.value = AddEditUiState(
                name = server?.name.orEmpty(),
                localUrl = server?.localUrl.orEmpty(),
                remoteUrl = server?.remoteUrl.orEmpty(),
                // Pre-populate the actual stored key when editing so the user
                // can see, copy, or modify it. Falls back to empty for a new
                // server. Earlier versions used a '••••' placeholder which got
                // typed-onto and then saved as the literal new key.
                apiKey = server?.id?.let { servers.apiKeyFor(it) }.orEmpty(),
            )
        }
    }

    fun setName(v: String)    { _state.value = _state.value.copy(name = v, testState = TestState.Idle) }
    fun setLocal(v: String)   { _state.value = _state.value.copy(localUrl = v, testState = TestState.Idle) }
    fun setRemote(v: String)  { _state.value = _state.value.copy(remoteUrl = v, testState = TestState.Idle) }
    fun setApiKey(v: String)  { _state.value = _state.value.copy(apiKey = v, testState = TestState.Idle) }
    fun toggleKeyVisible()    { _state.value = _state.value.copy(showKey = !_state.value.showKey) }

    fun test() {
        val s = _state.value
        if (s.localUrl.isBlank() && s.remoteUrl.isBlank()) return
        if (s.apiKey.isBlank() || s.apiKey.all { it == '•' }) {
            _state.value = s.copy(testState = TestState.Fail, testMessage = "Enter the API key first")
            return
        }
        _state.value = s.copy(testState = TestState.Testing, testMessage = null)
        viewModelScope.launch {
            val url = s.localUrl.ifBlank { s.remoteUrl }
            val err = unraid.testConnection(url, s.apiKey)
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
            val hostname = s.localUrl
                .removePrefix("http://").removePrefix("https://")
                .substringBefore('/')
                .ifBlank { s.remoteUrl }
            servers.upsert(
                Server(
                    id = existingId,
                    name = s.name.trim(),
                    hostname = hostname,
                    localUrl = s.localUrl.trim(),
                    remoteUrl = s.remoteUrl.trim(),
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
    LaunchedEffect(server?.id) { vm.load(server) }
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
                        .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(2.dp)),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (server != null) "Edit server" else "Add server",
                    color = t.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                UnraidIconButton(icon = { UC.X(20.dp, t.text) }, onClick = onDismiss)
            }
            Spacer(Modifier.height(12.dp))

            UnraidField(
                label = "Server name",
                value = state.name,
                onChange = vm::setName,
                placeholder = "Tower",
                leadingIcon = { UC.Server(18.dp, t.muted) },
            )
            UnraidField(
                label = "Local URL",
                value = state.localUrl,
                onChange = vm::setLocal,
                placeholder = "http://192.168.1.10",
                leadingIcon = { UC.Wifi(18.dp, t.muted) },
                helper = "Used on home network",
                keyboardType = KeyboardType.Uri,
            )
            UnraidField(
                label = "Remote URL (Unraid Connect)",
                value = state.remoteUrl,
                onChange = vm::setRemote,
                placeholder = "https://your-server.unraid.net",
                leadingIcon = { UC.Cloud(18.dp, t.muted) },
                helper = "Optional · used when away from home",
                keyboardType = KeyboardType.Uri,
            )
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
                    (state.localUrl.isNotBlank() || state.remoteUrl.isNotBlank()) &&
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
        TestState.Ok   -> t.accent.copy(alpha = 0.08f)
        TestState.Fail -> t.danger.copy(alpha = 0.08f)
        else           -> Color.White.copy(alpha = 0.04f)
    }
    val border = when (state) {
        TestState.Ok   -> t.accent.copy(alpha = 0.30f)
        TestState.Fail -> t.danger.copy(alpha = 0.30f)
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
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
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
                fontSize = 11.sp,
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
