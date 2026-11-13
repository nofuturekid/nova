package io.github.nofuturekid.nova.ui.screens.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.nofuturekid.nova.data.alerts.ALERT_INTERVALS_MIN
import io.github.nofuturekid.nova.data.alerts.AlertLevel
import io.github.nofuturekid.nova.ui.components.BtnVariant
import io.github.nofuturekid.nova.ui.components.SectionLabel
import io.github.nofuturekid.nova.ui.components.Tone
import io.github.nofuturekid.nova.ui.components.UnraidButton
import io.github.nofuturekid.nova.ui.components.UnraidCard
import io.github.nofuturekid.nova.ui.theme.UnraidDims
import io.github.nofuturekid.nova.ui.theme.UnraidTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val TIPS_URL = "https://dontkillmyapp.com"
private const val INSTANT_ALERTS_URL = "https://github.com/hellwand/nova#alerts-outside-the-app"

/**
 * Settings, Notifications: the switch for alerts outside the app (ADR-0048) and its choices.
 * Emits its own label and card into the Settings column.
 *
 * The permission flow lives here because the system's request needs the activity: the switch
 * opens an explanation, "Turn on" asks Android for the permission (Android 13 and later, when it
 * is not granted yet) and only a granted answer reaches [AlertsViewModel.onPermissionResult] as
 * true, which is the one place that schedules anything. A denial leaves the switch off.
 */
