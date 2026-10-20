package ai.rootforge.os.device

import ai.rootforge.os.usb.TargetMode

enum class RootGuideKind {
    PIXEL,
    SAMSUNG,
    XIAOMI,
    ONEPLUS,
    MOTOROLA,
    SONY,
    HTC,
    FAIRPHONE,
    NOTHING,
    OPPO,
    REALME,
    VIVO,
    NOKIA,
    LENOVO,
    ASUS,
    LG,
    HUAWEI,
    TRANSSION,
    GENERIC,
}

enum class RootGuideAction {
    NONE,
    OPEN_URL,
    OPEN_ADB,
    OPEN_FASTBOOT,
    OPEN_RECOVERY,
    OPEN_ROOT,
}

data class RootGuideStep(
    val id: String,
    val title: String,
    val detail: String,
    val erases: String,
    val dryRunFirst: Boolean,
    val confirmPhrase: String? = null,
    val action: RootGuideAction = RootGuideAction.NONE,
    val url: String? = null,
    val write: Boolean = false,
    val autoReboot: Boolean = false,
) {
    init {
        require(!autoReboot) { "$id: guides never auto-reboot" }
        require(url == null || url.startsWith("https://")) { "$id: urls must be https" }
        if (action == RootGuideAction.OPEN_URL) require(!url.isNullOrBlank()) { "$id: OPEN_URL needs a url" }
        if (write) require(!confirmPhrase.isNullOrBlank()) { "$id: a write needs a typed phrase" }
    }
}

data class RootGuide(
    val id: String,
    val kind: RootGuideKind,
    val title: String,
    val summary: String,
    val brands: List<String>,
    val steps: List<RootGuideStep>,
    val knoxWarning: String? = null,
    val odinFlashing: Boolean = false,
)

/**
 * Brand-specific step lists. Matching is from the read-only identity.
 * Writes still go through existing Fastboot / Custom Recovery / Root screens
 * (dry-run, typed phrase, no auto-reboot). Samsung Download Mode / Odin is
 * explained only; RootForge does not speak Odin.
 */
object RootGuides {
    const val WIDE_LAYOUT_DP = 840

    fun columnsForWidthDp(widthDp: Int): Int = if (widthDp >= WIDE_LAYOUT_DP) 2 else 1

    fun forIdentity(identity: DeviceIdentity?): RootGuide {
        if (identity == null || identity.mode == TargetMode.EDL) return GENERIC
        val kind = BrandCatalog.forIdentity(identity)?.guideKind
        if (kind != null) return all.first { it.kind == kind }
        val tokens = identity.brandTokens()
        return all.firstOrNull { guide ->
            guide.kind != RootGuideKind.GENERIC && guide.brands.any { token -> token.lowercase() in tokens }
        } ?: GENERIC
    }

    private val PIXEL = RootGuide(
        id = "pixel",
        kind = RootGuideKind.PIXEL,
        title = "Google Pixel",
        summary = "OEM unlocking, then fastboot unlock, then custom recovery, then a root manager.",
        brands = listOf("google", "pixel"),
        steps = listOf(
            officialPortalStep(UnlockPortals.GOOGLE),
            readUnlockDataStep(UnlockPortals.GOOGLE),
            step(
                "oem", "Turn on OEM unlocking",
                "On the phone: Settings → About → tap Build number seven times. Then Developer options → OEM unlocking and USB debugging.",
                erases = "Nothing yet.",
                action = RootGuideAction.OPEN_ADB,
            ),
            step(
                "bootloader", "Reboot to the bootloader",
                "From ADB use Reboot → Bootloader, or hold Volume down + Power while the Pixel is off. RootForge never reboots the phone for you.",
                erases = "Nothing yet.",
                action = RootGuideAction.OPEN_ADB,
            ),
            step(
                "unlock", "Unlock the bootloader",
                "On Fastboot, tap Unlock. RootForge shows the plan first. Type UNLOCK. The Pixel also asks you to confirm with volume keys. Nothing is written until both confirms succeed.",
                erases = "All user data (factory reset). Photos, accounts and apps on the phone are wiped.",
                dryRunFirst = true,
                confirmPhrase = "UNLOCK",
                action = RootGuideAction.OPEN_FASTBOOT,
                write = true,
            ),
            recoveryStep(),
            rootStep(),
        ),
    )

