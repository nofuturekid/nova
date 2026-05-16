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
    val paused: Boolean,
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
    /** Resolved WebUI URL from the container's Unraid template
     *  (e.g. `http://192.168.1.2:8989` for Sonarr). Null when the
     *  template has no WebUI declaration. */
    val webUiUrl: String?,
    /** LAN-routable IP the container has on a non-default Docker
     *  network (e.g. a macvlan/ipvlan `br0`). Null for bridge-mode
     *  containers — those are reachable via the host's IP and the
     *  mapped host port, so [webUiUrl] from the template is already
     *  correct. When non-null, the UI substitutes this for the host
     *  portion of [webUiUrl] before opening the browser. */
    val networkIp: String?,
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

enum class NotifImportance { Info, Warning, Alert }

data class UnraidNotification(
    val id: String,
    val title: String,
    val subject: String,
    val description: String,
    val importance: NotifImportance,
    val timestamp: String?,
)

/** Which transport produced the current notifications snapshot.
 *  Phase E pilot diagnostic (ADR-0026 E1): the WS subscription and the
 *  poll fallback are intentionally indistinguishable in behaviour, so
 *  this is surfaced in the sheet header to verify the pilot on-device.
 *  Remove once the subscription path is proven and the gate decided. */
enum class NotifTransport { Subscription, Poll }

/** Unread warning+alert count for the bell badge + the deduped list for the sheet. */
data class Notifications(
    val unreadWarning: Int,
    val unreadAlert: Int,
    val items: List<UnraidNotification>,
    val transport: NotifTransport = NotifTransport.Poll,
) {
    val badgeCount: Int get() = unreadWarning + unreadAlert
}

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

/** Dynamic CPU/memory metrics. Polled at high frequency while Overview is visible.
 *  [memTotalGb] reflects the OS-reported total (preferred for display because
 *  it accounts for memory reserved by firmware/hardware); falls back to
 *  [ServerInfo.memTotalGb] (sum of hardware DIMM slot sizes) when zero. */
data class LiveMetrics(
    val cpuPercent: Double,
    val memTotalGb: Double,
    val memUsedGb: Double,
    val memBuffGb: Double,
)

data class LogLine(
    val time: String,
    val message: String,
)
