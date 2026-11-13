package io.github.nofuturekid.nova.ui.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ColumnScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.nofuturekid.nova.data.local.LayoutMode
import io.github.nofuturekid.nova.data.model.ArrayInfo
import io.github.nofuturekid.nova.data.model.ArrayState
import io.github.nofuturekid.nova.data.model.ConnectionMode
import io.github.nofuturekid.nova.data.model.Container
import io.github.nofuturekid.nova.data.model.ContainerLiveStats
import io.github.nofuturekid.nova.data.model.ContainerStatus
import io.github.nofuturekid.nova.data.model.ContainerUpdateStatus
import io.github.nofuturekid.nova.data.model.Disk
import io.github.nofuturekid.nova.data.model.DiskStatus
import io.github.nofuturekid.nova.data.model.DiskType
import io.github.nofuturekid.nova.data.model.LiveMetrics
import io.github.nofuturekid.nova.data.model.LogLine
import io.github.nofuturekid.nova.data.model.NetworkThroughput
import io.github.nofuturekid.nova.data.model.NotifImportance
import io.github.nofuturekid.nova.data.model.NotifType
import io.github.nofuturekid.nova.data.model.Notifications
import io.github.nofuturekid.nova.data.model.ParityCheck
import io.github.nofuturekid.nova.data.model.Server
import io.github.nofuturekid.nova.data.model.ServerInfo
import io.github.nofuturekid.nova.data.model.SensorType
import io.github.nofuturekid.nova.data.model.Temperature
import io.github.nofuturekid.nova.data.model.TemperatureStatus
import io.github.nofuturekid.nova.data.model.TemperatureUnit
import io.github.nofuturekid.nova.data.model.UnraidNotification
import io.github.nofuturekid.nova.data.model.Vm
import io.github.nofuturekid.nova.data.model.VmState
import io.github.nofuturekid.nova.data.repository.DomainState
import io.github.nofuturekid.nova.ui.screens.array.ArrayTab
import io.github.nofuturekid.nova.ui.screens.container.ContainerDetailSheet
import io.github.nofuturekid.nova.ui.screens.docker.DockerTab
import io.github.nofuturekid.nova.ui.screens.main.MainTab
import io.github.nofuturekid.nova.ui.screens.main.TabBar
import io.github.nofuturekid.nova.ui.screens.main.TopBar
import io.github.nofuturekid.nova.ui.screens.notifications.NotificationsSheet
import io.github.nofuturekid.nova.ui.screens.overview.OverviewTab
import io.github.nofuturekid.nova.ui.screens.vms.VmsTab
import io.github.nofuturekid.nova.ui.theme.ThemeMode
import io.github.nofuturekid.nova.ui.theme.UnraidTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the F-Droid / store phone screenshots from the app's REAL
 * composables and theme, fed with a fictional demo server — no phone, no
 * network. Excluded from the normal `test` task; run with
 * `-PnovaScreenshots` (see docs/local-build.md). Output goes to
 * fastlane/metadata/android/en-US/images/phoneScreenshots/N.png.
 *
 * Demo data uses only RFC 5737 addresses and generic public container
 * names — nothing here belongs to a real server.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 411x731 dp at 420 dpi = 1078x1918 px (9:16). Google Play rejects phone
// screenshots whose longer side is more than twice the shorter one, which
// the earlier 20:9 size (1078x2399) broke; F-Droid has no such limit.
@Config(sdk = [35], qualifiers = "w411dp-h731dp-night-420dpi")
class StoreScreenshots {

    @get:Rule
    val rule = createComposeRule()

    private val outDir = System.getProperty("nova.screenshots.dir")
        ?: error("run with -PnovaScreenshots")

