package ai.rootforge.os.ui

import ai.rootforge.os.core.LinkState
import ai.rootforge.os.core.LogLevel
import ai.rootforge.os.core.LogLine
import ai.rootforge.os.core.TargetInfo
import ai.rootforge.os.ui.theme.RootForgeTheme
import ai.rootforge.os.usb.AttachedTarget
import ai.rootforge.os.usb.TargetMode
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

internal fun previewState(): AppUiState = AppUiState(
    targets = listOf(
        AttachedTarget(
            deviceName = "/dev/bus/usb/001/002",
            vendorId = 0x18D1,
            productId = 0x4EE7,
            mode = TargetMode.ADB,
            interfaceId = 0,
            hasPermission = true,
            serial = "PIXELTEST",
            product = "Pixel 8",
            manufacturer = "Google",
        ),
    ),
    link = LinkState.Connected(TargetMode.ADB, "Google Pixel 8"),
    info = TargetInfo(
        TargetMode.ADB,
        listOf(
            "Model" to "Google Pixel 8",
            "Codename" to "shiba",
            "Android" to "15 (SDK 35)",
            "Bootloader locked" to "no",
            "Slot" to "_a",
            "Root managers" to "Magisk",
        ),
    ),
    log = listOf(
        LogLine(1, LogLevel.COMMAND, "adb shell getprop ro.product.model", 0),
        LogLine(2, LogLevel.OUTPUT, "Pixel 8", 0),
        LogLine(3, LogLevel.INFO, "Connected over USB 18d1:4ee7", 0),
    ),
    nodeId = "rf-00112233445566778899aabbccddeeff",
    appVersion = "0.1.0-debug",
    adbFingerprint = "A1:B2:C3:D4:E5:F6:00:11:22:33:44:55:66:77:88:99",
)

@Preview(showBackground = true, widthDp = 412, heightDp = 892, name = "Dashboard")
@Composable
fun PreviewDashboard() {
    RootForgeTheme { RootForgeUi(previewState(), NoopActions, startAt = Destination.Dashboard, startExpanded = true) }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 892, name = "Flash Tools")
@Composable
fun PreviewFlashTools() {
    RootForgeTheme { RootForgeUi(previewState(), NoopActions, startAt = Destination.FlashTools) }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 892, name = "ADB")
@Composable
fun PreviewAdb() {
    RootForgeTheme { RootForgeUi(previewState(), NoopActions, startAt = Destination.Adb) }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 892, name = "Fastboot")
@Composable
fun PreviewFastboot() {
    RootForgeTheme { RootForgeUi(previewState().copy(link = LinkState.Connected(TargetMode.FASTBOOT, "Pixel 8")), NoopActions, startAt = Destination.Fastboot) }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 892, name = "Modules")
@Composable
fun PreviewModules() {
    RootForgeTheme { RootForgeUi(previewState(), NoopActions, startAt = Destination.Modules) }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 892, name = "Settings")
@Composable
fun PreviewSettings() {
    RootForgeTheme { RootForgeUi(previewState(), NoopActions, startAt = Destination.Settings) }
}
