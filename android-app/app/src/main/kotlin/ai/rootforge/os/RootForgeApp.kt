package ai.rootforge.os

import ai.rootforge.os.adb.AdbKeyStore
import ai.rootforge.os.bridge.BridgeExecutor
import ai.rootforge.os.bridge.BridgeIngress
import ai.rootforge.os.bridge.CallerIdentity
import ai.rootforge.os.bridge.ControllerBackend
import ai.rootforge.os.bridge.FleetExecutor
import ai.rootforge.os.bridge.PairingPersistence
import ai.rootforge.os.bridge.ProbingNodeStatusSource
import ai.rootforge.os.bridge.PairingStore
import ai.rootforge.os.catalog.BuildCatalogStore
import ai.rootforge.os.core.AppSettings
import ai.rootforge.os.core.ApprovalCenter
import ai.rootforge.os.core.ApprovalRequest
import ai.rootforge.os.core.Library
import ai.rootforge.os.core.LinkState
import ai.rootforge.os.core.OutputLog
import ai.rootforge.os.core.TargetController
import ai.rootforge.os.desktopmode.AndroidCompanionLookup
import ai.rootforge.os.desktopmode.DesktopMode
import ai.rootforge.os.root.RootManager
import ai.rootforge.os.shizuku.AndroidShizukuHost
import ai.rootforge.os.shizuku.BoundPrivilegedShell
import ai.rootforge.os.shizuku.PrivilegedShell
import ai.rootforge.os.shizuku.ShizukuManager
import ai.rootforge.os.shizuku.ShizukuState
import ai.rootforge.os.usb.TargetMode
import ai.rootforge.os.usb.UsbHost
import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Wires the long-lived pieces once so the UI, the USB receiver and the bridge service share one controller. */
class RootForgeApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val log by lazy { OutputLog(persistFile = File(filesDir, "command-log.jsonl")) }
    val settings by lazy { AppSettings(getSharedPreferences("settings", MODE_PRIVATE)) }
    val library by lazy { Library(File(filesDir, "library")) }
    val buildCatalog by lazy { BuildCatalogStore(File(filesDir, "build-catalog")) }
    val usb by lazy { UsbHost(this) }
    val keyStore by lazy { AdbKeyStore(File(noBackupFilesDir, "adb")) }
    val approvals = ApprovalCenter(onNewRequest = { notifyApproval(it) })
    val controller by lazy {
        TargetController(
            usb, log, library, approvals, keyStore, appScope,
            localPackages = { installedManagerPackages() },
            canRunLocalSu = true,
        )
    }
    val pairing by lazy {
        val prefs = getSharedPreferences("pairing", MODE_PRIVATE)
        PairingStore(object : PairingPersistence {
            override fun read(): String = prefs.getString("paired_controllers", "[]") ?: "[]"
            override fun write(json: String) = prefs.edit().putString("paired_controllers", json).apply()
        })
    }
    val shizukuHost by lazy { AndroidShizukuHost(this) }
    val shizukuManager by lazy { ShizukuManager(shizukuHost) }
    val privilegedShell: PrivilegedShell by lazy { BoundPrivilegedShell(this, shizukuManager) }
    val bridge by lazy {
        val backend = ControllerBackend(controller, BuildConfig.VERSION_NAME)
        val enabled = { settings.bridgeEnabled }
        BridgeIngress(
            BridgeExecutor(settings.nodeId, backend, approvals, pairing, bridgeEnabled = enabled),
            FleetExecutor(
                settings.nodeId,
                backend,
                approvals,
                pairing,
                bridgeEnabled = enabled,
                privileged = privilegedShell,
                statusSource = ProbingNodeStatusSource(
                    packageName = packageName,
                    installedManagers = { installedManagerPackages() },
                ),
            ),
        )
    }
    val shizukuState = kotlinx.coroutines.flow.MutableStateFlow(ShizukuState.NOT_INSTALLED)
    val shizukuBusy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val shizukuResult = kotlinx.coroutines.flow.MutableStateFlow("")

    val adbFingerprint = kotlinx.coroutines.flow.MutableStateFlow("")
    val desktopMode = kotlinx.coroutines.flow.MutableStateFlow(
        DesktopMode.probe(emptyList(), pinned = null),
    )
    val companionLookup by lazy { AndroidCompanionLookup(packageManager) }

    @Volatile var uiVisible = false
    private var lastAutoKey: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        usb.start()
        shizukuManager.addListener { shizukuState.value = it }
        shizukuManager.start()
        refreshFingerprint()
        refreshDesktopMode()
        watchPendingPairing()
        appScope.launch {
            usb.targets.collect { list ->
                controller.onTargetsChanged(list)
                autoConnect(list)
            }
        }
    }

    fun refreshFingerprint() {
        appScope.launch(Dispatchers.IO) { adbFingerprint.value = keyStore.loadOrCreate().fingerprint() }
    }

    private fun installedManagerPackages(): Set<String> {
        val pm = packageManager
        return RootManager.entries.map { it.packageName }.filter { name ->
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(name, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(name, 0)
                }
            }.isSuccess
        }.toSet()
    }

    fun refreshDesktopMode() {
        val view = runCatching {
            DesktopMode.probe(companionLookup.installed(), settings.victorSuitePin)
        }.getOrElse { DesktopMode.probe(emptyList(), settings.victorSuitePin) }
        desktopMode.value = view
    }

    private fun autoConnect(list: List<ai.rootforge.os.usb.AttachedTarget>) {
        if (!settings.autoConnect || controller.link.value !is LinkState.Disconnected) return
        val candidate = list.firstOrNull { it.mode != TargetMode.UNKNOWN && it.hasPermission } ?: return
        val key = "${candidate.deviceName}/${candidate.mode}"
        if (key == lastAutoKey) return
        lastAutoKey = key
        appScope.launch { controller.connect(candidate) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVALS, "Approval requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A controller app is asking to pair or run a command on the target"
            },
        )
    }

    private fun watchPendingPairing() {
        appScope.launch {
            var lastSize = pairing.pending.value.size
            pairing.pending.collect { list ->
                if (list.size > lastSize) notifyPendingPairing(list.last())
                lastSize = list.size
            }
        }
    }

    private fun notifyPendingPairing(caller: CallerIdentity) {
        if (uiVisible) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Controller waiting to pair")
            .setContentText("${caller.packageName} asked to control this node. Open Settings to approve.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFY_PENDING_PAIR, n)
    }

    private fun notifyApproval(request: ApprovalRequest) {
        if (uiVisible) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(request.title)
            .setContentText("Requested by ${request.source}. Tap to review.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        getSystemService(NotificationManager::class.java).notify(request.id.toInt(), n)
    }

    private companion object {
        const val CHANNEL_APPROVALS = "approvals"
        const val NOTIFY_PENDING_PAIR = 0x50616972 // 'Pair'
    }
}
