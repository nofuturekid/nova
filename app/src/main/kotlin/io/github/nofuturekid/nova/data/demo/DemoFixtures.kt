package io.github.nofuturekid.nova.data.demo

import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.ArrayState
import io.github.nofuturekid.nova.data.model.Container
import io.github.nofuturekid.nova.data.model.ContainerLiveStats
import io.github.nofuturekid.nova.data.model.ContainerStatus
import io.github.nofuturekid.nova.data.model.ContainerUpdateStatus
import io.github.nofuturekid.nova.data.model.DisplayThresholds
import io.github.nofuturekid.nova.data.model.Disk
import io.github.nofuturekid.nova.data.model.DiskStatus
import io.github.nofuturekid.nova.data.model.DiskType
import io.github.nofuturekid.nova.data.model.LogLine
import io.github.nofuturekid.nova.data.model.NetworkInterface
import io.github.nofuturekid.nova.data.model.NotifImportance
import io.github.nofuturekid.nova.data.model.NotifType
import io.github.nofuturekid.nova.data.model.Notifications
import io.github.nofuturekid.nova.data.model.Plugin
import io.github.nofuturekid.nova.data.model.PluginInstallOperation
import io.github.nofuturekid.nova.data.model.PluginInstallStatus
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.model.ServerInfo
import io.github.nofuturekid.nova.data.model.UnraidNotification
import io.github.nofuturekid.nova.data.model.Vm
import io.github.nofuturekid.nova.data.model.VmState

/**
 * The fictional server behind the demo mode (ADR-0050) and behind the store screenshots
 * (`StoreScreenshots` reads these values, so the two cannot drift apart).
 *
 * Nothing here belongs to a real server or person: the server is "Tower" on `example.net`,
 * addresses come from RFC 5737 / RFC 7042 documentation ranges, container images are
 * generic public names. The data is plain Kotlin on purpose (no resources, no JSON), so it
 * adds a few kilobytes of code and no asset to the APK.
 */
object DemoFixtures {
    /** Id of the one demo entry in the server list; a second demo entry cannot exist. */
    const val SERVER_ID = "demo"

    /**
     * The base URL the demo tags its data with. It is not an address: the scheme is not
     * routable, so a stray use of it (an icon URL, say) fails locally instead of leaving
     * the device.
     */
    const val BASE_URL = "demo://tower.example.net"

    /** The demo server entry: no address, no key, no certificate; only a name to show. */
    val server = Server(
        id = SERVER_ID,
        name = "Tower",
        hostname = "tower.example.net",
        localUrl = "",
        remoteUrl = "",
        isDemo = true,
    )

    val info = ServerInfo(
        hostname = "Tower",
        uptime = "21d 6h",
        unraidVersion = "7.2.0",
        kernel = "6.12.54-Unraid",
        cpuBrand = "Intel Core i5-12400",
        cpuCores = 6,
        cpuThreads = 12,
        cpuMaxGhz = 4.4,
        memTotalGb = 32.0,
    )

    /** Global temperature thresholds, as the dynamix display settings would report them. */
    val thresholds = DisplayThresholds(diskWarnC = 45, diskCritC = 55, cpuWarnC = 70, cpuCritC = 85)

    private fun disk(
        name: String, device: String, type: DiskType, size: Double, used: Double,
        temp: Int, model: String, spinning: Boolean = true, rotational: Boolean = true,
    ) = Disk(
        name = name, device = device, type = type, sizeTb = size, usedTb = used,
        tempC = temp, status = DiskStatus.Ok, model = model, isSpinning = spinning,
        rotational = rotational, numErrors = 0, warningC = null, criticalC = null,
    )

    val disks = listOf(
        disk("parity", "sda", DiskType.Parity, 16.0, 0.0, 36, "WDC WD161KRYZ-01AGBB0"),
        disk("disk1", "sdb", DiskType.Data, 16.0, 11.2, 38, "WDC WD161KRYZ-01AGBB0"),
        disk("disk2", "sdc", DiskType.Data, 12.0, 8.4, 40, "ST12000VN0008-2YS101"),
        disk("disk3", "sdd", DiskType.Data, 12.0, 6.1, 0, "ST12000VN0008-2YS101", spinning = false),
        disk("disk4", "sde", DiskType.Data, 8.0, 7.3, 37, "WDC WD80EFAX-68LHPN0"),
        disk("cache", "nvme0n1", DiskType.Cache, 1.0, 0.46, 41, "Samsung SSD 980 PRO 1TB", rotational = false),
        disk("cache2", "nvme1n1", DiskType.Cache, 1.0, 0.46, 39, "Samsung SSD 980 PRO 1TB", rotational = false),
    )

    val array = ArrayInfo(
        state = ArrayState.Started,
        totalTb = 48.0,
        usedTb = 33.0,
        parity = null,
        disks = disks,
    )

