package ai.rootforge.os.device

import java.net.URI

/**
 * ESP32 and Raspberry Pi helpers for **owner** bootloader unlock and
 * own-device flashing only.
 *
 * REFUSED (ethics): screen-lock bypass, FRP (Factory Reset Protection)
 * bypass, HID / keyboard injection to type a PIN, pattern or password,
 * “button automation” against an authentication UI, or any flow whose
 * purpose is to defeat an ownership lock. Those requests are out of
 * scope even if they appear in a spec, a community guide page, or an
 * Ask question. Volume-key confirm during `fastboot flashing unlock` is
 * done by the owner on the phone. An ESP32 here is a USB-serial bridge,
 * never a key-injection gadget.
 *
 * A Raspberry Pi is a Linux host: it runs the same `:desktop`
 * `HostController` `adb` / `fastboot` path (dry-run, typed confirm,
 * backup before a recovery write, never auto-reboot).
 */
enum class BoardHelperKind {
    RASPBERRY_PI,
    ESP32,
    ANDROID_PLATFORM_TOOLS,
}

enum class BoardHelperRole {
    LINUX_HOST_ADB_FASTBOOT,
    USB_SERIAL_BRIDGE,
    FASTBOOT_TIMING,
}

data class BoardHelperPage(
    val label: String,
    val url: String,
    val allowedHosts: List<String>,
) {
    init {
        require(label.isNotBlank()) { "page label" }
        require(url.startsWith("https://")) { "$label: helper urls must be https" }
        require(!url.contains(' ')) { "$label: helper url has whitespace" }
        val host = URI(url).host.orEmpty().lowercase()
        require(allowedHosts.isNotEmpty()) { "$label: live page needs allowedHosts" }
        require(allowedHosts.any { host == it || host.endsWith(".$it") }) {
            "$label: $host is not an allowed host $allowedHosts"
        }
    }
}

data class BoardHelper(
    val id: String,
    val name: String,
    val kind: BoardHelperKind,
    val role: BoardHelperRole,
    val page: BoardHelperPage,
    val summary: String,
    val inScope: String,
    val extras: List<BoardHelperPage> = emptyList(),
) {
    init {
        require(id.isNotBlank() && name.isNotBlank()) { id }
        require(summary.isNotBlank() && inScope.isNotBlank()) { id }
        require(!summary.contains('\n') && !inScope.contains('\n')) { "$id: keep helper text one line" }
    }

    val urls: List<String> get() = listOf(page.url) + extras.map { it.url }
}

object BoardHelpers {
    /**
     * In-app refuse lines. Kept here so Links, Help, Ask and tests share
     * the same wording. Do not turn these into how-to steps.
     */
    val REFUSED: List<String> = listOf(
        "Screen-lock bypass (PIN, pattern, password, fingerprint UI)",
        "Factory Reset Protection (FRP) / Google account lock bypass",
        "HID or serial injection that types credentials or dismisses an auth screen",
        "Any helper whose purpose is to use a phone the operator does not own",
    )

    val RASPBERRY_PI = BoardHelper(
        id = "raspberry-pi",
        name = "Raspberry Pi as a Linux host",
        kind = BoardHelperKind.RASPBERRY_PI,
        role = BoardHelperRole.LINUX_HOST_ADB_FASTBOOT,
        page = BoardHelperPage(
            "Raspberry Pi documentation",
            "https://github.com/raspberrypi/documentation",
            listOf("github.com"),
        ),
        extras = listOf(
            BoardHelperPage(
                "Raspberry Pi product information",
                "https://pip.raspberrypi.com/",
                listOf("raspberrypi.com"),
            ),
        ),
        summary = "A Pi running Raspberry Pi OS 64-bit is a Linux computer. Install JDK 17 and host adb/fastboot, then run the desktop client (`:desktop:run`) or the CLI. Same HostController as a PC: adb -s / fastboot -s, dry-run, typed confirm, backup first, never auto-reboot. This agent saw HTTP 403 from raspberrypi.com (Cloudflare); GitHub raspberrypi/documentation returned 200 (2026-10-20).",
        inScope = "USB host for phones you own: identify, unlock bootloader, flash with the existing desktop safety gates. Not a lock-pick.",
    )

