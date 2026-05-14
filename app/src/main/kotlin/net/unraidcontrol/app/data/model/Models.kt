package net.unraidcontrol.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Server(
    val id: String,
    val name: String,
    val hostname: String,
    val localUrl: String,
    val remoteUrl: String,
)

enum class ConnectionMode { Local, Remote }

enum class ServerHealth { Healthy, ParityCheck, DiskError, Offline }

enum class ArrayState { Started, Stopped, Parity, Error, Offline }

enum class DiskType { Parity, Data, Cache }
enum class DiskStatus { Ok, Error }

data class Disk(
    val name: String,
    val device: String,
    val type: DiskType,
    val sizeTb: Double,
    val usedTb: Double,
    val tempC: Int,
    val status: DiskStatus,
    val model: String,
)

data class ParityCheck(
    val progress: Float,        // 0..1
    val speedMbps: Double,
    val errors: Int,
    val etaSeconds: Int,
)

data class ArrayInfo(
    val state: ArrayState,
    val totalTb: Double,
    val usedTb: Double,
    val parity: ParityCheck?,
    val disks: List<Disk>,
)

enum class ContainerStatus { Running, Paused, Exited }

enum class ContainerUpdateStatus { UpToDate, UpdateAvailable, RebuildReady, Unknown }

fun ContainerUpdateStatus.hasUpdate(): Boolean =
    this == ContainerUpdateStatus.UpdateAvailable || this == ContainerUpdateStatus.RebuildReady

data class Container(
    val id: String,
    val name: String,
    val image: String,
    val status: ContainerStatus,
    val autoStart: Boolean,
    val iconColorHex: String?,
    val iconUrl: String?,
    val cpu: Double,
    val memMb: Int,
    val ports: List<String>,
    val volumes: List<String>,
    val updateStatus: ContainerUpdateStatus,
)

enum class VmState { Running, Paused, Stopped }

data class Vm(
    val id: String,
    val name: String,
    val state: VmState,
    val vcpus: Int,
    val memGb: Int,
    val gpu: String?,
)

/** Static server identity + hardware spec. Polled rarely (info changes only between reboots). */
data class ServerInfo(
    val hostname: String,
    val uptime: String,
    val unraidVersion: String,
    val kernel: String,
    val cpuBrand: String,
    val cpuCores: Int,
    val cpuThreads: Int,
    val cpuMaxGhz: Double,
    val memTotalGb: Double,
)

/** Dynamic CPU/memory metrics. Polled at high frequency while Overview is visible. */
data class LiveMetrics(
    val cpuPercent: Double,
    val memUsedGb: Double,
    val memBuffGb: Double,
)

data class LogLine(
    val time: String,
    val message: String,
)