    private fun ctr(
        name: String, image: String, status: ContainerStatus = ContainerStatus.Running,
        color: String?, cpu: Double, mem: Int, update: ContainerUpdateStatus = ContainerUpdateStatus.UpToDate,
        port: String? = null,
    ) = Container(
        id = name, name = name, image = image, status = status, autoStart = true,
        iconColorHex = color, iconUrl = null, cpu = cpu, memMb = mem,
        ports = listOfNotNull(port),
        volumes = listOf("/mnt/user/appdata/$name:/config", "/mnt/user/media:/data:ro"),
        updateStatus = update,
        webUiUrl = port?.let { "http://192.0.2.10:${it.substringBefore(':')}" },
        networkIp = null,
    )

    val containers = listOf(
        ctr("plex", "lscr.io/linuxserver/plex:latest", color = "#E5A00D", cpu = 6.4, mem = 1180, port = "32400:32400/tcp"),
        ctr("nextcloud", "nextcloud:latest", color = "#0082C9", cpu = 2.1, mem = 640, update = ContainerUpdateStatus.UpdateAvailable, port = "8443:443/tcp"),
        ctr("home-assistant", "ghcr.io/home-assistant/home-assistant:stable", color = "#18BCF2", cpu = 3.3, mem = 820, port = "8123:8123/tcp"),
        ctr("pihole", "pihole/pihole:latest", color = "#96060C", cpu = 0.4, mem = 96, port = "8080:80/tcp"),
        ctr("jellyfin", "jellyfin/jellyfin:latest", color = "#00A4DC", cpu = 0.0, mem = 0, status = ContainerStatus.Exited, port = "8096:8096/tcp"),
        ctr("sonarr", "lscr.io/linuxserver/sonarr:latest", color = "#35C5F4", cpu = 1.2, mem = 410, port = "8989:8989/tcp"),
        ctr("radarr", "lscr.io/linuxserver/radarr:latest", color = "#FFC230", cpu = 0.9, mem = 380, port = "7878:7878/tcp"),
        ctr("vaultwarden", "vaultwarden/server:latest", color = "#175DDC", cpu = 0.1, mem = 58, port = "8081:80/tcp"),
        ctr("grafana", "grafana/grafana:latest", color = "#F46800", cpu = 0.7, mem = 190, update = ContainerUpdateStatus.UpdateAvailable, port = "3000:3000/tcp"),
        ctr("mosquitto", "eclipse-mosquitto:2", color = "#3C5280", cpu = 0.1, mem = 6, port = "1883:1883/tcp"),
    )

    /** One snapshot of the live per-container figures, by container id. */
    val stats = mapOf(
        "plex" to ContainerLiveStats(6.4, 3.6, "1.15 GiB / 32 GiB", "48.2 GB / 912 GB", "31.4 GB / 2.1 GB"),
        "nextcloud" to ContainerLiveStats(2.1, 2.0, "640 MiB / 32 GiB", "3.4 GB / 1.8 GB", "12.8 GB / 4.5 GB"),
        "home-assistant" to ContainerLiveStats(3.3, 2.6, "820 MiB / 32 GiB", "1.2 GB / 640 MB", "6.1 GB / 3.3 GB"),
        "pihole" to ContainerLiveStats(0.4, 0.3, "96 MiB / 32 GiB", "2.9 GB / 1.1 GB", "210 MB / 96 MB"),
        "sonarr" to ContainerLiveStats(1.2, 1.3, "410 MiB / 32 GiB", "1.0 GB / 220 MB", "4.4 GB / 1.9 GB"),
        "radarr" to ContainerLiveStats(0.9, 1.2, "380 MiB / 32 GiB", "860 MB / 190 MB", "3.9 GB / 1.6 GB"),
        "vaultwarden" to ContainerLiveStats(0.1, 0.2, "58 MiB / 32 GiB", "120 MB / 80 MB", "40 MB / 22 MB"),
        "grafana" to ContainerLiveStats(0.7, 0.6, "190 MiB / 32 GiB", "410 MB / 150 MB", "1.1 GB / 0.8 GB"),
        "mosquitto" to ContainerLiveStats(0.1, 0.0, "6 MiB / 32 GiB", "88 MB / 90 MB", "4 MB / 8 MB"),
    )

    val vms = listOf(
        Vm("v1", "Windows 11", VmState.Running, vcpus = 6, memGb = 16, gpu = "NVIDIA RTX 3060"),
        Vm("v2", "Ubuntu Server 24.04", VmState.Running, vcpus = 2, memGb = 4, gpu = null),
        Vm("v3", "Home Assistant OS", VmState.Running, vcpus = 2, memGb = 4, gpu = null),
        Vm("v4", "Debian Build Box", VmState.Paused, vcpus = 4, memGb = 8, gpu = null),
        Vm("v5", "macOS Sonoma", VmState.Stopped, vcpus = 4, memGb = 8, gpu = "AMD RX 580"),
    )

    private fun note(id: String, subject: String, desc: String, imp: NotifImportance, ts: String, type: NotifType = NotifType.Unread) =
        UnraidNotification(
            id = id, title = "Unraid Status", subject = subject, description = desc,
            importance = imp, type = type, link = null, timestamp = null, formattedTimestamp = ts,
        )

