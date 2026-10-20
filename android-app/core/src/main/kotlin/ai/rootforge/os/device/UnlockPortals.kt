package ai.rootforge.os.device

import ai.rootforge.os.usb.TargetMode
import java.net.URI

/**
 * Official maker bootloader-unlock token/code pages. Live URLs were fetched
 * 2026-10-20 (https, vendor domain). RootForge only opens them in the system
 * browser. It never pastes serial, IMEI or `oem get_unlock_data` output into
 * a site.
 */
data class UnlockPortal(
    val id: String,
    val name: String,
    val brands: List<String>,
    val url: String?,
    val offered: Boolean,
    val note: String,
    val unlockData: String,
    val unlockDataCommand: String? = null,
    val allowedHosts: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank() && name.isNotBlank() && note.isNotBlank() && unlockData.isNotBlank()) { id }
        require(!note.contains('\n') && !unlockData.contains('\n')) { "$id: keep portal text one line" }
        if (url != null) {
            require(url.startsWith("https://")) { "$id: portal urls must be https" }
            require(!url.contains(' ')) { "$id: portal url has whitespace" }
            val host = URI(url).host.orEmpty().lowercase()
            require(allowedHosts.isNotEmpty()) { "$id: live portal needs allowedHosts" }
            require(allowedHosts.any { host == it || host.endsWith(".$it") }) {
                "$id: $host is not a vendor host $allowedHosts"
            }
        } else {
            require(!offered || unlockData.isNotBlank()) { "$id: no URL still needs unlock-data wording" }
        }
        if (!offered) require(url == null) { "$id: a closed programme has no live portal URL" }
    }
}

object UnlockPortals {
    val MOTOROLA = UnlockPortal(
        id = "motorola",
        name = "Motorola unlock portal",
        brands = listOf("motorola", "moto"),
        url = "https://en-us.support.motorola.com/app/standalone/bootloader/unlock-your-device-a",
        offered = true,
        allowedHosts = listOf("motorola.com"),
        note = "Motorola issues a per-phone unlock key after you paste the bootloader identifier. HTTP 200 on motorola.com (2026-10-20). RootForge does not fetch or type that key.",
        unlockData = "On Fastboot, read the identifier with oem get_unlock_data. Copy it yourself onto Motorola's page if you want a code. Dry-run first; RootForge never uploads those lines.",
        unlockDataCommand = "oem get_unlock_data",
    )

    val SONY = UnlockPortal(
        id = "sony",
        name = "Sony Open Devices unlock",
        brands = listOf("sony", "xperia"),
        url = "https://developer.sony.com/open-source/aosp-on-xperia-open-devices/get-started/unlock-bootloader",
        offered = true,
        allowedHosts = listOf("sony.com"),
        note = "Sony's Open Devices page issues an unlock code from IMEI / IDID / MEID. This agent saw HTTP 202 (WAF) with an empty body; the host is developer.sony.com. RootForge never sends IMEI for you.",
        unlockData = "Read IMEI 1 with *#06# on the phone (or from the box). Dual-SIM phones must use IMEI 1. Paste it yourself on Sony's page. RootForge never sends IMEI.",
    )

    val XIAOMI = UnlockPortal(
        id = "xiaomi",
        name = "Xiaomi Mi Unlock",
        brands = listOf("xiaomi", "redmi", "poco", "blackshark"),
        url = "https://en.miui.com/unlock/",
        offered = true,
        allowedHosts = listOf("miui.com"),
        note = "Official Mi Unlock page (en.miui.com, HTTP 200 on 2026-10-20). Bind a Xiaomi account and wait; RootForge cannot skip the wait and does not run the PC unlock tool.",
        unlockData = "There is no oem get_unlock_data for Xiaomi. Bind the account under Developer options → Mi Unlock status, then use Xiaomi's PC tool after the wait. RootForge never logs into a Xiaomi account.",
    )

    val HTC = UnlockPortal(
        id = "htc",
        name = "HTCdev unlock",
        brands = listOf("htc"),
        url = "https://www.htcdev.com/bootloader",
        offered = true,
        allowedHosts = listOf("htcdev.com"),
        note = "HTCdev still serves the unlock portal (HTTP 200 on 2026-10-20). HTC says models released after June 2018 are not supported. RootForge does not submit the identifier token.",
        unlockData = "On Fastboot, read the identifier token with oem get_identifier_token. Copy it yourself onto HTCdev if this model is still supported. Dry-run first; nothing is uploaded.",
        unlockDataCommand = "oem get_identifier_token",
    )