    private val SAMSUNG = RootGuide(
        id = "samsung",
        kind = RootGuideKind.SAMSUNG,
        title = "Samsung",
        summary = "Download Mode and Knox are Samsung-specific. RootForge does not run Odin.",
        brands = listOf("samsung"),
        knoxWarning = "Unlocking or unofficial images usually trip Knox (Warranty bit 0x1). That is permanent: Samsung Pay, Secure Folder and the warranty typically stop. RootForge cannot undo it.",
        odinFlashing = false,
        steps = listOf(
            officialPortalStep(UnlockPortals.SAMSUNG),
            readUnlockDataStep(UnlockPortals.SAMSUNG),
            step(
                "knox", "Read the Knox warning",
                "Knox is Samsung's tamper flag. A tripped flag does not brick the phone, but it does not reset. If you need banking apps or Samsung Pay, stop here.",
                erases = "Nothing yet. A later unlock or unofficial flash is what trips Knox.",
            ),
            step(
                "download", "Download Mode is not fastboot",
                "Many Samsungs use Download Mode (often Volume down + Power, or Volume down + Bixby + Power), not Google fastboot. Odin is Samsung's PC program for that mode. RootForge has no Odin protocol and will not flash a tar in Download Mode.",
                erases = "Nothing. This step is explanation only.",
            ),
            step(
                "odin-link", "Odin stays on a PC, not in this app",
                "If you need Odin, that is a separate Samsung-side tool on a computer. RootForge will not download or run it. Matching TWRP/PitchBlack rows can still open Custom Recovery when this phone is in ADB with root — that path is the existing su/dd installer, not Odin.",
                erases = "Nothing from this button. A later Custom Recovery write still needs typed FLASH RECOVERY and a backup first.",
                action = RootGuideAction.OPEN_URL,
                url = "https://www.samsungknox.com/en",
            ),
            step(
                "fastboot-optional", "If this phone is actually in fastboot",
                "Some models expose fastboot. You may use RootForge's existing Fastboot Unlock. That still types UNLOCK, still wipes data, still trips Knox, and still does not reboot by itself. It is not Odin.",
                erases = "All user data if you confirm UNLOCK.",
                dryRunFirst = true,
                confirmPhrase = "UNLOCK",
                action = RootGuideAction.OPEN_FASTBOOT,
                write = true,
            ),
            recoveryStep(),
            rootStep(),
        ),
    )

    private val XIAOMI = RootGuide(
        id = "xiaomi",
        kind = RootGuideKind.XIAOMI,
        title = "Xiaomi / Redmi / POCO",
        summary = "Bind a Xiaomi account, wait out Mi Unlock, then fastboot unlock.",
        brands = listOf("xiaomi", "redmi", "poco"),
        steps = listOf(
            step(
                "oem", "Developer options and Mi Unlock status",
                "Enable OEM unlocking and USB debugging. Sign in with a Xiaomi account on the phone and request unlocking in Developer options (wording varies by MIUI / HyperOS).",
                erases = "Nothing yet.",
                action = RootGuideAction.OPEN_ADB,
            ),
            readUnlockDataStep(UnlockPortals.XIAOMI),
            step(
                "wait", "Mi Unlock wait period",
                "Xiaomi often makes you wait 7 to 30 days (sometimes longer on HyperOS) after the account is bound before it will allow an unlock. RootForge cannot skip, shorten, or fake that wait. The clock is on Xiaomi's side. Open Xiaomi's official Mi Unlock page in the browser; RootForge never logs into a Xiaomi account.",
                erases = "Nothing during the wait.",
                action = RootGuideAction.OPEN_URL,
                url = UnlockPortals.XIAOMI.url,
            ),
            step(
                "unlock", "Unlock in fastboot after the wait",
                "When Xiaomi says the wait is over, reboot to the bootloader. On Fastboot tap Unlock and type UNLOCK. Dry-run is the plan on screen; nothing is written until you confirm. RootForge never reboots the phone for you.",
                erases = "All user data (factory reset).",
                dryRunFirst = true,
                confirmPhrase = "UNLOCK",
                action = RootGuideAction.OPEN_FASTBOOT,
                write = true,
            ),
            recoveryStep(),
            rootStep(),
        ),
    )

