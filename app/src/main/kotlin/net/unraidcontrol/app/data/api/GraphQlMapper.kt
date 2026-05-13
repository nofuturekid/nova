package net.unraidcontrol.app.data.api

import net.unraidcontrol.app.data.model.ArrayInfo
import net.unraidcontrol.app.data.model.ArrayState
import net.unraidcontrol.app.data.model.Container
import net.unraidcontrol.app.data.model.ContainerStatus
import net.unraidcontrol.app.data.model.CpuStats
import net.unraidcontrol.app.data.model.Disk
import net.unraidcontrol.app.data.model.DiskStatus
import net.unraidcontrol.app.data.model.DiskType
import net.unraidcontrol.app.data.model.MemoryStats
import net.unraidcontrol.app.data.model.NetworkStats
import net.unraidcontrol.app.data.model.ParityCheck
import net.unraidcontrol.app.data.model.ServerSnapshot
import net.unraidcontrol.app.data.model.SystemInfo
import net.unraidcontrol.app.data.model.Vm
import net.unraidcontrol.app.data.model.VmState
import net.unraidcontrol.app.graphql.GetServerSnapshotQuery
import net.unraidcontrol.app.graphql.type.ArrayDiskStatus
import net.unraidcontrol.app.graphql.type.ArrayDiskType
import net.unraidcontrol.app.graphql.type.ArrayState as GArrayState
import net.unraidcontrol.app.graphql.type.ContainerState as GContainerState
import net.unraidcontrol.app.graphql.type.VmState as GVmState

/**
 * Maps Apollo-generated Unraid 7 GraphQL types → domain models.
 *
 * Domain models in data/model/Models.kt are stable; GraphQL field names
 * are not. If a future Unraid version renames fields, only schema.graphqls,
 * the .graphql operations, and this file need updating.
 */
fun GetServerSnapshotQuery.Data.toSnapshot(serverBaseUrl: String = ""): ServerSnapshot {
    val infoBlock = info
    val arrBlock = array
    val dockerBlock = docker
    val vmsBlock = vms
    val metricsBlock = metrics

    // ── System info ────────────────────────────────────────────────
    val totalMemBytes = metricsBlock?.memory?.total
        ?: infoBlock.memory.layout.sumOf { it.size }
    val usedMemBytes = metricsBlock?.memory?.used ?: 0L
    val sys = SystemInfo(
        hostname = infoBlock.os.hostname.orEmpty(),
        uptime = infoBlock.os.uptime.orEmpty(),
        cpu = CpuStats(
            brand = infoBlock.cpu.brand.orEmpty(),
            cores = infoBlock.cpu.cores ?: 0,
            threads = infoBlock.cpu.threads ?: 0,
            maxGhz = infoBlock.cpu.speedmax ?: 0.0,
            percent = metricsBlock?.cpu?.percentTotal ?: 0.0,
        ),
        memory = MemoryStats(
            totalGb = totalMemBytes.bytesToGb(),
            usedGb = usedMemBytes.bytesToGb(),
            buffersGb = 0.0, // Unraid GraphQL doesn't expose buff/cache directly
        ),
        // Network throughput isn't part of the Unraid GraphQL schema; leave 0.
        network = NetworkStats(rxMbps = 0.0, txMbps = 0.0),
        unraidVersion = infoBlock.versions.core.unraid.orEmpty(),
        kernel = infoBlock.versions.core.kernel ?: infoBlock.os.kernel.orEmpty(),
    )

    // ── Array ──────────────────────────────────────────────────────
    val arr = if (arrBlock != null) {
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
        ArrayInfo(
            state = arrBlock.state.toDomain(),
            totalTb = totalTb,
            usedTb = usedTb,
            parity = arrBlock.parityCheckStatus?.takeIf {
                it.running == true && it.paused != true
            }?.let { p ->
                ParityCheck(
                    progress = (p.progress ?: 0) / 100f,
                    speedMbps = parseSpeedMbps(p.speed),
                    errors = p.errors ?: 0,
                    etaSeconds = 0,
                )
            },
            disks = parities + data + caches,
        )
    } else {
        ArrayInfo(ArrayState.Offline, 0.0, 0.0, null, emptyList())
    }

    // ── Docker containers ──────────────────────────────────────────
    val containers = dockerBlock?.containers.orEmpty().map { c ->
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
            volumes = emptyList(),
        )
    }

    // ── VMs ─────────────────────────────────────────────────────────
    val vmList = vmsBlock?.domains.orEmpty().map { d ->
        Vm(
            id = d.id,
            name = d.name.orEmpty(),
            state = d.state.toDomain(),
            vcpus = 0,
            memGb = 0,
            gpu = null,
        )
    }

    return ServerSnapshot(
        info = sys, array = arr, containers = containers, vms = vmList,
        serverBaseUrl = serverBaseUrl,
    )
}

