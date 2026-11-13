package io.github.nofuturekid.nova.data.model

/** A pending release on GitHub that's newer than the currently installed build. */
data class UpdateInfo(
    val version: String,         // e.g. "0.1.16" (no leading v)
    val tag: String,             // e.g. "v0.1.16"
    val releaseUrl: String,      // browser URL of the GitHub Release page
    /** The release's APK file; null if the release has no APK attached. */
    val apk: ApkAsset? = null,
    val isPrerelease: Boolean,
    val releaseNotes: String,
    /** Release publish time as epoch millis; null if GitHub didn't supply it. */
    val publishedAtEpochMs: Long? = null,
) {
    /**
     * What "Download" opens in the browser: the APK file itself, or the
     * release page when the release has no APK attached (ADR-0046). The
     * app never downloads or installs the file itself.
     */
    val downloadUrl: String get() = apk?.url ?: releaseUrl
}

/** The APK file of a GitHub release, as listed by the Releases API. */
data class ApkAsset(
    val url: String,             // browser_download_url of the asset
    val sizeBytes: Long,
    /**
     * GitHub asset content digest, e.g. "sha256:<hex>". Null on older
     * assets GitHub did not digest (pre-digest releases).
     */
    val digest: String? = null,
) {
    /**
     * The file's SHA-256 as 64 lower-case hex digits, to show next to the
     * download so the user can compare it; null if GitHub gave no digest
     * or one that is not a SHA-256.
     */
    val sha256: String?
        get() = digest
            ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substring("sha256:".length)
            ?.trim()
            ?.lowercase()
            ?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
}

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

/**
 * What the app knows about the latest release, for the CURRENT "Include
 * pre-releases" setting. The Overview notice, its sheet and every line and
 * button of the Settings update section render from this one value.
 */
data class UpdateStatus(
    /**
     * Last good outcome of a check made under the current setting:
     * [UpdateState.UpToDate] or [UpdateState.Available]. [UpdateState.Checking]
     * means nothing is known yet for this setting. Never [UpdateState.Error]:
     * a failed check does not replace the last good result, see [failure].
     */
    val latest: UpdateState = UpdateState.Checking,
    /** Message of the most recent check if it failed, null if it succeeded. */
    val failure: String? = null,
) {
    /**
     * The release the Overview notice offers: the latest known newer release
     * unless the user dismissed exactly this tag; null means no notice.
     */
    fun notice(dismissedTag: String?): UpdateInfo? =
        (latest as? UpdateState.Available)?.info?.takeIf { it.tag != dismissedTag }
}
