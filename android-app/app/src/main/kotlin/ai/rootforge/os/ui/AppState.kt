package ai.rootforge.os.ui

import ai.rootforge.os.bridge.CallerIdentity
import ai.rootforge.os.bridge.PairedController
import ai.rootforge.os.core.ApprovalRequest
import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.core.LinkState
import ai.rootforge.os.core.LogLine
import ai.rootforge.os.core.Progress
import ai.rootforge.os.core.TargetInfo
import ai.rootforge.os.fastboot.RebootTarget
import ai.rootforge.os.module.BuiltModule
import ai.rootforge.os.module.ModuleSpec
import ai.rootforge.os.root.BootImageParser
import ai.rootforge.os.root.RootManager
import ai.rootforge.os.usb.AttachedTarget

/** Everything the screens render. Plain data so screens can be previewed and screenshot-tested. */
data class AppUiState(
    val targets: List<AttachedTarget> = emptyList(),
    val link: LinkState = LinkState.Disconnected,
    val info: TargetInfo? = null,
    val busy: String? = null,
    val progress: Progress? = null,
    val library: List<LibraryFile> = emptyList(),
    val logcatRunning: Boolean = false,
    val log: List<LogLine> = emptyList(),
    val approvals: List<ApprovalRequest> = emptyList(),
    val bridgeEnabled: Boolean = false,
    val autoConnect: Boolean = true,
    val nodeId: String = "",
    val paired: List<PairedController> = emptyList(),
    val pending: List<CallerIdentity> = emptyList(),
    val adbFingerprint: String = "",
    val appVersion: String = "",
)

/** Every user-triggerable action. The real implementation talks to the target; the preview one does nothing. */
interface AppActions {
    fun requestUsbPermission(target: AttachedTarget)
    fun connect(target: AttachedTarget)
    fun disconnect()
    fun refreshInfo()

    /** Opens the system file picker and copies the choice into the app library. */
    fun importFile(then: (LibraryFile) -> Unit = {})
    fun deleteFile(name: String)

    fun adbShell(command: String)
    fun adbReboot(target: String)
    fun adbInstall(file: LibraryFile)
    fun adbPush(file: LibraryFile, remotePath: String)
    fun adbPull(remotePath: String, saveAs: String)
    fun adbSideload(file: LibraryFile)
    fun startLogcat(filter: String)
    fun stopLogcat()

    fun fastbootGetvar(name: String)
    fun fastbootFlash(partition: String, file: LibraryFile)
    fun fastbootBoot(file: LibraryFile)
    fun fastbootErase(partition: String)
    fun fastbootUnlock()
    fun fastbootLock()
    fun fastbootSlot(slot: String)
    fun fastbootReboot(target: RebootTarget)

    fun edlUpload(file: LibraryFile)
    fun firehoseConfigure(memory: String)
    fun firehoseInfo(lun: Int)
    fun firehoseProgram(lun: Int, startSector: Long, sectorSize: Int, file: LibraryFile)
    fun firehoseReset()

    fun inspectBoot(file: LibraryFile): BootImageParser.Result
    fun stageBoot(file: LibraryFile)
    fun launchManager(manager: RootManager)
    fun fetchPatched(manager: RootManager, then: (LibraryFile) -> Unit)

    fun buildModule(spec: ModuleSpec, then: (BuiltModule) -> Unit)
    fun installModule(file: LibraryFile, manager: RootManager)

    fun answerApproval(id: Long, approve: Boolean, typed: String)

    fun runTerminal(line: String)
    fun clearLog()
    fun shareLog()

    fun setBridgeEnabled(enabled: Boolean)
    fun setAutoConnect(enabled: Boolean)
    fun resetAdbKey()
    fun approveController(caller: CallerIdentity)
    fun dismissController(caller: CallerIdentity)
    fun revokeController(packageName: String)
    fun openUrl(url: String)
}

object NoopActions : AppActions {
    override fun requestUsbPermission(target: AttachedTarget) = Unit
    override fun connect(target: AttachedTarget) = Unit
    override fun disconnect() = Unit
    override fun refreshInfo() = Unit
    override fun importFile(then: (LibraryFile) -> Unit) = Unit
    override fun deleteFile(name: String) = Unit
    override fun adbShell(command: String) = Unit
    override fun adbReboot(target: String) = Unit
    override fun adbInstall(file: LibraryFile) = Unit
    override fun adbPush(file: LibraryFile, remotePath: String) = Unit
    override fun adbPull(remotePath: String, saveAs: String) = Unit
    override fun adbSideload(file: LibraryFile) = Unit
    override fun startLogcat(filter: String) = Unit
    override fun stopLogcat() = Unit
    override fun fastbootGetvar(name: String) = Unit
    override fun fastbootFlash(partition: String, file: LibraryFile) = Unit
    override fun fastbootBoot(file: LibraryFile) = Unit
    override fun fastbootErase(partition: String) = Unit
    override fun fastbootUnlock() = Unit
    override fun fastbootLock() = Unit
    override fun fastbootSlot(slot: String) = Unit
    override fun fastbootReboot(target: RebootTarget) = Unit
    override fun edlUpload(file: LibraryFile) = Unit
    override fun firehoseConfigure(memory: String) = Unit
    override fun firehoseInfo(lun: Int) = Unit
    override fun firehoseProgram(lun: Int, startSector: Long, sectorSize: Int, file: LibraryFile) = Unit
    override fun firehoseReset() = Unit
    override fun inspectBoot(file: LibraryFile) = BootImageParser.Result.Invalid("preview")
    override fun stageBoot(file: LibraryFile) = Unit
    override fun launchManager(manager: RootManager) = Unit
    override fun fetchPatched(manager: RootManager, then: (LibraryFile) -> Unit) = Unit
    override fun buildModule(spec: ModuleSpec, then: (BuiltModule) -> Unit) = Unit
    override fun installModule(file: LibraryFile, manager: RootManager) = Unit
    override fun answerApproval(id: Long, approve: Boolean, typed: String) = Unit
    override fun runTerminal(line: String) = Unit
    override fun clearLog() = Unit
    override fun shareLog() = Unit
    override fun setBridgeEnabled(enabled: Boolean) = Unit
    override fun setAutoConnect(enabled: Boolean) = Unit
    override fun resetAdbKey() = Unit
    override fun approveController(caller: CallerIdentity) = Unit
    override fun dismissController(caller: CallerIdentity) = Unit
    override fun revokeController(packageName: String) = Unit
    override fun openUrl(url: String) = Unit
}
