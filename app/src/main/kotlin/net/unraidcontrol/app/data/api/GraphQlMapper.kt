package net.unraidcontrol.app.data.api

import net.unraidcontrol.app.data.model.ArrayInfo
import net.unraidcontrol.app.data.model.ArrayState
import net.unraidcontrol.app.data.model.Container
import net.unraidcontrol.app.data.model.ContainerStatus
import net.unraidcontrol.app.data.model.ContainerUpdateStatus
import net.unraidcontrol.app.data.model.Disk
import net.unraidcontrol.app.data.model.DiskStatus
import net.unraidcontrol.app.data.model.DiskType
import net.unraidcontrol.app.data.model.LiveMetrics
import net.unraidcontrol.app.data.model.NotifImportance
import net.unraidcontrol.app.data.model.Notifications
import net.unraidcontrol.app.data.model.ParityCheck
import net.unraidcontrol.app.data.model.ServerInfo
import net.unraidcontrol.app.data.model.UnraidNotification
import net.unraidcontrol.app.data.model.Vm
import net.unraidcontrol.app.data.model.VmState
import net.unraidcontrol.app.graphql.GetArrayQuery
import net.unraidcontrol.app.graphql.GetDockerContainersQuery
import net.unraidcontrol.app.graphql.GetMetricsQuery
import net.unraidcontrol.app.graphql.GetNotificationsQuery
import net.unraidcontrol.app.graphql.GetServerInfoQuery
import net.unraidcontrol.app.graphql.GetVmsQuery
import net.unraidcontrol.app.graphql.type.ArrayDiskStatus
import net.unraidcontrol.app.graphql.type.ArrayDiskType
import net.unraidcontrol.app.graphql.type.ArrayState as GArrayState
import net.unraidcontrol.app.graphql.type.ContainerState as GContainerState
import net.unraidcontrol.app.graphql.type.NotificationImportance as GNotifImportance
import net.unraidcontrol.app.graphql.type.VmState as GVmState

/**
 * Maps Apollo-generated Unraid 7 GraphQL types → domain models.
 *
 * Domain models in `data/model/Models.kt` are stable; GraphQL field names
 * are not. If a future Unraid version renames fields, only schema.graphqls,
 * the .graphql operations, and this file need updating.
 *
 * Five mappers — one per per-domain query (ADR-0017). Splitting the
 * snapshot query into per-domain streams also splits the mapping logic
 * into per-domain functions.
 */

// ── Info ─────────────────────────────────────────────────────────────

fun GetServerInfoQuery.Data.toServerInfo(): ServerInfo {
    val totalMemBytes = info.memory.layout.sumOf { it.size }
    return ServerInfo(
        hostname = info.os.hostname.orEmpty(),
        uptime = info.os.uptime.orEmpty(),
        unraidVersion = info.versions.core.unraid.orEmpty(),
        kernel = info.versions.core.kernel ?: info.os.kernel.orEmpty(),
        cpuBrand = info.cpu.brand.orEmpty(),
        cpuCores = info.cpu.cores ?: 0,
        cpuThreads = info.cpu.threads ?: 0,
        cpuMaxGhz = info.cpu.speedmax ?: 0.0,
        memTotalGb = totalMemBytes.bytesToGib(),
    )
}

// ── Metrics ──────────────────────────────────────────────────────────

fun GetMetricsQuery.Data.toLiveMetrics(): LiveMetrics {
    val m = metrics
    return LiveMetrics(
        cpuPercent = m?.cpu?.percentTotal ?: 0.0,
        memTotalGb = (m?.memory?.total ?: 0L).bytesToGib(),
        memUsedGb = (m?.memory?.used ?: 0L).bytesToGib(),
        memBuffGb = (m?.memory?.buffcache ?: 0L).bytesToGib(),
    )
}

// ── Array ────────────────────────────────────────────────────────────

fun GetArrayQuery.Data.toArrayInfo(): ArrayInfo {
    val arrBlock = array ?: return ArrayInfo(ArrayState.Offline, 0.0, 0.0, null, emptyList())
    val cap = arrBlock.capacity.kilobytes
    val totalTb = cap.total.toLongOrNull()?.kbToTb() ?: 0.0
    val usedTb  = cap.used.toLongOrNull()?.kbToTb() ?: 0.0
    val parities = arrBlock.parities.map {
        mapDisk(it.name, it.device, it.size, null, it.status, it.temp, it.type)
    }
    val data = arrBlock.disks.map {
        mapDisk(it.name, it.device, it.size, it.fsUsed, it.status, it.temp, it.type)
    }
    val caches = arrBlock.caches.map {
        mapDisk(it.name, it.device, it.size, it.fsUsed, it.status, it.temp, it.type)
    }
    return ArrayInfo(
        state = arrBlock.state.toDomain(),
        totalTb = totalTb,
        usedTb = usedTb,
        parity = arrBlock.parityCheckStatus?.takeIf {
            it.running == true
        }?.let { p ->
            ParityCheck(
                progress = (p.progress ?: 0) / 100f,
                speedMbps = parseSpeedMbps(p.speed),
                errors = p.errors ?: 0,
                etaSeconds = 0,
                paused = p.paused == true,
            )
        },
        disks = parities + data + caches,
    )
}

