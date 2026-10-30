package ai.rootforge.os.bridge

import ai.rootforge.os.usb.AttachedTarget
import ai.rootforge.os.usb.TargetMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeListingTest {
    @Test
    fun onlyTheActiveSerialInThatModeIsConnected() {
        val left = phone("LEFT", TargetMode.ADB)
        val right = phone("RIGHT", TargetMode.ADB)
        assertTrue(BridgeListing.isConnected(left, TargetMode.ADB, "LEFT"))
        assertFalse(BridgeListing.isConnected(right, TargetMode.ADB, "LEFT"))
        assertFalse(BridgeListing.isConnected(left, TargetMode.FASTBOOT, "LEFT"))
        assertFalse(BridgeListing.isConnected(left, TargetMode.ADB, null))
    }

    private fun phone(serial: String, mode: TargetMode): AttachedTarget = AttachedTarget(
        deviceName = serial,
        vendorId = 0,
        productId = 0,
        mode = mode,
        interfaceId = null,
        hasPermission = true,
        serial = serial,
        product = null,
        manufacturer = null,
    )
}
