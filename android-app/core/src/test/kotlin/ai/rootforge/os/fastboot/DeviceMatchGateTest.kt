package ai.rootforge.os.fastboot

import ai.rootforge.os.core.FileDigest
import ai.rootforge.os.core.Library
import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.core.OutputLog
import ai.rootforge.os.core.TargetInfo
import ai.rootforge.os.usb.TargetMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory

class DeviceMatchGateTest {
    private val oriole = TargetIdentity(product = "oriole", codename = "oriole", slot = "a", fingerprint = FP)
    private val claim = ImageClaim(product = "oriole", codename = "oriole", slot = "a", fingerprint = FP)

    @Test
    fun aMatchingClaimIsAllowed() {
        assertEquals(DeviceMatch.Ok, DeviceMatchGate.check(oriole, claim))
    }

    @Test
    fun noSidecarIsAMismatch() {
        val m = DeviceMatchGate.check(oriole, null) as DeviceMatch.Mismatch
        assertEquals(listOf(DeviceMatchGate.NO_CLAIM), m.reasons)
    }

    @Test
    fun aManifestWithoutProductIsRefused() {
        val m = DeviceMatchGate.check(oriole, ImageClaim(slot = "a")) as DeviceMatch.Mismatch
        assertTrue(m.reasons.contains(DeviceMatchGate.NO_PRODUCT))
    }

    @Test
    fun productMismatchIsReportedWithBothSides() {
        val m = DeviceMatchGate.check(
            TargetIdentity(product = "shiba", codename = "shiba", slot = "a"),
            ImageClaim(product = "oriole", slot = "*"),
        ) as DeviceMatch.Mismatch
        assertEquals(
            "Image is for product/codename 'oriole'; target reports product 'shiba' / codename 'shiba'.",
            m.reasons.single(),
        )
    }

    @Test
    fun aClaimedProductMayMatchTheTargetCodename() {
        val target = TargetIdentity(product = null, codename = "oriole", slot = "a")
        assertEquals(DeviceMatch.Ok, DeviceMatchGate.check(target, ImageClaim(product = "oriole", slot = "*")))
    }

    @Test
    fun unknownTargetProductIsNotAWildcard() {
        val m = DeviceMatchGate.check(
            TargetIdentity(),
            ImageClaim(product = "oriole"),
        ) as DeviceMatch.Mismatch
        assertTrue(m.reasons.contains(DeviceMatchGate.TARGET_UNKNOWN))
    }

    @Test
    fun slotMismatchIsReported() {
        val m = DeviceMatchGate.check(
            oriole.copy(slot = "b"),
            ImageClaim(product = "oriole", slot = "a"),
        ) as DeviceMatch.Mismatch
        assertEquals("Image is for slot 'a'; target is on slot 'b'.", m.reasons.single())
    }

    @Test
    fun unknownTargetSlotBlocksAClaimedSlot() {
        val m = DeviceMatchGate.check(
            oriole.copy(slot = null),
            ImageClaim(product = "oriole", slot = "_a"),
        ) as DeviceMatch.Mismatch
        assertEquals("Image is for slot 'a'; target slot is unknown.", m.reasons.single())
    }

    @Test
    fun aStarSlotMatchesEitherSide() {
        assertEquals(DeviceMatch.Ok, DeviceMatchGate.check(oriole.copy(fingerprint = null), ImageClaim(product = "oriole", slot = "*")))
    }

    @Test
    fun aDeclaredManifestWithoutFingerprintIsEnough() {
        assertEquals(
            DeviceMatch.Ok,
            DeviceMatchGate.check(oriole.copy(fingerprint = "other"), ImageClaim(product = "oriole", slot = "a")),
        )
    }

    @Test
    fun aClaimedFingerprintMustMatch() {
        val m = DeviceMatchGate.check(
            oriole.copy(fingerprint = "other/build"),
            claim,
        ) as DeviceMatch.Mismatch
        assertEquals(listOf(DeviceMatchGate.FP_MISMATCH), m.reasons)
    }

    @Test
    fun aClaimedFingerprintAgainstAnUnknownTargetIsRefused() {
        val m = DeviceMatchGate.check(
            oriole.copy(fingerprint = null),
            claim,
        ) as DeviceMatch.Mismatch
        assertEquals(listOf(DeviceMatchGate.TARGET_FP_UNKNOWN), m.reasons)
    }

    @Test
    fun slotSuffixFromAdbIsNormalised() {
        val fromAdb = TargetIdentity.fromAdbProps(
            mapOf(
                "ro.product.name" to "oriole",
                "ro.product.device" to "oriole",
                "ro.boot.slot_suffix" to "_b",
                "ro.build.fingerprint" to FP,
            ),
        )
        assertEquals("b", fromAdb.slot)
        val fromInfo = TargetIdentity.fromTargetInfo(
            TargetInfo(TargetMode.FASTBOOT, listOf("Product" to "oriole", "Current slot" to "a")),
        )
        assertEquals("a", fromInfo.slot)
        assertEquals("oriole", fromInfo.product)
    }

    @Test
    fun mergePrefersPrimaryFields() {
        val merged = TargetIdentity.merge(
            TargetIdentity(product = "oriole", slot = "a"),
            TargetIdentity(codename = "oriole", fingerprint = FP, slot = "b"),
        )
        assertEquals("oriole", merged.product)
        assertEquals("oriole", merged.codename)
        assertEquals("a", merged.slot)
        assertEquals(FP, merged.fingerprint)
    }

    @Test
    fun flashSafetyLoadsASidecarAndRefusesAHashMismatch() {
        val dir = createTempDirectory("rf-flash").toFile()
        val lib = Library(dir)
        val image = File(dir, "boot.img").apply { writeBytes("ANDROID!".toByteArray() + ByteArray(32)) }
        lib.refresh()
        val actual = FileDigest.sha256Hex(image)
        File(dir, "boot.img.manifest.json").writeText(
            """{"product":"oriole","slot":"a","sha256":"${"00".repeat(32)}"}""",
        )
        lib.refresh()
        val result = FlashSafety.evaluateLibrary(lib, LibraryFile("boot.img", image), oriole.copy(fingerprint = null))
        assertFalse(result.allowed)
        assertTrue(result.blockers.any { it == FileDigest.MISMATCH })
        assertEquals(actual, result.sha256)

        File(dir, "boot.img.manifest.json").writeText(
            """{"product":"oriole","slot":"a","sha256":"$actual"}""",
        )
        lib.refresh()
        val ok = FlashSafety.evaluateLibrary(lib, LibraryFile("boot.img", image), oriole.copy(fingerprint = null))
        assertTrue(ok.allowed)

        val log = OutputLog()
        try {
            FlashSafety.refuse(log, result)
            fail("mismatch must throw")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Refused:"))
            assertTrue(log.asText().contains(FileDigest.MISMATCH))
        }
    }

    @Test
    fun anUnreadableSidecarIsReportedNotGuessed() {
        val result = FlashSafety.evaluate(
            oriole,
            "boot.img",
            kotlin.io.path.createTempFile("boot", ".img").toFile().apply { writeBytes(ByteArray(8)) },
            "{not-json",
        )
        assertFalse(result.allowed)
        assertTrue(result.blockers.single().contains("not a valid manifest"))
    }

    companion object {
        private const val FP = "google/oriole/oriole:14/AP2A.240805.005/1:user/release-keys"
    }
}
