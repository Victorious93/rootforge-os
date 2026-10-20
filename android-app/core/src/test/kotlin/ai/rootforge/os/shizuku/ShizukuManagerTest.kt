package ai.rootforge.os.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeShizukuHost : ShizukuHost {
    var signals = ShizukuSignals(false, false, false)
    var requestCodes = mutableListOf<Int>()
    var bound: ShizukuHostListener? = null

    override fun signals(): ShizukuSignals = signals

    override fun requestPermission(requestCode: Int) {
        requestCodes += requestCode
    }

    override fun setListener(listener: ShizukuHostListener?) {
        bound = listener
    }
}

class ShizukuManagerTest {
    @Test
    fun startIsIdempotentAndBinderChangesNotifyListeners() {
        val host = FakeShizukuHost()
        val seen = mutableListOf<ShizukuState>()
        val manager = ShizukuManager(host)
        manager.start()
        manager.start()
        manager.addListener { seen += it }
        assertEquals(listOf(ShizukuState.NOT_INSTALLED), seen)

        host.signals = ShizukuSignals(managerInstalled = true, binderAlive = true, permissionGranted = false)
        host.bound!!.onBinderChanged()
        assertEquals(ShizukuState.DENIED, seen.last())
    }

    @Test
    fun requestPermissionOnlyFiresWhenDenied() {
        val host = FakeShizukuHost()
        val manager = ShizukuManager(host)
        manager.start()

        manager.requestPermission()
        assertTrue(host.requestCodes.isEmpty())

        host.signals = ShizukuSignals(true, true, false)
        manager.requestPermission()
        assertEquals(listOf(ShizukuManager.PERMISSION_REQUEST_CODE), host.requestCodes)

        host.signals = ShizukuSignals(true, true, true)
        manager.requestPermission()
        assertEquals(1, host.requestCodes.size)
    }

    @Test
    fun permissionListenerIgnoresOtherRequestCodes() {
        val host = FakeShizukuHost()
        val seen = mutableListOf<ShizukuState>()
        val manager = ShizukuManager(host)
        manager.start()
        manager.addListener { seen += it }
        seen.clear()
        host.bound!!.onPermissionResult(99, true)
        assertTrue(seen.isEmpty())
        host.signals = ShizukuSignals(true, true, true)
        host.bound!!.onPermissionResult(ShizukuManager.PERMISSION_REQUEST_CODE, true)
        assertEquals(listOf(ShizukuState.GRANTED), seen)
    }

    @Test
    fun stopDropsTheHostListener() {
        val host = FakeShizukuHost()
        val manager = ShizukuManager(host)
        manager.start()
        manager.stop()
        assertEquals(null, host.bound)
    }

    @Test
    fun refreshReEmitsWithoutRequestingPermission() {
        val host = FakeShizukuHost()
        val seen = mutableListOf<ShizukuState>()
        val manager = ShizukuManager(host)
        manager.start()
        manager.addListener { seen += it }
        seen.clear()
        host.signals = ShizukuSignals(true, true, false)
        manager.refresh()
        assertEquals(listOf(ShizukuState.DENIED), seen)
        assertTrue(host.requestCodes.isEmpty())
    }
}
