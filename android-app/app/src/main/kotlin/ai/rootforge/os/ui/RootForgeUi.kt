package ai.rootforge.os.ui

import ai.rootforge.os.core.LinkState
import ai.rootforge.os.ui.screens.AdbScreen
import ai.rootforge.os.ui.screens.DashboardScreen
import ai.rootforge.os.ui.screens.EdlScreen
import ai.rootforge.os.ui.screens.FastbootScreen
import ai.rootforge.os.ui.screens.FlashToolsScreen
import ai.rootforge.os.ui.screens.AskScreen
import ai.rootforge.os.ui.screens.GuideScreen
import ai.rootforge.os.ui.screens.LogsScreen
import ai.rootforge.os.ui.screens.ModulesScreen
import ai.rootforge.os.ui.screens.CustomRecoveryScreen
import ai.rootforge.os.ui.screens.RecoveryScreen
import ai.rootforge.os.ui.screens.RootScreen
import ai.rootforge.os.ui.screens.SettingsScreen
import ai.rootforge.os.ui.screens.modeColor
import ai.rootforge.os.ui.theme.ForgeColors
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

enum class Destination(val title: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Filled.Dashboard),
    Ask("Ask", Icons.Filled.QuestionAnswer),
    RootGuide("Root Guide", Icons.AutoMirrored.Filled.MenuBook),
    FlashTools("Flash Tools", Icons.Filled.Build),
    Adb("ADB", Icons.Filled.Android),
    Fastboot("Fastboot", Icons.Filled.FlashOn),
    Edl("EDL", Icons.Filled.Memory),
    Root("Root / Patching", Icons.Filled.Security),
    Modules("Modules", Icons.Filled.Extension),
    Recovery("Recovery", Icons.Filled.SettingsBackupRestore),
    CustomRecovery("Custom Recovery", Icons.Filled.SystemUpdateAlt),
    Logs("Logs / Terminal", Icons.Filled.Terminal),
    Settings("Settings", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootForgeUi(state: AppUiState, actions: AppActions, startAt: Destination = Destination.Dashboard, startExpanded: Boolean = false) {
    var current by rememberSaveable { mutableStateOf(startAt) }
    // Guide page opened from Ask ("id" or "id#anchor"); cleared when the guide is opened from the drawer.
    var guideTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var terminalOpen by rememberSaveable { mutableStateOf(startExpanded) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp).statusBarsPadding()) {
                    Text("RootForge OS", style = MaterialTheme.typography.headlineSmall, color = ForgeColors.Ember)
                    Text("Android root toolkit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Destination.entries.forEach { d ->
                    NavigationDrawerItem(
                        label = { Text(d.title) },
                        icon = { Icon(d.icon, null) },
                        selected = d == current,
                        onClick = { guideTarget = null; current = d; scope.launch { drawer.close() } },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(current.title, style = MaterialTheme.typography.titleLarge)
                            Text(linkSummary(state.link), style = MaterialTheme.typography.labelSmall, color = linkColor(state.link))
                        }
                    },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Menu") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            },
            bottomBar = {
                Column(Modifier.fillMaxWidth()) {
                    ProgressBanner(state.progress, state.busy)
                    if (current != Destination.Logs && current != Destination.RootGuide && current != Destination.Ask) {
                        TerminalPanel(state.log, terminalOpen, { terminalOpen = !terminalOpen }, actions)
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                when (current) {
                    Destination.Dashboard -> DashboardScreen(state, actions)
                    Destination.Ask -> AskScreen(onOpenGuide = { id, anchor ->
                        guideTarget = if (anchor != null) "$id#$anchor" else id
                        current = Destination.RootGuide
                    })
                    Destination.RootGuide -> androidx.compose.runtime.key(guideTarget) { GuideScreen(state, actions, initialPage = guideTarget) }
                    Destination.FlashTools -> FlashToolsScreen(state, actions)
                    Destination.Adb -> AdbScreen(state, actions)
                    Destination.Fastboot -> FastbootScreen(state, actions)
                    Destination.Edl -> EdlScreen(state, actions)
                    Destination.Root -> RootScreen(state, actions)
                    Destination.Modules -> ModulesScreen(state, actions)
                    Destination.Recovery -> RecoveryScreen(state, actions)
                    Destination.CustomRecovery -> CustomRecoveryScreen(state, actions)
                    Destination.Logs -> LogsScreen(state, actions)
                    Destination.Settings -> SettingsScreen(state, actions)
                }
            }
        }
    }

    state.approvals.firstOrNull()?.let { ApprovalDialog(it, actions) }
}

private fun linkSummary(link: LinkState): String = when (link) {
    is LinkState.Connected -> "${link.label} · ${link.mode.label}"
    is LinkState.Connecting -> link.message
    is LinkState.Failed -> "Connection failed"
    LinkState.Disconnected -> "No target connected"
}

@Composable
private fun linkColor(link: LinkState) = when (link) {
    is LinkState.Connected -> modeColor(link.mode)
    is LinkState.Connecting -> ForgeColors.Warn
    is LinkState.Failed -> ForgeColors.Danger
    LinkState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
}