    private val ONEPLUS = RootGuide(
        id = "oneplus",
        kind = RootGuideKind.ONEPLUS,
        title = "OnePlus",
        summary = "OEM unlocking, then fastboot unlock. Some ColorOS builds also need OnePlus's Deep Testing / unlock token.",
        brands = listOf("oneplus"),
        steps = listOf(
            officialPortalStep(UnlockPortals.ONEPLUS),
            readUnlockDataStep(UnlockPortals.ONEPLUS),
            step(
                "oem", "Turn on OEM unlocking",
                "Developer options → OEM unlocking and USB debugging. On some ColorOS OnePlus phones you must also apply for Deep Testing in the system app before fastboot will accept unlock. RootForge does not request that token for you.",
                erases = "Nothing yet.",
                action = RootGuideAction.OPEN_ADB,
            ),
            step(
                "unlock", "Unlock the bootloader",
                "Reboot to the bootloader yourself. On Fastboot tap Unlock and type UNLOCK. The plan is shown first. The phone is not rebooted for you.",
                erases = "All user data (factory reset).",
                dryRunFirst = true,
                confirmPhrase = "UNLOCK",
                action = RootGuideAction.OPEN_FASTBOOT,
                write = true,
            ),
            recoveryStep(),
            rootStep(),
        ),
    )

    private val MOTOROLA = RootGuide(
        id = "motorola",
        kind = RootGuideKind.MOTOROLA,
        title = "Motorola",
        summary = "Get an unlock code from Motorola, then fastboot unlock.",
        brands = listOf("motorola", "moto"),
        steps = listOf(
            readUnlockDataStep(UnlockPortals.MOTOROLA),
            step(
                "code", "Get the official unlock code",
                "Motorola issues a per-phone unlock key from its support site after you paste the bootloader identifier. Copy oem get_unlock_data yourself onto that page. RootForge does not fetch or type that code, and never uploads those lines.",
                erases = "Nothing on the Motorola site. Unlocking later wipes the phone.",
                action = RootGuideAction.OPEN_URL,
                url = UnlockPortals.MOTOROLA.url,
            ),
            step(
                "unlock", "Unlock with Motorola's code",
                "Reboot to the bootloader. On Fastboot tap Unlock and type UNLOCK. If this model needs `oem unlock <code>`, do that on a computer after reading Motorola's page — RootForge's Unlock button is the existing `flashing unlock` / `oem unlock` path, still gated by typed UNLOCK, and never auto-reboots.",
                erases = "All user data (factory reset).",
                dryRunFirst = true,
                confirmPhrase = "UNLOCK",
                action = RootGuideAction.OPEN_FASTBOOT,
                write = true,
            ),
            recoveryStep(),
            rootStep(),
        ),
    )

    private val SONY = oemPortalGuide(
        id = "sony",
        kind = RootGuideKind.SONY,
        title = "Sony Xperia",
        summary = "Sony Open Devices issues an unlock code from IMEI 1, then fastboot unlock.",
        brands = listOf("sony", "xperia"),
        portal = UnlockPortals.SONY,
    )

    private val HTC = oemPortalGuide(
        id = "htc",
        kind = RootGuideKind.HTC,
        title = "HTC",
        summary = "HTCdev identifier token, then fastboot unlock — only for models HTCdev still lists.",
        brands = listOf("htc"),
        portal = UnlockPortals.HTC,
    )

    private val FAIRPHONE_GUIDE = oemPortalGuide(
        id = "fairphone",
        kind = RootGuideKind.FAIRPHONE,
        title = "Fairphone",
        summary = "OEM unlocking, then fastboot unlock. Older software used Fairphone's code page.",
        brands = listOf("fairphone"),
        portal = UnlockPortals.FAIRPHONE,
    )

    private val NOTHING_GUIDE = oemPortalGuide(
        id = "nothing",
        kind = RootGuideKind.NOTHING,
        title = "Nothing / CMF",
        summary = "Nothing has no token webpage. OEM unlocking, then fastboot unlock.",
        brands = listOf("nothing", "cmf"),
        portal = UnlockPortals.NOTHING,
    )

    private val OPPO = oemPortalGuide(
        id = "oppo",
        kind = RootGuideKind.OPPO,
        title = "Oppo",
        summary = "ColorOS Deep Testing is in-phone. No public token webpage. Then fastboot unlock if Oppo allows it.",
        brands = listOf("oppo"),
        portal = UnlockPortals.OPPO,
    )

    private val REALME = oemPortalGuide(
        id = "realme",
        kind = RootGuideKind.REALME,
        title = "realme",
        summary = "Realme UI Deep Testing is in-phone. No public token webpage. Then fastboot unlock if realme allows it.",
        brands = listOf("realme"),
        portal = UnlockPortals.REALME,
    )

    private val VIVO = closedGuide(
        id = "vivo",
        kind = RootGuideKind.VIVO,
        title = "vivo / iQOO",
        summary = "vivo does not publish an official unlock programme.",
        brands = listOf("vivo", "iqoo"),
        portal = UnlockPortals.VIVO,
    )

