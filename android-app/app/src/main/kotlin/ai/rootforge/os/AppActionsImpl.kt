package ai.rootforge.os

import ai.rootforge.os.bridge.CallerIdentity
import ai.rootforge.os.core.DigestCheck
import ai.rootforge.os.core.FileInspection
import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.core.importUri
import ai.rootforge.os.core.TerminalCommand
import ai.rootforge.os.fastboot.RebootTarget
import ai.rootforge.os.module.BuiltModule
import ai.rootforge.os.module.ModuleSpec
import ai.rootforge.os.root.BootImageParser
import ai.rootforge.os.root.RootManager
import ai.rootforge.os.ui.AppActions
import ai.rootforge.os.usb.AttachedTarget
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Binds the UI's action surface to the real controller. Each action runs in the app scope, off the main thread. */
class AppActionsImpl(
    private val app: RootForgeApp,
    private val pickDocument: ((Uri?) -> Unit) -> Unit,
    private val startActivity: (Intent) -> Unit,
) : AppActions {
    private val controller get() = app.controller
    private val scope get() = app.appScope
    private val log get() = app.log

    override fun requestUsbPermission(target: AttachedTarget) = controller.requestPermission(target)
    override fun connect(target: AttachedTarget) { scope.launch { controller.connect(target) } }
    override fun disconnect() = controller.disconnect()
    override fun refreshInfo() { scope.launch { controller.refreshInfo() } }

    override fun importFile(then: (LibraryFile) -> Unit) {
        pickDocument { uri ->
            if (uri == null) return@pickDocument
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { app.library.importUri(app, uri) } }
                    .onSuccess { log.info("Imported ${it.name}"); then(it) }
                    .onFailure { log.error("Import failed: ${it.message}") }
            }
        }
    }

    override fun deleteFile(name: String) {
        if (app.library.delete(name)) log.info("Removed $name") else log.error("Could not remove $name")
    }

    override fun downloadBuildCatalog() {
        scope.launch {
            log.info("Downloading the Custom RR catalog from raw.githubusercontent.com")
            withContext(Dispatchers.IO) { app.buildCatalog.download() }
                .onSuccess { log.info(it) }
                .onFailure { log.error("Catalog download failed: ${it.message}") }
        }
    }

    override fun importBuildCatalog() {
        importFile { picked ->
            scope.launch {
                withContext(Dispatchers.IO) { app.buildCatalog.import(picked.file) }
                    .onSuccess { log.info(it) }
                    .onFailure { log.error("Not imported as a catalog: ${it.message}") }
                // The library copy was only a staging step; the catalog is stored separately.
                app.library.delete(picked.name)
            }
        }
    }

    override fun clearBuildCatalog() {
        app.buildCatalog.clear()
        log.info("Removed the stored build catalog")
    }

    override fun adbShell(command: String) { scope.launch { controller.adbShell(command) } }
    override fun adbReboot(target: String) { scope.launch { controller.adbReboot(target) } }
    override fun adbInstall(file: LibraryFile) { scope.launch { controller.adbInstall(file) } }
    override fun adbPush(file: LibraryFile, remotePath: String) { scope.launch { controller.adbPush(file, remotePath) } }
    override fun adbPull(remotePath: String, saveAs: String) { scope.launch { controller.adbPull(remotePath, saveAs) } }
    override fun adbSideload(file: LibraryFile) { scope.launch { controller.adbSideload(file) } }
    override fun startLogcat(filter: String) = controller.startLogcat(filter)
    override fun stopLogcat() = controller.stopLogcat()

    override fun fastbootGetvar(name: String) {
        scope.launch { if (name == "all") controller.fastbootCommand("getvar:all") else controller.fastbootCommand("getvar:$name") }
    }
    override fun fastbootFlash(partition: String, file: LibraryFile) { scope.launch { controller.fastbootFlash(partition, file) } }
    override fun fastbootBoot(file: LibraryFile) { scope.launch { controller.fastbootBootImage(file) } }
    override fun fastbootErase(partition: String) { scope.launch { controller.fastbootErase(partition) } }
    override fun fastbootUnlock() { scope.launch { controller.fastbootUnlock() } }
    override fun fastbootLock() { scope.launch { controller.fastbootLock() } }
    override fun fastbootSlot(slot: String) { scope.launch { controller.fastbootSetSlot(slot) } }
    override fun fastbootReboot(target: RebootTarget) { scope.launch { controller.fastbootReboot(target) } }

    override fun edlUpload(file: LibraryFile) { scope.launch { controller.edlUploadProgrammer(file) } }
    override fun firehoseConfigure(memory: String) { scope.launch { controller.firehoseConfigure(memory) } }
    override fun firehoseInfo(lun: Int) { scope.launch { controller.firehoseStorageInfo(lun) } }
    override fun firehoseProgram(lun: Int, startSector: Long, sectorSize: Int, file: LibraryFile) {
        scope.launch { controller.firehoseProgram(lun, startSector, sectorSize, file) }
    }
    override fun firehoseReset() { scope.launch { controller.firehoseReset() } }

    override fun inspectBoot(file: LibraryFile): BootImageParser.Result =
        runCatching { controller.inspectBootImage(file) }.getOrElse { BootImageParser.Result.Invalid("Could not read the file: ${it.message}") }

    override fun stageBoot(file: LibraryFile) { scope.launch { controller.stageBootImage(file) } }
    override fun launchManager(manager: RootManager) { scope.launch { controller.launchManager(manager) } }
    override fun fetchPatched(manager: RootManager, then: (LibraryFile) -> Unit) {
        scope.launch {
            val name = controller.findPatchedImage(manager).getOrNull() ?: return@launch
            controller.pullPatchedImage(name).onSuccess { then(it) }
        }
    }

    override fun buildModule(spec: ModuleSpec, then: (BuiltModule) -> Unit) {
        scope.launch { controller.buildModule(spec).onSuccess { then(it) } }
    }
    override fun installModule(file: LibraryFile, manager: RootManager) { scope.launch { controller.installModuleOnTarget(file, manager) } }

    override fun answerApproval(id: Long, approve: Boolean, typed: String) {
        app.approvals.answer(id, approve, typed)
    }

    override fun scanUsb() = controller.scanUsb()
    override fun adbDeviceCheck() { scope.launch { controller.adbDeviceCheck() } }
    override fun adbListPackages(thirdPartyOnly: Boolean) { scope.launch { controller.adbListPackages(thirdPartyOnly) } }
    override fun adbScreenInfo() { scope.launch { controller.adbScreenInfo() } }
    override fun adbScreenshot() { scope.launch { controller.adbScreenshot() } }
    override fun fastbootPartitionInfo() { scope.launch { controller.fastbootPartitionInfo() } }
    override fun inspectLibraryFile(file: LibraryFile): FileInspection = controller.inspectLibraryFile(file)
    override fun verifySha256(file: LibraryFile, expectedHex: String): DigestCheck = controller.verifyLibrarySha256(file, expectedHex)

    override fun runTerminal(line: String) {
        val cmd = TerminalCommand.parse(line) ?: return
        if (cmd is TerminalCommand.Help || cmd is TerminalCommand.Devices || cmd is TerminalCommand.Refused) log.command(line.trim())
        when (cmd) {
            TerminalCommand.Help -> log.out(TerminalCommand.HELP)
            TerminalCommand.Clear -> log.clear()
            TerminalCommand.Devices -> {
                val t = controller.targets.value
                if (t.isEmpty()) log.out("No USB devices attached.") else t.forEach { log.out("${it.vidPid}  ${it.mode.label}  ${it.displayName}") }
            }
            is TerminalCommand.Refused -> log.warn(cmd.reason)
            is TerminalCommand.AdbShell -> adbShell(cmd.command)
            is TerminalCommand.AdbReboot -> adbReboot(cmd.target)
            is TerminalCommand.FastbootGetvar -> fastbootGetvar(cmd.name)
            is TerminalCommand.FastbootReboot -> fastbootReboot(cmd.target)
            is TerminalCommand.FastbootErase -> fastbootErase(cmd.partition)
            is TerminalCommand.FastbootSlot -> fastbootSlot(cmd.slot)
            TerminalCommand.FastbootUnlock -> fastbootUnlock()
            TerminalCommand.FastbootLock -> fastbootLock()
        }
    }

    override fun clearLog() = log.clear()

    override fun shareLog() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log.asText())
        startActivity(Intent.createChooser(send, "Share RootForge log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun setBridgeEnabled(enabled: Boolean) { app.settings.bridgeEnabled = enabled }
    override fun setAutoConnect(enabled: Boolean) { app.settings.autoConnect = enabled }

    override fun resetAdbKey() {
        scope.launch(Dispatchers.IO) {
            app.keyStore.reset()
            log.info("Generated a new ADB key. Targets will ask you to authorise this phone again.")
            app.refreshFingerprint()
        }
    }

    override fun approveController(caller: CallerIdentity) {
        app.pairing.approve(caller)
        log.info("Paired ${caller.packageName}")
    }

    override fun dismissController(caller: CallerIdentity) = app.pairing.dismiss(caller)
    override fun revokeController(packageName: String) {
        app.pairing.revoke(packageName)
        log.info("Revoked $packageName")
    }

    override fun openUrl(url: String) {
        if (!url.startsWith("https://")) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            log.warn("No browser available to open $url")
        }
    }
}
