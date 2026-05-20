package io.github.nofuturekid.nova.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles APK download + invocation of Android's PackageInstaller.
 *
 * Flow:
 *   1. download(url, onProgress) → File in app cache
 *   2. install(File): if user hasn't granted "install unknown apps" yet,
 *      throws NeedsPermissionException carrying the settings intent.
 *      Otherwise opens a PackageInstaller session and commits it; the
 *      PendingIntent fires InstallStatusReceiver which routes events
 *      onto InstallStatusReceiver.events.
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ApkInstaller {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun download(
        url: String,
        expectedSizeBytes: Long,
        expectedDigest: String?,
        onProgress: (Float) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            // Defence in depth: refuse a non-https URL even though
            // UpdateRepository already screens it (ADR-0034 #1).
            if (!url.startsWith("https://", ignoreCase = true)) {
                throw ApkInstaller.VerificationException(
                    "Refusing to download update over a non-HTTPS URL",
                )
            }
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} downloading APK")
                val body = resp.body
                val total = body.contentLength().coerceAtLeast(1L)
                val out = updatesDir().resolve("NOVA-update.apk")
                out.parentFile?.mkdirs()
                if (out.exists()) out.delete()
                val sha = MessageDigest.getInstance("SHA-256")
                var written = 0L
                body.source().use { src ->
                    out.sink().buffer().use { sink ->
                        val buffer = okio.Buffer()
                        while (true) {
                            val read = src.read(buffer, 8192L)
                            if (read == -1L) break
                            // Snapshot exactly the bytes we are about to
                            // persist, hash them, then write the same bytes.
                            val chunk = buffer.readByteArray(read)
                            sha.update(chunk)
                            sink.write(chunk)
                            written += read
                            onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                        sink.flush()
                    }
                }
                onProgress(1f)

                // ── Verify BEFORE the bytes can reach PackageInstaller ──
                // Fail closed: any mismatch deletes the temp file and
                // throws; install() is never reached. See ADR-0034 #1.
                if (written != expectedSizeBytes) {
                    out.delete()
                    throw ApkInstaller.VerificationException(
                        "Update size mismatch: expected $expectedSizeBytes bytes, got $written",
                    )
                }
                if (expectedDigest != null) {
                    val expectedHex = expectedDigest.substringAfter("sha256:", "")
                        .trim()
                        .lowercase()
                    if (expectedHex.isEmpty()) {
                        out.delete()
                        throw ApkInstaller.VerificationException(
                            "Update digest is not a sha256 digest: $expectedDigest",
                        )
                    }
                    val actualHex = sha.digest()
                        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                    if (!MessageDigest.isEqual(
                            expectedHex.toByteArray(Charsets.US_ASCII),
                            actualHex.toByteArray(Charsets.US_ASCII),
                        )
                    ) {
                        out.delete()
                        throw ApkInstaller.VerificationException(
                            "Update SHA-256 mismatch — refusing to install",
                        )
                    }
                }
                out
            }
        }

    override fun install(apk: File) {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !pm.canRequestPackageInstalls()) {
            throw ApkInstaller.NeedsPermissionException(unknownAppSourcesIntent())
        }
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(InstallStatusReceiver.ACTION).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pi.intentSender)
        }
    }

    fun unknownAppSourcesIntent(): Intent {
        val uri = Uri.parse("package:${context.packageName}")
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun updatesDir(): File = File(context.cacheDir, "updates")
}
