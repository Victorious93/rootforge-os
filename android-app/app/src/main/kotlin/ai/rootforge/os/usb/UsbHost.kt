package ai.rootforge.os.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/** Bulk endpoints of one claimed interface, driven through the Android USB Host API. */
class UsbHostTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
) : BulkTransport {
    @Volatile private var closed = false

    override fun write(data: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        var sent = 0
        while (sent < length) {
            check(!closed) { "USB connection closed" }
            val chunk = minOf(CHUNK, length - sent)
            val n = connection.bulkTransfer(outEndpoint, data, offset + sent, chunk, timeoutMs)
            if (n < 0) throw IOException("USB bulk write failed after $sent of $length bytes")
            sent += n
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        check(!closed) { "USB connection closed" }
        val n = connection.bulkTransfer(inEndpoint, buffer, offset, length, timeoutMs)
        if (n < 0) throw UsbTimeoutException("USB bulk read timed out or failed")
        return n
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    private companion object {
        const val CHUNK = 16 * 1024
    }
}

/**
 * Enumerates USB devices, asks for permission (Android requires an explicit
 * user grant per device), and opens a claimed bulk transport.
 *
 * Nothing here opens a device without a permission the user granted, and no
 * state-changing command is sent from this class.
 */
class UsbHost(private val context: Context) {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val state = MutableStateFlow<List<AttachedTarget>>(emptyList())
    val targets: StateFlow<List<AttachedTarget>> = state.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh()
        }
    }
    private var registered = false

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        refresh()
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    fun refresh() {
        state.value = manager.deviceList.values
            .map { describe(it) }
            .sortedBy { it.mode == TargetMode.UNKNOWN }
    }

    private fun describe(device: UsbDevice): AttachedTarget {
        val ifaces = (0 until device.interfaceCount).map { device.getInterface(it) }
            .map { UsbIfaceInfo(it.id, it.interfaceClass, it.interfaceSubclass, it.interfaceProtocol) }
        val cls = UsbClassifier.classify(device.vendorId, device.productId, ifaces)
        val granted = manager.hasPermission(device)
        return AttachedTarget(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            mode = cls.mode,
            interfaceId = cls.interfaceId,
            hasPermission = granted,
            // Reading the serial or product strings needs the permission on API 29+.
            serial = if (granted) runCatching { device.serialNumber }.getOrNull() else null,
            product = if (granted) runCatching { device.productName }.getOrNull() else null,
            manufacturer = if (granted) runCatching { device.manufacturerName }.getOrNull() else null,
        )
    }

    fun requestPermission(target: AttachedTarget) {
        val device = manager.deviceList[target.deviceName] ?: return
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        // The system fills the grant result into this intent, so it must be mutable.
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    /** Opens and claims the classified interface; throws if the user has not granted access. */
    fun open(target: AttachedTarget): UsbHostTransport {
        val device = manager.deviceList[target.deviceName]
            ?: throw IOException("The device was unplugged.")
        if (!manager.hasPermission(device)) throw IOException("USB permission has not been granted for ${target.vidPid}.")
        val interfaceId = target.interfaceId ?: throw IOException("No usable interface found on ${target.vidPid}.")
        val iface = (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.id == interfaceId } ?: throw IOException("Interface $interfaceId disappeared.")
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
        }
        if (inEp == null || outEp == null) throw IOException("Interface has no bulk IN/OUT endpoint pair.")
        val connection = manager.openDevice(device) ?: throw IOException("openDevice failed (device busy or permission revoked).")
        // force=true takes the interface from a kernel driver if one is bound (rare on Android hosts).
        if (!connection.claimInterface(iface, true)) {
            connection.close()
            throw IOException("Could not claim the USB interface; another app may be using it.")
        }
        return UsbHostTransport(connection, iface, inEp, outEp)
    }

    companion object {
        const val ACTION_USB_PERMISSION = "ai.rootforge.os.USB_PERMISSION"
    }
}