    private val NOKIA = oemPortalGuide(
        id = "nokia",
        kind = RootGuideKind.NOKIA,
        title = "HMD / Nokia",
        summary = "OEM unlocking, then fastboot unlock. HMD has no token webpage.",
        brands = listOf("nokia", "hmd"),
        portal = UnlockPortals.NOKIA,
    )

    private val LENOVO_GUIDE = oemPortalGuide(
        id = "lenovo",
        kind = RootGuideKind.LENOVO,
        title = "Lenovo",
        summary = "No public token webpage. OEM unlocking, then fastboot unlock if this model allows it. Motorola has its own portal.",
        brands = listOf("lenovo"),
        portal = UnlockPortals.LENOVO,
    )

    private val ASUS_GUIDE = closedGuide(
        id = "asus",
        kind = RootGuideKind.ASUS,
        title = "ASUS",
        summary = "ASUS shut its unlock servers. There is no live official portal.",
        brands = listOf("asus", "zenfone"),
        portal = UnlockPortals.ASUS,
    )

    private val LG_GUIDE = closedGuide(
        id = "lg",
        kind = RootGuideKind.LG,
        title = "LG",
        summary = "LG shut its official unlock-code site. LG no longer offers unlocking.",
        brands = listOf("lge", "lg"),
        portal = UnlockPortals.LG,
    )

    private val HUAWEI_GUIDE = closedGuide(
        id = "huawei",
        kind = RootGuideKind.HUAWEI,
        title = "Huawei / Honor",
        summary = "Huawei stopped issuing unlock codes in 2018.",
        brands = listOf("huawei", "honor"),
        portal = UnlockPortals.HUAWEI,
    )

    private val TRANSSION = oemPortalGuide(
        id = "transsion",
        kind = RootGuideKind.TRANSSION,
        title = "TECNO / Infinix / itel",
        summary = "Transsion brands usually unlock with OEM unlocking then fastboot. No maker token page.",
        brands = listOf("tecno", "infinix", "itel"),
        portal = UnlockPortals.TECNO,
        extraNote = "TECNO, Infinix and itel share this path. The Root plan still shows that exact brand's official page when one exists.",
    )

    private val GENERIC = RootGuide(
        id = "generic",
        kind = RootGuideKind.GENERIC,
        title = "Generic fastboot",
        summary = "The common path when the brand is unknown: OEM unlocking, fastboot unlock, recovery, root manager.",
        brands = emptyList(),
        steps = listOf(
            step(
                "oem", "Turn on OEM unlocking",
                "If the maker allows it: Developer options → OEM unlocking and USB debugging. Some phones hide this until an account or wait period is done.",
                erases = "Nothing yet.",
                action = RootGuideAction.OPEN_ADB,
            ),
            step(
                "unlock", "Unlock in fastboot",
                "Reboot to the bootloader yourself. On Fastboot tap Unlock and type UNLOCK. The plan is shown first. Nothing is written until you confirm. RootForge never reboots the phone for you.",
                erases = "Usually all user data (factory reset). Stop if you have not backed up.",
                dryRunFirst = true,
                confirmPhrase = "UNLOCK",
                action = RootGuideAction.OPEN_FASTBOOT,
                write = true,
            ),
            recoveryStep(),
            rootStep(),
        ),
    )

    val all: List<RootGuide> = listOf(
        PIXEL, SAMSUNG, XIAOMI, ONEPLUS, MOTOROLA, SONY, HTC, FAIRPHONE_GUIDE, NOTHING_GUIDE,
        OPPO, REALME, VIVO, NOKIA, LENOVO_GUIDE, ASUS_GUIDE, LG_GUIDE, HUAWEI_GUIDE, TRANSSION, GENERIC,
    )

    private fun oemPortalGuide(
        id: String,
        kind: RootGuideKind,
        title: String,
        summary: String,
        brands: List<String>,
        portal: UnlockPortal,
        extraNote: String? = null,
    ) = RootGuide(
        id = id,
        kind = kind,
        title = title,
        summary = summary,
        brands = brands,
        steps = listOfNotNull(
            officialPortalStep(portal),
            readUnlockDataStep(portal),
            extraNote?.let {
                step("brand-note", "This brand family", it, erases = "Nothing yet.")
            },
            step(
                "oem", "Turn on OEM unlocking",
                "Developer options → OEM unlocking and USB debugging. Some ColorOS / Realme UI / HMD builds also need an in-phone Deep Testing app. RootForge does not apply for that token.",
                erases = "Nothing yet.",
                action = RootGuideAction.OPEN_ADB,
            ),
            step(
                "unlock", "Unlock the bootloader",
                "Reboot to the bootloader yourself. On Fastboot tap Unlock and type UNLOCK. The plan is shown first. The phone is not rebooted for you.",
                erases = "All user data (factory reset).",
                dryRunFirst = true,
                confirmPhrase = "UNLOCK",
                action = RootGuideAction.OPEN_FASTBOOT,
                write = true,
            ),
            recoveryStep(),
            rootStep(),
        ),
    )

