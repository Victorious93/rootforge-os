package ai.rootforge.os.desktop

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement

/**
 * Serial captured when a desktop operation starts. Host `adb` / `fastboot`
 * keep passing `-s` this value after a scan clears the active device, so a
 * confirmation dialog cannot be redirected onto a different phone.
 */
internal class DeviceSerialPin(val serial: String?) : ThreadContextElement<DeviceSerialPin?> {
    override val key: CoroutineContext.Key<DeviceSerialPin> = Key

    override fun updateThreadContext(context: CoroutineContext): DeviceSerialPin? {
        val previous = CURRENT.get()
        CURRENT.set(this)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: DeviceSerialPin?) {
        if (oldState == null) CURRENT.remove() else CURRENT.set(oldState)
    }

    internal companion object Key : CoroutineContext.Key<DeviceSerialPin> {
        private val CURRENT = ThreadLocal<DeviceSerialPin?>()

        fun current(): DeviceSerialPin? = CURRENT.get()
    }
}