// ── Docker ───────────────────────────────────────────────────────────

fun GetDockerContainersQuery.Data.toContainers(): List<Container> =
    docker?.containers.orEmpty().map { c ->
        val displayName = c.names.firstOrNull()?.removePrefix("/").orEmpty()
        Container(
            id = c.id,
            name = displayName,
            image = c.image,
            status = c.state.toDomain(),
            autoStart = c.autoStart,
            iconColorHex = null,
            iconUrl = c.iconUrl,
            cpu = 0.0,
            memMb = 0,
            ports = c.ports.map { p ->
                val host = p.publicPort?.toString().orEmpty()
                val ctn  = p.privatePort?.toString().orEmpty()
                if (host.isNotEmpty() && ctn.isNotEmpty()) "$host:$ctn" else (host.ifEmpty { ctn })
            },
            volumes = parseMountsArray(c.mounts),
            updateStatus = deriveUpdateStatus(c.isUpdateAvailable, c.isRebuildReady),
            webUiUrl = c.webUiUrl?.takeIf { it.isNotBlank() },
            networkIp = parseContainerNetworkIp(c.networkSettings),
        )
    }

// ── VMs ──────────────────────────────────────────────────────────────

fun GetVmsQuery.Data.toVms(): List<Vm> =
    vms?.domains.orEmpty().map { d ->
        Vm(
            id = d.id,
            name = d.name.orEmpty(),
            state = d.state.toDomain(),
            vcpus = 0,
            memGb = 0,
            gpu = null,
        )
    }

fun GetNotificationsQuery.Data.toNotifications(): Notifications {
    val unread = notifications.overview.unread
    return Notifications(
        unreadWarning = unread.warning,
        unreadAlert = unread.alert,
        items = notifications.warningsAndAlerts.map { n ->
            UnraidNotification(
                id = n.id,
                title = n.title,
                subject = n.subject,
                description = n.description,
                importance = n.importance.toDomain(),
                timestamp = n.timestamp,
            )
        },
    )
}

// ── Helpers ──────────────────────────────────────────────────────────

@Suppress("LongParameterList")
private fun mapDisk(
    name: String?,
    device: String?,
    sizeKb: Long?,
    usedKb: Long?,
    status: ArrayDiskStatus?,
    temp: Int?,
    type: ArrayDiskType,
): Disk {
    val sizeTb = (sizeKb ?: 0L).kbToTb()
    val usedTb = (usedKb ?: 0L).kbToTb()
    return Disk(
        name = name.orEmpty(),
        device = device.orEmpty(),
        type = type.toDomain(),
        sizeTb = sizeTb,
        usedTb = usedTb,
        tempC = temp ?: 0,
        status = status?.toDomain() ?: DiskStatus.Ok,
        model = "",
    )
}

private fun GArrayState.toDomain(): ArrayState = when (this) {
    GArrayState.STARTED -> ArrayState.Started
    GArrayState.STOPPED -> ArrayState.Stopped
    GArrayState.NEW_ARRAY, GArrayState.RECON_DISK -> ArrayState.Parity
    GArrayState.DISABLE_DISK, GArrayState.INVALID_EXPANSION,
    GArrayState.PARITY_NOT_BIGGEST, GArrayState.TOO_MANY_MISSING_DISKS -> ArrayState.Error
    else -> ArrayState.Offline
}

private fun ArrayDiskStatus.toDomain(): DiskStatus = when (this) {
    ArrayDiskStatus.DISK_OK -> DiskStatus.Ok
    else                    -> DiskStatus.Error
}

private fun ArrayDiskType.toDomain(): DiskType = when (this) {
    ArrayDiskType.PARITY -> DiskType.Parity
    ArrayDiskType.DATA   -> DiskType.Data
    ArrayDiskType.CACHE  -> DiskType.Cache
    ArrayDiskType.FLASH  -> DiskType.Cache
    else                 -> DiskType.Data
}

