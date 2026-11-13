package io.github.nofuturekid.nova.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.nofuturekid.nova.BuildConfig
import io.github.nofuturekid.nova.data.model.ApkAsset
import io.github.nofuturekid.nova.data.model.UpdateInfo
import io.github.nofuturekid.nova.data.model.UpdateState
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls release metadata from GitHub and decides whether a newer build is
 * available. Independent of Apollo / Unraid auth — uses a dedicated OkHttp
 * client without any Unraid headers. It only reports; the user downloads
 * and installs the file through the browser (ADR-0046).
 */
@Singleton
class UpdateRepository @Inject constructor() {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun check(includePrereleases: Boolean): UpdateState = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases?per_page=20")
            .header("Accept", "application/vnd.github+json")
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext UpdateState.Error("GitHub HTTP ${resp.code}")
                }
                evaluate(resp.body.string(), includePrereleases, BuildConfig.VERSION_NAME)
            }
        } catch (e: Exception) {
            UpdateState.Error(e.message ?: "Network error")
        }
    }

    // ── DTOs from the GitHub Releases API ─────────────────────────

    @Serializable
    private data class GhRelease(
        val tag_name: String,
        val name: String? = null,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val html_url: String,
        val published_at: String? = null,
        val assets: List<GhAsset> = emptyList(),
    ) {
        val tagName: String get() = tag_name
        val htmlUrl: String get() = html_url
        val publishedAt: String? get() = published_at
    }

    @Serializable
    private data class GhAsset(
        val name: String,
        val size: Long = 0,
        val browser_download_url: String,
        // Present on current GitHub (e.g. "sha256:<hex>"); null on older
        // assets uploaded before GitHub digested release assets.
        val digest: String? = null,
    ) {
        val browserDownloadUrl: String get() = browser_download_url
    }

    companion object {
        private const val REPO = "hellwand/nova"
        private const val RELEASES_PREFIX = "https://github.com/$REPO/releases/"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Decides from the Releases API response [body] which release, if
         * any, is the newest one above [currentVersion], whatever order
         * GitHub lists them in. Drafts are never offered;
         * pre-releases only when [includePrereleases]. A release without
         * an APK asset is still offered (its link is the release page).
         */
        internal fun evaluate(
            body: String,
            includePrereleases: Boolean,
            currentVersion: String,
        ): UpdateState {
            val releases = json.decodeFromString<List<GhRelease>>(body)
            val current = parseVersion(currentVersion)
                ?: return UpdateState.Error("Can't parse own version $currentVersion")
            val (_, rel) = releases.asSequence()
                .filter { !it.draft }
                .filter { includePrereleases || !it.prerelease }
                .mapNotNull { rel -> parseVersion(rel.tagName)?.let { it to rel } }
                .filter { (v, _) -> v > current }
                .maxByOrNull { (v, _) -> v }
                ?: return UpdateState.UpToDate
            val apk = rel.assets.firstOrNull { it.name.endsWith(".apk") }
            // Defence in depth: the browser is only sent to this repository's
            // own release pages and files, over HTTPS. A tampered or
            // redirected API response that points elsewhere (another host,
            // another repository, a non-https scheme) fails closed.
            // See ADR-0034 #1 and ADR-0046.
            if (!rel.htmlUrl.startsWith(RELEASES_PREFIX, ignoreCase = true) ||
                (apk != null && !apk.browserDownloadUrl.startsWith(RELEASES_PREFIX, ignoreCase = true))
            ) {
                return UpdateState.Error("Refusing update: link is not a release link of $REPO")
            }
            return UpdateState.Available(
                UpdateInfo(
                    version = rel.tagName.removePrefix("v"),
                    tag = rel.tagName,
                    releaseUrl = rel.htmlUrl,
                    apk = apk?.let { ApkAsset(it.browserDownloadUrl, it.size, it.digest) },
                    isPrerelease = rel.prerelease,
                    releaseNotes = rel.body.orEmpty(),
                    publishedAtEpochMs = parseIso8601(rel.publishedAt),
                ),
            )
        }

        private fun parseIso8601(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            return try {
                Instant.parse(raw).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }

        // Visible for tests / settings display
        internal fun parseVersion(raw: String): SemVer? {
            val trimmed = raw.removePrefix("v").trim()
            // Accept e.g. "0.1.16", "0.1.16-beta1", "1.2.3-rc2"
            val match = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-([A-Za-z0-9]+))?$""").matchEntire(trimmed)
                ?: return null
            val (maj, min, patch, suffix) = match.destructured
            return SemVer(maj.toInt(), min.toInt(), patch.toInt(), suffix.ifBlank { null })
        }
    }
}

/** Lightweight semver-ish version with optional pre-release suffix. */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val pre: String?,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        major.compareTo(other.major).also { if (it != 0) return it }
        minor.compareTo(other.minor).also { if (it != 0) return it }
        patch.compareTo(other.patch).also { if (it != 0) return it }
        // Same numeric tuple: stable (null pre) beats pre-release.
        return when {
            pre == null && other.pre == null -> 0
            pre == null                      -> 1   // we're stable, other is pre
            other.pre == null                -> -1  // they're stable, we're pre
            else                             -> comparePre(pre, other.pre)
        }
    }

    // Compare two pre-release suffixes by (label, number) so the number
    // sorts numerically: "beta9" < "beta10". A plain string compare made
    // "beta10" < "beta9" ('1' < '9'), so the updater never offered
    // beta10+ over beta9.
    private fun comparePre(a: String, b: String): Int {
        val (al, an) = splitPre(a)
        val (bl, bn) = splitPre(b)
        al.compareTo(bl).also { if (it != 0) return it }
        return an.compareTo(bn)
    }

    private fun splitPre(s: String): Pair<String, Int> {
        val m = Regex("""^([A-Za-z]+)(\d+)$""").matchEntire(s)
        return if (m != null) m.groupValues[1] to m.groupValues[2].toInt() else s to 0
    }
}
