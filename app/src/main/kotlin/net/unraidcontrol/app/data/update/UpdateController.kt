package net.unraidcontrol.app.data.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.unraidcontrol.app.data.model.InstallState
import net.unraidcontrol.app.data.model.UpdateInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-global owner of the in-app updater's install pipeline (ADR-0012
 * revisit, actioned in ADR-0030 D2).
 *
 * Previously the download + PackageInstaller + permission flow + the
 * [InstallStatusReceiver.events] collector were duplicated in both
 * `MainViewModel` and `SettingsViewModel`, each with its own `_installState`
 * and an `ownsInstall` guard so a ViewModel only reflected installs it
 * itself started.
 *
 * There is exactly one install in flight at any time, so a single owner
 * matches reality. This singleton holds the one [installState]; both
 * ViewModels observe and forward it. The per-ViewModel `ownsInstall` guard
 * is structurally gone: with a single shared state there is no "other"
 * ViewModel to isolate from. **Intended behaviour change:** install
 * progress is now reflected on both the Overview and Settings screens
 * regardless of which screen started the install (documented in ADR-0012
 * and the ADR-0030 D2 acceptance line).
 *
 * The broadcast collector runs on a process-lifetime [scope] — a
 * [SupervisorJob] on [Dispatchers.Default]. The singleton outlives any
 * screen, so this is deliberately not tied to a ViewModel's lifecycle.
 * Hilt uses the [@Inject][Inject] constructor (installer only); the
 * second constructor lets tests drive the controller with a `TestScope`.
 */
@Singleton
class UpdateController(
    private val installer: ApkInstaller,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(installer: ApkInstaller) : this(
        installer,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    init {
        // Mirror PackageInstaller broadcasts into the single install-state
        // flow so any observing screen reacts to system confirm / success
        // / failure. No `ownsInstall` guard: there is one shared state for
        // the one in-flight install (ADR-0012 revisit / ADR-0030 D2).
        scope.launch {
            InstallStatusReceiver.events.collect { event ->
                _installState.value = when (event) {
                    is InstallEvent.UserConfirmShown -> InstallState.Installing
                    is InstallEvent.Success          -> InstallState.Idle
                    is InstallEvent.Failed           -> InstallState.Failed(event.message)
                }
            }
        }
    }

    fun installUpdate(info: UpdateInfo) = scope.launch {
        _installState.value = InstallState.Downloading(0f)
        try {
            val apk = installer.download(info.downloadUrl) { progress ->
                _installState.value = InstallState.Downloading(progress)
            }
            _installState.value = InstallState.Installing
            try {
                installer.install(apk)
            } catch (e: ApkInstaller.NeedsPermissionException) {
                // No session committed → no broadcast will arrive.
                _installState.value = InstallState.NeedsPermission(e.intent)
            }
        } catch (e: Exception) {
            _installState.value = InstallState.Failed(e.message ?: "Update failed")
        }
    }

    fun resetInstall() {
        _installState.value = InstallState.Idle
    }
}
