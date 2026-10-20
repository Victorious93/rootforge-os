package ai.rootforge.os.desktop

import ai.rootforge.os.bridge.BridgeBackend
import ai.rootforge.os.bridge.BridgeDevice
import ai.rootforge.os.core.LinkState
import ai.rootforge.os.device.publicLabel
import ai.rootforge.os.fastboot.RebootTarget
import ai.rootforge.os.module.ModuleSpec
import ai.rootforge.os.usb.TargetMode

class HostBridgeBackend(private val controller: HostController, override val appVersion: String) : BridgeBackend {
    override val nodeKind: String = "desktop-host"
    override val usbHost: Boolean = false
    override fun devices(): List<BridgeDevice> {
        val linked = controller.link.value as? LinkState.Connected
        return controller.targets.value.map { t ->
            BridgeDevice(
                serial = t.serial ?: t.deviceName,
                mode = when (t.mode) {
                    TargetMode.ADB -> "adb"; TargetMode.FASTBOOT -> "fastboot"; TargetMode.EDL -> "edl"; TargetMode.UNKNOWN -> "unknown"
                },
                state = if (linked != null && linked.mode == t.mode) "connected" else if (t.hasPermission) "authorized" else "unauthorized",
                usable = t.mode != TargetMode.UNKNOWN && t.hasPermission,
                note = t.publicLabel(),
            )
        }
    }

    override suspend fun deviceInfo(): Result<Map<String, String>> {
        val info = controller.info.value ?: return Result.failure(IllegalStateException("No target is connected."))
        return Result.success(info.rows.toMap())
    }

    override fun activeSerial(): String? = controller.activeSerial()

    override fun transportEpoch(): Long = controller.transportEpoch()

    override suspend fun adbShell(command: String, boundEpoch: Long?) = controller.adbShell(command, boundEpoch)

    override suspend fun adbReboot(target: String, boundEpoch: Long?) = controller.adbReboot(target, boundEpoch)

    override suspend fun fastbootReboot(target: String, boundEpoch: Long?): Result<Unit> {
        val t = RebootTarget.entries.firstOrNull { it.name.equals(target, true) || (target == "fastbootd" && it == RebootTarget.FASTBOOTD) }
            ?: return Result.failure(IllegalArgumentException("unknown reboot target"))
        return controller.fastbootReboot(t, boundEpoch)
    }

    override suspend fun moduleBuild(spec: ModuleSpec) =
        controller.buildModule(spec).map { it.fileName to it.sha256 }

    override suspend fun fastbootFlash(partition: String, imageName: String, source: String): Result<Unit> {
        val image = controller.library.get(imageName)
            ?: return Result.failure(IllegalArgumentException("'$imageName' is not in the library; import it in RootForge OS first."))
        return controller.fastbootFlash(partition, image, source)
    }

    override suspend fun fastbootUnlock(source: String) = controller.fastbootUnlock(source)
    override suspend fun fastbootLock(source: String) = controller.fastbootLock(source)
    override suspend fun fastbootSetSlot(slot: String, source: String) = controller.fastbootSetSlot(slot, source)
}