@Composable
fun AlertsSection(vm: AlertsViewModel = hiltViewModel()) {
    val ui by vm.state.collectAsState()
    val ctx = LocalContext.current
    var showExplanation by remember { mutableStateOf(false) }
    var resumes by remember { mutableIntStateOf(0) }

    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onPermissionResult(granted)
    }

    // Coming back from Android's settings: the notification block or the battery setting may have changed.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshNotificationsAllowed()
                resumes++
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val t = UnraidTheme.colors
    val settings = ui.settings
    SectionLabel("Notifications")
    UnraidCard(padding = UnraidTheme.tokens.pad) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Alerts from your servers", color = t.text, style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = if (settings.enabled) {
                            "On. NOVA checks your servers about every ${intervalPhrase(settings.intervalMinutes)}."
                        } else {
                            "Off. NOVA does nothing in the background."
                        },
                        color = t.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Toggle(value = settings.enabled, onChange = { on -> if (on) showExplanation = true else vm.turnOff() })
            }

            if (ui.blocked || ui.channelBlocked) {
                Text(
                    text = if (ui.channelBlocked) {
                        "Android blocks NOVA's \"Server alerts\" or \"Server warnings\" notifications, so those " +
                            "alerts are not shown. Allow them in Android's settings."
                    } else if (settings.enabled) {
                        "Android blocks notifications for NOVA, so no alert can be shown and the checks are paused. " +
                            "Allow notifications in Android's settings."
                    } else {
                        "Android blocks notifications for NOVA. Allow them in Android's settings, then switch this on."
                    },
                    color = t.warn,
                    style = MaterialTheme.typography.bodyMedium,
                )
                UnraidButton(
                    onClick = { ctx.open(notificationSettingsIntent(ctx)) },
                    label = "Open notification settings",
                    variant = BtnVariant.Tonal,
                )
            }

            if (ui.hasDemoServer) {
                Text(
                    text = "The demo server is never checked. Alerts only cover your own servers.",
                    color = t.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (settings.enabled) {
                Option("Check every", "More often uses a little more battery.") {
                    Segmented(
                        value = settings.intervalMinutes,
                        options = ALERT_INTERVALS_MIN,
                        label = ::intervalLabel,
                        onChange = vm::setInterval,
                    )
                }
                Option("Alert me about", "Unraid also sends a warning each time a parity check starts.") {
                    Segmented(
                        value = settings.level,
                        options = listOf(AlertLevel.AlertsAndWarnings, AlertLevel.AlertsOnly),
                        label = { if (it == AlertLevel.AlertsOnly) "Alerts only" else "Alerts and warnings" },
                        onChange = vm::setLevel,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingRow(label = "Only on Wi-Fi") {
                        Toggle(value = settings.unmeteredOnly, onChange = vm::setUnmeteredOnly)
                    }
                    Text("Skip checks on mobile data.", color = t.muted, style = MaterialTheme.typography.bodySmall)
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Checks the server you are using now and shows its newest unread alert or warning as a test.",
                        color = t.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    UnraidButton(
                        onClick = vm::sendTest,
                        label = "Send test notification",
                        variant = BtnVariant.Tonal,
                        enabled = ui.canTest && !ui.testing && !ui.blocked,
                    )
                    ui.testMessage?.let {
                        Text(it, color = t.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (ui.servers.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Last check", color = t.muted, style = MaterialTheme.typography.labelLarge)
                        ui.servers.forEach { line ->
                            Column {
                                Text(line.name, color = t.text, style = MaterialTheme.typography.labelLarge)
                                Text(
                                    text = line.text.replaceFirstChar { it.uppercase() } +
                                        (line.atEpochMs?.let { " · ${formatCheckTime(it)}" } ?: ""),
                                    color = t.muted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                val batteryOptimised = remember(resumes) { ctx.isBatteryOptimised() }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Android decides when a check runs. When the phone lies still, a check can be an hour or more late." +
                            if (batteryOptimised) " Battery saving can delay or stop the checks." else "",
                        color = t.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (batteryOptimised) {
                            TextButton(onClick = { ctx.open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
                                Text("Open battery settings", color = t.accent)
                            }
                        }
                        TextButton(onClick = { ctx.open(Intent(Intent.ACTION_VIEW, Uri.parse(TIPS_URL))) }) {
                            Text("Tips for your phone", color = t.accent)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Unraid can also send alerts itself, for example to your own ntfy server.",
                        color = t.muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { ctx.open(Intent(Intent.ACTION_VIEW, Uri.parse(INSTANT_ALERTS_URL))) }) {
                        Text("How", color = t.accent)
                    }
                }
            }
        }
    }

    if (showExplanation) {
        ExplanationDialog(
            intervalMinutes = settings.intervalMinutes,
            onTurnOn = {
                showExplanation = false
                if (needsPermissionRequest(ctx)) {
                    permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    vm.onPermissionResult(true)
                }
            },
            onDismiss = { showExplanation = false },
        )
    }
}

@Composable
private fun Option(label: String, help: String, control: @Composable () -> Unit) {
    val t = UnraidTheme.colors
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = t.muted, style = MaterialTheme.typography.labelLarge)
        control()
        Text(help, color = t.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ExplanationDialog(intervalMinutes: Int, onTurnOn: () -> Unit, onDismiss: () -> Unit) {
    val t = UnraidTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(UnraidDims.radDialog),
        containerColor = t.surface2,
        titleContentColor = t.text,
        textContentColor = t.muted,
        title = { Text("Get alerts outside the app?", style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                text = "While this is on, NOVA asks each of your servers about every ${intervalPhrase(intervalMinutes)} " +
                    "whether something needs attention: new Unraid alerts and warnings, and an array that is not " +
                    "started or is degraded. The checks go only to your own servers, with the API key you saved, " +
                    "and a read-only key is enough. They use only https addresses with a trusted certificate; " +
                    "a server that has only an http address is not checked. Android decides the exact time, so an " +
                    "alert can come late, sometimes by an hour or more while the phone is idle. Turn this off at " +
                    "any time and NOVA stops all background checks.",
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
            )
        },
        confirmButton = { UnraidButton(onClick = onTurnOn, label = "Turn on", variant = BtnVariant.Text) },
        dismissButton = {
            UnraidButton(
                onClick = onDismiss,
                label = "Not now",
                variant = BtnVariant.Text,
                tone = Tone.Neutral,
            )
        },
    )
}

/** Android 13 and later need the runtime permission; before that the channel or app setting is all there is. */
private fun needsPermissionRequest(ctx: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

private fun notificationSettingsIntent(ctx: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)

private fun Context.isBatteryOptimised(): Boolean =
    !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

/** Starts [intent]; a phone without a matching app (no browser, no settings page) is not worth a crash. */
private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Nothing to open it with.
    }
}

private fun intervalLabel(minutes: Int): String = when (minutes) {
    60 -> "1 hour"
    180 -> "3 hours"
    else -> "$minutes min"
}

private fun intervalPhrase(minutes: Int): String = when (minutes) {
    60 -> "hour"
    180 -> "3 hours"
    else -> "$minutes minutes"
}

/** "today 14:05", or the date and time for an older check. */
private fun formatCheckTime(epochMs: Long): String {
    val at = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(at)
    return if (at.toLocalDate() == LocalDate.now(at.zone)) "today $time" else {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(at) + " " + time
    }
}
