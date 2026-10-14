package ai.rootforge.os.bridge

import ai.rootforge.os.core.TargetController
import ai.rootforge.os.fastboot.RebootTarget
import ai.rootforge.os.module.ModuleSpec
import ai.rootforge.os.usb.TargetMode

/** Adapts the real [TargetController] to the narrow surface the bridge may use. */
class ControllerBackend(private val controller: TargetController, override val appVersion: String) : BridgeBackend {

    override fun devices(): List<BridgeDevice> {
        val linked = controller.link.value as? ai.rootforge.os.core.LinkState.Connected
        return controller.targets.value.map { t ->
            BridgeDevice(
                serial = t.serial ?: t.vidPid,
                mode = when (t.mode) {
                    TargetMode.ADB -> "adb"; TargetMode.FASTBOOT -> "fastboot"; TargetMode.EDL -> "edl"; TargetMode.UNKNOWN -> "unknown"
                },
                state = if (linked != null && linked.mode == t.mode) "connected" else if (t.hasPermission) "authorized" else "needs_usb_permission",
                usable = t.mode != TargetMode.UNKNOWN && t.hasPermission,
                note = "${t.vidPid} ${t.displayName}".trim(),
            )
        }
    }

    override suspend fun deviceInfo(): Result<Map<String, String>> {
        val info = controller.info.value ?: return Result.failure(IllegalStateException("No target is connected in the app."))
        return Result.success(info.rows.toMap())
    }

    override suspend fun adbShell(command: String) =
        controller.adbShell(command).map { it.output to it.exitCode }

    override suspend fun adbReboot(target: String) = controller.adbReboot(target)

    override suspend fun fastbootReboot(target: String): Result<Unit> {
        val t = RebootTarget.entries.firstOrNull { it.name.equals(target, true) || (target == "fastbootd" && it == RebootTarget.FASTBOOTD) }
            ?: return Result.failure(IllegalArgumentException("unknown reboot target"))
        return controller.fastbootReboot(t)
    }

    override suspend fun moduleBuild(spec: ModuleSpec) =
        controller.buildModule(spec).map { it.fileName to it.sha256 }

    override suspend fun fastbootFlash(partition: String, imageName: String, source: String): Result<Unit> {
        val image = controller.library.get(imageName)
            ?: return Result.failure(IllegalArgumentException("'$imageName' is not in the app library; import it in RootForge OS first."))
        return controller.fastbootFlash(partition, image, source)
    }

    override suspend fun fastbootUnlock(source: String) = controller.fastbootUnlock(source)
    override suspend fun fastbootLock(source: String) = controller.fastbootLock(source)
    override suspend fun fastbootSetSlot(slot: String, source: String) = controller.fastbootSetSlot(slot, source)
}
