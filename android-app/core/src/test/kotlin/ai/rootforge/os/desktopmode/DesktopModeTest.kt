package ai.rootforge.os.desktopmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopModeTest {
    private val vs = CompanionPackage(
        packageName = VictorSuite.PACKAGE,
        certSha256 = "aa".repeat(32),
        exportsRunCommand = true,
        exportsDesktopAction = false,
    )

    @Test
    fun packageIsVictorSuitesTermuxId() {
        assertEquals("com.termux", VictorSuite.PACKAGE)
        assertTrue(VictorSuite.CANDIDATE_PACKAGES.contains("com.termux"))
        assertTrue(VictorSuite.CANDIDATE_PACKAGES.contains("ai.victorsuite.app"))
    }

    @Test
    fun missingCompanionIsAHintNotACrash() {
        val view = DesktopMode.probe(emptyList(), pinned = null)
        assertEquals(DesktopModeStatus.NOT_INSTALLED, view.status)
        assertFalse(view.canLaunch)
        assertFalse(view.needsPin)
        assertTrue(view.body.contains("will not crash"))
        assertNull(DesktopMode.intentFor(view, DesktopModeOp.START))
    }

    @Test
    fun installedWithoutEntrypointIsNotSupportedYet() {
        val found = vs.copy(exportsRunCommand = false, exportsDesktopAction = false)
        val view = DesktopMode.probe(listOf(found), pinned = null)
        assertEquals(DesktopModeStatus.INSTALLED_NO_ENTRYPOINT, view.status)
        assertFalse(view.canLaunch)
        assertTrue(view.body.contains("docs/DESKTOP_MODE.md"))
        assertTrue(view.body.contains(VictorSuite.ACTION_DESKTOP_MODE))
    }

    @Test
    fun firstSightNeedsAPinBeforeAnyIntent() {
        val view = DesktopMode.probe(listOf(vs), pinned = null)
        assertEquals(DesktopModeStatus.NEEDS_PIN, view.status)
        assertTrue(view.needsPin)
        assertFalse(view.canLaunch)
        assertEquals(VictorSuite.PACKAGE, view.packageName)
        assertNull(DesktopMode.intentFor(view, DesktopModeOp.START))
    }

    @Test
    fun certChangeRefusesTheOldPin() {
        val pin = PinnedCompanion(VictorSuite.PACKAGE, "bb".repeat(32))
        val view = DesktopMode.probe(listOf(vs), pin)
        assertEquals(DesktopModeStatus.CERT_CHANGED, view.status)
        assertTrue(view.needsPin)
        assertFalse(view.canLaunch)
        assertNull(DesktopMode.intentFor(view, DesktopModeOp.SETUP))
    }

    @Test
    fun matchingPinWithRunCommandBuildsAConstrainedIntent() {
        val pin = PinnedCompanion(vs.packageName, vs.certSha256)
        val view = DesktopMode.probe(listOf(vs), pin)
        assertEquals(DesktopModeStatus.READY_RUN_COMMAND, view.status)
        assertTrue(view.canLaunch)
        assertEquals("RUN_COMMAND", view.transportLabel)
        val spec = DesktopMode.intentFor(view, DesktopModeOp.START)!!
        assertEquals(VictorSuite.RUN_COMMAND_ACTION, spec.action)
        assertEquals(VictorSuite.RUN_COMMAND_SERVICE, spec.className)
        assertEquals(VictorSuite.RUN_COMMAND_PERMISSION, spec.permission)
        assertTrue(spec.isService)
        assertEquals(VictorSuite.BASH_PATH, spec.stringExtras[VictorSuite.EXTRA_COMMAND_PATH])
        val args = spec.stringArrayExtras[VictorSuite.EXTRA_ARGUMENTS]!!
        assertEquals(listOf("-lc", DesktopMode.runCommandLine(DesktopModeOp.START)), args)
        assertTrue(args[1].contains("rootforge-desktop-mode start"))
        assertFalse(args[1].contains("; rm"))
        assertEquals(false, spec.booleanExtras[VictorSuite.EXTRA_BACKGROUND])
        for (op in DesktopModeOp.entries) {
            val line = DesktopMode.runCommandLine(op)
            assertTrue(line.contains(op.wire))
            assertTrue("operation must be a token, not interpolated UI text", op.wire.matches(Regex("[a-z]+")))
        }
    }

    @Test
    fun dedicatedActionPreferredOverRunCommand() {
        val found = vs.copy(exportsDesktopAction = true)
        val pin = PinnedCompanion(found.packageName, found.certSha256)
        val view = DesktopMode.probe(listOf(found), pin)
        assertEquals(DesktopModeStatus.READY_DEDICATED, view.status)
        val spec = DesktopMode.intentFor(view, DesktopModeOp.SETUP)!!
        assertEquals(VictorSuite.ACTION_DESKTOP_MODE, spec.action)
        assertEquals(VictorSuite.PERMISSION_DESKTOP_MODE, spec.permission)
        assertEquals("setup", spec.stringExtras[VictorSuite.EXTRA_OPERATION])
        assertEquals(VictorSuite.DEFAULT_DISPLAY, spec.stringExtras[VictorSuite.EXTRA_DISPLAY])
        assertFalse(spec.stringArrayExtras.containsKey(VictorSuite.EXTRA_ARGUMENTS))
    }

    @Test
    fun candidateOrderPrefersTermuxPackage() {
        val other = CompanionPackage("ai.victorsuite.app", "cc".repeat(32), exportsRunCommand = true, exportsDesktopAction = true)
        val picked = DesktopMode.pick(listOf(other, vs))
        assertEquals(VictorSuite.PACKAGE, picked!!.packageName)
    }

    @Test
    fun unknownPackagesAreIgnored() {
        val stranger = CompanionPackage("com.malware", "dd".repeat(32), true, true)
        assertNull(DesktopMode.pick(listOf(stranger)))
        val view = DesktopMode.probe(listOf(stranger), pinned = null)
        assertEquals(DesktopModeStatus.NOT_INSTALLED, view.status)
    }

    @Test
    fun pinWireRoundTrip() {
        val pin = PinnedCompanion("com.termux", "ab".repeat(32))
        assertEquals("com.termux:" + "ab".repeat(32), pin.wire())
        assertEquals(pin, PinnedCompanion.parse(pin.wire()))
        assertNull(PinnedCompanion.parse(""))
        assertNull(PinnedCompanion.parse("com.termux"))
        assertNull(PinnedCompanion.parse(":abcd"))
        assertNull(PinnedCompanion.parse("com.termux:"))
    }

    @Test
    fun opFromWireRejectsJunk() {
        assertEquals(DesktopModeOp.START, DesktopModeOp.fromWire("start"))
        assertEquals(DesktopModeOp.STATUS, DesktopModeOp.fromWire("status"))
        assertNull(DesktopModeOp.fromWire("rm -rf /"))
        assertNull(DesktopModeOp.fromWire("START"))
        assertNull(DesktopModeOp.fromWire(""))
    }

    @Test
    fun runCommandLineNeverTakesCallerText() {
        val line = DesktopMode.runCommandLine(DesktopModeOp.STOP)
        assertTrue(line.startsWith("command -v rootforge-desktop-mode"))
        assertTrue(line.contains("exec rootforge-desktop-mode stop"))
        assertNotNull(DesktopMode.dedicatedIntent(VictorSuite.PACKAGE, DesktopModeOp.STOP))
    }
}