private fun GContainerState.toDomain(): ContainerStatus = when (this) {
    GContainerState.RUNNING -> ContainerStatus.Running
    GContainerState.PAUSED  -> ContainerStatus.Paused
    GContainerState.EXITED  -> ContainerStatus.Exited
    else                    -> ContainerStatus.Exited
}

private fun deriveUpdateStatus(
    isUpdateAvailable: Boolean?,
    isRebuildReady: Boolean?,
): ContainerUpdateStatus = when {
    isRebuildReady == true     -> ContainerUpdateStatus.RebuildReady
    isUpdateAvailable == true  -> ContainerUpdateStatus.UpdateAvailable
    isUpdateAvailable == false -> ContainerUpdateStatus.UpToDate
    else                       -> ContainerUpdateStatus.Unknown
}

private fun GVmState.toDomain(): VmState = when (this) {
    GVmState.RUNNING                                   -> VmState.Running
    GVmState.PAUSED, GVmState.PMSUSPENDED              -> VmState.Paused
    GVmState.SHUTDOWN, GVmState.SHUTOFF, GVmState.CRASHED, GVmState.IDLE, GVmState.NOSTATE -> VmState.Stopped
    else                                                -> VmState.Stopped
}

private fun GNotifImportance.toDomain(): NotifImportance = when (this) {
    GNotifImportance.WARNING -> NotifImportance.Warning
    GNotifImportance.ALERT   -> NotifImportance.Alert
    else                     -> NotifImportance.Info   // INFO + UNKNOWN__
}

private fun parseSpeedMbps(speedString: String?): Double {
    if (speedString.isNullOrBlank()) return 0.0
    val numberPart = speedString.takeWhile { it.isDigit() || it == '.' }
    val value = numberPart.toDoubleOrNull() ?: return 0.0
    val unit = speedString.removePrefix(numberPart).trim().lowercase()
    return when {
        unit.startsWith("g") -> value * 1000
        unit.startsWith("k") -> value / 1000
        else                 -> value
    }
}

/** Storage capacity uses *decimal* GB/TB (HDD-marketing convention).  */
private fun Long.kbToTb():     Double = this / 1_000_000_000.0
/** RAM uses *binary* GiB (matches `/proc/meminfo`, `free -h`, `htop`).
 *  We display it as "GB" because that's what users mentally call it. */
private fun Long.bytesToGib(): Double = this / 1_073_741_824.0

/**
 * Pick a LAN-routable IP from the container's `NetworkSettings.Networks`
 * block. Returns null for bridge-mode containers (where the URL should
 * keep using the host's IP + mapped port) and for any container whose
 * networks all share the default-bridge name.
 *
 * The Docker `NetworkSettings` JSON looks roughly like:
 * ```
 * { "Networks": {
 *     "br0":    { "IPAddress": "192.168.11.230", … },
 *     "bridge": { "IPAddress": "172.17.0.3", … }
 * } }
 * ```
 * We prefer the first entry whose key is not `bridge` and whose IPAddress
 * is non-empty — that's where macvlan / ipvlan / custom-bridge containers
 * surface their LAN-routable IP.
 */
fun parseContainerNetworkIp(raw: Any?): String? {
    val ns = raw as? Map<*, *> ?: return null
    val networks = ns["Networks"] as? Map<*, *> ?: return null
    for ((name, cfg) in networks) {
        if (name == "bridge") continue
        val map = cfg as? Map<*, *> ?: continue
        val ip = (map["IPAddress"] as? String)?.takeIf { it.isNotBlank() } ?: continue
        return ip
    }
    return null
}

/**
 * Parse Docker container mount entries.
 *
 * Apollo's [JsonAnyAdapter] delivers `mounts: JSON` as `Any?` — typically a
 * `List<Map<String, Any?>>` where each element looks like:
 *   { "Type"="bind", "Source"="/mnt/user/appdata/x",
 *     "Destination"="/config", "Mode"="rw", … }
 *
 * Return one "source → destination" string per mount, dropping entries
 * that are missing both fields.
 */
fun parseMountsArray(raw: Any?): List<String> {
    val list = raw as? List<*> ?: return emptyList()
    return list.mapNotNull { mountToString(it) }
}

private fun mountToString(entry: Any?): String? {
    val map = entry as? Map<*, *> ?: return null
    val src = (map["Source"] as? String)?.takeIf { it.isNotBlank() }
    val dst = (map["Destination"] as? String)?.takeIf { it.isNotBlank() }
    return when {
        src != null && dst != null -> "$src → $dst"
        dst != null                -> dst
        src != null                -> src
        else                       -> null
    }
}
