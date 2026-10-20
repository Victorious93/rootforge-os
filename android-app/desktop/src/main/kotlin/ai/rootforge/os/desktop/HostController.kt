package ai.rootforge.os.desktop

import ai.rootforge.os.core.ApprovalCenter
import ai.rootforge.os.core.DigestCheck
import ai.rootforge.os.core.FileDigest
import ai.rootforge.os.core.FileInspection
import ai.rootforge.os.core.FileInspector
import ai.rootforge.os.core.Library
import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.core.LinkState
import ai.rootforge.os.core.OutputLog
import ai.rootforge.os.core.Progress
import ai.rootforge.os.core.TargetInfo
import ai.rootforge.os.core.humanSize
import ai.rootforge.os.device.DeviceIdentityParser
import ai.rootforge.os.device.DeviceRoster
import ai.rootforge.os.device.SerialMask
import ai.rootforge.os.device.SerialOpLock
import ai.rootforge.os.device.publicLabel
import ai.rootforge.os.device.rosterKey
import ai.rootforge.os.fastboot.FlashPlanner
import ai.rootforge.os.fastboot.FlashSafety
import ai.rootforge.os.fastboot.RebootTarget
import ai.rootforge.os.fastboot.TargetIdentity
import ai.rootforge.os.usb.UsbScanFilter
import ai.rootforge.os.host.HostDevices
import ai.rootforge.os.module.BuiltModule
import ai.rootforge.os.recovery.RecoveryCommands
import ai.rootforge.os.recovery.RecoveryInstallResult
import ai.rootforge.os.recovery.RecoveryInstaller
import ai.rootforge.os.recovery.RecoveryProbe
import ai.rootforge.os.recovery.RecoverySource
import ai.rootforge.os.recovery.RootSession
import ai.rootforge.os.recovery.RootShellResult
import ai.rootforge.os.module.ModuleBuilder
import ai.rootforge.os.module.ModuleSpec
import ai.rootforge.os.root.BootImageInfo
import ai.rootforge.os.root.BootImageParser
import ai.rootforge.os.root.RootManager
import ai.rootforge.os.root.RootPaths
import ai.rootforge.os.usb.AttachedTarget
import ai.rootforge.os.usb.TargetMode
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Desktop controller: same plan / typed-confirmation contract as the Android
 * [ai.rootforge.os.core.TargetController], but the transport is the host
 * `adb` / `fastboot` / `edl` binaries rather than USB Host bulk endpoints.
 */
