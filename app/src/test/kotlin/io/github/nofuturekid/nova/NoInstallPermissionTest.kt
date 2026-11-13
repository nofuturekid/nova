package io.github.nofuturekid.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WHY: Google Play Protect stopped every NOVA build that declared
 * REQUEST_INSTALL_PACKAGES ("Harmful app blocked") and rated the same build
 * without it "This app seems safe" (ADR-0046). The permission must not come
 * back unnoticed, neither through a flavor manifest nor by dropping the
 * remove marker that strips it from library manifests.
 *
 * Gradle runs unit tests with the module dir as working dir.
 */
class NoInstallPermissionTest {

    private val permission = "android.permission.REQUEST_INSTALL_PACKAGES"

    private fun manifests(): List<File> {
        val src = File("src")
        assertTrue("src not found from ${File(".").absolutePath}", src.isDirectory)
        return src.listFiles { f -> f.isDirectory }.orEmpty()
            .map { File(it, "AndroidManifest.xml") }
            .filter { it.isFile }
    }

    private fun declaring(text: String) =
        Regex("""<uses-permission\b[^>]*>""").findAll(text).map { it.value }
            .filter { it.contains(permission) }.toList()

    @Test
    fun noSourceManifestDeclaresTheInstallPermission() {
        val offenders = manifests().filter { m ->
            declaring(m.readText()).any { !it.contains("tools:node=\"remove\"") }
        }
        assertEquals("these manifests declare $permission: $offenders", emptyList<File>(), offenders)
    }

    @Test
    fun mainManifestStripsTheInstallPermissionFromEveryFlavorAndLibrary() {
        val main = File("src/main/AndroidManifest.xml")
        assertTrue("main manifest not found", main.isFile)
        val entries = declaring(main.readText())
        assertTrue(
            "main manifest must carry <uses-permission ... $permission tools:node=\"remove\" />",
            entries.any { it.contains("tools:node=\"remove\"") },
        )
    }
}
