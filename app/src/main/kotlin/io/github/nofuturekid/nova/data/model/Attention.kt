package io.github.nofuturekid.nova.data.model

/** How urgent an [AttentionItem] is. Declared least to most urgent: the order is the sort key. */
enum class AttentionSeverity { Info, Warning, Critical }

/**
 * Where an [AttentionItem] is dealt with. Kept free of UI types so background
 * alerts and a widget can reuse it. [Notifications] is the bell's sheet, not a tab.
 */
enum class AttentionTarget { Array, Notifications }

/**
 * One thing that needs the user's attention.
 *
 * [key] names the condition, not the moment: it stays the same while the same
 * condition holds, so a later alert can tell "still the same problem" from "a new
 * one" and not repeat itself. Changing numbers (progress, error count) therefore
 * stay out of the key and live in [detail]. A condition that is worse in kind
 * (warm to hot disk) gets its own key on purpose.
 */
data class AttentionItem(
    val key: String,
    val severity: AttentionSeverity,
    val title: String,
    val detail: String,
    val target: AttentionTarget,
)

/** Everything [attentionItems] reads, all of it data the app already loads. Null means not loaded. */
data class AttentionSnapshot(
    val array: ArrayInfo?,
    val notifications: Notifications?,
    val thresholds: DisplayThresholds?,
)

/**
 * The problems visible in [snapshot], most urgent first (equal severity keeps the
 * order the rules run in: array, parity, disks, notifications). Empty when all is well.
 *
 * Pure and free of Android and Compose, so the Overview card, background alerts and
 * a home-screen widget can share it.
 *
 * Only known data raises an item: an array that is not loaded, a disk without a
 * temperature, a threshold the server does not report or an error count the API does
 * not give are not problems. Callers that know the server is offline pass an empty
 * snapshot.
 *
 * Deliberately not covered: containers that are stopped but have auto-start on. The
 * flag stays set when the user stops a container on purpose, and one-shot containers
 * exit by design, so it cannot tell "should run" from "was stopped" (see issue 319).
 *
 * Disk temperatures need a threshold the server reported, per disk or globally; the
 * Array tab also falls back to fixed 42/50 degrees, this card does not, because a
 * guessed limit would raise alerts the server never configured.
 */
fun attentionItems(snapshot: AttentionSnapshot): List<AttentionItem> {
    val items = buildList {
        snapshot.array?.let { array ->
            addAll(arrayItems(array))
            addAll(parityItems(array))
            addAll(diskItems(array.disks, snapshot.thresholds))
        }
        snapshot.notifications?.let { addAll(notificationItems(it)) }
    }
    // sortedByDescending is stable, so equal severities keep their rule order.
    return items.sortedByDescending { it.severity }
}

private fun arrayItems(array: ArrayInfo): List<AttentionItem> = when (array.state) {
    ArrayState.Stopped -> listOf(
        AttentionItem("array-stopped", AttentionSeverity.Warning, "Array is not started", "Start it on the Array tab.", AttentionTarget.Array),
    )
    ArrayState.Error -> listOf(
        AttentionItem("array-degraded", AttentionSeverity.Critical, "Array is degraded", "A disk is missing, disabled or invalid.", AttentionTarget.Array),
    )
    // Rebuilding and syncing already shows as the running parity operation; the
    // state alone only gets its own row when the API gives no operation.
    ArrayState.Parity -> if (array.parity == null) listOf(
        AttentionItem("array-rebuilding", AttentionSeverity.Warning, "Array is rebuilding", "Parity is being rebuilt or synced.", AttentionTarget.Array),
    ) else emptyList()
    // Offline here means "not known", never a problem.
    ArrayState.Started, ArrayState.Offline -> emptyList()
}

private fun parityItems(array: ArrayInfo): List<AttentionItem> {
    val running = array.parity
    if (running != null) {
        val percent = "${(running.progress * 100).toInt()}%"
        return listOf(
            if (running.paused) {
                AttentionItem("parity-paused", AttentionSeverity.Warning, "Parity operation is paused", "Paused at $percent.", AttentionTarget.Array)
            } else {
                AttentionItem("parity-running", AttentionSeverity.Info, "Parity operation is running", "$percent done.", AttentionTarget.Array)
            },
        )
    }
    val last = array.parityLastRun ?: return emptyList()
    val errors = last.errors?.takeIf { it > 0 }
    return when {
        last.outcome == ParityOutcome.Failed ->
            listOf(AttentionItem("parity-failed", AttentionSeverity.Critical, "Last parity check failed", "It did not finish.", AttentionTarget.Array))
        // A cancelled check is the user's own doing and never raises an item.
        last.outcome == ParityOutcome.Completed && errors != null ->
            listOf(AttentionItem("parity-errors", AttentionSeverity.Critical, "Parity check found errors", "$errors ${plural(errors, "error")} in the last check.", AttentionTarget.Array))
        else -> emptyList()
    }
}

private fun diskItems(disks: List<Disk>, thresholds: DisplayThresholds?): List<AttentionItem> = buildList {
    for (disk in disks) {
        val id = disk.name.ifBlank { disk.device }
        if (id.isBlank()) continue
        if (disk.numErrors > 0) {
            add(AttentionItem("disk-errors:$id", AttentionSeverity.Critical, "$id has errors", "${disk.numErrors} ${plural(disk.numErrors, "error")} reported.", AttentionTarget.Array))
        }
        // A spun-down disk has no temperature; isSpinning is the truth source.
        if (!disk.isSpinning) continue
        val crit = (disk.criticalC ?: thresholds?.diskCritC)?.takeIf { it > 0 }
        val warn = (disk.warningC ?: thresholds?.diskWarnC)?.takeIf { it > 0 }
        when {
            crit != null && disk.tempC >= crit ->
                add(AttentionItem("disk-temp-critical:$id", AttentionSeverity.Critical, "$id is too hot", "${disk.tempC} °C, critical from $crit °C.", AttentionTarget.Array))
            warn != null && disk.tempC >= warn ->
                add(AttentionItem("disk-temp-warning:$id", AttentionSeverity.Warning, "$id is running warm", "${disk.tempC} °C, warning from $warn °C.", AttentionTarget.Array))
        }
    }
}

private fun notificationItems(n: Notifications): List<AttentionItem> = buildList {
    if (n.unreadAlert > 0) {
        add(AttentionItem("notifications-alert", AttentionSeverity.Critical, "${n.unreadAlert} unread ${plural(n.unreadAlert, "alert")}", "From the server notifications.", AttentionTarget.Notifications))
    }
    if (n.unreadWarning > 0) {
        add(AttentionItem("notifications-warning", AttentionSeverity.Warning, "${n.unreadWarning} unread ${plural(n.unreadWarning, "warning")}", "From the server notifications.", AttentionTarget.Notifications))
    }
}

private fun plural(count: Number, noun: String): String = if (count.toLong() == 1L) noun else "${noun}s"