class HostController(
    private val tools: HostToolset,
    private val runner: ProcessRunner,
    val log: OutputLog,
    val library: Library,
    val approvals: ApprovalCenter,
    private val scope: CoroutineScope,
) {
    private val targetState = MutableStateFlow<List<AttachedTarget>>(emptyList())
    val targets: StateFlow<List<AttachedTarget>> = targetState.asStateFlow()
    private val linkState = MutableStateFlow<LinkState>(LinkState.Disconnected)
    val link: StateFlow<LinkState> = linkState.asStateFlow()
    private val infoState = MutableStateFlow<TargetInfo?>(null)
    val info: StateFlow<TargetInfo?> = infoState.asStateFlow()
    private val busyState = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = busyState.asStateFlow()
    private val progressState = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = progressState.asStateFlow()
    private val logcatState = MutableStateFlow(false)
    val logcatRunning: StateFlow<Boolean> = logcatState.asStateFlow()
    private val identityState = MutableStateFlow<Map<String, ai.rootforge.os.device.DeviceIdentity>>(emptyMap())
    val identities: StateFlow<Map<String, ai.rootforge.os.device.DeviceIdentity>> = identityState.asStateFlow()
    private val serialLock = SerialOpLock()
    val serialBusy: StateFlow<Map<String, String>> = serialLock.busyMap

    private var active: AttachedTarget? = null
    private var logcatJob: Job? = null
    @Volatile private var logcatStop = false

    val currentMode: TargetMode? get() = (linkState.value as? LinkState.Connected)?.mode

    fun scan() {
        val adbOut = tools.adb?.let { runner.run(it, listOf("devices", "-l"), 15_000) }?.output.orEmpty()
        val fbOut = tools.fastboot?.let { runner.run(it, listOf("devices"), 15_000) }?.output.orEmpty()
        val lsusb = if (HostToolset.isWindows()) "" else runCatching {
            runner.run(File("/usr/bin/lsusb").takeIf { it.isFile } ?: File("/bin/lsusb"), emptyList(), 5_000).output
        }.getOrDefault("")
        targetState.value = HostDevices.merge(
            HostDevices.parseAdb(adbOut),
            HostDevices.parseFastboot(fbOut),
            HostDevices.parseLsusbEdl(lsusb),
        )
        val current = targetState.value
        val act = active
        if (act != null && current.none { it.serial == act.serial && it.mode == act.mode }) {
            log.warn("The target disconnected (or changed mode).")
            disconnect()
        }
        identityState.value = DeviceRoster.prune(identityState.value, current)
    }

    fun scanUsb() {
        scan()
        val result = UsbScanFilter.partition(targetState.value)
        log.command("usb scan")
        val serials = knownSerials()
        UsbScanFilter.describe(result).forEach { line ->
            val masked = SerialMask.replaceKnown(line, serials)
            if (line.startsWith("Ignored ") || line.startsWith("No ADB")) log.info(masked) else log.out(masked)
        }
        scope.launch { identifyAttached(force = true) }
    }

    fun inspectLibraryFile(file: LibraryFile): FileInspection {
        val head = file.file.inputStream().use { it.readNBytes(4096) }
        val sha = FileDigest.sha256Hex(file.file)
        return FileInspector.inspect(file.name, file.size, head, sha, FlashSafety.loadSidecar(library, file.name))
    }

    fun verifyLibrarySha256(file: LibraryFile, expectedHex: String): DigestCheck {
        val check = FileDigest.verify(expectedHex, FileDigest.sha256Hex(file.file))
        if (check.ok) log.info("${file.name}: ${check.message}") else log.error("${file.name}: ${check.message}")
        return check
    }

    internal fun currentIdentity(fastbootVars: Map<String, String>? = null): TargetIdentity =
        TargetIdentity.merge(
            fastbootVars?.let { TargetIdentity.fromFastboot(it) },
            infoState.value?.let { TargetIdentity.fromTargetInfo(it) },
        )

    fun requestPermission(target: AttachedTarget) {
        if (!target.hasPermission) {
            log.warn("adb reports ${target.publicLabel()} as unauthorized. Unlock the target and allow this computer.")
        }
    }

    fun disconnect() {
        logcatStop = true
        active = null
        infoState.value = null
        linkState.value = LinkState.Disconnected
    }

    suspend fun connect(target: AttachedTarget): Result<Unit> {
        if (!target.hasPermission) {
            requestPermission(target)
            return Result.failure(IOException("Target ${target.publicLabel()} is unauthorized or offline."))
        }
        val key = target.rosterKey()
        val activeKey = active?.rosterKey()
        if (!activeKey.isNullOrEmpty() && activeKey != key && serialLock.isBusy(activeKey)) {
            val msg = "Finish the current action on ${SerialMask.mask(activeKey)} first."
            log.error(msg)
            return Result.failure(IOException(msg))
        }
        return operationOn(key, "Connecting to ${target.publicLabel()}") {
            disconnect()
            linkState.value = LinkState.Connecting("Talking to ${target.publicLabel()}")
            try {
                active = target
                when (target.mode) {
                    TargetMode.ADB -> {
                        requireAdb()
                        loadAdbInfo(target)
                        linkState.value = LinkState.Connected(TargetMode.ADB, target.publicLabel())
                    }
                    TargetMode.FASTBOOT -> {
                        val vars = getvarAll(target.serial ?: target.deviceName)
                        val identity = DeviceIdentityParser.fromFastboot(vars, target)
                        publishIdentity(identity)
                        infoState.value = identity.toTargetInfo()
                        linkState.value = LinkState.Connected(TargetMode.FASTBOOT, target.publicLabel())
                    }
                    TargetMode.EDL -> {
                        val identity = DeviceIdentityParser.fromEdl(target)
                        publishIdentity(identity)
                        infoState.value = identity.toTargetInfo()
                        linkState.value = LinkState.Connected(TargetMode.EDL, target.publicLabel())
                        log.info("EDL on desktop is identify-only here. The host `edl` binary is not invoked from Devices.")
                    }
                    TargetMode.UNKNOWN -> throw IOException("${target.publicLabel()} is not an ADB, fastboot or EDL device.")
                }
            } catch (e: Exception) {
                disconnect()
                linkState.value = LinkState.Failed(e.message ?: e.javaClass.simpleName)
                throw e
            }
        }
    }

    suspend fun refreshInfo(): Result<Unit> = operation("Refreshing device info") {
        val target = active ?: return@operation
        when (currentMode) {
            TargetMode.ADB -> loadAdbInfo(target)
            TargetMode.FASTBOOT -> {
                val identity = DeviceIdentityParser.fromFastboot(getvarAll(), target)
                publishIdentity(identity)
                infoState.value = identity.toTargetInfo()
            }
            else -> Unit
        }
    }

    suspend fun adbShell(command: String): Result<Pair<String, Int?>> = operation("adb shell") {
        log.command("adb shell $command")
        val r = adb(listOf("shell", command), 120_000)
        log.out(r.output.take(64 * 1024))
        if (!r.ok) log.warn("exit status ${r.exit}")
        r.output to r.exit
    }

    suspend fun adbReboot(target: String): Result<Unit> = operation("Reboot ${target.ifBlank { "system" }}") {
        val args = if (target.isBlank()) listOf("reboot") else listOf("reboot", target)
        log.command("adb ${args.joinToString(" ")}")
        adb(args, 30_000)
        log.info("Reboot requested; the target will reappear in a moment.")
    }

    suspend fun adbInstall(apk: LibraryFile): Result<String> = operation("Install ${apk.name}") {
        log.command("adb install -r ${apk.name}")
        val r = adb(listOf("install", "-r", apk.file.absolutePath), 300_000)
        log.out(r.output)
        if (!r.output.contains("Success") && !r.ok) throw IOException("adb install failed")
        r.output.trim()
    }

    suspend fun adbPush(file: LibraryFile, remotePath: String): Result<Unit> = operation("Push ${file.name}") {
        require(remotePath.startsWith("/")) { "Remote path must be absolute" }
        log.command("adb push ${file.name} $remotePath")
        val r = adb(listOf("push", file.file.absolutePath, remotePath), 300_000)
        log.out(r.output)
        if (!r.ok) throw IOException(r.output.ifBlank { "adb push failed" })
    }

    suspend fun adbPull(remotePath: String, saveAs: String): Result<LibraryFile> = operation("Pull $remotePath") {
        pullToLibrary(remotePath, saveAs)
    }

    suspend fun adbDeviceCheck(): Result<Unit> = operation("Device check") {
        loadAdbInfo(active ?: throw IOException("Not connected."))
        log.command("adb device check")
        infoState.value?.rows?.forEach { (k, v) -> log.out("$k: $v") }
    }

    suspend fun adbListPackages(thirdPartyOnly: Boolean = false): Result<String> = operation("List packages") {
        val args = if (thirdPartyOnly) listOf("shell", "pm", "list", "packages", "-3") else listOf("shell", "pm", "list", "packages")
        log.command("adb ${args.drop(1).joinToString(" ")}")
        val r = adb(args, 60_000)
        log.out(r.output)
        r.output
    }

    suspend fun adbScreenInfo(): Result<Unit> = operation("Screen info") {
        log.command("adb shell wm size")
        log.out(adb(listOf("shell", "wm", "size"), 15_000).output)
        log.command("adb shell wm density")
        log.out(adb(listOf("shell", "wm", "density"), 15_000).output)
    }

    suspend fun adbScreenshot(): Result<LibraryFile> = operation("Screenshot") {
        val remote = "/sdcard/Download/rf-screencap.png"
        log.command("adb shell screencap -p $remote")
        val cap = adb(listOf("shell", "screencap", "-p", remote), 30_000)
        log.out(cap.output)
        if (!cap.ok) throw IOException(cap.output.ifBlank { "screencap failed" })
        pullToLibrary(remote, "rf-screencap.png")
    }

    suspend fun adbSideload(zip: LibraryFile): Result<Unit> = operation("Sideload ${zip.name}") {
        log.command("adb sideload ${zip.name}")
        val r = adb(listOf("sideload", zip.file.absolutePath), 600_000)
        log.out(r.output)
        if (!r.ok) throw IOException(r.output.ifBlank { "adb sideload failed" })
    }

    fun startLogcat(filter: String) {
        val exe = tools.adb ?: run { log.error("adb not found on PATH / ANDROID_HOME."); return }
        val serial = activeSerial() ?: run { log.error("Connect to a target over ADB first."); return }
        if (logcatState.value) return
        logcatStop = false
        logcatState.value = true
        logcatJob = scope.launch(Dispatchers.IO) {
            try {
                serialLock.withLock(serial, "Logcat") {
                    busyState.value = "Logcat"
                    val args = listOf("-s", serial, "logcat", "-v", "threadtime") + filter.split(" ").filter { it.isNotBlank() }
                    log.command("adb ${args.joinToString(" ")}")
                    try {
                        val proc = ProcessBuilder(listOf(exe.absolutePath) + args).start()
                        proc.outputStream.close()
                        proc.inputStream.bufferedReader().useLines { lines ->
                            for (line in lines) {
                                if (logcatStop) break
                                logMasked(line)
                            }
                        }
                        proc.destroyForcibly()
                    } catch (e: Exception) {
                        log.error("logcat ended: ${e.message}")
                    } finally {
                        logcatState.value = false
                    }
                }
            } finally {
                refreshBusy()
            }
        }
    }

    fun stopLogcat() {
        logcatStop = true
    }

    suspend fun fastbootGetvar(name: String): Result<String> = operation("fastboot getvar $name") {
        log.command("fastboot getvar $name")
        val r = fastboot(listOf("getvar", name), 30_000)
        logMasked(r.output)
        r.output
    }

    suspend fun fastbootPartitionInfo(): Result<Unit> = operation("Partition info") {
        log.command("fastboot getvar all")
        val vars = getvarAll()
        vars.filterKeys { it.startsWith("partition-") || it in setOf("product", "current-slot", "slot-count") }
            .toSortedMap()
            .forEach { (k, v) -> logMasked("$k: $v") }
        val target = active
        if (target != null) {
            val identity = DeviceIdentityParser.fromFastboot(vars, target)
            publishIdentity(identity)
            infoState.value = identity.toTargetInfo()
        }
    }

    suspend fun fastbootReboot(target: RebootTarget): Result<Unit> = operation("Reboot ${target.label}") {
        log.command("fastboot ${target.command}")
        val args = when (target) {
            RebootTarget.SYSTEM -> listOf("reboot")
            RebootTarget.BOOTLOADER -> listOf("reboot-bootloader")
            RebootTarget.RECOVERY -> listOf("reboot-recovery")
            RebootTarget.FASTBOOTD -> listOf("reboot-fastboot")
        }
        fastboot(args, 30_000)
        log.info("Reboot requested.")
    }

    suspend fun fastbootFlash(partition: String, image: LibraryFile, source: String = "RootForge OS"): Result<Unit> =
        operation("Flash $partition") {
            val vars = getvarAll()
            val identity = currentIdentity(vars)
            val safety = FlashSafety.evaluateLibrary(library, image, identity)
            FlashSafety.refuse(log, safety)
            val head = image.file.inputStream().use { it.readNBytes(4096) }
            val plan = FlashPlanner.plan(
                partition = partition,
                slot = vars["current-slot"],
                imageName = image.name,
                imageSize = image.size,
                imageHead = head,
                maxDownload = FlashPlanner.parseNumber(vars["max-download-size"]),
                deviceUnlocked = vars["unlocked"]?.let { it == "yes" || it == "true" },
            )
            if (!plan.allowed) {
                plan.blockers.forEach { log.error("Refused: $it") }
                throw IOException("Refused: " + plan.blockers.first())
            }
            val approved = approvals.request(
                title = "Flash ${plan.partition}",
                lines = plan.lines + safety.planLines(identity),
                source = source,
                phrase = plan.confirmationPhrase,
            )
            if (!approved) throw IOException("Flash was not confirmed; nothing was written.")
            val after = getvarAll()
            if (after["unlocked"] != vars["unlocked"] || after["current-slot"] != vars["current-slot"] || after["serialno"] != vars["serialno"]) {
                throw IOException("The device state changed after you confirmed; nothing was written. Start again.")
            }
            FlashSafety.refuse(log, FlashSafety.evaluateLibrary(library, image, currentIdentity(after)))
            log.command("fastboot flash $partition ${image.name}")
            val r = fastboot(listOf("flash", partition, image.file.absolutePath), 600_000)
            log.out(r.output)
            if (!r.ok) throw IOException(r.output.ifBlank { "fastboot flash failed" })
            log.info("Flashed $partition.")
        }

    suspend fun fastbootBootImage(image: LibraryFile): Result<Unit> = operation("Boot ${image.name} (temporary)") {
        val head = image.file.inputStream().use { it.readNBytes(8) }
        if (String(head, Charsets.ISO_8859_1) != "ANDROID!") throw IOException("${image.name} is not an Android boot image.")
        log.command("fastboot boot ${image.name}")
        val r = fastboot(listOf("boot", image.file.absolutePath), 180_000)
        log.out(r.output)
        if (!r.ok) throw IOException(r.output.ifBlank { "fastboot boot failed" })
        log.info("The image was sent; the device boots it once and nothing is written.")
    }

    suspend fun fastbootSetSlot(slot: String, source: String = "RootForge OS"): Result<Unit> = operation("Switch to slot $slot") {
        val vars = getvarAll()
        if ((vars["slot-count"]?.toIntOrNull() ?: 0) < 2) throw IOException("This device reports no A/B slots.")
        val ok = approvals.request(
            title = "Switch active slot to $slot",
            lines = listOf("Current slot: ${vars["current-slot"] ?: "unknown"}", "New slot: $slot", "If the other slot holds an older or incomplete build the device may not boot."),
            source = source,
            phrase = "SLOT $slot",
        )
        if (!ok) throw IOException("Not confirmed; slot unchanged.")
        log.command("fastboot set_active $slot")
        val r = fastboot(listOf("set_active", slot), 30_000)
        log.out(r.output)
        if (!r.ok) throw IOException(r.output.ifBlank { "set_active failed" })
        val after = getvarAll()
        val target = active
        if (target != null) {
            val identity = DeviceIdentityParser.fromFastboot(after, target)
            publishIdentity(identity)
            infoState.value = identity.toTargetInfo()
        }
    }

    suspend fun fastbootErase(partition: String): Result<Unit> = operation("Erase $partition") {
        if (!Regex("[A-Za-z0-9_.-]{1,40}").matches(partition)) throw IOException("Not a plausible partition name.")
        val ok = approvals.request(
            title = "Erase $partition",
            lines = listOf("Partition: $partition", "Erasing destroys its contents permanently.", if (partition == "userdata") "All user data on the target will be lost." else "Make sure you have a backup."),
            phrase = "ERASE $partition",
        )
        if (!ok) throw IOException("Not confirmed; nothing was erased.")
        log.command("fastboot erase $partition")
        val r = fastboot(listOf("erase", partition), 120_000)
        log.out(r.output)
        if (!r.ok) throw IOException(r.output.ifBlank { "fastboot erase failed" })
    }

    suspend fun fastbootUnlock(source: String = "RootForge OS"): Result<Unit> = operation("Unlock bootloader") {
        val vars = getvarAll()
        if (vars["unlocked"] == "yes") throw IOException("The bootloader is already unlocked.")
        val ok = approvals.request(
            title = "Unlock bootloader",
            lines = listOf(
                "Unlocking ERASES ALL DATA on the target (factory reset).",
                "It can trip tamper flags (Knox, Widevine L1, banking apps) and void warranty.",
                "The target will ask you to confirm with its volume and power keys.",
                "Product: ${vars["product"] ?: "unknown"}",
            ),
            source = source,
            phrase = "UNLOCK",
        )
        if (!ok) throw IOException("Not confirmed; the bootloader was not touched.")
        log.command("fastboot flashing unlock")
        var r = fastboot(listOf("flashing", "unlock"), 60_000)
        log.out(r.output)
        if (!r.ok) {
            log.warn("flashing unlock refused; trying oem unlock")
            r = fastboot(listOf("oem", "unlock"), 60_000)
            log.out(r.output)
        }
    }

    suspend fun fastbootLock(source: String = "RootForge OS"): Result<Unit> = operation("Lock bootloader") {
        val vars = getvarAll()
        val ok = approvals.request(
            title = "Lock bootloader",
            lines = listOf(
                "Locking ERASES ALL DATA on the target.",
                "Do NOT lock with a custom or patched boot, recovery or vendor image installed: with verified boot enforced the device will refuse to start (a hard brick on many models).",
                "Product: ${vars["product"] ?: "unknown"}",
            ),
            source = source,
            phrase = "LOCK",
        )
        if (!ok) throw IOException("Not confirmed; the bootloader was not touched.")
        log.command("fastboot flashing lock")
        val r = fastboot(listOf("flashing", "lock"), 60_000)
        log.out(r.output)
        if (!r.ok) throw IOException(r.output.ifBlank { "flashing lock failed" })
    }

    suspend fun edlUpload(file: LibraryFile): Result<Unit> = operation("EDL upload programmer") {
        val edl = tools.edl ?: throw IOException("No `edl` binary on PATH. Desktop EDL uses bkerler's edl (Linux) or a vendor tool; this app ships none.")
        val ok = approvals.request(
            title = "Send programmer to the EDL device",
            lines = listOf(
                "File: ${file.name} (${humanSize(file.size)})",
                "Binary: ${edl.absolutePath}",
                "Use only the loader built for this exact model and OEM.",
            ),
            phrase = "EDL",
        )
        if (!ok) throw IOException("Not confirmed; nothing was sent.")
        log.command("edl --loader ${file.name} printgpt")
        val r = runner.run(edl, listOf("--loader", file.file.absolutePath, "printgpt"), 180_000)
        log.out(r.output)
        if (!r.ok) throw IOException(r.output.ifBlank { "edl failed" })
    }

    suspend fun firehoseConfigure(memory: String): Result<Unit> = operation("Firehose configure") {
        log.warn("Desktop Firehose configure is not implemented as a raw Sahara client; use `edl` (bkerler) for $memory, or the Android USB-Host app.")
    }

    suspend fun firehoseInfo(lun: Int): Result<Unit> = operation("Firehose storage info") {
        val edl = tools.edl ?: throw IOException("No `edl` binary on PATH.")
        log.command("edl printgpt --lun $lun")
        val r = runner.run(edl, listOf("printgpt", "--lun", lun.toString()), 60_000)
        log.out(r.output)
    }

    suspend fun firehoseProgram(lun: Int, startSector: Long, sectorSize: Int, file: LibraryFile): Result<Unit> =
        operation("Firehose program") {
            val edl = tools.edl ?: throw IOException("No `edl` binary on PATH.")
            val ok = approvals.request(
                title = "Write raw sectors via edl",
                lines = listOf("LUN $lun, start sector $startSector, sector size $sectorSize", "File: ${file.name} (${humanSize(file.size)})", "This overwrites storage at that exact location."),
                phrase = "WRITE LUN $lun SECTOR $startSector",
            )
            if (!ok) throw IOException("Not confirmed; nothing was written.")
            log.command("edl w ...")
            val r = runner.run(
                edl,
                listOf("w", "--lun", lun.toString(), "--startsector", startSector.toString(), "--sectorsize", sectorSize.toString(), file.file.absolutePath),
                600_000,
            )
            log.out(r.output)
            if (!r.ok) throw IOException(r.output.ifBlank { "edl write failed" })
        }

    suspend fun firehoseReset(): Result<Unit> = operation("Firehose reset") {
        val edl = tools.edl ?: throw IOException("No `edl` binary on PATH.")
        val r = runner.run(edl, listOf("reset"), 30_000)
        log.out(r.output)
    }

    fun inspectBootImage(file: LibraryFile): BootImageParser.Result {
        RandomAccessFile(file.file, "r").use { raf ->
            val head = ByteArray(minOf(4096L, raf.length()).toInt()).also { raf.readFully(it) }
            val tailLen = minOf(65536L, raf.length()).toInt()
            raf.seek(raf.length() - tailLen)
            val tail = ByteArray(tailLen).also { raf.readFully(it) }
            return BootImageParser.parse(head, tail, raf.length())
        }
    }

    fun bootImageInfoOrNull(file: LibraryFile): BootImageInfo? =
        (inspectBootImage(file) as? BootImageParser.Result.Ok)?.info

    suspend fun stageBootImage(image: LibraryFile): Result<Unit> = operation("Stage boot image on target") {
        val parsed = inspectBootImage(image)
        if (parsed is BootImageParser.Result.Invalid) throw IOException(parsed.reason)
        adbPush(image, RootPaths.stagedBootPath()).getOrThrow()
        log.info("Staged at ${RootPaths.stagedBootPath()}. Patch it with the manager app, then fetch the result.")
    }

    suspend fun launchManager(manager: RootManager): Result<Unit> = operation("Open ${manager.label} on target") {
        log.command("adb shell monkey -p ${manager.packageName}")
        val r = adb(listOf("shell", "monkey", "-p", manager.packageName, "-c", "android.intent.category.LAUNCHER", "1"), 30_000)
        log.out(r.output)
    }

    suspend fun findPatchedImage(manager: RootManager): Result<String?> = operation("Look for patched image") {
        val r = adb(listOf("shell", "ls", "-t", RootPaths.DOWNLOAD_DIR), 15_000)
        r.output.lines().map { it.trim() }.firstOrNull { it.startsWith(manager.patchedPrefix) && it.endsWith(".img") }
            .also { log.info(it?.let { n -> "Found $n" } ?: "No ${manager.patchedPrefix}*.img in Download yet.") }
    }

    suspend fun pullPatchedImage(remoteName: String): Result<LibraryFile> {
        require(Regex("[A-Za-z0-9._-]+\\.img").matches(remoteName)) { "unexpected file name" }
        return adbPull("${RootPaths.DOWNLOAD_DIR}/$remoteName", remoteName)
    }

    suspend fun buildModule(spec: ModuleSpec): Result<BuiltModule> = operation("Build module ${spec.id}") {
        val built = ModuleBuilder.build(spec)
        library.fileFor(built.fileName).writeBytes(built.zip)
        library.refresh()
        log.info("Built ${built.fileName} (${humanSize(built.zip.size.toLong())}, sha256 ${built.sha256})")
        built.entries.forEach { log.out("  $it") }
        built
    }

    suspend fun installModuleOnTarget(zip: LibraryFile, manager: RootManager): Result<String> =
        operation("Install ${zip.name} via ${manager.label}") {
            val approved = approvals.request(
                title = "Install ${zip.name} as root",
                lines = listOf(
                    "Module zip: ${zip.name} (${humanSize(zip.size)})",
                    "SHA-256: ${FileDigest.sha256Hex(zip.file)}",
                    "Installed through: ${manager.label}",
                    "A module runs scripts as root on the target at install and at every boot. RootForge cannot check what is inside it.",
                    "Only continue for a module you built or trust. A bad module can cause a bootloop.",
                ),
                phrase = "INSTALL MODULE",
            )
            if (!approved) throw IOException("Install was not confirmed; nothing was pushed to the target.")
            val remote = "/data/local/tmp/rf-module.zip"
            adb(listOf("push", zip.file.absolutePath, remote), 120_000)
            val bin = manager.moduleBinary
            val cmd = "su -c '$bin ${manager.moduleInstallCommand.substringAfter(' ')} $remote'"
            log.warn("The target may show a Superuser prompt for the shell; allow it once.")
            log.command("adb shell $cmd")
            val r = adb(listOf("shell", cmd), 180_000)
            log.out(r.output)
            adb(listOf("shell", "rm", "-f", remote), 15_000)
            if (!r.ok) throw IOException("Module install exited with ${r.exit}. Is ${manager.label} active and root granted to shell?")
            r.output
        }

    suspend fun probeAdbRecovery(): Result<RecoveryProbe> = operation("Probe recovery (USB target)") {
        val installer = RecoveryInstaller(log, library, approvals)
        val props = recoveryProps()
        val id = adb(listOf("shell", RecoveryCommands.suShell(RecoveryCommands.rootId())), 15_000)
        val rooted = installer.parseRoot(id.output, id.exit)
        val listing = if (rooted) {
            adb(listOf("shell", RecoveryCommands.suShell(RecoveryCommands.listPartitions())), 15_000).output
        } else {
            adb(listOf("shell", RecoveryCommands.listPartitions()), 15_000).output
        }
        installer.probe(RecoverySource.ADB_TARGET, props, listing, id.output, id.exit).also {
            log.info("USB target ${it.identity.device ?: "unknown"}: rooted=${it.rooted}, ${it.layout.kind}, write=${it.layout.writePath ?: "none"}")
        }
    }

    suspend fun installCustomRecovery(
        probe: RecoveryProbe,
        image: LibraryFile,
        dryRun: Boolean,
        publishedMd5: String? = null,
        publishedSha256: String? = null,
    ): Result<RecoveryInstallResult> = operation(if (dryRun) "Recovery dry-run" else "Write recovery") {
        if (probe.source != RecoverySource.ADB_TARGET) {
            throw IOException("Desktop writes recovery only on a USB-connected rooted target, not on this computer.")
        }
        RecoveryInstaller(log, library, approvals).install(HostAdbRootSession(), probe, image, dryRun, publishedMd5, publishedSha256)
    }

    suspend fun rebootCustomRecovery(probe: RecoveryProbe): Result<Unit> = operation("Reboot to recovery") {
        if (probe.source != RecoverySource.ADB_TARGET) throw IOException("Connect the target over ADB first.")
        RecoveryInstaller(log, library, approvals).rebootToRecovery(HostAdbRootSession())
    }

    private fun recoveryProps(): Map<String, String> {
        val keys = listOf(
            "ro.product.device", "ro.build.product", "ro.product.name", "ro.product.model",
            "ro.boot.slot_suffix", "ro.build.ab_update",
        )
        val p = mutableMapOf<String, String>()
        for (k in keys) p[k] = adb(listOf("shell", "getprop", k), 10_000).stdout.trim()
        return p
    }

    private inner class HostAdbRootSession : RootSession {
        override fun su(command: String, timeoutMs: Long): RootShellResult {
            val r = adb(listOf("shell", RecoveryCommands.suShell(command)), timeoutMs)
            return RootShellResult(r.output, r.exit)
        }

        override fun push(local: File, remote: String) {
            require(remote == RecoveryCommands.IMAGE_REMOTE)
            val r = adb(listOf("push", local.absolutePath, remote), 300_000)
            if (!r.ok) throw IOException(r.output.ifBlank { "adb push failed" })
        }

        override fun pull(remote: String, local: File) {
            require(remote == RecoveryCommands.BACKUP_REMOTE || remote == RecoveryCommands.IMAGE_REMOTE)
            local.parentFile?.mkdirs()
            val r = adb(listOf("pull", remote, local.absolutePath), 300_000)
            if (!r.ok) throw IOException(r.output.ifBlank { "adb pull failed" })
        }

        override fun rebootRecovery() {
            adb(listOf("reboot", "recovery"), 30_000)
        }
    }

    private fun loadAdbInfo(target: AttachedTarget) {
        val serial = target.serial ?: target.deviceName
        val dump = adb(listOf("shell", "getprop"), 20_000, serial).output
        val parsed = DeviceIdentityParser.parseGetpropDump(dump)
        val props = parsed.ifEmpty {
            val keys = listOf(
                "ro.product.brand", "ro.product.manufacturer", "ro.product.model", "ro.product.name",
                "ro.product.device", "ro.board.platform", "ro.soc.model", "ro.soc.manufacturer",
                "ro.build.version.release", "ro.build.version.sdk", "ro.build.version.security_patch",
                "ro.build.fingerprint", "ro.serialno", "ro.boot.verifiedbootstate",
                "ro.boot.flash.locked", "ro.boot.vbmeta.device_state", "ro.boot.slot_suffix",
            )
            keys.associateWith { k -> adb(listOf("shell", "getprop", k), 10_000, serial).stdout.trim() }
        }
        val pkgs = adb(listOf("shell", "pm", "list", "packages"), 15_000, serial).output
        val identity = DeviceIdentityParser.fromAdb(props, pkgs, target)
        publishIdentity(identity)
        if (active?.rosterKey() == target.rosterKey()) infoState.value = identity.toTargetInfo()
    }

    private fun getvarAll(serial: String? = activeSerial()): Map<String, String> {
        val r = fastboot(listOf("getvar", "all"), 30_000, serial)
        return DeviceIdentityParser.parseGetvarDump(r.output)
    }

    suspend fun identifyAttached(force: Boolean = false) {
        val current = targetState.value
        identityState.value = DeviceRoster.prune(identityState.value, current)
        for (target in current) {
            if (target.mode == TargetMode.UNKNOWN) continue
            if (!force && !DeviceRoster.needsIdentify(target, identityState.value)) continue
            runCatching { identifyOne(target) }.onFailure { e ->
                log.warn("Identify ${target.publicLabel()} failed: ${e.message}")
                publishIdentity(DeviceIdentityParser.stub(target, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private suspend fun identifyOne(target: AttachedTarget) {
        if (!target.hasPermission) {
            publishIdentity(DeviceIdentityParser.stub(target, "unauthorized or offline"))
            return
        }
        val key = target.rosterKey()
        serialLock.withLock(key, "Identify") {
            when (target.mode) {
                TargetMode.ADB -> loadAdbInfo(target)
                TargetMode.FASTBOOT -> {
                    val identity = DeviceIdentityParser.fromFastboot(getvarAll(target.serial ?: target.deviceName), target)
                    publishIdentity(identity)
                    if (active?.rosterKey() == key) infoState.value = identity.toTargetInfo()
                }
                TargetMode.EDL -> {
                    val identity = DeviceIdentityParser.fromEdl(target)
                    publishIdentity(identity)
                    if (active?.rosterKey() == key) infoState.value = identity.toTargetInfo()
                }
                TargetMode.UNKNOWN -> Unit
            }
        }
    }

    private fun publishIdentity(identity: ai.rootforge.os.device.DeviceIdentity) {
        val key = identity.rosterKey().ifEmpty { return }
        identityState.update { it + (key to identity) }
        log.info("Identified ${identity.brand ?: "unknown"} ${identity.model ?: ""} (${identity.codename ?: "no codename"}) · ${identity.mode.label} · serial ${identity.maskedSerial}".replace("  ", " "))
    }

    private fun knownSerials(): List<String> =
        (targetState.value.mapNotNull { it.serial } + identityState.value.values.mapNotNull { it.serial } + listOfNotNull(active?.serial)).distinct()

    private fun logMasked(text: String) {
        log.out(SerialMask.replaceKnown(text, knownSerials()))
    }

    private fun activeSerial(): String? = active?.serial ?: active?.deviceName

    private fun pullToLibrary(remotePath: String, saveAs: String): LibraryFile {
        require(remotePath.startsWith("/")) { "Remote path must be absolute" }
        val dest = library.fileFor(saveAs)
        log.command("adb pull $remotePath")
        val r = adb(listOf("pull", remotePath, dest.absolutePath), 300_000)
        log.out(r.output)
        if (!r.ok) throw IOException(r.output.ifBlank { "adb pull failed" })
        library.refresh()
        log.info("Saved ${humanSize(dest.length())} to library as ${dest.name}.")
        return LibraryFile(dest.name, dest)
    }

    private fun requireAdb(): File = tools.adb ?: throw IOException("adb not found. Install Android platform-tools and put them on PATH, or set ANDROID_HOME.")
    private fun requireFastboot(): File = tools.fastboot ?: throw IOException("fastboot not found. Install Android platform-tools and put them on PATH, or set ANDROID_HOME.")

    private fun adb(args: List<String>, timeoutMs: Long, serial: String? = activeSerial()): CmdResult {
        val exe = requireAdb()
        val full = if (serial != null) listOf("-s", serial) + args else args
        val r = runner.run(exe, full, timeoutMs)
        if (!r.ok && r.output.contains("no devices", ignoreCase = true)) throw IOException("No adb device (current mode: ${currentMode?.label ?: "none"}).")
        return r
    }

    private fun fastboot(args: List<String>, timeoutMs: Long, serial: String? = activeSerial()): CmdResult {
        val exe = requireFastboot()
        val full = if (serial != null) listOf("-s", serial) + args else args
        return runner.run(exe, full, timeoutMs)
    }

    private suspend fun <T> operation(label: String, block: suspend () -> T): Result<T> =
        operationOn(active?.rosterKey(), label, block)

    private fun refreshBusy() {
        if (logcatState.value) {
            busyState.value = "Logcat"
            return
        }
        val activeKey = SerialOpLock.key(active?.rosterKey())
        busyState.value = serialLock.busyLabel(activeKey) ?: serialLock.busyMap.value.values.firstOrNull()
    }

    private suspend fun <T> operationOn(serial: String?, label: String, block: suspend () -> T): Result<T> {
        val logcatSerial = active?.rosterKey()
        if (logcatState.value && label != "Logcat" && (serial == null || serial == logcatSerial)) {
            val msg = "Stop logcat before running another action."
            log.error(msg)
            return Result.failure(IOException(msg))
        }
        return try {
            serialLock.withLock(serial, label) {
                busyState.value = label
                try {
                    Result.success(withContext(Dispatchers.IO) { block() })
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("$label failed: ${e.message ?: e.javaClass.simpleName}")
                    Result.failure(e)
                } finally {
                    progressState.value = null
                }
            }
        } finally {
            refreshBusy()
        }
    }
}
