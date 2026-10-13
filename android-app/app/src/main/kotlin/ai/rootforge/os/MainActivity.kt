package ai.rootforge.os

import ai.rootforge.os.ui.AppUiState
import ai.rootforge.os.ui.RootForgeUi
import ai.rootforge.os.ui.theme.RootForgeTheme
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private var pendingPick: ((Uri?) -> Unit)? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingPick?.invoke(uri)
        pendingPick = null
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as RootForgeApp
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        val actions = AppActionsImpl(
            app,
            pickDocument = { callback ->
                pendingPick = callback
                picker.launch(arrayOf("*/*"))
            },
            startActivity = { startActivity(it) },
        )
        setContent {
            RootForgeTheme {
                RootForgeUi(rememberAppUiState(app), actions)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as RootForgeApp).uiVisible = true
        (application as RootForgeApp).usb.refresh()
    }

    override fun onStop() {
        (application as RootForgeApp).uiVisible = false
        super.onStop()
    }
}

@Composable
private fun rememberAppUiState(app: RootForgeApp): AppUiState {
    val c = app.controller
    val targets = c.targets.collectAsStateWithLifecycle().value
    val link = c.link.collectAsStateWithLifecycle().value
    val info = c.info.collectAsStateWithLifecycle().value
    val busy = c.busy.collectAsStateWithLifecycle().value
    val progress = c.progress.collectAsStateWithLifecycle().value
    val logcat = c.logcatRunning.collectAsStateWithLifecycle().value
    val log = app.log.lines.collectAsStateWithLifecycle().value
    val approvals = app.approvals.pending.collectAsStateWithLifecycle().value
    val library = app.library.files.collectAsStateWithLifecycle().value
    val bridge = app.settings.bridgeEnabledFlow.collectAsStateWithLifecycle().value
    val auto = app.settings.autoConnectFlow.collectAsStateWithLifecycle().value
    val paired = app.pairing.paired.collectAsStateWithLifecycle().value
    val pending = app.pairing.pending.collectAsStateWithLifecycle().value
    val fingerprint = app.adbFingerprint.collectAsStateWithLifecycle().value
    val nodeId = remember { app.settings.nodeId }
    return AppUiState(
        targets = targets, link = link, info = info, busy = busy, progress = progress, library = library,
        logcatRunning = logcat, log = log, approvals = approvals, bridgeEnabled = bridge, autoConnect = auto,
        nodeId = nodeId, paired = paired, pending = pending, adbFingerprint = fingerprint, appVersion = BuildConfig.VERSION_NAME,
    )
}
