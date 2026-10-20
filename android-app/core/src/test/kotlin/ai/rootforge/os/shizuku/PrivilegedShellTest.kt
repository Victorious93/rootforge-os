package ai.rootforge.os.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedShellTest {
    @Test
    fun nullShellIsUnavailableAndNeverFabricatesSuccess() {
        val shell = NullPrivilegedShell()
        assertFalse(shell.isAvailable())
        assertTrue(shell.exec(listOf("id")) is PrivilegedResult.Failure)
        assertTrue(shell.readDeviceState() is PrivilegedResult.Failure)
        assertTrue(shell.pmGrant("ai.rootforge.os", "android.permission.INTERNET") is PrivilegedResult.Failure)
    }

    @Test
    fun resultCodecRoundTripsSuccessAndFailureIncludingNewlines() {
        val success = PrivilegedResult.Success(0, "out\nline", "err\nline", timedOut = false)
        assertEquals(success, PrivilegedResultCodec.decode(PrivilegedResultCodec.encode(success)))
        val timedOut = PrivilegedResult.Success(143, "", "", timedOut = true)
        assertEquals(timedOut, PrivilegedResultCodec.decode(PrivilegedResultCodec.encode(timedOut)))
        val failure = PrivilegedResult.Failure("nope\nreally")
        val decoded = PrivilegedResultCodec.decode(PrivilegedResultCodec.encode(failure)) as PrivilegedResult.Failure
        assertEquals("nope\nreally", decoded.reason)
    }

    @Test
    fun codecRejectsGarbageWithoutThrowing() {
        assertTrue(PrivilegedResultCodec.decode("") is PrivilegedResult.Failure)
        assertTrue(PrivilegedResultCodec.decode("nope") is PrivilegedResult.Failure)
        assertTrue(PrivilegedResultCodec.decode("S\nnot-an-int\n0\n\n") is PrivilegedResult.Failure)
    }

    @Test
    fun androidNamesAcceptPackagesAndPermissionsAndRejectInjection() {
        assertTrue(PrivilegedNames.isSafeAndroidName("ai.rootforge.os"))
        assertTrue(PrivilegedNames.isSafeAndroidName("android.permission.WRITE_SECURE_SETTINGS"))
        assertFalse(PrivilegedNames.isSafeAndroidName("id"))
        assertFalse(PrivilegedNames.isSafeAndroidName("ai.rootforge.os; rm -rf /"))
        assertFalse(PrivilegedNames.isSafeAndroidName("ai.rootforge.os && id"))
        assertFalse(PrivilegedNames.isSafeAndroidName(""))
    }

    @Test
    fun processRunnerExecutesArgvAndRefusesAnEmptyVector() {
        assertTrue(PrivilegedProcessRunner.run(emptyList(), 5_000) is PrivilegedResult.Failure)
        val result = PrivilegedProcessRunner.run(listOf("echo", "shizuku-ok"), 5_000) as PrivilegedResult.Success
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("shizuku-ok"))
        assertFalse(result.timedOut)
    }

    @Test
    fun processRunnerCapsOutputAndDoesNotUseAShellString() {
        assertEquals(64 * 1024, PrivilegedProcessRunner.MAX_OUTPUT_BYTES)
        val nul = PrivilegedProcessRunner.run(listOf("echo", "x\u0000y"), 5_000)
        assertTrue(nul is PrivilegedResult.Failure)
    }

    @Test
    fun pmGrantRefusesUnsafeNamesBeforeSpawning() {
        val badPkg = PrivilegedProcessRunner.pmGrant("not a package", "android.permission.INTERNET") as PrivilegedResult.Failure
        assertTrue(badPkg.reason.contains("package"))
        val badPerm = PrivilegedProcessRunner.pmGrant("ai.rootforge.os", "INTERNET")
        assertTrue(badPerm is PrivilegedResult.Failure)
    }
}
