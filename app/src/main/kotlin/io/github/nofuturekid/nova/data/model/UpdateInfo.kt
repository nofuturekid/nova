package io.github.nofuturekid.nova.data.model

/** A pending release on GitHub that's newer than the currently installed build. */
data class UpdateInfo(
    val version: String,         // e.g. "0.1.16" (no leading v)
    val tag: String,             // e.g. "v0.1.16"
    val releaseUrl: String,      // browser URL of the GitHub Release page
    val downloadUrl: String,     // direct APK asset URL
    val sizeBytes: Long,
    /**
     * GitHub asset content digest, e.g. "sha256:<hex>". Null on older
     * assets GitHub did not digest (pre-digest releases). When null the
     * install path falls back to the size check only — see ADR-0034.
     */
    val digest: String? = null,
    val isPrerelease: Boolean,
    val releaseNotes: String,
    /** Release publish time as epoch millis; null if GitHub didn't supply it. */
    val publishedAtEpochMs: Long? = null,
)

sealed interface UpdateState {
    /** Initial value or while a request is in flight. */
    data object Checking : UpdateState
    /** Current build is at or beyond the latest considered release. */
    data object UpToDate : UpdateState
    /** Newer release is available. */
    data class Available(val info: UpdateInfo) : UpdateState
    /** Check failed (network, parse, rate-limit). */
    data class Error(val message: String) : UpdateState
}

sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(val progress: Float) : InstallState
    data object Installing : InstallState
    data class NeedsPermission(val intent: android.content.Intent) : InstallState
    data class Failed(val message: String) : InstallState
}