// Common disk mapper — Apollo generates three distinct types for
// array.parities / array.disks / array.caches because they're three
// separate inline selections, so we pass primitives instead of trying
// to name the generated parent type.
//
// NB: per the Unraid 7 schema, `ArrayDisk.size` and `ArrayDisk.fs*` are
// expressed in KILOBYTES (not bytes — that's only true for the top-level
// `Disk` type). Mixing those up shows a 1 TB drive as "1 GB". Use kbToTb.
private fun mapDisk(
    name: String?,
    device: String?,
    sizeKb: Long?,
    fsUsedKb: Long?,
    status: ArrayDiskStatus?,
    temp: Int?,
    type: ArrayDiskType,
): Disk = Disk(
    name = name.orEmpty(),
    device = device.orEmpty(),
    type = type.toDomain(),
    sizeTb = (sizeKb ?: 0L).kbToTb(),
    usedTb = (fsUsedKb ?: 0L).kbToTb(),
    tempC = temp ?: 0,
    status = status?.toDomain() ?: DiskStatus.Ok,
    model = "",
)

private fun GArrayState.toDomain(): ArrayState = when (this) {
    GArrayState.STARTED -> ArrayState.Started
    GArrayState.STOPPED -> ArrayState.Stopped
    GArrayState.RECON_DISK, GArrayState.DISABLE_DISK -> ArrayState.Parity
    GArrayState.NEW_ARRAY, GArrayState.INVALID_EXPANSION,
    GArrayState.PARITY_NOT_BIGGEST, GArrayState.TOO_MANY_MISSING_DISKS -> ArrayState.Error
    else -> ArrayState.Stopped
}

private fun ArrayDiskType.toDomain(): DiskType = when (this) {
    ArrayDiskType.PARITY -> DiskType.Parity
    ArrayDiskType.DATA   -> DiskType.Data
    ArrayDiskType.CACHE  -> DiskType.Cache
    ArrayDiskType.FLASH  -> DiskType.Cache
    else                 -> DiskType.Data
}

private fun ArrayDiskStatus.toDomain(): DiskStatus = when (this) {
    ArrayDiskStatus.DISK_OK -> DiskStatus.Ok
    else                    -> DiskStatus.Error
}

private fun GContainerState.toDomain(): ContainerStatus = when (this) {
    GContainerState.RUNNING -> ContainerStatus.Running
    GContainerState.PAUSED  -> ContainerStatus.Paused
    GContainerState.EXITED  -> ContainerStatus.Exited
    else                    -> ContainerStatus.Exited
}

private fun GVmState.toDomain(): VmState = when (this) {
    GVmState.RUNNING                                   -> VmState.Running
    GVmState.PAUSED, GVmState.PMSUSPENDED              -> VmState.Paused
    GVmState.SHUTDOWN, GVmState.SHUTOFF,
    GVmState.NOSTATE, GVmState.IDLE, GVmState.CRASHED  -> VmState.Stopped
    else                                               -> VmState.Stopped
}

private fun Long.bytesToTb(): Double = this / 1_000_000_000_000.0
private fun Long.kbToTb():    Double = this / 1_000_000_000.0
private fun Long.bytesToGb(): Double = this / 1_000_000_000.0

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
