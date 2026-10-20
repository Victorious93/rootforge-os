package ai.rootforge.os.desktop

import ai.rootforge.os.bridge.BridgeExecutor
import ai.rootforge.os.bridge.BridgeIngress
import ai.rootforge.os.bridge.FleetExecutor
import ai.rootforge.os.bridge.PairingStore
import ai.rootforge.os.catalog.BuildCatalogStore
import ai.rootforge.os.core.ApprovalCenter
import ai.rootforge.os.core.Library
import ai.rootforge.os.core.OutputLog
import ai.rootforge.os.ui.AppUiState
import ai.rootforge.os.ui.RootForgeUi
import ai.rootforge.os.ui.theme.RootForgeTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val DESKTOP_VERSION = "0.1.0"

fun main(args: Array<String>) {
    val home = File(System.getProperty("user.home"), ".rootforge")
    val settings = DesktopSettings(File(home, "desktop.properties"))
    val log = OutputLog(persistFile = File(home, "command-log.jsonl"))
    val library = Library(File(home, "library"))
    val buildCatalog = BuildCatalogStore(File(home, "build-catalog"))
    val approvals = ApprovalCenter()
    val tools = HostToolset.detect()
    val runner = ProcessRunner()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = HostController(tools, runner, log, library, approvals, scope)
    log.info("RootForge OS desktop $DESKTOP_VERSION")
    log.info(tools.summary())

    val pairing = PairingStore(FilePairingPersistence(File(home, "paired.json")))
    val backend = HostBridgeBackend(controller, DESKTOP_VERSION)
    val headless = args.contains("--headless") || args.contains("--api")
    // Headless auth is the bearer token (HeadlessApi auto-pairs that caller).
    // The Settings switch gates the GUI / Messenger-shaped path only.
    val enabled = { headless || settings.bridgeEnabled }
    val legacy = BridgeExecutor(
        settings.nodeId,
        backend,
        approvals,
        pairing,
        bridgeEnabled = enabled,
    )
    val fleet = FleetExecutor(
        settings.nodeId,
        backend,
        approvals,
        pairing,
        bridgeEnabled = enabled,
    )
    val executor = BridgeIngress(legacy, fleet)

    if (headless) {
        val port = args.portArg() ?: 8787
        val token = System.getenv("ROOTFORGE_API_TOKEN")?.takeIf { it.isNotBlank() } ?: settings.ensureApiToken()
        val bindAll = args.contains("--bind-all")
        if (bindAll) {
            System.err.println("Refusing --bind-all: the API listens on 127.0.0.1 only. Put a reverse proxy in front if you need LAN access.")
        }
        val api = HeadlessApi(executor, pairing, token, approvals, HeadlessApi.loadWebUi())
        val server = api.start(port)
        System.err.println("RootForge OS API on http://127.0.0.1:$port  (Bearer token in ~/.rootforge/desktop.properties or ROOTFORGE_API_TOKEN)")
        System.err.println("node_id=${settings.nodeId}")
        scope.launch {
            while (isActive) {
                controller.scan()
                controller.identifyAttached()
                delay(2_000)
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread { server.stop(0) })
        Thread.currentThread().join()
        return
    }

    application {
        val windowState = rememberWindowState(width = 1100.dp, height = 760.dp)
        val actions = remember {
            DesktopActions(controller, settings, buildCatalog, scope) { "host adb (${tools.adb?.absolutePath ?: "missing"})" }
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = "RootForge OS",
            state = windowState,
        ) {
            RootForgeTheme {
                val ui = rememberDesktopUiState(controller, settings, log, library, buildCatalog, approvals, pairing, tools)
                LaunchedEffect(Unit) {
                    while (true) {
                        controller.scan()
                        controller.identifyAttached()
                        val auto = settings.autoConnect
                        val link = controller.link.value
                        if (auto && link is ai.rootforge.os.core.LinkState.Disconnected) {
                            val first = controller.targets.value.firstOrNull { it.hasPermission && it.mode != ai.rootforge.os.usb.TargetMode.UNKNOWN }
                            if (first != null) controller.connect(first)
                        }
                        delay(2_000)
                    }
                }
                RootForgeUi(ui, actions)
            }
        }
    }
}

@Composable
private fun rememberDesktopUiState(
    controller: HostController,
    settings: DesktopSettings,
    log: OutputLog,
    library: Library,
    buildCatalog: BuildCatalogStore,
    approvals: ApprovalCenter,
    pairing: PairingStore,
    tools: HostToolset,
): AppUiState {
    val targets by controller.targets.collectAsState()
    val link by controller.link.collectAsState()
    val info by controller.info.collectAsState()
    val busy by controller.busy.collectAsState()
    val progress by controller.progress.collectAsState()
    val logcat by controller.logcatRunning.collectAsState()
    val lines by log.lines.collectAsState()
    val pendingApprovals by approvals.pending.collectAsState()
    val files by library.files.collectAsState()
    val catalog by buildCatalog.loaded.collectAsState()
    val identities by controller.identities.collectAsState()
    val serialBusy by controller.serialBusy.collectAsState()
    val bridge by settings.bridgeEnabledFlow.collectAsState()
    val auto by settings.autoConnectFlow.collectAsState()
    val paired by pairing.paired.collectAsState()
    val pending by pairing.pending.collectAsState()
    return AppUiState(
        targets = targets, link = link, info = info, busy = busy, progress = progress, library = files, buildCatalog = catalog,
        logcatRunning = logcat, log = lines, approvals = pendingApprovals, bridgeEnabled = bridge, autoConnect = auto,
        nodeId = settings.nodeId, paired = paired, pending = pending,
        adbFingerprint = tools.adb?.absolutePath ?: "adb not found",
        appVersion = DESKTOP_VERSION,
        hostCaption = LinuxBoard.hostCaption(),
        transportCaption = "Host ${tools.summary()}",
        emptyTargetsHint = "No adb/fastboot device. Plug the target into ${LinuxBoard.hostCaption()}, enable USB debugging, " +
            "or boot it to the bootloader. Install Google platform-tools (or Debian's adb/fastboot) and put them on PATH. " +
            "A Raspberry Pi uses this same Linux host path. On Windows also install the Google USB driver / Qualcomm 9008 driver — see the README.",
        identities = identities,
        serialBusy = serialBusy,
    )
}

private fun Array<String>.portArg(): Int? {
    val i = indexOfFirst { it == "--port" }
    if (i >= 0 && i + 1 < size) return get(i + 1).toIntOrNull()
    return firstOrNull { it.startsWith("--port=") }?.substringAfter("=")?.toIntOrNull()
}