    /**
     * Screenshot of the screen with a bottom sheet open. The sheet lives in
     * its own dialog window and Robolectric renders that window over an
     * empty backdrop, hiding the screen behind it. So: capture the screen
     * first (sheet closed), then with the sheet open, and compose the two
     * the way a device does: the screen dimmed by the Material 3 scrim
     * (black, 32 %) wherever the sheet capture shows only that backdrop,
     * the sheet capture everywhere else.
     */
    private fun captureWithSheet(path: String, showSheet: androidx.compose.runtime.MutableState<Boolean>) {
        fun shot(): java.awt.image.BufferedImage {
            val tmp = java.io.File.createTempFile("shot", ".png")
            try {
                captureScreenRoboImage(tmp.absolutePath)
                return javax.imageio.ImageIO.read(tmp)
            } finally { tmp.delete() }
        }
        systemBars.value = false
        rule.waitForIdle()
        val screen = shot()
        systemBars.value = true
        rule.waitForIdle()
        val bars = shot()
        systemBars.value = false
        showSheet.value = true
        rule.waitForIdle()
        val withSheet = shot()
        val backdrop = withSheet.getRGB(0, 0)
        for (y in 0 until withSheet.height) for (x in 0 until withSheet.width) {
            if (withSheet.getRGB(x, y) == backdrop) {
                val c = screen.getRGB(x, y)
                fun dim(v: Int) = (v * 0.68).toInt()
                withSheet.setRGB(x, y, (dim(c shr 16 and 0xFF) shl 16) or (dim(c shr 8 and 0xFF) shl 8) or dim(c and 0xFF))
            }
        }
        // On a device the system bars sit above every window, scrim included:
        // lay the status-bar glyphs and the gesture pill (the pixels that
        // differ between the two screen captures) over the composite undimmed.
        for (y in 0 until withSheet.height) for (x in 0 until withSheet.width) {
            if (bars.getRGB(x, y) != screen.getRGB(x, y)) withSheet.setRGB(x, y, bars.getRGB(x, y))
        }
        javax.imageio.ImageIO.write(withSheet, "png", java.io.File(path))
    }

    /** Whether Chrome draws the status-bar glyphs and gesture pill. */
    private val systemBars = mutableStateOf(true)

    private fun out(n: Int) = "$outDir/$n.png"

    // ── Demo data ────────────────────────────────────────────────────

    private val server = Server(
        id = "demo",
        name = "Tower",
        hostname = "tower.example.net",
        localUrl = "http://192.0.2.10",
        remoteUrl = "https://tower.example.net",
    )

