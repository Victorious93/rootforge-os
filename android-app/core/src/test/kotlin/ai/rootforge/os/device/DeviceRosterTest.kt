package ai.rootforge.os.device

import ai.rootforge.os.usb.AttachedTarget
import ai.rootforge.os.usb.TargetMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRosterTest {
    @Test
    fun aPermissionStubIsIdentifiedAgainOnceThePhoneIsUsable() {
        val blocked = target(hasPermission = false)
        val stub = DeviceIdentityParser.stub(blocked, "USB permission needed.")
        val identities = mapOf(blocked.rosterKey() to stub)

        assertFalse(
            "do not keep republishing the same stub every poll",
            DeviceRoster.needsIdentify(blocked, identities),
        )
        assertTrue(
            "after USB access is granted the stub must be replaced",
            DeviceRoster.needsIdentify(target(hasPermission = true), identities),
        )
    }

    @Test
    fun aDesktopUnauthorisedStubIsIdentifiedAgainAfterAdbAuthorises() {
        val blocked = target(hasPermission = false, serial = "PIXELTEST")
        val stub = DeviceIdentityParser.stub(blocked, "unauthorized or offline")
        val identities = mapOf(blocked.rosterKey() to stub)

        assertFalse(DeviceRoster.needsIdentify(blocked, identities))
        assertTrue(DeviceRoster.needsIdentify(target(hasPermission = true, serial = "PIXELTEST"), identities))
    }

    @Test
    fun aFailedIdentifyStubIsNotRetriedEveryPoll() {
        val usable = target(hasPermission = true)
        val stub = DeviceIdentityParser.stub(usable, "boom")
        assertFalse(DeviceRoster.needsIdentify(usable, mapOf(usable.rosterKey() to stub)))
    }

    private fun target(hasPermission: Boolean, serial: String = "ABC123"): AttachedTarget =
        AttachedTarget(
            deviceName = "/dev/bus/usb/001/002",
            vendorId = 0x18D1,
            productId = 0x4EE7,
            mode = TargetMode.ADB,
            interfaceId = 1,
            hasPermission = hasPermission,
            serial = serial,
            product = "Pixel 8",
            manufacturer = "Google",
        )
}
