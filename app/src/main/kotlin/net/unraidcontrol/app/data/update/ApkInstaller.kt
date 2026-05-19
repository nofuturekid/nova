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
    /**
     * Download [url] to a cache file, reporting 0f..1f progress, then
     * verify the bytes before returning (ADR-0034 #1):
     *
     * - the downloaded byte count must equal [expectedSizeBytes];
     * - if [expectedDigest] is non-null (`"sha256:<hex>"`) the file's
     *   SHA-256 must match it (constant-time compare).
     *
     * On any mismatch the temp file is deleted and
     * [VerificationException] is thrown — the bytes are never handed to
     * the system installer. A null [expectedDigest] falls back to the
     * size check only (documented residual gap, ADR-0034).
     */
    suspend fun download(
        url: String,
        expectedSizeBytes: Long,
        expectedDigest: String?,
        onProgress: (Float) -> Unit,
    ): File

    /** Thrown by [download] when the downloaded APK fails verification. */
    class VerificationException(message: String) : RuntimeException(message)

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
