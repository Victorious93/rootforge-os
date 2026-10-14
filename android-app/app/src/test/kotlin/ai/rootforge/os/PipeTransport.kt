package ai.rootforge.os

import ai.rootforge.os.usb.BulkTransport
import ai.rootforge.os.usb.UsbTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * An in-memory USB link. Each write becomes one "packet" for the other side,
 * so the tests see the same header/payload split a real bulk endpoint does.
 */
class PipeEnd(private val outbound: LinkedBlockingQueue<ByteArray>, private val inbound: LinkedBlockingQueue<ByteArray>) : BulkTransport {
    private var leftover = ByteArray(0)
    @Volatile var closed = false

    override fun write(data: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        check(!closed) { "closed" }
        outbound.put(data.copyOfRange(offset, offset + length))
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (leftover.isEmpty()) {
            leftover = inbound.poll(timeoutMs.toLong().coerceAtLeast(1), TimeUnit.MILLISECONDS)
                ?: throw UsbTimeoutException("timeout")
        }
        val n = minOf(length, leftover.size)
        System.arraycopy(leftover, 0, buffer, offset, n)
        leftover = leftover.copyOfRange(n, leftover.size)
        return n
    }

    /** Test-side helper: blocks for the next whole packet. */
    fun nextPacket(timeoutMs: Long = 5000): ByteArray =
        (if (leftover.isNotEmpty()) leftover.also { leftover = ByteArray(0) } else inbound.poll(timeoutMs, TimeUnit.MILLISECONDS))
            ?: throw UsbTimeoutException("device side timed out waiting for a packet")

    fun send(packet: ByteArray) = outbound.put(packet)

    override fun close() { closed = true }
}

class Pipe {
    private val a = LinkedBlockingQueue<ByteArray>()
    private val b = LinkedBlockingQueue<ByteArray>()
    val host = PipeEnd(outbound = a, inbound = b)
    val device = PipeEnd(outbound = b, inbound = a)
}
