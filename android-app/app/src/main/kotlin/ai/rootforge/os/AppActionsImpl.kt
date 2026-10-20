package ai.rootforge.os

import ai.rootforge.os.bridge.CallerIdentity
import ai.rootforge.os.core.DigestCheck
import ai.rootforge.os.core.FileInspection
import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.core.TerminalApp
import ai.rootforge.os.core.importUri
import ai.rootforge.os.shizuku.PrivilegedCommands
import ai.rootforge.os.shizuku.PrivilegedResult
import ai.rootforge.os.shizuku.ShizukuCopy
import ai.rootforge.os.shizuku.ShizukuPackages
import ai.rootforge.os.core.TerminalCommand
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
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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

    override fun probeCustomRecovery(then: (List<RecoveryProbe>) -> Unit) {
        scope.launch {
            val out = mutableListOf<RecoveryProbe>()
            runCatching { withContext(Dispatchers.IO) { controller.probeThisPhone() } }
                .onSuccess { out += it }
                .onFailure { log.error("This-phone probe failed: ${it.message}") }
            controller.probeAdbRecovery().onSuccess { out += it }
            then(out)
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
                    val dest = app.library.fileFor(Library.sanitize(offer.filename))
                    RecoveryFetcher.fetchToFile(url, dest, referer = offer.referer)
                    val head = dest.inputStream().use { it.readNBytes(8) }
                    val payload = RecoveryPlanner.payloadOf(dest.name, head)
                    if (payload == RecoveryPayload.OTHER) {
                        dest.delete()
                        error("${offer.filename} is not a recovery.img or zip (the host may have returned an HTML page).")
                    }
                    offer.md5?.let { expected ->
                        val check = FileDigest.verifyMd5(expected, FileDigest.md5Hex(dest))
                        if (!check.ok) {
                            dest.delete()
                            error(check.message)
                        }
                    }
                    offer.sha256?.let { expected ->
                        val check = FileDigest.verify(expected, FileDigest.sha256Hex(dest))
                        if (!check.ok) {
                            dest.delete()
                            error(check.message)
                        }
                    }
                    app.library.refresh()
                    val stored = app.library.get(dest.name) ?: error("Download was not stored.")
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

    override fun setTerminalApp(packageName: String) {
        val name = packageName.trim()
        if (name.isNotEmpty() && !TerminalApp.isValidPackage(name)) {
            log.warn("'$name' is not a valid Android package name; terminal app not changed.")
            return
        }
        app.settings.terminalApp = name
    }

    override fun openTerminalApp() {
        val name = app.settings.terminalApp
        if (!TerminalApp.isValidPackage(name)) {
            log.warn("Set the terminal app package in Settings first.")
            return
        }
        val launch = app.packageManager.getLaunchIntentForPackage(name)
        if (launch == null) {
            log.warn("$name is not installed or has no launcher screen.")
            return
        }
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

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

    override fun requestShizukuPermission() = app.shizukuManager.requestPermission()
    override fun refreshShizuku() = app.shizukuManager.refresh()

    override fun openShizukuApp() {
        val launch = app.packageManager.getLaunchIntentForPackage(ShizukuPackages.SHIZUKU)
        if (launch == null) {
            log.warn("Shizuku is not installed. Opening the download page.")
            openUrl(ShizukuCopy.DOWNLOAD)
            return
        }
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            log.warn("Could not open Shizuku.")
        }
    }

    override fun shizukuListPackages(thirdPartyOnly: Boolean) {
        val argv = PrivilegedCommands.listPackages(thirdPartyOnly)
        val label = if (thirdPartyOnly) "list user packages" else "list packages"
        runPrivileged(label, argv) { app.privilegedShell.exec(argv) }
    }

    override fun shizukuPackageInfo(packageName: String) {
        val argv = PrivilegedCommands.packageInfo(packageName)
        if (argv == null) {
            app.shizukuResult.value = "Refused: not a plausible package name."
            return
        }
        runPrivileged("package info", argv) { app.privilegedShell.exec(argv) }
    }

    override fun shizukuSettingsGet(namespace: String, key: String) {
        val argv = PrivilegedCommands.settingsGet(namespace, key)
        if (argv == null) {
            app.shizukuResult.value = "Refused: namespace must be system, secure or global, and the key must be a simple token."
            return
        }
        runPrivileged("settings get", argv) { app.privilegedShell.exec(argv) }
    }

    override fun shizukuGetprop(key: String) {
        val argv = PrivilegedCommands.getprop(key)
        if (argv == null) {
            app.shizukuResult.value = "Refused: not a plausible property name."
            return
        }
        runPrivileged("getprop", argv) { app.privilegedShell.exec(argv) }
    }

    override fun shizukuListBlockDevices() {
        val commands = PrivilegedCommands.blockDeviceCommands()
        runPrivileged("list block devices", commands.first(), extraLines = commands.drop(1).map { it.joinToString(" ") }) {
            val chunks = commands.map { argv ->
                val header = argv.joinToString(" ")
                when (val result = app.privilegedShell.exec(argv)) {
                    is PrivilegedResult.Failure -> "$header\nERROR ${result.reason}"
                    is PrivilegedResult.Success -> "$header\n${display(result)}"
                }
            }
            PrivilegedResult.Success(0, chunks.joinToString("\n\n"), "", timedOut = false)
        }
    }

    override fun shizukuDeviceInfo() {
        runPrivileged("device info", listOf("readDeviceState"), extraLines = listOf("Fixed snapshot: id + a few build properties. No caller-supplied command.")) {
            app.privilegedShell.readDeviceState()
        }
    }

    /**
     * Fleet interop: `pm grant`. Not on [ai.rootforge.os.ui.AppActions] and
     * not shown in Settings. Requires a typed `GRANT PERMISSION`.
     */
    fun shizukuPmGrant(packageName: String, permission: String) {
        runPrivileged(
            title = "pm grant",
            argv = listOf("pm", "grant", packageName, permission),
            extraLines = listOf("This writes a runtime permission on this phone."),
            phrase = "GRANT PERMISSION",
        ) {
            app.privilegedShell.pmGrant(packageName, permission)
        }
    }

    private val shizukuRunning = AtomicBoolean(false)

    private fun runPrivileged(
        title: String,
        argv: List<String>,
        extraLines: List<String> = emptyList(),
        phrase: String? = null,
        op: () -> PrivilegedResult,
    ) {
        scope.launch {
            if (!shizukuRunning.compareAndSet(false, true)) {
                log.warn("A Shizuku lookup is already running.")
                return@launch
            }
            app.shizukuBusy.value = true
            try {
                val lines = listOf(
                    "This runs a privileged command on this phone via Shizuku/Sui.",
                    "Command: ${argv.joinToString(" ")}",
                ) + extraLines + listOf("Output stays on the Settings card and is not copied into the command log.")
                val ok = app.approvals.request(
                    title = "Shizuku: $title",
                    lines = lines,
                    source = "Shizuku",
                    phrase = phrase,
                )
                if (!ok) {
                    app.shizukuResult.value = "Not confirmed; nothing ran."
                    log.info("Shizuku: $title was not confirmed")
                    return@launch
                }
                if (!app.privilegedShell.isAvailable()) {
                    app.shizukuResult.value = ShizukuCopy.UNAVAILABLE_FAILURE
                    log.warn("Shizuku: $title skipped (not connected)")
                    return@launch
                }
                log.info("Shizuku: $title (this phone)")
                val result = withContext(Dispatchers.IO) { op() }
                app.shizukuResult.value = display(result)
                when (result) {
                    is PrivilegedResult.Success -> log.info("Shizuku: $title finished")
                    is PrivilegedResult.Failure -> log.warn("Shizuku: $title failed")
                }
            } finally {
                shizukuRunning.set(false)
                app.shizukuBusy.value = false
            }
        }
    }

    private fun display(result: PrivilegedResult): String = when (result) {
        is PrivilegedResult.Success -> buildString {
            append(result.stdout)
            if (result.stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(result.stderr)
            }
            if (result.timedOut) {
                if (isNotEmpty()) append('\n')
                append("(timed out)")
            }
            if (isBlank()) append(if (result.exitCode == 0) "(no output)" else "exit ${result.exitCode}")
        }
        is PrivilegedResult.Failure -> result.reason
    }
}
