package ai.rootforge.os.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuStateResolverTest {
    @Test
    fun notInstalledWhenNoManagerAndNoBinder() {
        assertEquals(
            ShizukuState.NOT_INSTALLED,
            ShizukuStateResolver.resolve(ShizukuSignals(managerInstalled = false, binderAlive = false, permissionGranted = false)),
        )
    }

    @Test
    fun notRunningWhenManagerIsInstalledButBinderIsDead() {
        assertEquals(
            ShizukuState.NOT_RUNNING,
            ShizukuStateResolver.resolve(ShizukuSignals(managerInstalled = true, binderAlive = false, permissionGranted = false)),
        )
    }

    @Test
    fun deniedWhenBinderIsAliveWithoutPermission() {
        assertEquals(
            ShizukuState.DENIED,
            ShizukuStateResolver.resolve(ShizukuSignals(managerInstalled = true, binderAlive = true, permissionGranted = false)),
        )
    }

    @Test
    fun grantedWhenBinderIsAliveAndPermissionIsGranted() {
        assertEquals(
            ShizukuState.GRANTED,
            ShizukuStateResolver.resolve(ShizukuSignals(managerInstalled = true, binderAlive = true, permissionGranted = true)),
        )
    }

    @Test
    fun suiWithALiveBinderAndNoVisibleManagerIsStillPresent() {
        assertEquals(
            ShizukuState.DENIED,
            ShizukuStateResolver.resolve(ShizukuSignals(managerInstalled = false, binderAlive = true, permissionGranted = false)),
        )
        assertEquals(
            ShizukuState.GRANTED,
            ShizukuStateResolver.resolve(ShizukuSignals(managerInstalled = false, binderAlive = true, permissionGranted = true)),
        )
    }

    @Test
    fun permissionGrantedIsIgnoredWhenTheBinderIsDead() {
        assertEquals(
            ShizukuState.NOT_RUNNING,
            ShizukuStateResolver.resolve(ShizukuSignals(managerInstalled = true, binderAlive = false, permissionGranted = true)),
        )
        assertEquals(
            ShizukuState.NOT_INSTALLED,
            ShizukuStateResolver.resolve(ShizukuSignals(managerInstalled = false, binderAlive = false, permissionGranted = true)),
        )
    }

    @Test
    fun presentIsManagerInstalledOrBinderAlive() {
        val present = { installed: Boolean, alive: Boolean -> installed || alive }
        assertEquals(false, present(false, false))
        assertEquals(true, present(true, false))
        assertEquals(true, present(false, true))
        assertEquals(true, present(true, true))
    }

    @Test
    fun copyCoversEveryStateAndTheRequestPermissionPrompt() {
        ShizukuState.entries.forEach { state ->
            assertTrue(ShizukuCopy.statusTitle(state).isNotBlank())
            assertTrue(ShizukuCopy.statusBody(state).isNotBlank())
        }
        assertTrue(ShizukuCopy.statusBody(ShizukuState.DENIED).contains("Grant this app permission"))
        assertTrue(ShizukuCopy.statusBody(ShizukuState.NOT_RUNNING).contains("wireless debugging"))
        assertTrue(ShizukuCopy.statusBody(ShizukuState.NOT_INSTALLED).contains("still works"))
        assertTrue(ShizukuCopy.statusBody(ShizukuState.GRANTED).contains("confirm"))
        assertEquals(4, ShizukuState.entries.size)
    }
}
