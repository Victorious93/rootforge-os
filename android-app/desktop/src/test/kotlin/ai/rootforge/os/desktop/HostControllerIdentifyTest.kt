package ai.rootforge.os.desktop

import ai.rootforge.os.core.ApprovalCenter
import ai.rootforge.os.core.Library
import ai.rootforge.os.core.OutputLog
import ai.rootforge.os.device.DeviceRoster
import ai.rootforge.os.device.RootGuideKind
import ai.rootforge.os.device.RootGuides
import ai.rootforge.os.device.UnlockPortals
import ai.rootforge.os.device.rosterKey
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostControllerIdentifyTest {
    @Test
    fun scanAndIdentifyTwoPhonesOverHostAdbAndFastboot() = runBlocking {
        val dir = createTempDirectory("rf-host-identify").toFile()
        val adb = File(dir, "adb").apply { writeText("#!/bin/true\n"); setExecutable(true) }
        val fastboot = File(dir, "fastboot").apply { writeText("#!/bin/true\n"); setExecutable(true) }
        val log = OutputLog()
        val controller = HostController(
            HostToolset(adb, fastboot, null),
            ScriptedRunner(),
            log,
            Library(File(dir, "library")),
            ApprovalCenter(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        controller.scan()
        assertEquals(2, controller.targets.value.size)
        controller.identifyAttached(force = true)

        val identities = controller.identities.value
        val pixel = identities.getValue(PIXEL_SERIAL)
        val samsung = identities.getValue(SAMSUNG_SERIAL)
        assertEquals("google", pixel.brand)
        assertEquals("Pixel 8", pixel.model)
        assertEquals("••••PIX8", pixel.maskedSerial)
        assertEquals(RootGuideKind.PIXEL, RootGuides.forIdentity(pixel).kind)
        assertEquals(RootGuideKind.SAMSUNG, RootGuides.forIdentity(samsung).kind)
        assertFalse(RootGuides.forIdentity(samsung).odinFlashing)
        assertNotNull(RootGuides.forIdentity(samsung).knoxWarning)

        val cards = DeviceRoster.cards(controller.targets.value, identities, emptyMap(), null)
        assertEquals(2, cards.size)
        val pixelCard = cards.first { it.key == PIXEL_SERIAL }
        val pixelGuide = pixelCard.plan.guide
        assertEquals(RootGuideKind.PIXEL, pixelGuide?.kind)
        assertTrue(pixelGuide!!.steps.any { it.write && it.dryRunFirst && it.confirmPhrase == "UNLOCK" && !it.autoReboot })
        assertEquals("google", pixelCard.plan.portal?.id)
        assertNull(pixelCard.plan.portal?.url)
        val samsungCard = cards.first { it.key == SAMSUNG_SERIAL }
        assertEquals("samsung", samsungCard.plan.portal?.id)
        assertFalse(samsungCard.plan.portal!!.offered)
        assertEquals(UnlockPortals.SAMSUNG.id, samsungCard.plan.portal?.id)
        assertEquals(2, RootGuides.columnsForWidthDp(1100))
        assertEquals(1, RootGuides.columnsForWidthDp(412))

        val text = log.asText()
        assertFalse(text.contains(PIXEL_SERIAL))
        assertFalse(text.contains(SAMSUNG_SERIAL))
        assertTrue(text.contains("••••PIX8"))
        assertTrue(text.contains("••••SAMS"))
    }

    /**
     * `adb connect` lists a Wi-Fi phone as `ip:port`, never as its hardware serial, so `ro.serialno`
     * differs from the target's key. The identity must still land on that target's card and must not
     * be dropped and re-read on every 2-second tick of the desktop loop.
     */
    @Test
    fun aWirelessAdbPhoneIsIdentifiedOnceAndShownOnItsCard() = runBlocking {
        val dir = createTempDirectory("rf-host-wireless").toFile()
        val adb = File(dir, "adb").apply { writeText("#!/bin/true\n"); setExecutable(true) }
        val runner = WirelessRunner()
        val controller = HostController(
            HostToolset(adb, null, null),
            runner,
            OutputLog(),
            Library(File(dir, "library")),
            ApprovalCenter(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        controller.scan()
        controller.identifyAttached()
        val target = controller.targets.value.single()
        assertEquals(WIRELESS_SERIAL, target.rosterKey())
        val card = DeviceRoster.cards(controller.targets.value, controller.identities.value, emptyMap(), null).single()
        assertEquals("google", card.identity?.brand)
        assertEquals("the phone's own serial is still what is shown", "••••PIX8", card.identity?.maskedSerial)
        assertEquals(1, runner.getpropRuns)

        // The 2-second tick: scan, then identify anything that still needs it.
        controller.scan()
        controller.identifyAttached()
        controller.scan()
        controller.identifyAttached()
        assertEquals("identified again although nothing changed", 1, runner.getpropRuns)
    }

    private class WirelessRunner : ProcessRunner() {
        var getpropRuns = 0
        override fun run(exe: File, args: List<String>, timeoutMs: Long, stdinBytes: ByteArray?): CmdResult = when {
            args == listOf("devices", "-l") -> CmdResult(0, "List of devices attached\n$WIRELESS_SERIAL device product:shiba model:Pixel_8 device:shiba transport_id:3\n", "")
            args.contains("getprop") -> { getpropRuns++; CmdResult(0, PIXEL_GETPROP, "") }
            args.contains("packages") -> CmdResult(0, "package:com.android.settings\n", "")
            else -> CmdResult(0, "", "")
        }
    }

    private class ScriptedRunner : ProcessRunner() {
        override fun run(exe: File, args: List<String>, timeoutMs: Long, stdinBytes: ByteArray?): CmdResult {
            return when {
                args == listOf("devices", "-l") -> CmdResult(0, ADB_DEVICES, "")
                args == listOf("devices") -> CmdResult(0, FASTBOOT_DEVICES, "")
                args.contains("getprop") -> CmdResult(0, PIXEL_GETPROP, "")
                args.contains("packages") -> CmdResult(0, "package:com.topjohnwu.magisk\n", "")
                args.contains("getvar") -> CmdResult(0, SAMSUNG_GETVAR, "")
                else -> CmdResult(0, "", "")
            }
        }
    }

    private companion object {
        const val PIXEL_SERIAL = "3B051FD0H2PIX8"
        const val SAMSUNG_SERIAL = "R5CT70TESTSAMS"
        const val WIRELESS_SERIAL = "192.168.1.50:5555"

        val ADB_DEVICES = """
            List of devices attached
            $PIXEL_SERIAL         device product:shiba model:Pixel_8 device:shiba transport_id:1
        """.trimIndent()

        val FASTBOOT_DEVICES = "$SAMSUNG_SERIAL\tfastboot\n"

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
            [ro.serialno]: [$PIXEL_SERIAL]
        """.trimIndent()

        val SAMSUNG_GETVAR = """
            (bootloader) product: dm1q
            (bootloader) oem: samsung
            (bootloader) unlocked: no
            (bootloader) current-slot: b
            (bootloader) serialno: $SAMSUNG_SERIAL
            (bootloader) secure: yes
        """.trimIndent()
    }
}