    private fun closedGuide(
        id: String,
        kind: RootGuideKind,
        title: String,
        summary: String,
        brands: List<String>,
        portal: UnlockPortal,
    ) = RootGuide(
        id = id,
        kind = kind,
        title = title,
        summary = summary,
        brands = brands,
        steps = listOf(
            officialPortalStep(portal),
            readUnlockDataStep(portal),
            step(
                "closed", "Official unlocking is not offered",
                "${portal.note} RootForge will not invent a portal or an unofficial tool. Firmware/support links, when shown, are official https only.",
                erases = "Nothing. This step is explanation only.",
            ),
            step(
                "fastboot-optional", "If this phone is actually in fastboot and still accepts unlock",
                "A closed programme does not always mean the bootloader refuses `flashing unlock`. If you still choose Unlock, type UNLOCK, expect a wipe, and know the maker will not help. RootForge never auto-reboots.",
                erases = "All user data if you confirm UNLOCK.",
                dryRunFirst = true,
                confirmPhrase = "UNLOCK",
                action = RootGuideAction.OPEN_FASTBOOT,
                write = true,
            ),
            recoveryStep(),
            rootStep(),
        ),
    )

    private fun officialPortalStep(portal: UnlockPortal) = step(
        "portal",
        portal.name,
        if (portal.url != null) {
            "${portal.note} Opens in the system browser only. RootForge never sends serial, IMEI or unlock data there."
        } else if (!portal.offered) {
            "${portal.note} This brand no longer offers official unlocking. There is no portal to open."
        } else {
            "${portal.note} There is no maker token webpage to open."
        },
        erases = "Nothing on a maker website. Unlocking later usually wipes the phone.",
        action = if (portal.url != null) RootGuideAction.OPEN_URL else RootGuideAction.NONE,
        url = portal.url,
    )

    private fun readUnlockDataStep(portal: UnlockPortal) = step(
        "unlock-data",
        "Read unlock data",
        if (portal.unlockDataCommand != null) {
            "${portal.unlockData} Dry-run first; a later Unlock still needs typed UNLOCK. This read is not a write. RootForge never pastes the output into a site."
        } else {
            portal.unlockData
        },
        erases = "Nothing. Reading an identifier does not unlock or wipe.",
        dryRunFirst = portal.unlockDataCommand != null,
        action = if (portal.unlockDataCommand != null) RootGuideAction.OPEN_FASTBOOT else RootGuideAction.NONE,
    )

    private fun recoveryStep() = step(
        "recovery", "Custom recovery",
        "Open Custom Recovery for this serial. Official TWRP or PitchBlack is looked up from the probed phone. Dry-run stays on. A write needs typed FLASH RECOVERY, backs up first, writes only a dedicated recovery partition, and does not reboot.",
        erases = "The recovery partition is replaced after a backup. User data is not wiped by that write.",
        dryRunFirst = true,
        confirmPhrase = "FLASH RECOVERY",
        action = RootGuideAction.OPEN_RECOVERY,
        write = true,
    )

    private fun rootStep() = step(
        "root", "Root manager",
        "Open Root / Patching for this serial. RootForge pushes a boot image and opens Magisk, KernelSU or APatch; it does not patch the image itself. Later module installs still need typed INSTALL MODULE. Nothing reboots by itself.",
        erases = "Nothing in this step. A later fastboot flash of a patched boot image writes that partition after a typed confirm.",
        dryRunFirst = true,
        action = RootGuideAction.OPEN_ROOT,
    )

    private fun step(
        id: String,
        title: String,
        detail: String,
        erases: String,
        dryRunFirst: Boolean = false,
        confirmPhrase: String? = null,
        action: RootGuideAction = RootGuideAction.NONE,
        url: String? = null,
        write: Boolean = false,
    ) = RootGuideStep(
        id = id,
        title = title,
        detail = detail,
        erases = erases,
        dryRunFirst = dryRunFirst,
        confirmPhrase = confirmPhrase,
        action = action,
        url = url,
        write = write,
        autoReboot = false,
    )
}
