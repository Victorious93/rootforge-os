package ai.rootforge.os.device

import ai.rootforge.os.catalog.BuildCatalogParser
import ai.rootforge.os.catalog.CatalogRecoveryBridge
import ai.rootforge.os.catalog.LoadedCatalog
import ai.rootforge.os.root.RootManager
import ai.rootforge.os.usb.AttachedTarget
import ai.rootforge.os.usb.TargetMode
import ai.rootforge.os.usb.UsbClassifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootPlanTest {
    @Test
    fun lockedFastbootPutsUnlockFirst() {
        val id = DeviceIdentity(
            mode = TargetMode.FASTBOOT,
            brand = "samsung",
            model = "SM-S911B",
            codename = "dm1q",
            bootloader = BootloaderLock.LOCKED,
            serial = "R5CT70TESTSAMS",
        )
        val plan = RootPlanner.plan(id, catalog())
        assertEquals("Locked — unlock first", plan.headline)
        assertEquals("unlock", plan.steps.first().id)
        assertTrue(plan.steps.map { it.id }.containsAll(listOf("unlock", "portal", "unlock-data", "recovery", "root")))
        assertFalse(plan.steps.first { it.id == "unlock" }.done)
        assertTrue(plan.steps.first { it.id == "unlock" }.detail.contains("wipes"))
        assertEquals(RootGuideKind.SAMSUNG, plan.guide?.kind)
        assertFalse(plan.guide!!.odinFlashing)
        assertNotNull(plan.guide!!.knoxWarning)
        assertEquals("samsung", plan.portal?.id)
        assertFalse(plan.portal!!.offered)
        assertNull(plan.portal!!.url)
        assertTrue(plan.steps.first { it.id == "portal" }.detail.contains("no longer offers") || plan.steps.first { it.id == "portal" }.detail.contains("does not publish"))
    }

    @Test
    fun pixelUnlockedUsesCatalogRecoveryAndRom() {
        val id = DeviceIdentity(
            mode = TargetMode.ADB,
            brand = "google",
            model = "Pixel 8",
            codename = "shiba",
            product = "shiba",
            bootloader = BootloaderLock.UNLOCKED,
            serial = "3B051FD0H2TEST",
        )
        val plan = RootPlanner.plan(id, catalog())
        assertEquals("Recommended: unlock → recovery → root manager", plan.headline)
        assertTrue(plan.steps.first { it.id == "unlock" }.done)
        assertEquals("google", plan.portal?.id)
        assertNull(plan.portal!!.url)
        assertTrue(plan.portal!!.offered)
        assertEquals(RootGuideKind.PIXEL, plan.guide?.kind)
        assertEquals(listOf("lineage"), plan.roms.map { it.entry.id })
        assertEquals(listOf("twrp"), plan.recoveries.map { it.entry.id })
        assertNotNull(CatalogRecoveryBridge.prefill(plan.recoveries.single()))
        assertTrue(plan.steps.first { it.id == "recovery" }.detail.contains("TWRP"))
        assertEquals("Example ROM", plan.steps.first { it.id == "rom" }.detail.substringAfter("lists ").substringBefore("."))
    }

    @Test
    fun alreadyRootedMarksTheRootStepDone() {
        val id = DeviceIdentity(
            mode = TargetMode.ADB,
            codename = "shiba",
            product = "shiba",
            bootloader = BootloaderLock.UNLOCKED,
            rootManagers = listOf(RootManager.MAGISK),
            serial = "PIXELTEST",
        )
        val plan = RootPlanner.plan(id, catalog())
        assertEquals("Rooted; bootloader unlocked", plan.headline)
        assertTrue(plan.steps.first { it.id == "root" }.done)
        assertTrue(plan.steps.first { it.id == "root" }.detail.contains("Magisk"))
    }

    @Test
    fun edlPlanNeverRecommendsAWrite() {
        val id = DeviceIdentityParser.fromEdl(
            UsbClassifier.QUALCOMM_VID,
            UsbClassifier.EDL_PID,
            chipSerial = "44332211",
        )
        val plan = RootPlanner.plan(id, catalog())
        assertEquals("EDL identify only — MSM / EDL guide", plan.headline)
        assertEquals(listOf("edl", "msm"), plan.steps.map { it.id })
        assertTrue(plan.steps.first { it.id == "edl" }.detail.contains("does not write") || plan.steps.first { it.id == "edl" }.detail.contains("No EDL writes"))
        assertTrue(plan.steps.first { it.id == "msm" }.detail.contains("never ships") || plan.steps.first { it.id == "msm" }.detail.contains("MSM"))
        assertTrue(plan.roms.isEmpty())
        assertNull(plan.guide)
        assertNull(plan.portal)
        assertNotNull(plan.msm)
        assertFalse(plan.msm!!.writes)
        assertFalse(plan.msm!!.shipsTool)
    }

    @Test
    fun unknownIdentityAsksToIdentify() {
        val plan = RootPlanner.plan(null, null)
        assertEquals("identify", plan.steps.single().id)
        assertFalse(plan.steps.single().done)
        assertNull(plan.guide)
        assertNull(plan.portal)
    }

    @Test
    fun motorolaPlanShowsOfficialPortalAndUnlockDataCommand() {
        val id = DeviceIdentity(
            mode = TargetMode.FASTBOOT,
            brand = "motorola",
            model = "motorola edge 40",
            codename = "rtwo",
            bootloader = BootloaderLock.LOCKED,
            serial = "ZY22TESTMOTO",
        )
        val plan = RootPlanner.plan(id, catalog())
        assertEquals("motorola", plan.portal?.id)
        assertEquals(UnlockPortals.MOTOROLA.url, plan.portal?.url)
        assertTrue(plan.portal!!.url!!.startsWith("https://"))
        val data = plan.steps.first { it.id == "unlock-data" }
        assertTrue(data.detail.contains("oem get_unlock_data"))
        assertTrue(data.detail.contains("never") || data.detail.contains("never pastes"))
        val portalStep = plan.steps.first { it.id == "portal" }
        assertTrue(portalStep.detail.contains("system browser") || portalStep.detail.contains("never sends"))
    }

    @Test
    fun sonyGenericWizardStillGetsTheOfficialPortal() {
        val id = DeviceIdentity(mode = TargetMode.ADB, brand = "sony", model = "Xperia 1 V", codename = "pdx234")
        val plan = RootPlanner.plan(id, catalog())
        assertEquals(RootGuideKind.SONY, plan.guide?.kind)
        assertEquals("sony", plan.portal?.id)
        assertTrue(plan.portal!!.url!!.startsWith("https://developer.sony.com/"))
        assertTrue(plan.steps.any { it.id == "unlock-data" && it.detail.contains("IMEI") })
    }

    @Test
    fun rosterListsEveryAttachedPhone() {
        val pixel = AttachedTarget("/dev/bus/usb/001/002", 0x18D1, 0x4EE7, TargetMode.ADB, 0, true, "PIXELTEST", "Pixel 8", "Google")
        val edl = AttachedTarget("/dev/bus/usb/001/004", UsbClassifier.QUALCOMM_VID, UsbClassifier.EDL_PID, TargetMode.EDL, 0, true, "44332211", "9008", "Qualcomm")
        val identities = mapOf(
            "PIXELTEST" to DeviceIdentity(mode = TargetMode.ADB, codename = "shiba", product = "shiba", serial = "PIXELTEST"),
        )
        val cards = DeviceRoster.cards(listOf(pixel, edl), identities, mapOf("PIXELTEST" to "Flash boot"), catalog())
        assertEquals(2, cards.size)
        assertEquals("PIXELTEST", cards[0].key)
        assertEquals("Flash boot", cards[0].busy)
        assertEquals("shiba", cards[0].identity?.codename)
        assertNull(cards[1].identity)
        assertEquals(listOf("edl", "msm"), RootPlanner.plan(DeviceIdentityParser.fromEdl(edl, "44332211"), null).steps.map { it.id })
        assertTrue(DeviceRoster.needsIdentify(edl, identities))
        assertFalse(DeviceRoster.needsIdentify(pixel, identities))
        assertEquals(1, DeviceRoster.prune(identities + ("GONE" to identities.values.first()), listOf(pixel)).size)
    }

    @Test
    fun publicLabelMasksSerialFallback() {
        val t = AttachedTarget("s", 0, 0, TargetMode.ADB, null, true, "PIXELTEST", null, null)
        assertEquals("••••TEST", t.publicLabel())
        assertFalse(t.publicLabel().contains("PIXELTEST"))
    }

    private fun catalog(): LoadedCatalog {
        val json = """
            {
              "roms": [
                {"id": "lineage", "name": "Example ROM", "downloadUrl": "https://example.org/rom",
                 "devices": [{"brand": "Google", "model": "Pixel 8", "codename": "shiba"}]}
              ],
              "recoveries": [
                {"id": "twrp", "name": "TWRP", "downloadUrl": "https://twrp.me/Devices/",
                 "devices": [{"brand": "Google", "model": "Pixel 8", "codename": "shiba"}]}
              ],
              "roots": [
                {"id": "magisk", "name": "Magisk", "downloadUrl": "https://github.com/topjohnwu/Magisk/releases",
                 "devices": [{"brand": "Google", "model": "Pixel 8", "codename": "shiba"}]}
              ]
            }
        """.trimIndent()
        return LoadedCatalog(BuildCatalogParser.parseCatalog(json), null, "test")
    }
}