    val unreadNotes = listOf(
        note("n1", "Warning [TOWER] - Disk 3 temperature", "Disk 3 is at 47 C (ST12000VN0008-2YS101_ZV70XXXX)", NotifImportance.Warning, "Today 09:14"),
        note("n2", "Update available for nextcloud", "A newer image of nextcloud is available.", NotifImportance.Info, "Today 06:00"),
        note("n3", "Notice [TOWER] - Parity check finished", "Parity check finished (0 errors). Duration: 11 hours, 2 minutes.", NotifImportance.Info, "Yesterday 17:42"),
        note("n4", "Alert [TOWER] - Docker image disk utilization", "Docker image disk utilization is at 71%.", NotifImportance.Alert, "Yesterday 08:03"),
    )

    val archivedNotes = listOf(
        note("a1", "Notice [TOWER] - Array turned good", "Array has 0 disks with read errors.", NotifImportance.Info, "Mon 21:10", NotifType.Archive),
    )

    /** The notification overview for a pair of lists: the counts are derived, never stored. */
    fun notificationsOf(unread: List<UnraidNotification>, archived: List<UnraidNotification>) = Notifications(
        unreadWarning = unread.count { it.importance == NotifImportance.Warning },
        unreadAlert = unread.count { it.importance == NotifImportance.Alert },
        unreadInfo = unread.count { it.importance == NotifImportance.Info },
        unreadTotal = unread.size,
        items = unread.filter { it.importance != NotifImportance.Info },
        unread = unread,
        archived = archived,
    )

    val notifications = notificationsOf(unreadNotes, archivedNotes)

    // ── Only the demo mode uses what follows ─────────────────────────

    val plugins = listOf(
        Plugin("unraid-api", "4.37.11", hasApiModule = true, hasCliModule = true),
        Plugin("community-applications", "2026.11.01", hasApiModule = false, hasCliModule = false),
        Plugin("dynamix-system-temp", "2026.10.20", hasApiModule = false, hasCliModule = false),
    )

    val installedPlugins = listOf(
        "unraid-api.plg",
        "community.applications.plg",
        "dynamix.system.temp.plg",
    )

    val pluginOperations = listOf(
        PluginInstallOperation(
            id = "op1", url = "https://example.net/plugins/community.applications.plg",
            name = "community.applications.plg", status = PluginInstallStatus.Succeeded,
            createdAt = "2026-11-02T08:15:00.000Z", finishedAt = "2026-11-02T08:15:21.000Z",
            output = listOf("plugin: downloading community.applications.plg", "plugin: installed"),
        ),
    )

    val networkInterfaces = listOf(
        NetworkInterface(
            name = "eth0", isPrimary = true, description = "Ethernet", status = "up", protocol = "ipv4",
            macAddress = "00:00:5E:00:53:10", ipAddress = "192.0.2.10", netmask = "255.255.255.0",
            gateway = "192.0.2.1", useDhcp = false, ipv6Address = null, ipv6Netmask = null,
            ipv6Gateway = null, useDhcp6 = null, vendor = "Intel", model = "Ethernet Controller I225-V",
            speed = "2500 Mbps", virtual = false,
        ),
        NetworkInterface(
            name = "docker0", isPrimary = false, description = "Docker bridge", status = "up", protocol = "ipv4",
            macAddress = "00:00:5E:00:53:11", ipAddress = "172.17.0.1", netmask = "255.255.0.0",
            gateway = null, useDhcp = false, ipv6Address = null, ipv6Netmask = null,
            ipv6Gateway = null, useDhcp6 = null, vendor = null, model = null,
            speed = null, virtual = true,
        ),
    )

    /** What a running container's log would plausibly say; a few lines are warnings or debug output. */
    private val logTemplates = listOf(
        "Starting %s",
        "Loaded configuration from /config",
        "Listening on the configured port",
        "Health check passed",
        "GET /status 200 3 ms",
        "debug: cache hit ratio 0.93",
        "Background task finished in 412 ms",
        "GET /api/v1/items 200 11 ms",
        "warn: slow disk response (212 ms)",
        "Scheduled job ran, next run in 15 minutes",
    )

    /**
     * The last [tail] log lines of [name], newest last, 15 s apart and ending at [nowMs]. The
     * lines depend only on the name and the line's position, so two calls tell the same story.
     */
    fun logLines(name: String, nowMs: Long, tail: Int): List<LogLine> {
        val count = tail.coerceIn(0, 60)
        val salt = name.fold(0) { acc, c -> acc * 31 + c.code }.let { if (it < 0) -it else it } % logTemplates.size
        return List(count) { i ->
            val age = (count - 1 - i) * 15_000L
            // The position counted from the newest line, so a line keeps its text as new ones are added.
            val slot = (nowMs - age) / 15_000L
            val message = logTemplates[Math.floorMod(slot + salt, logTemplates.size.toLong()).toInt()].format(name)
            LogLine(time = java.time.Instant.ofEpochMilli(nowMs - age).toString(), message = message)
        }
    }
}
