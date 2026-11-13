package io.github.nofuturekid.nova.data.update

import io.github.nofuturekid.nova.data.model.ApkAsset
import io.github.nofuturekid.nova.data.model.UpdateInfo
import io.github.nofuturekid.nova.data.model.UpdateState
import io.github.nofuturekid.nova.data.update.UpdateRepository.Companion.evaluate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update check only announces a release; the user downloads and installs
 * it in the browser (ADR-0046). These tests pin what the app sends the
 * browser to, because that is all that is left of the updater and a wrong
 * link is the one way it can still fail the user.
 */
class UpdateRepositoryTest {

    private val sha = "a".repeat(64)

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        assets: String = apkAsset(tag),
    ) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,
         "html_url":"https://github.com/hellwand/nova/releases/tag/$tag",
         "body":"notes","published_at":"2026-11-13T10:00:00Z","assets":[$assets]}
    """.trimIndent()

    private fun apkAsset(tag: String, digest: String? = "sha256:$sha") = """
        {"name":"NOVA-$tag.apk","size":4096,
         "browser_download_url":"https://github.com/hellwand/nova/releases/download/$tag/NOVA-$tag.apk"
         ${if (digest != null) ""","digest":"$digest"""" else ""}}
    """.trimIndent()

    private fun json(vararg releases: String) = releases.joinToString(",", "[", "]")

    private fun available(state: UpdateState): UpdateInfo {
        assertTrue("expected Available, was $state", state is UpdateState.Available)
        return (state as UpdateState.Available).info
    }

    /**
     * WHY: the browser must land on the APK itself so one tap starts the
     * download; the page link is only the fallback.
     */
    @Test
    fun releaseWithApkOpensTheApkUrl() {
        val info = available(evaluate(json(release("v0.1.47")), false, "0.1.46"))
        assertEquals(
            "https://github.com/hellwand/nova/releases/download/v0.1.47/NOVA-v0.1.47.apk",
            info.downloadUrl,
        )
        assertEquals("https://github.com/hellwand/nova/releases/tag/v0.1.47", info.releaseUrl)
        assertEquals(4096L, info.apk?.sizeBytes)
    }

    /**
     * WHY: a newer release must still be announced when its APK is missing
     * (it used to be skipped silently, hiding the update); the user then
     * gets the release page instead of a dead download.
     */
    @Test
    fun releaseWithoutApkOpensTheReleasePage() {
        val info = available(evaluate(json(release("v0.1.47", assets = "")), false, "0.1.46"))
        assertNull(info.apk)
        assertEquals("https://github.com/hellwand/nova/releases/tag/v0.1.47", info.downloadUrl)
    }

    @Test
    fun nonApkAssetsAreIgnored() {
        val txt = """{"name":"SHA256SUMS.txt","size":10,"browser_download_url":"https://github.com/x/SHA256SUMS.txt"}"""
        val info = available(evaluate(json(release("v0.1.47", assets = txt)), false, "0.1.46"))
        assertNull(info.apk)
    }

    /**
     * WHY: stable users must not be offered betas; the switch in Settings is
     * the only way to opt in. This was true before the browser change and
     * must stay true.
     */
    @Test
    fun prereleaseIsOfferedOnlyWhenIncluded() {
        val body = json(release("v0.1.47-beta1", prerelease = true))
        assertEquals(UpdateState.UpToDate, evaluate(body, false, "0.1.46"))
        assertEquals("0.1.47-beta1", available(evaluate(body, true, "0.1.46")).version)
    }

    @Test
    fun draftsAreNeverOffered() {
        val body = json(release("v0.1.47", draft = true))
        assertEquals(UpdateState.UpToDate, evaluate(body, true, "0.1.46"))
    }

    @Test
    fun sameOrOlderVersionIsUpToDate() {
        val body = json(release("v0.1.46"), release("v0.1.45"))
        assertEquals(UpdateState.UpToDate, evaluate(body, false, "0.1.46"))
    }

    /**
     * WHY: ADR-0034 #1 fail-closed. A link rewritten to plain HTTP must not
     * reach the browser, whether it is the APK or the release page.
     */
    @Test
    fun nonHttpsLinkIsRefused() {
        val http = """{"name":"a.apk","size":1,"browser_download_url":"http://github.com/hellwand/nova/releases/download/v0.1.47/a.apk"}"""
        assertTrue(evaluate(json(release("v0.1.47", assets = http)), false, "0.1.46") is UpdateState.Error)
        val httpPage = release("v0.1.47").replace("https://github.com/hellwand/nova/releases/tag", "http://github.com/hellwand/nova/releases/tag")
        assertTrue(evaluate(json(httpPage), false, "0.1.46") is UpdateState.Error)
    }

    /**
     * WHY: the app no longer compares the file with a digest, so the only
     * thing left to protect the user is where the browser is sent. A
     * tampered API response must not be able to open an https page on
     * another host or in another repository.
     */
    @Test
    fun linkOutsideThisRepositorysReleasesIsRefused() {
        val otherHostApk = """{"name":"a.apk","size":1,"browser_download_url":"https://evil.example/NOVA.apk"}"""
        assertTrue(evaluate(json(release("v0.1.47", assets = otherHostApk)), false, "0.1.46") is UpdateState.Error)
        val otherRepoApk = """{"name":"a.apk","size":1,"browser_download_url":"https://github.com/someone/else/releases/download/v0.1.47/a.apk"}"""
        assertTrue(evaluate(json(release("v0.1.47", assets = otherRepoApk)), false, "0.1.46") is UpdateState.Error)
        val lookalikeHost = """{"name":"a.apk","size":1,"browser_download_url":"https://github.com.evil.example/hellwand/nova/releases/download/v0.1.47/a.apk"}"""
        assertTrue(evaluate(json(release("v0.1.47", assets = lookalikeHost)), false, "0.1.46") is UpdateState.Error)
        val otherHostPage = release("v0.1.47").replace("https://github.com/hellwand/nova/releases/tag", "https://evil.example/releases/tag")
        assertTrue(evaluate(json(otherHostPage), false, "0.1.46") is UpdateState.Error)
        val otherRepoPage = release("v0.1.47").replace("hellwand/nova/releases/tag", "someone/else/releases/tag")
        assertTrue(evaluate(json(otherRepoPage), false, "0.1.46") is UpdateState.Error)
    }

    /**
     * WHY: the shown checksum is what the user compares with the downloaded
     * file, so only a real SHA-256 may be shown; anything else is omitted
     * rather than displayed as if it were verifiable.
     */
    @Test
    fun checksumIsShownOnlyForAValidSha256Digest() {
        fun sha(digest: String?) = ApkAsset("https://x/a.apk", 1, digest).sha256
        assertEquals(sha, sha("sha256:$sha"))
        assertEquals(sha, sha("SHA256:${sha.uppercase()}"))
        assertNull(sha(null))
        assertNull(sha("sha512:$sha"))
        assertNull(sha("sha256:abc"))
        assertNull(sha("sha256:${"z".repeat(64)}"))
    }

    @Test
    fun digestFromTheApiReachesTheUpdateInfo() {
        val info = available(evaluate(json(release("v0.1.47")), false, "0.1.46"))
        assertEquals(sha, info.apk?.sha256)
        val noDigest = release("v0.1.47", assets = apkAsset("v0.1.47", digest = null))
        assertNull(available(evaluate(json(noDigest), false, "0.1.46")).apk?.sha256)
    }
}
