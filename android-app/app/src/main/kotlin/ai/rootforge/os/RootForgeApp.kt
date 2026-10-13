package ai.rootforge.os

import ai.rootforge.os.adb.AdbKeyStore
import ai.rootforge.os.bridge.BridgeExecutor
import ai.rootforge.os.bridge.ControllerBackend
import ai.rootforge.os.bridge.PairingPersistence
import ai.rootforge.os.bridge.PairingStore
import ai.rootforge.os.core.AppSettings
import ai.rootforge.os.core.ApprovalCenter
import ai.rootforge.os.core.ApprovalRequest
import ai.rootforge.os.core.Library
import ai.rootforge.os.core.LinkState
import ai.rootforge.os.core.OutputLog
import ai.rootforge.os.core.TargetController
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
    val log = OutputLog()
    val settings by lazy { AppSettings(getSharedPreferences("settings", MODE_PRIVATE)) }
    val library by lazy { Library(File(filesDir, "library")) }
    val usb by lazy { UsbHost(this) }
    val keyStore by lazy { AdbKeyStore(File(noBackupFilesDir, "adb")) }
    val approvals = ApprovalCenter(onNewRequest = { notifyApproval(it) })
    val controller by lazy { TargetController(usb, log, library, approvals, keyStore, appScope) }
    val pairing by lazy {
        val prefs = getSharedPreferences("pairing", MODE_PRIVATE)
        PairingStore(object : PairingPersistence {
            override fun read(): String = prefs.getString("paired_controllers", "[]") ?: "[]"
            override fun write(json: String) = prefs.edit().putString("paired_controllers", json).apply()
        })
    }
    val bridge by lazy { BridgeExecutor(settings.nodeId, ControllerBackend(controller, BuildConfig.VERSION_NAME), approvals, pairing) }

    val adbFingerprint = kotlinx.coroutines.flow.MutableStateFlow("")

    @Volatile var uiVisible = false
    private var lastAutoKey: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        usb.start()
        refreshFingerprint()
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
                description = "A controller app is asking to run a command on the target"
            },
        )
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
    }
}