class SerialOpLockTest {
    @Test
    fun differentSerialsRunInParallel() = runTest {
        val lock = SerialOpLock()
        val aStarted = CompletableDeferred<Unit>()
        val bStarted = CompletableDeferred<Unit>()
        val jobA = launch {
            lock.withLock("AAA", "flash") {
                aStarted.complete(Unit)
                bStarted.await()
            }
        }
        val jobB = launch {
            lock.withLock("BBB", "identify") {
                bStarted.complete(Unit)
                aStarted.await()
            }
        }
        jobA.join()
        jobB.join()
        assertFalse(lock.isBusy("AAA"))
        assertFalse(lock.isBusy("BBB"))
    }

    @Test
    fun sameSerialIsSerialized() = runTest {
        val lock = SerialOpLock()
        val order = mutableListOf<String>()
        val firstInside = CompletableDeferred<Unit>()
        val allowFirstExit = CompletableDeferred<Unit>()
        val job1 = launch {
            lock.withLock("S1", "one") {
                order += "1-in"
                firstInside.complete(Unit)
                allowFirstExit.await()
                order += "1-out"
            }
        }
        firstInside.await()
        val job2 = launch {
            lock.withLock("S1", "two") { order += "2" }
        }
        delay(20)
        assertEquals(listOf("1-in"), order)
        assertTrue(lock.isBusy("S1"))
        assertEquals("one", lock.busyLabel("S1"))
        allowFirstExit.complete(Unit)
        job1.join()
        job2.join()
        assertEquals(listOf("1-in", "1-out", "2"), order)
    }

