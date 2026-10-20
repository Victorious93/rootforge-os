package ai.rootforge.os.desktop

import ai.rootforge.os.bridge.CallerIdentity
import ai.rootforge.os.catalog.BuildCatalogStore
import ai.rootforge.os.core.DigestCheck
import ai.rootforge.os.core.FileInspection
import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.core.TerminalCommand
import ai.rootforge.os.desktopmode.DesktopModeOp
import ai.rootforge.os.fastboot.RebootTarget
import ai.rootforge.os.core.FileDigest
import ai.rootforge.os.core.Library
import ai.rootforge.os.module.BuiltModule
import ai.rootforge.os.module.ModuleSpec
import ai.rootforge.os.recovery.RecoveryCatalogClient
import ai.rootforge.os.recovery.RecoveryFetcher
import ai.rootforge.os.recovery.RecoveryOffer
import ai.rootforge.os.recovery.RecoveryPlanner
import ai.rootforge.os.recovery.RecoveryPayload
import ai.rootforge.os.recovery.RecoveryProbe
import ai.rootforge.os.root.BootImageParser
import ai.rootforge.os.root.RootManager
import ai.rootforge.os.ui.AppActions
import ai.rootforge.os.usb.AttachedTarget
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DesktopActions(
    private val controller: HostController,
    private val settings: DesktopSettings,
    private val buildCatalog: BuildCatalogStore,
    private val scope: CoroutineScope,
    private val onFingerprint: () -> String,
) : AppActions {
    private val log get() = controller.log

    override fun requestUsbPermission(target: AttachedTarget) = controller.requestPermission(target)
    override fun connect(target: AttachedTarget) { scope.launch { controller.connect(target) } }
    override fun disconnect() = controller.disconnect()
    override fun refreshInfo() { scope.launch { controller.refreshInfo() } }

    override fun importFile(then: (LibraryFile) -> Unit) {
        scope.launch {
            val file = withContext(Dispatchers.IO) { pickFile() } ?: return@launch
            runCatching { withContext(Dispatchers.IO) { controller.library.importFile(file) } }
                .onSuccess { log.info("Imported ${it.name}"); then(it) }
                .onFailure { log.error("Import failed: ${it.message}") }
        }
    }

    override fun deleteFile(name: String) {
        if (controller.library.delete(name)) log.info("Removed $name") else log.error("Could not remove $name")
    }

    override fun downloadBuildCatalog() {
        scope.launch {
            log.info("Downloading the Custom RR catalog from raw.githubusercontent.com")
            withContext(Dispatchers.IO) { buildCatalog.download() }
                .onSuccess { log.info(it) }
                .onFailure { log.error("Catalog download failed: ${it.message}") }
        }
    }

    override fun importBuildCatalog() {
        scope.launch {
            val file = withContext(Dispatchers.IO) { pickFile() } ?: return@launch
            withContext(Dispatchers.IO) { buildCatalog.import(file) }
                .onSuccess { log.info(it) }
                .onFailure { log.error("Not imported as a catalog: ${it.message}") }
        }
    }

    override fun clearBuildCatalog() {
        buildCatalog.clear()
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
        scope.launch { if (name == "all") controller.fastbootGetvar("all") else controller.fastbootGetvar(name) }
    }
    override fun fastbootFlash(partition: String, file: LibraryFile) { scope.launch { controller.fastbootFlash(partition, file) } }
    override fun fastbootBoot(file: LibraryFile) { scope.launch { controller.fastbootBootImage(file) } }
    override fun fastbootErase(partition: String) { scope.launch { controller.fastbootErase(partition) } }
    override fun fastbootUnlock() { scope.launch { controller.fastbootUnlock() } }
    override fun fastbootLock() { scope.launch { controller.fastbootLock() } }
    override fun fastbootSlot(slot: String) { scope.launch { controller.fastbootSetSlot(slot) } }
    override fun fastbootReboot(target: RebootTarget) { scope.launch { controller.fastbootReboot(target) } }

    override fun edlUpload(file: LibraryFile) { scope.launch { controller.edlUpload(file) } }
    override fun firehoseConfigure(memory: String) { scope.launch { controller.firehoseConfigure(memory) } }
    override fun firehoseInfo(lun: Int) { scope.launch { controller.firehoseInfo(lun) } }
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

    override fun probeCustomRecovery(then: (List<RecoveryProbe>) -> Unit) {
        scope.launch {
            controller.probeAdbRecovery()
                .onSuccess { then(listOf(it)) }
                .onFailure { log.error("USB target probe failed: ${it.message}"); then(emptyList()) }
        }
    }

    override fun lookupOfficialRecoveries(codenames: List<String>, then: (List<RecoveryOffer>) -> Unit) {
        scope.launch {
            log.info("Looking up TWRP (twrp.me / dl.twrp.me) and PitchBlack (pitchblackrecovery.com / GitHub) for ${codenames.joinToString()}")
            runCatching {
                withContext(Dispatchers.IO) { RecoveryCatalogClient().lookup(codenames) }
            }.onSuccess {
                log.info("Found ${it.size} official recovery file(s).")
                then(it)
            }.onFailure { log.error("Recovery lookup failed: ${it.message}") }
        }
    }

    override fun downloadOfficialRecovery(offer: RecoveryOffer, then: (LibraryFile) -> Unit) {
        val url = offer.safeDownloadUrl() ?: run {
            log.error("Refusing a non-https or off-host download: ${offer.downloadUrl}")
            return
        }
        scope.launch {
            log.info("Downloading ${offer.filename} from ${java.net.URI(url).host}")
            runCatching {
                withContext(Dispatchers.IO) {
                    val dest = controller.library.fileFor(Library.sanitize(offer.filename))
                    RecoveryFetcher.fetchToFile(url, dest, referer = offer.referer)
                    val head = dest.inputStream().use { it.readNBytes(8) }
                    if (RecoveryPlanner.payloadOf(dest.name, head) == RecoveryPayload.OTHER) {
                        dest.delete()
                        error("${offer.filename} is not a recovery.img or zip (the host may have returned an HTML page).")
                    }
                    offer.md5?.let { expected ->
                        val check = FileDigest.verifyMd5(expected, FileDigest.md5Hex(dest))
                        if (!check.ok) { dest.delete(); error(check.message) }
                    }
                    offer.sha256?.let { expected ->
                        val check = FileDigest.verify(expected, FileDigest.sha256Hex(dest))
                        if (!check.ok) { dest.delete(); error(check.message) }
                    }
                    controller.library.refresh()
                    val stored = controller.library.get(dest.name) ?: error("Download was not stored.")
                    log.info("Saved ${stored.name} (SHA-256 ${FileDigest.sha256Hex(stored.file)})")
                    stored
                }
            }.onSuccess { then(it) }.onFailure { log.error("Download failed: ${it.message}") }
        }
    }

    override fun installCustomRecovery(probe: RecoveryProbe, file: LibraryFile, dryRun: Boolean, publishedMd5: String?, publishedSha256: String?) {
        scope.launch { controller.installCustomRecovery(probe, file, dryRun, publishedMd5, publishedSha256) }
    }

    override fun rebootCustomRecovery(probe: RecoveryProbe) {
        scope.launch { controller.rebootCustomRecovery(probe) }
    }

    override fun answerApproval(id: Long, approve: Boolean, typed: String) {
        controller.approvals.answer(id, approve, typed)
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
                if (t.isEmpty()) log.out("No devices (adb/fastboot/edl).") else t.forEach { log.out("${it.serial ?: it.vidPid}  ${it.mode.label}  ${it.displayName}") }
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
        val sel = StringSelection(log.asText())
        Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
        log.info("Log copied to the clipboard.")
    }

    override fun setBridgeEnabled(enabled: Boolean) { settings.bridgeEnabled = enabled }
    override fun setAutoConnect(enabled: Boolean) { settings.autoConnect = enabled }
    override fun setTerminalApp(packageName: String) = Unit
    override fun openTerminalApp() = log.info("Opening another app is an Android-only feature.")
    override fun pinVictorSuite() = Unit
    override fun launchDesktopMode(op: DesktopModeOp) =
        log.info("Desktop mode is an Android + VictorSuite feature. This computer already is the desktop client.")

    override fun resetAdbKey() {
        log.warn("Desktop uses the host adb key (~/.android/adbkey). Remove that file yourself if you want a new identity; this app will not delete it.")
        log.info("Current identity: ${onFingerprint()}")
    }

    override fun approveController(caller: CallerIdentity) {
        log.info("Paired ${caller.packageName} (desktop pairing is the API token; see Settings).")
    }
    override fun dismissController(caller: CallerIdentity) = Unit
    override fun revokeController(packageName: String) = log.info("Revoked $packageName")

    override fun openUrl(url: String) {
        if (!url.startsWith("https://")) return
        runCatching { Desktop.getDesktop().browse(URI(url)) }
            .onFailure { log.warn("Could not open $url: ${it.message}") }
    }

    override fun requestShizukuPermission() = Unit
    override fun refreshShizuku() = Unit
    override fun openShizukuApp() = log.info("Shizuku is an Android-only feature.")
    override fun shizukuListPackages(thirdPartyOnly: Boolean) = Unit
    override fun shizukuPackageInfo(packageName: String) = Unit
    override fun shizukuSettingsGet(namespace: String, key: String) = Unit
    override fun shizukuGetprop(key: String) = Unit
    override fun shizukuListBlockDevices() = Unit
    override fun shizukuDeviceInfo() = Unit

    private fun pickFile(): File? {
        val dlg = FileDialog(null as Frame?, "Import into RootForge library", FileDialog.LOAD)
        dlg.isVisible = true
        val dir = dlg.directory ?: return null
        val name = dlg.file ?: return null
        return File(dir, name)
    }
}
