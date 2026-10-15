package ai.rootforge.os.flash

import ai.rootforge.os.core.FileDigest
import ai.rootforge.os.core.FileInspector
import ai.rootforge.os.core.Library
import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.core.OutputLog
import ai.rootforge.os.fastboot.DeviceMatch
import ai.rootforge.os.fastboot.DeviceMatchGate
import ai.rootforge.os.fastboot.FlashSafety
import ai.rootforge.os.fastboot.ImageClaim
import ai.rootforge.os.fastboot.TargetIdentity
import ai.rootforge.os.usb.AttachedTarget
import ai.rootforge.os.usb.TargetMode
import ai.rootforge.os.usb.UsbClassifier
import ai.rootforge.os.usb.UsbIfaceInfo
import ai.rootforge.os.usb.UsbScanFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile

/**
 * The Flash Tools hub calls these :core rules; this file is the :app-side
 * contract so a UI change cannot drop the gate, the hash check, the persisted
 * log, or USB filtering without a failing test in this module.
 */
class FlashToolsContractTest {
    @Test
    fun flashToolsRefusesAMismatchedImageTheSameWayTheHubShows() {
        val target = TargetIdentity(product = "shiba", codename = "shiba", slot = "a")
        val claim = ImageClaim(product = "oriole", slot = "a")
        val mismatch = DeviceMatchGate.check(target, claim) as DeviceMatch.Mismatch
        assertTrue(mismatch.reasons.single().contains("oriole"))
        assertTrue(mismatch.reasons.single().contains("shiba"))
    }

    @Test
    fun inspectorSha256VerifyIsWhatTheInspectPaneCalls() {
        val bytes = "ANDROID!".toByteArray()
        val actual = FileDigest.sha256Hex(bytes)
        val inspection = FileInspector.inspect("boot.img", bytes.size.toLong(), bytes, actual, null)
        assertEquals(actual, inspection.sha256)
        assertTrue(FileDigest.verify(actual, inspection.sha256).ok)
        assertFalse(FileDigest.verify("11".repeat(32), inspection.sha256).ok)
    }

    @Test
    fun commandLogTheHubReadsIsThePersistedRedactedLog() {
        val file = createTempFile("app-command-log", ".jsonl").toFile()
        OutputLog(persistFile = file).info("password=hunter2")
        val restored = OutputLog(persistFile = file)
        assertFalse(restored.asText().contains("hunter2"))
        assertTrue(restored.asText().contains("[REDACTED]"))
    }

    @Test
    fun scanPaneKeepsAdbAndDropsHid() {
        assertTrue(UsbScanFilter.isFlashTarget(0x18D1, 0x4EE7, listOf(UsbIfaceInfo(0, 0xFF, 0x42, 0x01))))
        assertFalse(UsbScanFilter.isFlashTarget(0x046D, 0xC52B, listOf(UsbIfaceInfo(0, 0x03, 0x00, 0x00))))
        val adb = AttachedTarget("/dev/bus/usb/001/002", 0x18D1, 0x4EE7, TargetMode.ADB, 0, true, "X", "Pixel", "Google")
        val hid = AttachedTarget("/dev/bus/usb/001/003", 0x046D, 0xC52B, TargetMode.UNKNOWN, null, true, null, "Receiver", null)
        val scan = UsbScanFilter.partition(listOf(hid, adb))
        assertEquals(listOf(adb), scan.flashTargets)
        assertEquals(listOf(hid), scan.ignored)
        assertEquals(TargetMode.EDL, UsbClassifier.classify(UsbClassifier.QUALCOMM_VID, UsbClassifier.EDL_PID, listOf(UsbIfaceInfo(0, 0xFF, 0x00, 0x00))).mode)
    }

    @Test
    fun aLibrarySidecarIsRequiredBeforeFlashSafetyAllowsAWrite() {
        val dir = createTempDirectory("app-flash").toFile()
        val lib = Library(dir)
        val image = java.io.File(dir, "boot.img").apply { writeBytes("ANDROID!".toByteArray()) }
        lib.refresh()
        val identity = TargetIdentity(product = "oriole", slot = "a")
        val missing = FlashSafety.evaluateLibrary(lib, LibraryFile("boot.img", image), identity)
        assertFalse(missing.allowed)
        assertTrue(missing.blockers.any { it.contains("no device claim") })

        java.io.File(dir, "boot.img.manifest.json").writeText("""{"product":"oriole","slot":"*"}""")
        lib.refresh()
        val ok = FlashSafety.evaluateLibrary(lib, LibraryFile("boot.img", image), identity)
        assertTrue(ok.allowed)
    }
}
