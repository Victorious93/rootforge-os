package ai.rootforge.os.device

import ai.rootforge.os.usb.AttachedTarget
import ai.rootforge.os.usb.TargetMode
import ai.rootforge.os.usb.UsbClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityParserTest {
    @Test
    fun pixelGetprop() {
        val id = DeviceIdentityParser.fromAdb(
            DeviceIdentityParser.parseGetpropDump(PIXEL_GETPROP),
            "package:com.topjohnwu.magisk\npackage:com.android.settings\n",
        )
        assertEquals(TargetMode.ADB, id.mode)
        assertEquals("google", id.brand)
        assertEquals("Pixel 8", id.model)
        assertEquals("shiba", id.codename)
        assertEquals("Google Tensor G3", id.soc)
        assertEquals("15", id.androidVersion)
        assertEquals("35", id.sdk)
        assertEquals("2025-12-05", id.securityPatch)
        assertEquals(BootloaderLock.UNLOCKED, id.bootloader)
        assertEquals("a", id.slot)
        assertEquals("3B051FD0H2PIX8", id.serial)
        assertEquals("••••PIX8", id.maskedSerial)
        assertEquals("Magisk", id.rootManagers.single().label)
        assertFalse(id.displayRows().any { it.first == "Serial" && it.second.contains("3B051FD0H2PIX8") })
        assertEquals("shiba", id.toTargetIdentity().codename)
    }

    @Test
    fun samsungGetpropLocked() {
        val id = DeviceIdentityParser.fromAdb(DeviceIdentityParser.parseGetpropDump(SAMSUNG_GETPROP))
        assertEquals("samsung", id.brand)
        assertEquals("SM-S911B", id.model)
        assertEquals("dm1q", id.codename)
        assertEquals("kalama", id.soc)
        assertEquals(BootloaderLock.LOCKED, id.bootloader)
        assertEquals("b", id.slot)
        assertEquals("••••SAMS", id.maskedSerial)
        assertTrue(id.rootManagers.isEmpty())
    }

    @Test
    fun xiaomiGetpropUsesModelNumber() {
        val id = DeviceIdentityParser.fromAdb(DeviceIdentityParser.parseGetpropDump(XIAOMI_GETPROP))
        assertEquals("Xiaomi", id.brand)
        assertEquals("M1908C3IG", id.model)
        assertEquals("olive", id.codename)
        assertEquals("M1908C3IG", id.modelNumber)
        assertEquals(BootloaderLock.LOCKED, id.bootloader)
    }

    @Test
    fun onePlusGetprop() {
        val id = DeviceIdentityParser.fromAdb(DeviceIdentityParser.parseGetpropDump(ONEPLUS_GETPROP))
        assertEquals("OnePlus", id.brand)
        assertEquals("CPH2449", id.model)
        assertEquals("OP594DL1", id.codename)
        assertEquals("taro", id.soc)
        assertEquals(BootloaderLock.UNLOCKED, id.bootloader)
        assertEquals("a", id.slot)
    }

    @Test
    fun kernelSuAndAPatchPackagesAreDetected() {
        val ksu = DeviceIdentityParser.managersFromPm("package:me.weishu.kernelsu\npackage:com.android.settings\n")
        assertEquals(listOf("KernelSU"), ksu.map { it.label })
        val apatch = DeviceIdentityParser.managersFromPm("package:me.bmax.apatch\n")
        assertEquals(listOf("APatch"), apatch.map { it.label })
        assertTrue(DeviceIdentityParser.managersFromPm("package:com.android.vending\n").isEmpty())
    }

    @Test
    fun motorolaGetprop() {
        val id = DeviceIdentityParser.fromAdb(DeviceIdentityParser.parseGetpropDump(MOTOROLA_GETPROP))
        assertEquals("motorola", id.brand)
        assertEquals("motorola edge 40", id.model)
        assertEquals("rtwo", id.codename)
        assertEquals(BootloaderLock.LOCKED, id.bootloader)
        assertEquals("none", DeviceIdentityParser.managersFromPm("").joinToString { it.label }.ifBlank { "none" })
    }

    @Test
    fun pixelFastbootGetvar() {
        val id = DeviceIdentityParser.fromFastboot(DeviceIdentityParser.parseGetvarDump(PIXEL_GETVAR))
        assertEquals(TargetMode.FASTBOOT, id.mode)
        assertEquals("shiba", id.product)
        assertEquals("shiba", id.codename)
        assertEquals(BootloaderLock.UNLOCKED, id.bootloader)
        assertEquals("a", id.slot)
        assertEquals("••••PIX8", id.maskedSerial)
        assertTrue(id.note!!.contains("getvar"))
    }

    @Test
    fun samsungFastbootLocked() {
        val id = DeviceIdentityParser.fromFastboot(DeviceIdentityParser.parseGetvarDump(SAMSUNG_GETVAR))
        assertEquals("dm1q", id.codename)
        assertEquals("samsung", id.brand)
        assertEquals(BootloaderLock.LOCKED, id.bootloader)
        assertNull(id.androidVersion)
    }

    @Test
    fun fastbootFillsBrandFromUsbDescriptor() {
        val target = AttachedTarget(
            "/dev/bus/usb/001/002", 0x18D1, 0x4EE0, TargetMode.FASTBOOT, 0, true,
            "3B051FD0H2PIX8", "Pixel 8", "Google",
        )
        val id = DeviceIdentityParser.fromFastboot(DeviceIdentityParser.parseGetvarDump(PIXEL_GETVAR), target)
        assertEquals("Google", id.brand)
        assertEquals("Pixel 8", id.model)
        assertEquals(RootGuideKind.PIXEL, RootGuides.forIdentity(id).kind)
    }

    @Test
    fun getvarDumpKeepsEveryPartitionSizeAsItsOwnKey() {
        val vars = DeviceIdentityParser.parseGetvarDump(
            """
            (bootloader) has-slot:boot: yes
            (bootloader) partition-size:boot_a: 0x4000000
            (bootloader) partition-size:boot_b: 0x4000000
            (bootloader) partition-size:init_boot_a: 0x800000
            (bootloader) slot-successful:a: yes
            (bootloader) current-slot: a
            Finished. Total time: 0.001s
            """.trimIndent(),
        )
        assertEquals("0x4000000", vars["partition-size:boot_a"])
        assertEquals("0x4000000", vars["partition-size:boot_b"])
        assertEquals("0x800000", vars["partition-size:init_boot_a"])
        assertEquals("yes", vars["has-slot:boot"])
        assertEquals("yes", vars["slot-successful:a"])
        assertEquals("a", vars["current-slot"])
        assertNull("a bare 'partition-size' key means the argument was split off", vars["partition-size"])
    }

    /**
     * The roster looks an identity up by the attached target's key (USB or `adb devices` serial,
     * else the device name). An identity published under any other key is pruned on the next pass
     * and the phone is identified again forever.
     */
    @Test
    fun aWirelessAdbPhoneIsKeyedByItsAdbSerialNotByItsRoSerialno() {
        val target = AttachedTarget(
            "192.168.1.50:5555", 0, 0, TargetMode.ADB, null, true, "192.168.1.50:5555", "Pixel 8", null,
        )
        val id = DeviceIdentityParser.fromAdb(DeviceIdentityParser.parseGetpropDump(PIXEL_GETPROP), "", target)
        assertEquals("the displayed serial is still the phone's own", "••••PIX8", id.maskedSerial)
        assertEquals("192.168.1.50:5555", id.rosterKey())
        assertRosterKeepsIt(target, id)
    }

    @Test
    fun anEdlPhoneIsKeyedByItsUsbTargetNotBySaharaChipSerial() {
        val target = AttachedTarget(
            "/dev/bus/usb/001/007", UsbClassifier.QUALCOMM_VID, UsbClassifier.EDL_PID, TargetMode.EDL, 0, true,
            null, null, null,
        )
        val id = DeviceIdentityParser.fromEdl(target, chipSerial = "44332211", msmHwId = "001230e1")
        assertEquals("••••2211", id.maskedSerial)
        assertEquals("/dev/bus/usb/001/007", id.rosterKey())
        assertRosterKeepsIt(target, id)
    }

    @Test
    fun aFastbootPhoneWhoseGetvarSerialDiffersFromItsUsbSerialKeepsTheTargetKey() {
        val target = AttachedTarget(
            "/dev/bus/usb/001/002", 0x18D1, 0x4EE0, TargetMode.FASTBOOT, 0, true, "0123456789ABCDEF", null, null,
        )
        val id = DeviceIdentityParser.fromFastboot(DeviceIdentityParser.parseGetvarDump(SAMSUNG_GETVAR), target)
        assertEquals("••••SAMS", id.maskedSerial)
        assertEquals("0123456789ABCDEF", id.rosterKey())
        assertRosterKeepsIt(target, id)
    }

    @Test
    fun aFailedIdentifyStubUsesTheSameKeyAsItsTarget() {
        val target = AttachedTarget("/dev/bus/usb/001/009", 0x18D1, 0x4EE7, TargetMode.ADB, 1, true, "unknown", null, null)
        val stub = DeviceIdentityParser.stub(target, "boom")
        assertEquals(target.rosterKey(), stub.rosterKey())
        assertRosterKeepsIt(target, stub)
    }

    private fun assertRosterKeepsIt(target: AttachedTarget, id: DeviceIdentity) {
        val identities = mapOf(id.rosterKey() to id)
        assertEquals("prune must not drop it", identities, DeviceRoster.prune(identities, listOf(target)))
        assertEquals(id, DeviceRoster.cards(listOf(target), identities, emptyMap(), null).single().identity)
        assertFalse("must not be identified again", DeviceRoster.needsIdentify(target, identities))
    }

    @Test
    fun edlIsIdentifyOnlyForQualcomm9008() {
        val id = DeviceIdentityParser.fromEdl(
            UsbClassifier.QUALCOMM_VID,
            UsbClassifier.EDL_PID,
            chipSerial = "44332211",
            msmHwId = "001230e1",
        )
        assertEquals(TargetMode.EDL, id.mode)
        assertEquals("Qualcomm", id.brand)
        assertEquals("••••2211", id.maskedSerial)
        assertTrue(id.note!!.contains("identify only"))
        assertTrue(id.note!!.contains("does not write"))
        assertEquals("05c6:9008", "%04x:%04x".format(UsbClassifier.QUALCOMM_VID, UsbClassifier.EDL_PID))
        val previous = DeviceIdentity(mode = TargetMode.ADB, brand = "OnePlus", model = "CPH2449", serial = "44332211")
        val remembered = DeviceIdentityParser.fromEdl(
            UsbClassifier.QUALCOMM_VID,
            UsbClassifier.EDL_PID,
            chipSerial = "44332211",
            previous = previous,
        )
        assertEquals("OnePlus", remembered.brand)
        assertEquals("CPH2449", remembered.model)
        assertEquals(RootGuideKind.ONEPLUS, BrandCatalog.forIdentity(remembered)?.guideKind)
        assertTrue(MsmGuides.forIdentity(remembered).usesMsm)
    }

    @Test
    fun sonyAndOppoGetpropMatchTheIdentityTable() {
        val sony = DeviceIdentityParser.fromAdb(
            mapOf(
                "ro.product.brand" to "sony",
                "ro.product.model" to "Xperia 1 V",
                "ro.product.device" to "pdx234",
                "ro.serialno" to "SONYTESTXXXX",
            ),
        )
        assertEquals("sony", sony.brand)
        assertEquals(RootGuideKind.SONY, RootGuides.forIdentity(sony).kind)
        assertEquals("sony", UnlockPortals.forIdentity(sony)?.id)
        val oppo = DeviceIdentityParser.fromAdb(
            mapOf(
                "ro.product.brand" to "oppo",
                "ro.product.model" to "CPH1234",
                "ro.product.device" to "OP4E2F",
                "ro.serialno" to "OPPOTESTXXXX",
            ),
        )
        assertEquals("oppo", BrandCatalog.forIdentity(oppo)?.id)
        assertTrue(BrandCatalog.forIdentity(oppo)!!.usesMsm)
    }

    private companion object {
        val PIXEL_GETPROP = """
            [ro.product.brand]: [google]
            [ro.product.manufacturer]: [Google]
            [ro.product.model]: [Pixel 8]
            [ro.product.device]: [shiba]
            [ro.product.name]: [shiba]
            [ro.board.platform]: [zuma]
            [ro.soc.model]: [Tensor G3]
            [ro.soc.manufacturer]: [Google]
            [ro.build.version.release]: [15]
            [ro.build.version.sdk]: [35]
            [ro.build.version.security_patch]: [2025-12-05]
            [ro.boot.flash.locked]: [0]
            [ro.boot.slot_suffix]: [_a]
            [ro.serialno]: [3B051FD0H2PIX8]
            [ro.build.fingerprint]: [google/shiba/shiba:15/AP4A.250105.002/12801208:user/release-keys]
            [ro.boot.verifiedbootstate]: [orange]
        """.trimIndent()

        val SAMSUNG_GETPROP = """
            [ro.product.brand]: [samsung]
            [ro.product.manufacturer]: [samsung]
            [ro.product.model]: [SM-S911B]
            [ro.product.device]: [dm1q]
            [ro.product.name]: [dm1qxxx]
            [ro.board.platform]: [kalama]
            [ro.hardware]: [qcom]
            [ro.build.version.release]: [14]
            [ro.build.version.sdk]: [34]
            [ro.build.version.security_patch]: [2025-11-01]
            [ro.boot.flash.locked]: [1]
            [ro.boot.vbmeta.device_state]: [locked]
            [ro.boot.slot_suffix]: [_b]
            [ro.serialno]: [R5CT70TESTSAMS]
        """.trimIndent()

        val XIAOMI_GETPROP = """
            [ro.product.brand]: [Xiaomi]
            [ro.product.manufacturer]: [Xiaomi]
            [ro.product.model]: [M1908C3IG]
            [ro.product.device]: [olive]
            [ro.product.name]: [olive]
            [ro.board.platform]: [msm8953]
            [ro.build.version.release]: [10]
            [ro.build.version.sdk]: [29]
            [ro.build.version.security_patch]: [2021-08-01]
            [ro.boot.flash.locked]: [1]
            [ro.serialno]: [abc123oliveXX]
        """.trimIndent()

        val ONEPLUS_GETPROP = """
            [ro.product.brand]: [OnePlus]
            [ro.product.manufacturer]: [OnePlus]
            [ro.product.model]: [CPH2449]
            [ro.product.device]: [OP594DL1]
            [ro.product.name]: [CPH2449]
            [ro.board.platform]: [taro]
            [ro.build.version.release]: [14]
            [ro.build.version.sdk]: [34]
            [ro.build.version.security_patch]: [2025-09-01]
            [ro.boot.flash.locked]: [0]
            [ro.boot.slot_suffix]: [_a]
            [ro.serialno]: [f6e5d4c3b2a1]
        """.trimIndent()

        val MOTOROLA_GETPROP = """
            [ro.product.brand]: [motorola]
            [ro.product.manufacturer]: [motorola]
            [ro.product.model]: [motorola edge 40]
            [ro.product.device]: [rtwo]
            [ro.product.name]: [rtwo_g]
            [ro.board.platform]: [taro]
            [ro.build.version.release]: [13]
            [ro.build.version.sdk]: [33]
            [ro.build.version.security_patch]: [2024-06-01]
            [ro.boot.vbmeta.device_state]: [locked]
            [ro.serialno]: [ZY22H2TESTMOTO]
        """.trimIndent()

        val PIXEL_GETVAR = """
            (bootloader) product: shiba
            (bootloader) unlocked: yes
            (bootloader) current-slot: a
            (bootloader) serialno: 3B051FD0H2PIX8
            (bootloader) secure: no
            (bootloader) slot-count: 2
            (bootloader) hardware: shiba
        """.trimIndent()

        val SAMSUNG_GETVAR = """
            (bootloader) product: dm1q
            (bootloader) oem: samsung
            (bootloader) unlocked: no
            (bootloader) current-slot: b
            (bootloader) serialno: R5CT70TESTSAMS
            (bootloader) secure: yes
        """.trimIndent()
    }
}
