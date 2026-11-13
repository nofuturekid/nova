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
import io.github.nofuturekid.nova.data.demo.DemoFixtures
import io.github.nofuturekid.nova.data.local.LayoutMode
import io.github.nofuturekid.nova.data.model.ConnectionMode
import io.github.nofuturekid.nova.data.model.LiveMetrics
import io.github.nofuturekid.nova.data.model.LogLine
import io.github.nofuturekid.nova.data.model.NetworkThroughput
import io.github.nofuturekid.nova.data.model.SensorType
import io.github.nofuturekid.nova.data.model.Temperature
import io.github.nofuturekid.nova.data.model.TemperatureStatus
import io.github.nofuturekid.nova.data.model.TemperatureUnit
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
// 432x768 dp at 400 dpi = 1080x1920 px (9:16). Google Play rejects phone
// screenshots whose longer side is more than twice the shorter one, which
// the earlier 20:9 size (1078x2399) broke; F-Droid has no such limit.
@Config(sdk = [35], qualifiers = "w432dp-h768dp-night-400dpi")
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
    // The fictional server lives in main code (DemoFixtures, ADR-0050), the single source
    // for these images and for the in-app demo. The entry is shown as a real one here: the
    // store images show the app as it looks on a server, without the demo marker.

    private val server = DemoFixtures.server.copy(isDemo = false)
    private val info = DemoFixtures.info
    private val array = DemoFixtures.array
    private val containers = DemoFixtures.containers
    private val stats = DemoFixtures.stats
    private val vms = DemoFixtures.vms
    private val notifications = DemoFixtures.notifications

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