    @Test
    fun connectWaitsForAnotherDevicesActionButNotForIdentify() = runTest {
        val lock = SerialOpLock()
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            lock.withLock("AAA", "Erase userdata") {
                inside.complete(Unit)
                release.await()
            }
        }
        inside.await()
        assertEquals("Erase userdata", lock.connectRefusal("BBB"))
        assertEquals("Erase userdata", lock.connectRefusal("AAA"))
        assertEquals("Erase userdata", lock.otherBusyAction("BBB"))
        assertNull(lock.otherBusyAction("AAA"))
        release.complete(Unit)
        job.join()

        val idInside = CompletableDeferred<Unit>()
        val idRelease = CompletableDeferred<Unit>()
        val idJob = launch {
            lock.withLock("AAA", SerialOpLock.IDENTIFY_LABEL) {
                idInside.complete(Unit)
                idRelease.await()
            }
        }
        idInside.await()
        assertNull(lock.connectRefusal("BBB"))
        assertNull(lock.connectRefusal("AAA"))
        idRelease.complete(Unit)
        idJob.join()
    }

    @Test
    fun blankSerialSharesTheUnknownKey() {
        assertEquals(SerialOpLock.UNKNOWN_KEY, SerialOpLock.key(null))
        assertEquals(SerialOpLock.UNKNOWN_KEY, SerialOpLock.key("  "))
        assertEquals("ABC", SerialOpLock.key("ABC"))
    }
}