    val ONEPLUS = UnlockPortal(
        id = "oneplus",
        name = "OnePlus unlock token",
        brands = listOf("oneplus"),
        url = "https://www.oneplus.com/us/unlock_token",
        offered = true,
        allowedHosts = listOf("oneplus.com"),
        note = "OnePlus unlock-token page (oneplus.com, HTTP 200 on 2026-10-20). Some ColorOS builds use the in-phone Deep Testing app instead. RootForge does not apply for that token.",
        unlockData = "On Fastboot, read serial (getvar serialno) and any unlock code the bootloader prints. Paste them yourself on OnePlus's page if this model needs a token. RootForge never sends IMEI or serial to OnePlus.",
        unlockDataCommand = "getvar serialno",
    )

    val FAIRPHONE = UnlockPortal(
        id = "fairphone",
        name = "Fairphone unlock",
        brands = listOf("fairphone"),
        url = "https://www.fairphone.com/bootloader-unlocking-code-for-fairphone",
        offered = true,
        allowedHosts = listOf("fairphone.com"),
        note = "Fairphone's official page (fairphone.com, HTTP 200 on 2026-10-20). Current software often unlocks from Developer options with no code; older builds still use the code generator. RootForge never submits IMEI.",
        unlockData = "Current Fairphones: turn on OEM unlocking, then Fastboot Unlock (typed UNLOCK). Older software: copy IMEI / serial yourself onto Fairphone's code page. RootForge never sends those numbers.",
    )

    val GOOGLE = UnlockPortal(
        id = "google",
        name = "Google Pixel",
        brands = listOf("google", "pixel"),
        url = null,
        offered = true,
        note = "Google does not issue an unlock token. There is no official portal. Turn on OEM unlocking, then use Fastboot Unlock (typed UNLOCK).",
        unlockData = "No oem get_unlock_data. After OEM unlocking, open Fastboot and type UNLOCK. Dry-run first; unlocking wipes the phone.",
    )

    val SAMSUNG = UnlockPortal(
        id = "samsung",
        name = "Samsung",
        brands = listOf("samsung"),
        url = null,
        offered = false,
        note = "Samsung does not publish an official unlock-token website. Many models use Download Mode / Odin, which RootForge does not speak. Unlocking unofficially usually trips Knox for good.",
        unlockData = "There is no official get_unlock_data portal. If this phone is actually in fastboot, Unlock is the existing typed-UNLOCK path (still a wipe, still trips Knox).",
    )

    val NOTHING = UnlockPortal(
        id = "nothing",
        name = "Nothing",
        brands = listOf("nothing"),
        url = null,
        offered = true,
        note = "Nothing does not publish an unlock-token webpage (nothing.tech has no official portal; checked 2026-10-20). Unlocking is usually OEM unlocking then fastboot.",
        unlockData = "No maker token to read. Turn on OEM unlocking, then Fastboot Unlock (typed UNLOCK). RootForge never sends serials to a site.",
    )

    val ASUS = UnlockPortal(
        id = "asus",
        name = "ASUS",
        brands = listOf("asus", "zenfone"),
        url = null,
        offered = false,
        note = "ASUS no longer offers a bootloader unlock tool (unlock servers were shut down; phones after January 2024 are not covered). There is no live official portal to link.",
        unlockData = "No official identifier to read. RootForge will not invent an ASUS unlock site.",
    )

    val LG = UnlockPortal(
        id = "lg",
        name = "LG",
        brands = listOf("lge", "lg"),
        url = null,
        offered = false,
        note = "LG shut its official unlock-code site (developer.lge.com is gone). LG no longer offers bootloader unlocking.",
        unlockData = "No official identifier to read. RootForge will not invent an LG unlock site.",
    )

    val HUAWEI = UnlockPortal(
        id = "huawei",
        name = "Huawei",
        brands = listOf("huawei", "honor"),
        url = null,
        offered = false,
        note = "Huawei stopped issuing bootloader unlock codes in 2018. There is no live official portal.",
        unlockData = "No official identifier to read. RootForge will not invent a Huawei unlock site.",
    )

    val all: List<UnlockPortal> = listOf(
        MOTOROLA, SONY, XIAOMI, HTC, ONEPLUS, FAIRPHONE, GOOGLE, SAMSUNG, NOTHING, ASUS, LG, HUAWEI,
    )

    fun urls(): List<String> = all.mapNotNull { it.url }

    fun forIdentity(identity: DeviceIdentity?): UnlockPortal? {
        if (identity == null || identity.mode == TargetMode.EDL) return null
        val tokens = identity.brandTokens()
        return all.firstOrNull { portal -> portal.brands.any { token -> token.lowercase() in tokens } }
    }
}
