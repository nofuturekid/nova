package io.github.nofuturekid.nova.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.nofuturekid.nova.data.alerts.Alert
import io.github.nofuturekid.nova.data.alerts.AlertEvaluation
import io.github.nofuturekid.nova.data.alerts.AlertLevel
import io.github.nofuturekid.nova.data.alerts.AlertNotifier
import io.github.nofuturekid.nova.data.alerts.AlertScheduler
import io.github.nofuturekid.nova.data.alerts.AlertSettings
import io.github.nofuturekid.nova.data.alerts.AlertSource
import io.github.nofuturekid.nova.data.alerts.CheckOutcome
import io.github.nofuturekid.nova.data.alerts.CheckRecord
import io.github.nofuturekid.nova.data.alerts.ProbeResult
import io.github.nofuturekid.nova.data.alerts.ServerAlertState
import io.github.nofuturekid.nova.data.alerts.ServerProbe
import io.github.nofuturekid.nova.data.alerts.ServerRef
import io.github.nofuturekid.nova.data.alerts.hasCleartextOnlyAddresses
import io.github.nofuturekid.nova.data.alerts.isBackgroundCheckable
import io.github.nofuturekid.nova.data.alerts.toOutcome
import io.github.nofuturekid.nova.data.model.AttentionSeverity
import io.github.nofuturekid.nova.data.model.AttentionTarget
import io.github.nofuturekid.nova.data.model.NotifImportance
import io.github.nofuturekid.nova.data.model.Notifications
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.model.UnraidNotification
import io.github.nofuturekid.nova.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.OffsetDateTime
import javax.inject.Inject

/** One saved server in the "last check" list: its name, what to say about it and when it was checked. */
data class ServerCheckLine(val name: String, val text: String, val atEpochMs: Long?)

data class AlertsUi(
    val settings: AlertSettings = AlertSettings(),
    /** Android blocks NOVA's notifications and the user needs to know (switch on, or a request was denied). */
    val blocked: Boolean = false,
    val servers: List<ServerCheckLine> = emptyList(),
    val hasDemoServer: Boolean = false,
    /** At least one saved server can be checked, so the test action means something. */
    val canTest: Boolean = false,
    val testing: Boolean = false,
    val testMessage: String? = null,
)

/**
 * The state and actions behind Settings, Notifications (ADR-0048). The screen never touches
 * WorkManager itself: switching and changing choices go through [AlertScheduler], which does
 * nothing, and does not even ask for WorkManager, while alerts are off. Everything this class
 * reads comes from the settings store, so opening the screen with alerts off starts nothing.
 */