    val ESP32_SERIAL = BoardHelper(
        id = "esp32-usb-serial",
        name = "ESP32 USB-serial bridge",
        kind = BoardHelperKind.ESP32,
        role = BoardHelperRole.USB_SERIAL_BRIDGE,
        page = BoardHelperPage(
            "ESP32-S3 USB Serial/JTAG",
            "https://docs.espressif.com/projects/esp-idf/en/latest/esp32s3/api-guides/usb-serial-jtag-console.html",
            listOf("espressif.com"),
        ),
        extras = listOf(
            BoardHelperPage(
                "ESP32-S3 product",
                "https://www.espressif.com/en/products/socs/esp32-s3",
                listOf("espressif.com"),
            ),
            BoardHelperPage(
                "ESP-IDF get started",
                "https://docs.espressif.com/projects/esp-idf/en/latest/esp32s3/get-started/index.html",
                listOf("espressif.com"),
            ),
        ),
        summary = "An ESP32-S3 can expose Espressif's USB Serial/JTAG CDC so a Pi or PC has a serial console. Use that to watch your own board or a debug UART you already wired. HTTP 200 on espressif.com (2026-10-20). RootForge does not ship HID firmware and will not type keys.",
        inScope = "USB-serial (CDC) bridge for owner debug. Not a key-injection gadget, not FRP, not a screen-lock tool.",
    )

    val FASTBOOT_TIMING = BoardHelper(
        id = "fastboot-timing",
        name = "Timing fastboot after reboot",
        kind = BoardHelperKind.ANDROID_PLATFORM_TOOLS,
        role = BoardHelperRole.FASTBOOT_TIMING,
        page = BoardHelperPage(
            "SDK Platform Tools",
            "https://developer.android.com/tools/releases/platform-tools",
            listOf("android.com"),
        ),
        extras = listOf(
            BoardHelperPage(
                "Bootloader locking and unlocking",
                "https://source.android.com/docs/core/architecture/bootloader/locking_unlocking",
                listOf("android.com"),
            ),
        ),
        summary = "After Reboot → Bootloader, wait until the phone enumerates in fastboot (the Devices poll) before Unlock. Do not race commands. Dry-run first; typed UNLOCK still gates the write; the phone's volume-key confirm is yours. HTTP 200 on android.com (2026-10-20).",
        inScope = "Settle time for owner unlock/flash on a device already in the bootloader with OEM unlocking on. Not a way to skip authentication.",
    )

    val all: List<BoardHelper> = listOf(RASPBERRY_PI, ESP32_SERIAL, FASTBOOT_TIMING)

    fun urls(): List<String> = all.flatMap { it.urls }

    fun refusedText(): String =
        "RootForge will not help with: ${REFUSED.joinToString("; ")}. Use the maker's official account-recovery flow if you are locked out."
}

/**
 * Captions for the Devices empty-state. A Raspberry Pi is a Linux host,
 * not a second USB stack.
 */
object HostPlatform {
    const val PHONE_CAPTION = "this phone"
    const val COMPUTER_CAPTION = "this computer"
    const val PI_CAPTION = "this Raspberry Pi"

    fun raspberryPiModel(modelText: String?): String? {
        val text = modelText
            ?.trim { it <= ' ' || it == '\u0000' }
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return if (text.contains("Raspberry Pi", ignoreCase = true)) text else null
    }

    fun hostCaption(deviceTreeModel: String? = null, androidHost: Boolean = false): String {
        if (androidHost) return PHONE_CAPTION
        return if (raspberryPiModel(deviceTreeModel) != null) PI_CAPTION else COMPUTER_CAPTION
    }

    fun isHostComputer(caption: String): Boolean = caption != PHONE_CAPTION
}
