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

data class CpuStats(
    val brand: String,
    val cores: Int,
    val threads: Int,
    val maxGhz: Double,
    val percent: Double,
)

data class MemoryStats(
    val totalGb: Double,
    val usedGb: Double,
    val buffersGb: Double,
)

data class NetworkStats(
    val rxMbps: Double,
    val txMbps: Double,
)

data class SystemInfo(
    val hostname: String,
    val uptime: String,
    val cpu: CpuStats,
    val memory: MemoryStats,
    val network: NetworkStats,
    val unraidVersion: String,
    val kernel: String,
)

data class LogLine(
    val time: String,
    val message: String,
)

data class ServerSnapshot(
    val info: SystemInfo,
    val array: ArrayInfo,
    val containers: List<Container>,
    val vms: List<Vm>,
    /** Base URL of the server the snapshot came from, used by the UI to
     *  resolve relative container icon paths. Empty when unknown. */
    val serverBaseUrl: String = "",
)
