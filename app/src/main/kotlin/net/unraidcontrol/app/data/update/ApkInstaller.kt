package net.unraidcontrol.app.data.update

import android.content.Intent
import java.io.File

/**
 * The APK download + system-install seam used by [UpdateController].
 *
 * Extracted so the controller's install state machine can be unit-tested
 * without Android's `PackageManager` / `PackageInstaller` (ADR-0030 D2).
 * The sole production implementation is [UpdateInstaller]; bound in
 * `UpdateModule`.
 */
interface ApkInstaller {
    /** Download [url] to a cache file, reporting 0f..1f progress. */
    suspend fun download(url: String, onProgress: (Float) -> Unit): File

    /**
     * Hand [apk] to the system installer. Throws
     * [NeedsPermissionException] if "install unknown apps" is not yet
     * granted (no session is committed, so no broadcast will arrive).
     */
    fun install(apk: File)

    /** Thrown by [install] when the user must grant install permission. */
    class NeedsPermissionException(val intent: Intent) : RuntimeException(
        "User must enable 'install unknown apps' first.",
    )
}
