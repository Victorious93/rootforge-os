package ai.rootforge.os.core

import ai.rootforge.os.adb.AdbConnection
import ai.rootforge.os.adb.AdbKeyStore
import ai.rootforge.os.device.DeviceIdentityParser
import ai.rootforge.os.device.DeviceRoster
import ai.rootforge.os.device.SerialMask
import ai.rootforge.os.device.SerialOpLock
import ai.rootforge.os.device.publicLabel
import ai.rootforge.os.device.rosterKey
import ai.rootforge.os.edl.FirehoseClient
import ai.rootforge.os.edl.SaharaClient
import ai.rootforge.os.fastboot.FastbootClient
import ai.rootforge.os.fastboot.FastbootException
import ai.rootforge.os.fastboot.FastbootReply
import ai.rootforge.os.fastboot.FlashPlanner
import ai.rootforge.os.fastboot.FlashSafety
import ai.rootforge.os.fastboot.RebootTarget
import ai.rootforge.os.fastboot.TargetIdentity
import ai.rootforge.os.usb.UsbScanFilter
import ai.rootforge.os.module.BuiltModule
import ai.rootforge.os.module.InstalledModule
import ai.rootforge.os.module.LocalModules
import ai.rootforge.os.recovery.AdbRootSession
import ai.rootforge.os.recovery.ProcessRootSession
import ai.rootforge.os.recovery.RecoveryCommands
import ai.rootforge.os.recovery.RecoveryInstallResult
import ai.rootforge.os.recovery.RecoveryInstaller
import ai.rootforge.os.recovery.RecoveryProbe
import ai.rootforge.os.recovery.RecoverySource
import ai.rootforge.os.recovery.RootSession
import ai.rootforge.os.module.ModuleBuilder
import ai.rootforge.os.module.ModuleSpec
import ai.rootforge.os.root.BootImageInfo
import ai.rootforge.os.root.BootImageParser
import ai.rootforge.os.root.RootDetection
import ai.rootforge.os.root.RootManager
import ai.rootforge.os.root.RootPaths
import ai.rootforge.os.usb.AttachedTarget
import ai.rootforge.os.usb.BulkTransport
import ai.rootforge.os.usb.TargetMode
import ai.rootforge.os.usb.UsbHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

data class LocalRootSnapshot(
    val detection: RootDetection = RootDetection(emptyList(), null, "Not scanned yet."),
    val modules: List<InstalledModule> = emptyList(),
    val listed: Boolean = false,
)

/**
 * The single place that touches the target. The UI, the bridge for
 * droidcommand-ai and tests all go through here, so every destructive
 * operation passes the same plan, typed confirmation and re-check.
 */
