package io.github.nofuturekid.nova.data.alerts

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nofuturekid.nova.MainActivity
import io.github.nofuturekid.nova.R
import io.github.nofuturekid.nova.data.model.AttentionSeverity
import io.github.nofuturekid.nova.data.model.AttentionTarget
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Channel for critical alerts: sound and heads-up. */
const val ALERTS_CHANNEL_ID = "alerts"

/** Channel for warnings: sound, no heads-up. */
const val WARNINGS_CHANNEL_ID = "warnings"

/** What the lock screen shows instead of the alert: no server name, no content. */
private const val PUBLIC_TITLE = "Server alert"
private const val PUBLIC_TEXT = "Unlock to see details"

private const val SUMMARY_KEY = "|summary"

/**
 * Posts the alerts of [evaluateAlerts] as Android notifications.
 *
 * - Two channels, created on demand (the first time alerts are switched on or one is posted),
 *   never at app start: "Server alerts" (high) for critical items and "Server warnings"
 *   (default) for warnings. The user owns a channel's importance once it exists.
 * - Grouped per server. The Android id is a stable hash of server id and alert key and the tag
 *   is the server id, so one alert is one notification however often it is posted, and a
 *   server's notifications can be removed by tag.
 * - The lock screen shows only [PUBLIC_TITLE] and [PUBLIC_TEXT].
 * - A tap opens [MainActivity] on the server and place (immutable pending intent).
 * - No action buttons: "mark as read" would need a key that can write.
 */
@Singleton
class AlertNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val compat get() = NotificationManagerCompat.from(context)
    private val platform get() = context.getSystemService(NotificationManager::class.java)

    /** False when the user (or Android 13+, without the permission) blocks NOVA's notifications. */
    fun canPost(): Boolean = compat.areNotificationsEnabled()

    /**
     * True when the user switched off one of NOVA's two channels in Android's settings. Read-only:
     * the worker posts as before, Android just drops what goes into such a channel. A channel that
     * does not exist yet is not blocked.
     */
    fun blockedChannel(): Boolean = listOf(ALERTS_CHANNEL_ID, WARNINGS_CHANNEL_ID).any {
        compat.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE
    }

    /** Creates the two channels. Safe to call again: an existing channel is left as the user set it. */
    fun ensureChannels() {
        compat.createNotificationChannel(
            NotificationChannel(ALERTS_CHANNEL_ID, "Server alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Critical problems on your servers"
            },
        )
        compat.createNotificationChannel(
            NotificationChannel(WARNINGS_CHANNEL_ID, "Server warnings", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Warnings from your servers"
            },
        )
    }

    /**
     * Posts the alerts of [evaluation] for [server]. With two or more notifications, or folded
     * ones, a group summary is posted as well, carrying the "and N more" line. Returns false when
     * Android refused (permission withdrawn in the meantime), so the caller keeps its state and
     * tries again at the next check.
     */
    fun post(server: ServerRef, evaluation: AlertEvaluation): Boolean {
        if (evaluation.toPost.isEmpty()) return true
        return try {
            ensureChannels()
            val grouped = evaluation.toPost.size + evaluation.foldedCount > 1
            evaluation.toPost.forEach { alert ->
                notify(server.id, notificationId(server.id, alert.key), buildAlert(server, alert, grouped))
            }
            if (grouped) {
                notify(server.id, notificationId(server.id, SUMMARY_KEY), buildSummary(server, evaluation))
            }
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** Removes everything posted for [serverId]. */
    fun cancelForServer(serverId: String) {
        platform.activeNotifications
            .filter { it.tag == serverId }
            .forEach { platform.cancel(it.tag, it.id) }
    }

    /** Removes everything NOVA posted, for example when alerts are switched off. */
    fun cancelAll() {
        platform.cancelAll()
    }

    // The permission is declared in the manifest and callers check [canPost] before a run; a
    // permission withdrawn in between makes this throw, which [post] turns into "not posted".
    @SuppressLint("MissingPermission")
    private fun notify(tag: String, id: Int, notification: android.app.Notification) {
        compat.notify(tag, id, notification)
    }

    private fun buildAlert(server: ServerRef, alert: Alert, grouped: Boolean): android.app.Notification {
        val builder = base(server, channelFor(alert.severity), alert.target, notificationId(server.id, alert.key))
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setSubText(server.name)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
        alert.timestamp?.let(::epochMillis)?.let { builder.setWhen(it).setShowWhen(true) }
        // Several alerts of one check: the summary makes the sound, the single alerts stay silent.
        if (grouped) builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        return builder.build()
    }

    private fun buildSummary(server: ServerRef, evaluation: AlertEvaluation): android.app.Notification {
        val channel = if (evaluation.toPost.any { it.severity == AttentionSeverity.Critical }) {
            ALERTS_CHANNEL_ID
        } else {
            WARNINGS_CHANNEL_ID
        }
        val total = evaluation.toPost.size + evaluation.foldedCount
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(server.name)
        evaluation.toPost.forEach { inbox.addLine(it.title) }
        val text = if (evaluation.foldedCount > 0) {
            foldedSummaryText(server.name, evaluation.foldedCount).also { inbox.addLine(it) }
        } else {
            "$total new alerts"
        }
        return base(server, channel, AttentionTarget.Notifications, notificationId(server.id, SUMMARY_KEY))
            .setContentTitle(server.name)
            .setContentText(text)
            .setStyle(inbox)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .build()
    }

    private fun base(server: ServerRef, channelId: String, target: AttentionTarget, id: Int): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setGroup(groupKey(server.id))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_stat_alert)
                    .setContentTitle(PUBLIC_TITLE)
                    .setContentText(PUBLIC_TEXT)
                    .build(),
            )
            .setContentIntent(tapIntent(server.id, target, id))

    /** Opens the app on [serverId] at [target]. Immutable; one per notification (request code = [id]). */
    private fun tapIntent(serverId: String, target: AttentionTarget, id: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_SERVER_ID, serverId)
            .putExtra(EXTRA_TARGET, target.name)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun channelFor(severity: AttentionSeverity): String =
        if (severity == AttentionSeverity.Critical) ALERTS_CHANNEL_ID else WARNINGS_CHANNEL_ID
}

/** Stable across runs and processes: [String.hashCode] is specified. */
internal fun notificationId(serverId: String, key: String): Int = "$serverId|$key".hashCode()

private fun groupKey(serverId: String): String = "server:$serverId"

/** The server's ISO timestamp as epoch milliseconds, or null when it is not one. */
private fun epochMillis(iso: String): Long? =
    runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