@HiltViewModel
class AlertsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val scheduler: AlertScheduler,
    private val notifier: AlertNotifier,
    private val probe: ServerProbe,
) : ViewModel() {
    private val allowed = MutableStateFlow(notifier.canPost())

    /** The user said no to the permission request, or the switch could not be turned on. */
    private val denied = MutableStateFlow(false)
    private val testing = MutableStateFlow(false)
    private val testMessage = MutableStateFlow<String?>(null)

    private val servers = combine(repo.serverSnapshot, repo.alertStates) { snapshot, states ->
        snapshot.servers to states
    }

    private val notificationState = combine(allowed, denied, testing, testMessage) { a, d, t, m ->
        NotificationState(a, d, t, m)
    }

    val state: StateFlow<AlertsUi> = combine(repo.alertSettings, servers, notificationState) { settings, (list, states), n ->
        AlertsUi(
            settings = settings,
            blocked = !n.allowed && (settings.enabled || n.denied),
            servers = list.map { serverCheckLine(it, states[it.id]?.lastCheck) },
            hasDemoServer = list.any { it.isDemo },
            canTest = list.any { it.isBackgroundCheckable() },
            testing = n.testing,
            testMessage = n.testMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertsUi(blocked = false))

    /** Called when the screen comes back to the front: the user may have changed Android's settings meanwhile. */
    fun refreshNotificationsAllowed() {
        val now = notifier.canPost()
        allowed.value = now
        if (now) denied.value = false
    }

    /** The answer to the system's permission request, or true when none was needed. */
    fun onPermissionResult(granted: Boolean) {
        if (granted) turnOn() else {
            allowed.value = notifier.canPost()
            denied.value = true
        }
    }

    private fun turnOn() {
        viewModelScope.launch {
            val chosen = repo.alertSettings.first()
            val ok = scheduler.enable(chosen.intervalMinutes, chosen.level, chosen.unmeteredOnly)
            allowed.value = notifier.canPost()
            denied.value = !ok
            testMessage.value = null
        }
    }

    fun turnOff() {
        viewModelScope.launch {
            scheduler.disable()
            denied.value = false
            testMessage.value = null
        }
    }

    fun setInterval(minutes: Int) = change { it.copy(intervalMinutes = minutes) }
    fun setLevel(level: AlertLevel) = change { it.copy(level = level) }
    fun setUnmeteredOnly(value: Boolean) = change { it.copy(unmeteredOnly = value) }

    private fun change(edit: (AlertSettings) -> AlertSettings) {
        viewModelScope.launch {
            val next = edit(repo.alertSettings.first())
            scheduler.update(next.intervalMinutes, next.level, next.unmeteredOnly)
        }
    }

    /**
     * "Send test notification": checks the active server once, right now, through the same probe
     * the background job uses (so the https rule and the certificate rules apply), and posts its
     * newest unread alert or warning as a test. It reads and posts; it never writes the remembered
     * state, so a test cannot make a real alert disappear or appear twice.
     */
    fun sendTest() {
        if (testing.value) return
        viewModelScope.launch {
            testing.value = true
            testMessage.value = null
            testMessage.value = try {
                runTest()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "Test failed."
            } finally {
                testing.value = false
            }
        }
    }

    private suspend fun runTest(): String {
        val snapshot = repo.serverSnapshot.first()
        val server = snapshot.servers.firstOrNull { it.id == snapshot.activeId && it.isBackgroundCheckable() }
            ?: snapshot.servers.firstOrNull { it.isBackgroundCheckable() }
            ?: return "Test: there is no server to check. The demo server is never checked."
        if (!notifier.canPost()) {
            allowed.value = false
            return "Test: Android blocks notifications for NOVA."
        }
        val result = withTimeoutOrNull(TEST_TIMEOUT_MS) { probe.probe(server, snapshot.pins[server.id]) }
            ?: ProbeResult.Unreachable
        if (result !is ProbeResult.Ok) {
            return "Test: ${server.name}: ${outcomeText(result.toOutcome()).trimEnd('.')}."
        }
        val newest = result.notifications?.newestAlertOrWarning()
        if (newest == null) {
            return if (result.notifications == null) {
                "Test: NOVA reached ${server.name}, but could not read its alerts."
            } else {
                "Test: NOVA reached ${server.name}. No unread alerts or warnings."
            }
        }
        val subject = newest.subject.ifBlank { newest.title }.ifBlank { "Server notification" }
        val ref = ServerRef(server.id, server.name)
        val posted = notifier.post(ref, AlertEvaluation(ref, listOf(newest.toTestAlert(subject)), 0, ServerAlertState()))
        return if (posted) "Test: $subject" else "Test: Android blocked the notification."
    }

    private data class NotificationState(
        val allowed: Boolean,
        val denied: Boolean,
        val testing: Boolean,
        val testMessage: String?,
    )

    private companion object {
        const val TEST_TIMEOUT_MS = 45_000L
    }
}

/**
 * What the last-check line says about one server. The current address configuration wins over an
 * old record: a server that has only http addresses is never checked, whatever an earlier check
 * said, and a "not encrypted" record of a server that has an https address since is out of date.
 */
fun serverCheckLine(server: Server, record: CheckRecord?): ServerCheckLine = when {
    server.isDemo -> ServerCheckLine(server.name, "demo server, never checked", null)
    server.hasCleartextOnlyAddresses() ->
        ServerCheckLine(server.name, outcomeText(CheckOutcome.NotEncrypted), null)
    record == null || record.outcome == CheckOutcome.NotEncrypted ->
        ServerCheckLine(server.name, "not run yet", null)
    else -> ServerCheckLine(server.name, outcomeText(record.outcome), record.atEpochMs)
}

/** The words for an outcome, shared by the last-check line and the test action. */
fun outcomeText(outcome: CheckOutcome): String = when (outcome) {
    CheckOutcome.Ok -> "OK"
    CheckOutcome.Unreachable -> "could not be reached"
    CheckOutcome.NotEncrypted ->
        "not checked in the background because the connection is not encrypted. An https address is needed."
    CheckOutcome.CertificateProblem -> "certificate problem (changed or not trusted), open NOVA to review it"
    CheckOutcome.KeyMissing -> "API key missing"
    CheckOutcome.KeyUnreadable -> "API key cannot be read, enter it again"
    CheckOutcome.Rejected -> "API key refused or request rejected"
}

private fun Notifications.newestAlertOrWarning(): UnraidNotification? =
    items.filter { it.importance == NotifImportance.Alert || it.importance == NotifImportance.Warning }
        .maxByOrNull { epochMillis(it.timestamp) ?: Long.MIN_VALUE }

private fun epochMillis(iso: String?): Long? =
    iso?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

private fun UnraidNotification.toTestAlert(subject: String) = Alert(
    key = "nova-test",
    source = AlertSource.Notification,
    severity = AttentionSeverity.Critical,
    title = "Test: $subject",
    text = description,
    timestamp = timestamp,
    target = AttentionTarget.Notifications,
)
