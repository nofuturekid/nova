package net.unraidcontrol.app.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.unraidcontrol.app.BuildConfig
import net.unraidcontrol.app.data.model.UpdateInfo
import net.unraidcontrol.app.data.model.UpdateState
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
 * client without any Unraid headers.
 */
@Singleton
class UpdateRepository @Inject constructor() {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

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
                val body = resp.body.string()
                val releases = json.decodeFromString<List<GhRelease>>(body)
                val current = parseVersion(BuildConfig.VERSION_NAME)
                    ?: return@withContext UpdateState.Error("Can't parse own version ${BuildConfig.VERSION_NAME}")
                val match = releases.asSequence()
                    .filter { !it.draft }
                    .filter { includePrereleases || !it.prerelease }
                    .mapNotNull { rel ->
                        val v = parseVersion(rel.tagName) ?: return@mapNotNull null
                        val apk = rel.assets.firstOrNull { it.name.endsWith(".apk") }
                            ?: return@mapNotNull null
                        Triple(v, rel, apk)
                    }
                    .firstOrNull { (v, _, _) -> v > current }
                    ?: return@withContext UpdateState.UpToDate
                val (_, rel, apk) = match
                UpdateState.Available(
                    UpdateInfo(
                        version = rel.tagName.removePrefix("v"),
                        tag = rel.tagName,
                        releaseUrl = rel.htmlUrl,
                        downloadUrl = apk.browserDownloadUrl,
                        sizeBytes = apk.size,
                        isPrerelease = rel.prerelease,
                        releaseNotes = rel.body.orEmpty(),
                        publishedAtEpochMs = parseIso8601(rel.publishedAt),
                    ),
                )
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
    ) {
        val browserDownloadUrl: String get() = browser_download_url
    }

    companion object {
        private const val REPO = "nofuturekid/UnraidControl"

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
        // Same numeric tuple: stable (null pre) beats pre-release;
        // two pre-releases compare lexicographically (beta1 < beta2 < rc1).
        return when {
            pre == null && other.pre == null -> 0
            pre == null                      -> 1   // we're stable, other is pre
            other.pre == null                -> -1  // they're stable, we're pre
            else                             -> pre.compareTo(other.pre)
        }
    }
}
