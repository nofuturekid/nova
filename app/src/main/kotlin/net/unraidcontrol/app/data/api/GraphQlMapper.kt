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
import net.unraidcontrol.app.graphql.type.ArrayState as GArrayState
import net.unraidcontrol.app.graphql.type.ContainerStatus as GContainerStatus
import net.unraidcontrol.app.graphql.type.DiskStatus as GDiskStatus
import net.unraidcontrol.app.graphql.type.DiskType as GDiskType
import net.unraidcontrol.app.graphql.type.VmState as GVmState

/**
 * Maps Apollo-generated types → domain models.
 *
 * Reasoning: domain models are stable; GraphQL field names are not (the live
 * Unraid server may rename things between Connect plugin versions). All UI
 * code consumes domain models so a schema rename only requires updating the
 * .graphqls files and this file.
 */
fun GetServerSnapshotQuery.Data.toSnapshot(): ServerSnapshot {
    val sys = SystemInfo(
        hostname = info.hostname,
        uptime = info.uptime,
        cpu = CpuStats(
            brand = info.cpu.brand,
            cores = info.cpu.cores,
            threads = info.cpu.threads,
            maxGhz = info.cpu.maxGhz,
            percent = info.cpu.percent,
        ),
        memory = MemoryStats(
            totalGb = info.memory.totalGb,
            usedGb = info.memory.usedGb,
            buffersGb = info.memory.buffersGb,
        ),
        network = NetworkStats(rxMbps = info.network.rxMbps, txMbps = info.network.txMbps),
        unraidVersion = info.versions.unraid,
        kernel = info.versions.kernel,
    )

    val arr = ArrayInfo(
        state = array.state.toDomain(),
        totalTb = array.totalTb,
        usedTb = array.usedTb,
        parity = array.parityCheck?.let {
            ParityCheck(
                progress = it.progress.toFloat(),
                speedMbps = it.speedMbps,
                errors = it.errors,
                etaSeconds = it.etaSeconds,
            )
        },
        disks = array.disks.map { d ->
            Disk(
                name = d.name,
                device = d.device,
                type = d.type.toDomain(),
                sizeTb = d.sizeTb,
                usedTb = d.usedTb,
                tempC = d.tempC,
                status = d.status.toDomain(),
                model = d.model,
            )
        },
    )

    val containers = dockerContainers.map { c ->
        Container(
            id = c.id,
            name = c.name,
            image = c.image,
            status = c.status.toDomain(),
            autoStart = c.autoStart,
            iconColorHex = c.iconColorHex,
            cpu = c.cpu,
            memMb = c.memMb,
            ports = c.ports,
            volumes = c.volumes,
        )
    }

    val vmList = vms.list.map { v ->
        Vm(id = v.id, name = v.name, state = v.state.toDomain(),
            vcpus = v.vcpus, memGb = v.memGb, gpu = v.gpu)
    }

    return ServerSnapshot(info = sys, array = arr, containers = containers, vms = vmList)
}

private fun GArrayState.toDomain(): ArrayState = when (this) {
    GArrayState.STARTED -> ArrayState.Started
    GArrayState.STOPPED -> ArrayState.Stopped
    GArrayState.PARITY  -> ArrayState.Parity
    GArrayState.ERROR   -> ArrayState.Error
    GArrayState.OFFLINE -> ArrayState.Offline
    else                -> ArrayState.Stopped
}

private fun GDiskType.toDomain(): DiskType = when (this) {
    GDiskType.PARITY -> DiskType.Parity
    GDiskType.DATA   -> DiskType.Data
    GDiskType.CACHE  -> DiskType.Cache
    else             -> DiskType.Data
}

private fun GDiskStatus.toDomain(): DiskStatus = when (this) {
    GDiskStatus.OK    -> DiskStatus.Ok
    GDiskStatus.ERROR -> DiskStatus.Error
    else              -> DiskStatus.Ok
}

private fun GContainerStatus.toDomain(): ContainerStatus = when (this) {
    GContainerStatus.RUNNING -> ContainerStatus.Running
    GContainerStatus.PAUSED  -> ContainerStatus.Paused
    GContainerStatus.EXITED  -> ContainerStatus.Exited
    else                     -> ContainerStatus.Exited
}

private fun GVmState.toDomain(): VmState = when (this) {
    GVmState.RUNNING -> VmState.Running
    GVmState.PAUSED  -> VmState.Paused
    GVmState.STOPPED -> VmState.Stopped
    else             -> VmState.Stopped
}
