package net.unraidcontrol.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.unraidcontrol.app.data.model.Server
import net.unraidcontrol.app.ui.screens.main.MainScreen
import net.unraidcontrol.app.ui.screens.server.AddEditServerSheet
import net.unraidcontrol.app.ui.screens.server.ServerListScreen
import net.unraidcontrol.app.ui.screens.settings.AboutLibrariesScreen
import net.unraidcontrol.app.ui.screens.settings.SettingsScreen

object Routes {
    const val Main       = "main"
    const val ServerList = "server_list"
    const val Settings   = "settings"
    const val AboutLibraries = "about_libraries"
}

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    var sheetServer by remember { mutableStateOf<SheetTarget?>(null) }

    NavHost(navController = navController, startDestination = Routes.Main) {
        composable(Routes.Main) {
            MainScreen(
                onOpenServerList = { navController.navigate(Routes.ServerList) },
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onAddServer = { sheetServer = SheetTarget.New },
            )
        }
        composable(Routes.ServerList) {
            ServerListScreen(
                onBack = { navController.popBackStack() },
                onAdd = { sheetServer = SheetTarget.New },
                onEdit = { sheetServer = SheetTarget.Edit(it) },
            )
        }
        composable(Routes.Settings) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenAboutLibraries = { navController.navigate(Routes.AboutLibraries) },
            )
        }
        composable(Routes.AboutLibraries) {
            AboutLibrariesScreen(onBack = { navController.popBackStack() })
        }
    }

    when (val target = sheetServer) {
        SheetTarget.New -> AddEditServerSheet(
            server = null,
            onDismiss = { sheetServer = null },
            onSaved = { sheetServer = null },
            onDeleted = { sheetServer = null },
        )
        is SheetTarget.Edit -> AddEditServerSheet(
            server = target.server,
            onDismiss = { sheetServer = null },
            onSaved = { sheetServer = null },
            onDeleted = { sheetServer = null },
        )
        null -> Unit
    }
}

private sealed interface SheetTarget {
    data object New : SheetTarget
    data class Edit(val server: Server) : SheetTarget
}