    private val info = ServerInfo(
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

    private fun disk(
        name: String, device: String, type: DiskType, size: Double, used: Double,
        temp: Int, model: String, spinning: Boolean = true, rotational: Boolean = true,
    ) = Disk(
        name = name, device = device, type = type, sizeTb = size, usedTb = used,
        tempC = temp, status = DiskStatus.Ok, model = model, isSpinning = spinning,
        rotational = rotational, numErrors = 0, warningC = null, criticalC = null,
    )

    private val disks = listOf(
        disk("parity", "sda", DiskType.Parity, 16.0, 0.0, 36, "WDC WD161KRYZ-01AGBB0"),
        disk("disk1", "sdb", DiskType.Data, 16.0, 11.2, 38, "WDC WD161KRYZ-01AGBB0"),
        disk("disk2", "sdc", DiskType.Data, 12.0, 8.4, 40, "ST12000VN0008-2YS101"),
        disk("disk3", "sdd", DiskType.Data, 12.0, 6.1, 0, "ST12000VN0008-2YS101", spinning = false),
        disk("disk4", "sde", DiskType.Data, 8.0, 7.3, 37, "WDC WD80EFAX-68LHPN0"),
        disk("cache", "nvme0n1", DiskType.Cache, 1.0, 0.46, 41, "Samsung SSD 980 PRO 1TB", rotational = false),
        disk("cache2", "nvme1n1", DiskType.Cache, 1.0, 0.46, 39, "Samsung SSD 980 PRO 1TB", rotational = false),
    )

    private val array = ArrayInfo(
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

    private val containers = listOf(
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

    private val stats = mapOf(
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

    private val vms = listOf(
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

    private val unreadNotes = listOf(
        note("n1", "Warning [TOWER] - Disk 3 temperature", "Disk 3 is at 47 C (ST12000VN0008-2YS101_ZV70XXXX)", NotifImportance.Warning, "Today 09:14"),
        note("n2", "Update available for nextcloud", "A newer image of nextcloud is available.", NotifImportance.Info, "Today 06:00"),
        note("n3", "Notice [TOWER] - Parity check finished", "Parity check finished (0 errors). Duration: 11 hours, 2 minutes.", NotifImportance.Info, "Yesterday 17:42"),
        note("n4", "Alert [TOWER] - Docker image disk utilization", "Docker image disk utilization is at 71%.", NotifImportance.Alert, "Yesterday 08:03"),
    )

    private val notifications = Notifications(
        unreadWarning = 1, unreadAlert = 1, unreadInfo = 2, unreadTotal = 4,
        items = unreadNotes.filter { it.importance != NotifImportance.Info },
        unread = unreadNotes,
        archived = listOf(
            note("a1", "Notice [TOWER] - Array turned good", "Array has 0 disks with read errors.", NotifImportance.Info, "Mon 21:10", NotifType.Archive),
        ),
    )

    // Demo-mode-style system bars, drawn over the app's own background the
    // way a device does edge-to-edge (Robolectric renders no system UI).
    // Heights match the insets the app already reserves around its content.

    @Composable
    private fun StatusBar() {
        val ink = UnraidTheme.colors.text
        Row(
            Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (systemBars.value) {
                Text("12:00", color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (icon in listOf(Icons.Filled.Wifi, Icons.Filled.SignalCellularAlt, Icons.Filled.BatteryFull)) {
                        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun GestureBar() {
        Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.BottomCenter) {
            if (systemBars.value) {
                Box(
                    Modifier.padding(bottom = 8.dp).width(108.dp).height(4.dp)
                        .background(UnraidTheme.colors.text, RoundedCornerShape(2.dp)),
                )
            }
        }
    }

    // ── Chrome: the app's real TopBar + TabBar around a tab ──────────

    @Composable
    private fun Chrome(tab: MainTab, content: @Composable ColumnScope.() -> Unit) {
        UnraidTheme(themeMode = ThemeMode.Dark) {
            val bg = io.github.nofuturekid.nova.ui.theme.UnraidTheme.colors.bg
            Column(Modifier.fillMaxSize().background(bg)) {
                StatusBar() // status-bar area (edge-to-edge in the app)
                TopBar(
                    server = server,
                    connection = ConnectionMode.Local,
                    state = DomainState.Content(Unit),
                    onOpenServerList = {},
                    onMenu = {},
                    onToggleConnection = {},
                    notificationBadge = 4,
                    onOpenNotifications = {},
                )
                Box(Modifier.weight(1f)) { Column(Modifier.fillMaxSize(), content = content) }
                TabBar(active = tab, onChange = {})
                GestureBar() // gesture-bar area
            }
        }
    }

    private fun content() = DomainState.Content(Unit)

    // ── Screenshots ──────────────────────────────────────────────────

    @Test
    fun overview() {
        val metrics = mutableStateOf(LiveMetrics(14.0, 31.3, 11.4, 4.2))
        val net = mutableStateOf(NetworkThroughput(2_400_000.0, 900_000.0))
        val temp = mutableStateOf(Temperature(true, 46.0, 38.0, TemperatureUnit.Celsius, TemperatureStatus.Normal))
        rule.setContent {
            Chrome(MainTab.Overview) {
                OverviewTab(
                    infoState = DomainState.Content(info),
                    metricsState = DomainState.Content(metrics.value),
                    arrayState = DomainState.Content(array),
                    dockerState = DomainState.Content(containers),
                    vmsState = DomainState.Content(vms),
                    server = server,
                    onAddServer = {},
                    networkThroughput = net.value,
                    temperature = temp.value,
                    cpuWarnC = 70, cpuCritC = 85,
                )
            }
        }
        // Feed a deterministic history so the sparklines have shape.
        for (i in 0 until 40) {
            val w = Math.sin(i / 3.0)
            metrics.value = LiveMetrics(16.0 + 9 * w + (i % 5), 31.3, 11.0 + 1.4 * Math.sin(i / 5.0) + 0.03 * i, 4.2)
            net.value = NetworkThroughput(2_000_000.0 + 1_400_000 * Math.sin(i / 2.5) + 40_000 * (i % 7), 700_000.0 + 400_000 * Math.cos(i / 3.0))
            temp.value = Temperature(true, 44.0 + 3 * w + (i % 3), 37.0 + 1.5 * Math.cos(i / 4.0), TemperatureUnit.Celsius, TemperatureStatus.Normal)
            rule.waitForIdle()
        }
        captureScreenRoboImage(out(1))
    }

    @Test
    fun array() {
        rule.setContent {
            Chrome(MainTab.Array) {
                ArrayTab(
                    state = DomainState.Content(array),
                    view = LayoutMode.List,
                    onAddServer = {}, onStartArray = {}, onStopArray = {},
                    onStartParity = {}, onPauseParity = {}, onResumeParity = {}, onCancelParity = {},
                    globalDiskWarnC = 45, globalDiskCritC = 55,
                )
            }
        }
        rule.waitForIdle()
        captureScreenRoboImage(out(2))
    }

    @Test
    fun docker() {
        rule.setContent {
            Chrome(MainTab.Docker) {
                DockerTab(
                    state = DomainState.Content(containers, "http://192.0.2.10"),
                    view = LayoutMode.List,
                    query = "", onQueryChange = {},
                    onAddServer = {},
                    stats = stats,
                    onOpenContainer = {}, onStart = {}, onRestart = {}, onStop = {}, onUpdateAll = {},
                )
            }
        }
        rule.waitForIdle()
        captureScreenRoboImage(out(3))
    }

    @Test
    fun vms() {
        rule.setContent {
            Chrome(MainTab.Vms) {
                VmsTab(
                    state = DomainState.Content(vms),
                    view = LayoutMode.List,
                    onAddServer = {}, onStart = {}, onResume = {}, onPause = {}, onOpenVm = {},
                )
            }
        }
        rule.waitForIdle()
        captureScreenRoboImage(out(4))
    }

    @Test
    fun containerDetail() {
        val showSheet = mutableStateOf(false)
        rule.setContent {
            Chrome(MainTab.Docker) {
                DockerTab(
                    state = DomainState.Content(containers, "http://192.0.2.10"),
                    view = LayoutMode.List, query = "", onQueryChange = {}, onAddServer = {}, stats = stats,
                    onOpenContainer = {}, onStart = {}, onRestart = {}, onStop = {}, onUpdateAll = {},
                )
            }
            if (showSheet.value) UnraidTheme(themeMode = ThemeMode.Dark) {
                ContainerDetailSheet(
                    container = containers[1],
                    serverBaseUrl = "http://192.0.2.10",
                    liveStats = stats["nextcloud"],
                    isUpdating = false,
                    onFetchLogs = { emptyList<LogLine>() },
                    onRefresh = {}, onDismiss = {},
                    onStart = {}, onRestart = {}, onStop = {}, onUpdate = {},
                )
            }
        }
        captureWithSheet(out(5), showSheet)
    }

    @Test
    fun notificationsSheet() {
        val showSheet = mutableStateOf(false)
        rule.setContent {
            Chrome(MainTab.Overview) {
                OverviewTab(
                    infoState = DomainState.Content(info),
                    metricsState = DomainState.Content(LiveMetrics(18.0, 31.3, 11.4, 4.2)),
                    arrayState = DomainState.Content(array),
                    dockerState = DomainState.Content(containers),
                    vmsState = DomainState.Content(vms),
                    server = server, onAddServer = {},
                )
            }
            if (showSheet.value) UnraidTheme(themeMode = ThemeMode.Dark) {
                NotificationsSheet(
                    state = DomainState.Content(notifications),
                    onDismiss = {}, onArchive = {}, onUnread = {}, onArchiveAll = {},
                    onDeleteAllArchivedRequest = {}, onDeleteRequest = {},
                )
            }
        }
        captureWithSheet(out(6), showSheet)
    }
}
