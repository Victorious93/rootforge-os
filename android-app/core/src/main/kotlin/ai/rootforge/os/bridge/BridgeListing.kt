package ai.rootforge.os.bridge

import ai.rootforge.os.device.rosterKey
import ai.rootforge.os.usb.AttachedTarget
import ai.rootforge.os.usb.TargetMode

/**
 * How [BridgeBackend.devices] decides `state=connected`. Two phones in the
 * same USB mode used to both show as connected because only the mode was
 * compared.
 */
object BridgeListing {
    fun isConnected(target: AttachedTarget, activeMode: TargetMode?, activeSerial: String?): Boolean {
        if (activeMode == null || activeSerial.isNullOrBlank()) return false
        return target.mode == activeMode && target.rosterKey() == activeSerial
    }
}