class TargetController(
    private val usb: UsbHost,
    val log: OutputLog,
    val library: Library,
    val approvals: ApprovalCenter,
    private val keyStore: AdbKeyStore,
    private val scope: CoroutineScope,
    private val openTransport: (AttachedTarget) -> BulkTransport = { usb.open(it) },
    private val localPackages: () -> Set<String> = { emptySet() },
    private val canRunLocalSu: Boolean = false,
) {
    val targets: StateFlow<List<AttachedTarget>> = usb.targets

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
    private val localRootState = MutableStateFlow(LocalRootSnapshot())
    val localRoot: StateFlow<LocalRootSnapshot> = localRootState.asStateFlow()
    private val identityState = MutableStateFlow<Map<String, ai.rootforge.os.device.DeviceIdentity>>(emptyMap())
    val identities: StateFlow<Map<String, ai.rootforge.os.device.DeviceIdentity>> = identityState.asStateFlow()
    private val serialLock = SerialOpLock()
    val serialBusy: StateFlow<Map<String, String>> = serialLock.busyMap
    private var adb: AdbConnection? = null
    private var fastboot: FastbootClient? = null
    private var sahara: Pair<SaharaClient, BulkTransport>? = null
    private var activeTarget: AttachedTarget? = null
    private var logcatJob: Job? = null
    @Volatile private var logcatStop = false

    val currentMode: TargetMode? get() = (linkState.value as? LinkState.Connected)?.mode

    // ----- connection ---------------------------------------------------------------------------------

    fun requestPermission(target: AttachedTarget) {
        log.info("Asking Android for USB access to ${target.vidPid}...")
        usb.requestPermission(target)
    }

    fun disconnect() {
        logcatStop = true
        adb?.close(); adb = null
        fastboot?.close(); fastboot = null
        sahara?.second?.close(); sahara = null
        activeTarget = null
        firehoseClient = null
        infoState.value = null
        linkState.value = LinkState.Disconnected
    }

    /** Drops the link if the connected device was unplugged or re-enumerated (a reboot). */
    fun onTargetsChanged(current: List<AttachedTarget>) {
        val active = activeTarget
        if (active != null && current.none { it.deviceName == active.deviceName && it.mode == active.mode }) {
            log.warn("The target disconnected (or changed mode).")
            disconnect()
        }
        identityState.value = DeviceRoster.prune(identityState.value, current)
        scope.launch { identifyAttached() }
    }

    suspend fun connect(target: AttachedTarget): Result<Unit> {
        if (!target.hasPermission) {
            requestPermission(target)
            return Result.failure(IOException("USB permission needed: accept the Android prompt, then connect again."))
        }
        val key = target.rosterKey()
        val activeKey = activeTarget?.rosterKey()
        if (!activeKey.isNullOrEmpty() && activeKey != key && serialLock.isBusy(activeKey)) {
            val msg = "Finish the current action on ${SerialMask.mask(activeKey)} first."
            log.error(msg)
            return Result.failure(IOException(msg))
        }
        return operationOn(key, "Connecting to ${target.publicLabel()}") {
            disconnect()
            linkState.value = LinkState.Connecting("Opening ${target.vidPid}")
            try {
                val transport = openTransport(target)
                activeTarget = target
                when (target.mode) {
                    TargetMode.ADB -> connectAdb(transport, target)
                    TargetMode.FASTBOOT -> connectFastboot(transport, target)
                    TargetMode.EDL -> connectEdl(transport, target)
                    TargetMode.UNKNOWN -> {
                        transport.close()
                        throw IOException("${target.vidPid} is not an ADB, fastboot or EDL interface.")
                    }
                }
            } catch (e: Exception) {
                disconnect()
                linkState.value = LinkState.Failed(e.message ?: e.javaClass.simpleName)
                throw e
            }
        }
    }

    private fun connectAdb(transport: BulkTransport, target: AttachedTarget) {
        val conn = AdbConnection(transport, keyStore.loadOrCreate())
        log.command("adb connect (USB ${target.vidPid})")
        conn.connect(onAuthPrompt = {
            log.warn("Look at the target screen: tap Allow USB debugging (tick Always allow to remember this host).")
            linkState.value = LinkState.Connecting("Waiting for you to allow USB debugging on the target")
        })
        adb = conn
        log.out(conn.banner.ifBlank { "connected" })
        linkState.value = LinkState.Connected(TargetMode.ADB, target.publicLabel())
        loadAdbInfo(conn, target)
    }

    private fun connectFastboot(transport: BulkTransport, target: AttachedTarget) {
        val fb = FastbootClient(transport)
        fastboot = fb
        linkState.value = LinkState.Connected(TargetMode.FASTBOOT, target.publicLabel())
        log.command("fastboot getvar all")
        val vars = fb.getvarAll { logMasked(it) }
        val identity = DeviceIdentityParser.fromFastboot(vars, target)
        publishIdentity(identity)
        infoState.value = identity.toTargetInfo()
    }

    private fun connectEdl(transport: BulkTransport, target: AttachedTarget) {
        val client = SaharaClient(transport)
        sahara = client to transport
        linkState.value = LinkState.Connected(TargetMode.EDL, target.publicLabel())
        log.command("sahara: read device identity")
        val d = client.readDeviceInfo()
        val identity = DeviceIdentityParser.fromEdl(target, d.serial, d.msmHwId)
        publishIdentity(identity)
        infoState.value = identity.toTargetInfo()
        logMasked("serial=${d.serial} hwid=${d.msmHwId} sbl=${d.sblVersion}")
    }

    // ----- info ---------------------------------------------------------------------------------------

    private fun loadAdbInfo(conn: AdbConnection, target: AttachedTarget) {
        val p = conn.getprop()
        val pkgs = runCatching { conn.shell("pm list packages").output }.getOrDefault("")
        val identity = DeviceIdentityParser.fromAdb(p, pkgs, target)
        publishIdentity(identity)
        if (activeTarget?.rosterKey() == target.rosterKey()) {
            infoState.value = identity.toTargetInfo()
        }
    }

    private fun publishIdentity(identity: ai.rootforge.os.device.DeviceIdentity) {
        val key = identity.rosterKey().ifEmpty { return }
        identityState.update { it + (key to identity) }
        log.info("Identified ${identity.brand ?: "unknown"} ${identity.model ?: ""} (${identity.codename ?: "no codename"}) · ${identity.mode.label} · serial ${identity.maskedSerial}".replace("  ", " "))
    }

    private fun knownSerials(): List<String> =
        (usb.targets.value.mapNotNull { it.serial } + identityState.value.values.mapNotNull { it.serial } + listOfNotNull(activeTarget?.serial))
            .distinct()

    private fun logMasked(text: String) {
        log.out(SerialMask.replaceKnown(text, knownSerials()))
    }

    suspend fun identifyAttached(force: Boolean = false) {
        val current = usb.targets.value
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
            publishIdentity(DeviceIdentityParser.stub(target, "USB permission needed."))
            return
        }
        val key = target.rosterKey()
        serialLock.withLock(key, "Identify") {
            val active = activeTarget
            if (active?.rosterKey() == key) {
                when (target.mode) {
                    TargetMode.ADB -> adb?.takeIf { it.connected }?.let { loadAdbInfo(it, target) }
                    TargetMode.FASTBOOT -> fastboot?.let { fb ->
                        val vars = fb.getvarAll { logMasked(it) }
                        val identity = DeviceIdentityParser.fromFastboot(vars, target)
                        publishIdentity(identity)
                        infoState.value = identity.toTargetInfo()
                    }
                    TargetMode.EDL -> sahara?.first?.let { client ->
                        val d = client.readDeviceInfo()
                        val identity = DeviceIdentityParser.fromEdl(target, d.serial, d.msmHwId)
                        publishIdentity(identity)
                        infoState.value = identity.toTargetInfo()
                    }
                    TargetMode.UNKNOWN -> Unit
                }
                return@withLock
            }
            val transport = openTransport(target)
            try {
                when (target.mode) {
                    TargetMode.ADB -> {
                        val conn = AdbConnection(transport, keyStore.loadOrCreate())
                        conn.connect(onAuthPrompt = {
                            log.warn("Look at ${target.publicLabel()}: tap Allow USB debugging.")
                        })
                        loadAdbInfo(conn, target)
                        conn.close()
                    }
                    TargetMode.FASTBOOT -> {
                        val fb = FastbootClient(transport)
                        val vars = fb.getvarAll { logMasked(it) }
                        publishIdentity(DeviceIdentityParser.fromFastboot(vars, target))
                        fb.close()
                    }
                    TargetMode.EDL -> {
                        val client = SaharaClient(transport)
                        val d = client.readDeviceInfo()
                        publishIdentity(DeviceIdentityParser.fromEdl(target, d.serial, d.msmHwId))
                        transport.close()
                    }
                    TargetMode.UNKNOWN -> transport.close()
                }
            } catch (e: Exception) {
                runCatching { transport.close() }
                throw e
            }
        }
    }

    suspend fun refreshInfo(): Result<Unit> = operation("Refreshing device info") {
        val target = activeTarget ?: return@operation
        when (currentMode) {
            TargetMode.ADB -> loadAdbInfo(requireAdb(), target)
            TargetMode.FASTBOOT -> {
                val vars = requireFastboot().getvarAll { logMasked(it) }
                val identity = DeviceIdentityParser.fromFastboot(vars, target)
                publishIdentity(identity)
                infoState.value = identity.toTargetInfo()
            }
            else -> Unit
        }
    }

    fun fastbootVars(): Map<String, String> = infoState.value?.takeIf { it.mode == TargetMode.FASTBOOT }?.rows?.toMap().orEmpty()

    internal fun currentIdentity(fastbootVars: Map<String, String>? = null): TargetIdentity =
        TargetIdentity.merge(
            fastbootVars?.let { TargetIdentity.fromFastboot(it) },
            infoState.value?.let { TargetIdentity.fromTargetInfo(it) },
        )

    fun scanUsb() {
        usb.refresh()
        val scan = UsbScanFilter.partition(usb.targets.value)
        log.command("usb scan")
        val serials = knownSerials()
        UsbScanFilter.describe(scan).forEach { line ->
            val masked = SerialMask.replaceKnown(line, serials)
            if (line.startsWith("Ignored ") || line.startsWith("No ADB")) log.info(masked) else log.out(masked)
        }
        scope.launch { identifyAttached(force = true) }
    }

    fun inspectLibraryFile(file: LibraryFile): FileInspection {
        val head = file.file.readPrefix(4096)
        val sha = FileDigest.sha256Hex(file.file)
        return FileInspector.inspect(file.name, file.size, head, sha, FlashSafety.loadSidecar(library, file.name))
    }

    fun verifyLibrarySha256(file: LibraryFile, expectedHex: String): DigestCheck {
        val check = FileDigest.verify(expectedHex, FileDigest.sha256Hex(file.file))
        if (check.ok) log.info("${file.name}: ${check.message}") else log.error("${file.name}: ${check.message}")
        return check
    }

    // ----- ADB ----------------------------------------------------------------------------------------

    suspend fun adbShell(command: String): Result<ShellOutcome> = operation("adb shell") {
        log.command("adb shell $command")
        val r = requireAdb().shell(command, timeoutMs = 120_000) { logMasked(it) }
        if (r.exitCode != null && r.exitCode != 0) log.warn("exit status ${r.exitCode}")
        ShellOutcome(r.output, r.exitCode)
    }

    suspend fun adbReboot(target: String): Result<Unit> = operation("Reboot ${target.ifBlank { "system" }}") {
        log.command("adb reboot $target")
        requireAdb().reboot(target)
        log.info("Reboot requested; the target will reappear in a moment.")
    }

    suspend fun adbInstall(apk: LibraryFile): Result<String> = operation("Install ${apk.name}") {
        val conn = requireAdb()
        val remote = "/data/local/tmp/rf-install-${System.currentTimeMillis()}.apk"
        log.command("adb push ${apk.name} $remote")
        apk.file.inputStream().use { conn.push(it, apk.size, remote) { d, t -> setProgress("Pushing ${apk.name}", d, t) } }
        clearProgress()
        log.command("adb shell pm install -r $remote")
        val r = conn.shell("pm install -r '$remote'", timeoutMs = 300_000) { log.out(it) }
        conn.shell("rm -f '$remote'")
        if (!r.output.contains("Success")) throw IOException("pm install did not report Success")
        r.output.trim()
    }

    suspend fun adbPush(file: LibraryFile, remotePath: String): Result<Unit> = operation("Push ${file.name}") {
        require(remotePath.startsWith("/")) { "Remote path must be absolute" }
        log.command("adb push ${file.name} $remotePath")
        file.file.inputStream().use { requireAdb().push(it, file.size, remotePath) { d, t -> setProgress("Pushing ${file.name}", d, t) } }
        clearProgress()
        log.info("Pushed ${humanSize(file.size)}.")
    }

    suspend fun adbPull(remotePath: String, saveAs: String): Result<LibraryFile> = operation("Pull $remotePath") {
        pullToLibrary(remotePath, saveAs)
    }

    suspend fun adbDeviceCheck(): Result<Unit> = operation("Device check") {
        val target = activeTarget ?: throw IOException("No target connected.")
        loadAdbInfo(requireAdb(), target)
        log.command("adb device check")
        infoState.value?.rows?.forEach { (k, v) -> logMasked("$k: $v") }
    }

    suspend fun adbListPackages(thirdPartyOnly: Boolean = false): Result<String> = operation("List packages") {
        val cmd = if (thirdPartyOnly) "pm list packages -3" else "pm list packages"
        log.command("adb shell $cmd")
        requireAdb().shell(cmd, timeoutMs = 60_000) { log.out(it) }.output
    }

    suspend fun adbScreenInfo(): Result<Unit> = operation("Screen info") {
        val conn = requireAdb()
        log.command("adb shell wm size")
        conn.shell("wm size") { log.out(it) }
        log.command("adb shell wm density")
        conn.shell("wm density") { log.out(it) }
    }

    suspend fun adbScreenshot(): Result<LibraryFile> = operation("Screenshot") {
        val remote = "/sdcard/Download/rf-screencap.png"
        log.command("adb shell screencap -p $remote")
        val cap = requireAdb().shell("screencap -p '$remote'", timeoutMs = 30_000) { log.out(it) }
        if (cap.exitCode != null && cap.exitCode != 0) throw IOException("screencap failed (exit ${cap.exitCode})")
        pullToLibrary(remote, "rf-screencap.png")
    }

    suspend fun adbSideload(zip: LibraryFile): Result<Unit> = operation("Sideload ${zip.name}") {
        val conn = requireAdb()
        if (!conn.banner.startsWith("sideload")) {
            throw IOException("The target is not in sideload mode (banner '${conn.banner.take(24)}'). In recovery choose Apply update, then Apply from ADB.")
        }
        log.command("adb sideload ${zip.name}")
        conn.sideload(zip.file, progress = { done, total -> setProgress("Sideloading", done, total) })
        clearProgress()
        log.info("Sideload finished; check the target screen for the result.")
    }

    fun startLogcat(filter: String = "") {
        if (logcatState.value) return
        val conn = adb ?: run { log.error("Connect to a target over ADB first."); return }
        logcatStop = false
        logcatState.value = true
        logcatJob = scope.launch(Dispatchers.IO) {
            try {
                serialLock.withLock(activeTarget?.rosterKey(), "Logcat") {
                    busyState.value = "Logcat"
                    log.command("adb logcat -v threadtime $filter".trim())
                    try {
                        conn.streamShell("logcat -v threadtime $filter".trim(), { logcatStop }) { logMasked(it) }
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

    // ----- fastboot -----------------------------------------------------------------------------------

    suspend fun fastbootPartitionInfo(): Result<Unit> = operation("Partition info") {
        log.command("fastboot getvar all")
        val fb = requireFastboot()
        val vars = fb.getvarAll { logMasked(it) }
        vars.filterKeys { it.startsWith("partition-") || it in setOf("product", "current-slot", "slot-count") }
            .toSortedMap()
            .forEach { (k, v) -> logMasked("$k: $v") }
        val target = activeTarget
        if (target != null) {
            val identity = DeviceIdentityParser.fromFastboot(vars, target)
            publishIdentity(identity)
            infoState.value = identity.toTargetInfo()
        }
    }

    suspend fun fastbootCommand(command: String): Result<String> = operation("fastboot $command") {
        log.command("fastboot $command")
        val fb = requireFastboot()
        when (val r = fb.command(command, 60_000) { log.out(it) }) {
            is FastbootReply.Okay -> r.message.also { if (it.isNotBlank()) log.out(it) }
            is FastbootReply.Fail -> throw FastbootException(r.message)
            is FastbootReply.Data -> throw FastbootException("unexpected DATA reply")
        }
    }

    suspend fun fastbootReboot(target: RebootTarget): Result<Unit> = operation("Reboot ${target.label}") {
        log.command("fastboot ${target.command}")
        requireFastboot().reboot(target)
        log.info("Reboot requested.")
    }

    suspend fun fastbootFlash(partition: String, image: LibraryFile, source: String = "RootForge OS"): Result<Unit> =
        operation("Flash $partition") {
            val fb = requireFastboot()
            val vars = fb.getvarAll()
            val identity = currentIdentity(vars)
            val safety = FlashSafety.evaluateLibrary(library, image, identity)
            FlashSafety.refuse(log, safety)
            val head = image.file.readPrefix(4096)
            val plan = FlashPlanner.plan(
                partition = partition,
                slot = vars["current-slot"],
                imageName = image.name,
                imageSize = image.size,
                imageHead = head,
                maxDownload = FastbootClient.parseNumber(vars["max-download-size"]),
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
            // Re-check immediately before writing: the plan is only valid for the device it described.
            val after = fb.getvarAll()
            if (after["unlocked"] != vars["unlocked"] || after["current-slot"] != vars["current-slot"] || after["serialno"] != vars["serialno"]) {
                throw IOException("The device state changed after you confirmed; nothing was written. Start again.")
            }
            FlashSafety.refuse(log, FlashSafety.evaluateLibrary(library, image, currentIdentity(after)))
            log.command("fastboot flash $partition ${image.name}")
            image.file.inputStream().buffered().use {
                fb.flash(partition, it, image.size, { d, t -> setProgress("Flashing $partition", d, t) }) { line -> log.out(line) }
            }
            clearProgress()
            log.info("Flashed $partition.")
        }

    suspend fun fastbootBootImage(image: LibraryFile): Result<Unit> = operation("Boot ${image.name} (temporary)") {
        val head = image.file.readPrefix(8)
        if (String(head, Charsets.ISO_8859_1) != "ANDROID!") throw IOException("${image.name} is not an Android boot image.")
        log.command("fastboot boot ${image.name}")
        image.file.inputStream().buffered().use {
            requireFastboot().bootImage(it, image.size, { d, t -> setProgress("Sending ${image.name}", d, t) }) { line -> log.out(line) }
        }
        clearProgress()
        log.info("The image was sent; the device boots it once and nothing is written.")
    }

    suspend fun fastbootSetSlot(slot: String, source: String = "RootForge OS"): Result<Unit> = operation("Switch to slot $slot") {
        val fb = requireFastboot()
        val vars = fb.getvarAll()
        if ((vars["slot-count"]?.toIntOrNull() ?: 0) < 2) throw IOException("This device reports no A/B slots.")
        val ok = approvals.request(
            title = "Switch active slot to $slot",
            lines = listOf("Current slot: ${vars["current-slot"] ?: "unknown"}", "New slot: $slot", "If the other slot holds an older or incomplete build the device may not boot."),
            source = source,
            phrase = "SLOT $slot",
        )
        if (!ok) throw IOException("Not confirmed; slot unchanged.")
        log.command("fastboot set_active:$slot")
        fb.setActiveSlot(slot)
        val after = fb.getvarAll { logMasked(it) }
        val target = activeTarget
        if (target != null) {
            val identity = DeviceIdentityParser.fromFastboot(after, target)
            publishIdentity(identity)
            infoState.value = identity.toTargetInfo()
        }
    }

    suspend fun fastbootErase(partition: String): Result<Unit> = operation("Erase $partition") {
        FastbootClient.requirePartitionName(partition)
        val ok = approvals.request(
            title = "Erase $partition",
            lines = listOf("Partition: $partition", "Erasing destroys its contents permanently.", if (partition == "userdata") "All user data on the target will be lost." else "Make sure you have a backup."),
            phrase = "ERASE $partition",
        )
        if (!ok) throw IOException("Not confirmed; nothing erased.")
        log.command("fastboot erase $partition")
        requireFastboot().erase(partition)
    }

    suspend fun fastbootUnlock(source: String = "RootForge OS"): Result<Unit> = operation("Unlock bootloader") {
        val fb = requireFastboot()
        val vars = fb.getvarAll()
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
        when (val r = fb.command("flashing unlock", 60_000) { log.out(it) }) {
            is FastbootReply.Okay -> log.info("Accepted. Follow the prompt on the target screen.")
            is FastbootReply.Fail -> {
                log.warn("flashing unlock refused (${r.message}); trying oem unlock")
                fb.run("oem unlock", 60_000) { log.out(it) }
            }
            is FastbootReply.Data -> throw FastbootException("unexpected DATA reply")
        }
    }

    suspend fun fastbootLock(source: String = "RootForge OS"): Result<Unit> = operation("Lock bootloader") {
        val fb = requireFastboot()
        val vars = fb.getvarAll()
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
        fb.run("flashing lock", 60_000) { log.out(it) }
    }

    // ----- EDL ----------------------------------------------------------------------------------------

    suspend fun edlUploadProgrammer(programmer: LibraryFile, source: String = "RootForge OS"): Result<Unit> = operation("Upload programmer") {
        val (client, _) = sahara ?: throw IOException("Connect to an EDL device first.")
        val ok = approvals.request(
            title = "Send programmer to the EDL device",
            lines = listOf(
                "File: ${programmer.name} (${humanSize(programmer.size)})",
                "The programmer runs with full control of the storage. Use only the loader built for this exact model and OEM.",
                "On a Secure Boot device the chip will reject an unsigned or foreign loader.",
            ),
            source = source,
            phrase = "EDL",
        )
        if (!ok) throw IOException("Not confirmed; nothing was sent.")
        log.command("sahara: upload ${programmer.name}")
        client.uploadProgrammer(programmer.file.readBytes()) { d, t -> setProgress("Uploading programmer", d, t) }
        clearProgress()
        log.info("Programmer accepted. Firehose operations are now available.")
    }

    suspend fun firehoseConfigure(memory: String): Result<Unit> = operation("Firehose configure") {
        val fh = firehose()
        fh.configure(memory) { log.out(it) }
        log.info("Configured (max payload ${fh.maxPayload} bytes).")
    }

    suspend fun firehoseStorageInfo(lun: Int): Result<Unit> = operation("Firehose storage info") {
        firehose().storageInfo(lun) { log.out(it) }
    }

    suspend fun firehoseProgram(lun: Int, startSector: Long, sectorSize: Int, image: LibraryFile): Result<Unit> = operation("Firehose program") {
        val ok = approvals.request(
            title = "Write raw sectors via Firehose",
            lines = listOf("LUN $lun, start sector $startSector, sector size $sectorSize", "File: ${image.name} (${humanSize(image.size)})", "This overwrites storage at that exact location with no partition awareness."),
            phrase = "WRITE LUN $lun SECTOR $startSector",
        )
        if (!ok) throw IOException("Not confirmed; nothing was written.")
        log.command("firehose program lun=$lun start=$startSector")
        image.file.inputStream().buffered().use {
            firehose().programRaw(lun, startSector, sectorSize, it, image.size, { l -> log.out(l) }) { d, t -> setProgress("Writing", d, t) }
        }
        clearProgress()
    }

    suspend fun firehoseReset(): Result<Unit> = operation("Firehose reset") { firehose().reset { log.out(it) } }

    private var firehoseClient: FirehoseClient? = null
    private fun firehose(): FirehoseClient {
        val transport = sahara?.second ?: throw IOException("Connect to an EDL device first.")
        return firehoseClient ?: FirehoseClient(transport).also { firehoseClient = it }
    }

    // ----- root workflows -----------------------------------------------------------------------------

    fun inspectBootImage(file: LibraryFile): BootImageParser.Result {
        RandomAccessFile(file.file, "r").use { raf ->
            val head = ByteArray(minOf(4096L, raf.length()).toInt()).also { raf.readFully(it) }
            val tailLen = minOf(65536L, raf.length()).toInt()
            raf.seek(raf.length() - tailLen)
            val tail = ByteArray(tailLen).also { raf.readFully(it) }
            return BootImageParser.parse(head, tail, raf.length())
        }
    }

    fun bootImageInfoOrNull(file: LibraryFile): BootImageInfo? = (inspectBootImage(file) as? BootImageParser.Result.Ok)?.info

    private fun installedManagers(conn: AdbConnection): List<RootManager> {
        val out = conn.shell("pm list packages").output
        return RootManager.entries.filter { out.contains("package:${it.packageName}") }
    }

    suspend fun stageBootImage(image: LibraryFile): Result<Unit> = operation("Stage boot image on target") {
        val parsed = inspectBootImage(image)
        if (parsed is BootImageParser.Result.Invalid) throw IOException(parsed.reason)
        val path = RootPaths.stagedBootPath()
        log.command("adb push ${image.name} $path")
        image.file.inputStream().use { requireAdb().push(it, image.size, path) { d, t -> setProgress("Staging boot image", d, t) } }
        clearProgress()
        log.info("Staged at $path. Patch it with the manager app, then fetch the result.")
    }

    suspend fun launchManager(manager: RootManager): Result<Unit> = operation("Open ${manager.label} on target") {
        val conn = requireAdb()
        if (!installedManagers(conn).contains(manager)) throw IOException("${manager.label} is not installed on the target. Install its APK first.")
        log.command("adb shell monkey -p ${manager.packageName}")
        conn.shell("monkey -p ${manager.packageName} -c android.intent.category.LAUNCHER 1") { log.out(it) }
    }

    suspend fun findPatchedImage(manager: RootManager): Result<String?> = operation("Look for patched image") {
        val out = requireAdb().shell("ls -t ${RootPaths.DOWNLOAD_DIR}").output
        out.lines().map { it.trim() }.firstOrNull { it.startsWith(manager.patchedPrefix) && it.endsWith(".img") }
            .also { log.info(it?.let { n -> "Found $n" } ?: "No ${manager.patchedPrefix}*.img in Download yet.") }
    }

    suspend fun pullPatchedImage(remoteName: String): Result<LibraryFile> {
        require(Regex("[A-Za-z0-9._-]+\\.img").matches(remoteName)) { "unexpected file name" }
        return adbPull("${RootPaths.DOWNLOAD_DIR}/$remoteName", remoteName)
    }

    // ----- modules ------------------------------------------------------------------------------------

    suspend fun buildModule(spec: ModuleSpec): Result<BuiltModule> = operation("Build module ${spec.id}") {
        val built = ModuleBuilder.build(spec)
        library.fileFor(built.fileName).writeBytes(built.zip)
        library.refresh()
        log.info("Built ${built.fileName} (${humanSize(built.zip.size.toLong())}, sha256 ${built.sha256})")
        built.entries.forEach { log.out("  $it") }
        built
    }

    suspend fun installModuleOnTarget(zip: LibraryFile, manager: RootManager): Result<String> = operation("Install ${zip.name} via ${manager.label}") {
        val conn = requireAdb()
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
        zip.file.inputStream().use { conn.push(it, zip.size, remote) { d, t -> setProgress("Pushing module", d, t) } }
        clearProgress()
        val bin = manager.moduleBinary
        val cmd = "su -c '$bin ${manager.moduleInstallCommand.substringAfter(' ')} $remote'"
        log.warn("The target may show a Superuser prompt for the shell; allow it once.")
        log.command("adb shell $cmd")
        val r = conn.shell(cmd, timeoutMs = 180_000) { log.out(it) }
        conn.shell("rm -f $remote")
        if (r.exitCode != 0) throw IOException("Module install exited with ${r.exitCode}. Is ${manager.label} active and root granted to shell?")
        r.output
    }

    // ----- this-phone modules (su on the host phone) --------------------------------------------------

    fun refreshLocalRoot() {
        localRootState.value = localRootState.value.copy(detection = RootManager.detect(localPackages()))
    }

    suspend fun listLocalModules(): Result<List<InstalledModule>> = operation("List modules on this phone") {
        val session = requireLocalSu()
        val id = session.su("id", 8_000)
        if (!LocalModules.parseRooted(id.output, id.exitCode)) {
            throw IOException("su is not available on this phone (need Magisk, KernelSU or APatch, and grant RootForge). ${id.output.trim().take(120)}")
        }
        val listing = session.su(LocalModules.MARKERS_SCRIPT, 10_000)
        val markers = LocalModules.markersFromAdbLs(listing.output)
        val detection = RootManager.detect(localPackages(), markers)
        log.info(detection.reason)
        val dump = session.su(LocalModules.LIST_SCRIPT, 30_000)
        log.out(dump.output)
        val modules = LocalModules.parseList(dump.output)
        localRootState.value = LocalRootSnapshot(detection, modules, listed = true)
        log.info(if (modules.isEmpty()) "No modules in ${LocalModules.MODULES_DIR}." else "Listed ${modules.size} module(s) in ${LocalModules.MODULES_DIR}.")
        modules
    }

    suspend fun installModuleLocally(zip: LibraryFile, manager: RootManager, dryRun: Boolean): Result<String> =
        operation(if (dryRun) "Dry-run ${zip.name} on this phone" else "Install ${zip.name} on this phone via ${manager.label}") {
            if (!zip.file.isFile) throw IOException("${zip.name} is not in the library.")
            val sha = FileDigest.sha256Hex(zip.file)
            val plan = LocalModules.plan(zip.file.absolutePath, zip.name, zip.size, sha, manager)
            plan.dryRunLines.forEach { log.info(it) }
            if (dryRun) {
                log.command(plan.displayCommand)
                log.info("Dry run finished. Nothing was installed and this phone was not rebooted.")
                return@operation plan.displayCommand
            }
            requireLocalSu()
            val approved = approvals.request(
                title = "Install ${zip.name} as root on this phone",
                lines = plan.approvalLines,
                phrase = "INSTALL MODULE",
            )
            if (!approved) throw IOException("Install was not confirmed; nothing was installed on this phone.")
            log.warn("This phone may show a Superuser prompt; allow it once.")
            log.command(plan.displayCommand)
            val r = ProcessRootSession().su(plan.script, 180_000)
            log.out(r.output)
            if (!r.ok) {
                throw IOException(
                    "Module install exited with ${r.exitCode}. Is ${manager.label} active and root granted to RootForge?",
                )
            }
            log.info("Install finished. RootForge did not reboot; use Reboot this phone if the manager asks for it.")
            r.output
        }

    suspend fun rebootThisPhone(): Result<Unit> = operation("Reboot this phone") {
        val session = requireLocalSu()
        val approved = approvals.request(
            title = "Reboot this phone",
            lines = listOf(
                "This reboots the phone you are holding, not a USB target.",
                "Newly installed modules load after this reboot.",
                "RootForge does not reboot on its own after a module install.",
            ),
            phrase = null,
            destructive = true,
        )
        if (!approved) throw IOException("Reboot was not confirmed.")
        log.command(LocalModules.suShell(LocalModules.rebootInner()))
        session.su(LocalModules.rebootInner(), 10_000)
        log.info("Reboot requested.")
    }

    private fun requireLocalSu(): ProcessRootSession {
        if (!canRunLocalSu) throw IOException("Installing with su on this host is only in the Android APK.")
        return ProcessRootSession()
    }

    // ----- custom recovery (rooted su/dd) -------------------------------------------------------------

    private fun recoveryInstaller() = RecoveryInstaller(log, library, approvals)

    fun probeThisPhone(): RecoveryProbe {
        val installer = recoveryInstaller()
        val props = localGetprop()
        val id = runCatching { ai.rootforge.os.recovery.ProcessCapture.run(RecoveryCommands.suArgv(RecoveryCommands.rootId()), 8_000) }
        val listing = runCatching {
            ai.rootforge.os.recovery.ProcessCapture.run(RecoveryCommands.suArgv(RecoveryCommands.listPartitions()), 12_000)
        }.recoverCatching {
            ai.rootforge.os.recovery.ProcessCapture.run(listOf("sh", "-c", RecoveryCommands.listPartitions()), 12_000)
        }.getOrNull()?.output.orEmpty()
        return installer.probe(
            RecoverySource.THIS_PHONE,
            props,
            listing,
            id.getOrNull()?.output ?: (id.exceptionOrNull()?.message ?: "su not found"),
            id.getOrNull()?.exit,
        )
    }

    suspend fun probeAdbRecovery(): Result<RecoveryProbe> = operation("Probe recovery (USB target)") {
        val conn = requireAdb()
        val installer = recoveryInstaller()
        val props = conn.getprop()
        val id = conn.shell(RecoveryCommands.suShell(RecoveryCommands.rootId()), 15_000)
        val rooted = installer.parseRoot(id.output, id.exitCode)
        val listing = if (rooted) {
            conn.shell(RecoveryCommands.suShell(RecoveryCommands.listPartitions()), 15_000).output
        } else {
            conn.shell(RecoveryCommands.listPartitions(), 15_000).output
        }
        installer.probe(RecoverySource.ADB_TARGET, props, listing, id.output, id.exitCode).also {
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
        val session = sessionFor(probe.source)
        recoveryInstaller().install(session, probe, image, dryRun, publishedMd5, publishedSha256)
    }

    suspend fun rebootCustomRecovery(probe: RecoveryProbe): Result<Unit> = operation("Reboot to recovery") {
        recoveryInstaller().rebootToRecovery(sessionFor(probe.source))
    }

    private fun sessionFor(source: RecoverySource): RootSession = when (source) {
        RecoverySource.THIS_PHONE -> ProcessRootSession()
        RecoverySource.ADB_TARGET -> AdbRootSession(requireAdb())
    }

    private fun localGetprop(): Map<String, String> {
        val dump = runCatching {
            ai.rootforge.os.recovery.ProcessCapture.run(listOf("getprop"), 8_000).output
        }.getOrNull()
        val parsed = dump?.let { ai.rootforge.os.recovery.RecoveryIdentity.parseGetprop(it) }.orEmpty()
        if (parsed.isNotEmpty()) return parsed
        return mapOf(
            "ro.product.device" to android.os.Build.DEVICE,
            "ro.build.product" to android.os.Build.DEVICE,
            "ro.product.name" to android.os.Build.PRODUCT,
            "ro.product.model" to android.os.Build.MODEL,
        )
    }

    // ----- plumbing -----------------------------------------------------------------------------------

    data class ShellOutcome(val output: String, val exitCode: Int?)

    private fun pullToLibrary(remotePath: String, saveAs: String): LibraryFile {
        require(remotePath.startsWith("/")) { "Remote path must be absolute" }
        val dest = library.fileFor(saveAs)
        log.command("adb pull $remotePath")
        dest.outputStream().use { out -> requireAdb().pull(remotePath, out) { setProgress("Pulling", it, 0) } }
        clearProgress()
        library.refresh()
        log.info("Saved ${humanSize(dest.length())} to library as ${dest.name}.")
        return LibraryFile(dest.name, dest)
    }

    private fun requireAdb(): AdbConnection = adb?.takeIf { it.connected } ?: throw IOException("The target is not connected over ADB (current mode: ${currentMode?.label ?: "none"}).")
    private fun requireFastboot(): FastbootClient = fastboot ?: throw IOException("The target is not in fastboot mode (current mode: ${currentMode?.label ?: "none"}). Use Reboot > Bootloader first.")

    private fun setProgress(label: String, done: Long, total: Long) {
        progressState.value = Progress(label, done, total)
    }

    private fun clearProgress() {
        progressState.value = null
    }

    private suspend fun <T> operation(label: String, block: suspend () -> T): Result<T> =
        operationOn(activeTarget?.rosterKey(), label, block)

    private fun refreshBusy() {
        if (logcatState.value) {
            busyState.value = "Logcat"
            return
        }
        val activeKey = SerialOpLock.key(activeTarget?.rosterKey())
        busyState.value = serialLock.busyLabel(activeKey) ?: serialLock.busyMap.value.values.firstOrNull()
    }

    private suspend fun <T> operationOn(serial: String?, label: String, block: suspend () -> T): Result<T> {
        val logcatSerial = activeTarget?.rosterKey()
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
                    clearProgress()
                }
            }
        } finally {
            refreshBusy()
        }
    }
}
