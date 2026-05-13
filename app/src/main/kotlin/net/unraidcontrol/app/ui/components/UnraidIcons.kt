package net.unraidcontrol.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

/**
 * Maps the prototype's custom `I.*` glyphs to Material Icons.
 * The prototype's inline SVGs and Material's icons are visually equivalent
 * for the cases we use here (1.8 stroke, rounded line caps).
 */
object UC {
    @Composable fun Server(   size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Dns,            size, tint)
    @Composable fun Dashboard(size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Dashboard,      size, tint)
    @Composable fun Disk(     size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Storage,        size, tint)
    @Composable fun Docker(   size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.ViewInAr,       size, tint)
    @Composable fun Vm(       size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Computer,       size, tint)
    @Composable fun Play(     size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.PlayArrow,      size, tint)
    @Composable fun Pause(    size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Pause,          size, tint)
    @Composable fun Stop(     size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Stop,           size, tint)
    @Composable fun Restart(  size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Replay,         size, tint)
    @Composable fun Refresh(  size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Refresh,        size, tint)
    @Composable fun More(     size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.MoreVert,       size, tint)
    @Composable fun Menu(     size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Menu,           size, tint)
    @Composable fun Plus(     size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Add,            size, tint)
    @Composable fun Check(    size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Check,          size, tint)
    @Composable fun X(        size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Cancel,         size, tint)
    @Composable fun ChevD(    size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.KeyboardArrowDown, size, tint)
    @Composable fun ChevR(    size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.AutoMirrored.Rounded.KeyboardArrowRight, size, tint)
    @Composable fun ChevL(    size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.AutoMirrored.Rounded.ArrowBack, size, tint)
    @Composable fun Search(   size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Search,         size, tint)
    @Composable fun Settings( size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Settings,       size, tint)
    @Composable fun Cpu(      size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Bolt,           size, tint)
    @Composable fun Ram(      size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Memory,         size, tint)
    @Composable fun Network(  size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.NetworkCheck,   size, tint)
    @Composable fun Shield(   size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Security,       size, tint)
    @Composable fun Alert(    size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Warning,        size, tint)
    @Composable fun Thermo(   size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Thermostat,     size, tint)
    @Composable fun Power(    size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.PowerSettingsNew, size, tint)
    @Composable fun Link(     size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Link,           size, tint)
    @Composable fun Eye(      size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Visibility,     size, tint)
    @Composable fun EyeOff(   size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.VisibilityOff,  size, tint)
    @Composable fun Copy(     size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.ContentCopy,    size, tint)
    @Composable fun Terminal( size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Terminal,       size, tint)
    @Composable fun Folder(   size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Folder,         size, tint)
    @Composable fun Info(     size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Info,           size, tint)
    @Composable fun Trash(    size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Delete,         size, tint)
    @Composable fun Edit(     size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Edit,           size, tint)
    @Composable fun Lock(     size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Lock,           size, tint)
    @Composable fun Wifi(     size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Wifi,           size, tint)
    @Composable fun Cloud(    size: Dp = 20.dp, tint: Color = Color.Unspecified) = Ico(Icons.Outlined.Cloud,          size, tint)
}

@Composable
private fun Ico(
    image: androidx.compose.ui.graphics.vector.ImageVector,
    size: Dp,
    tint: Color,
) {
    Icon(
        imageVector = image,
        contentDescription = null,
        modifier = Modifier.size(size),
        tint = tint,
    )
}
