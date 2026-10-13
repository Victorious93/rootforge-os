package ai.rootforge.os.usb

import java.io.Closeable
import java.io.IOException

class UsbTimeoutException(message: String) : IOException(message)

/**
 * A pair of bulk endpoints. Everything above this interface (ADB, fastboot,
 * Sahara/Firehose) is plain Kotlin that can be tested against a fake, which
 * is how the protocol code is verified without hardware.
 */
interface BulkTransport : Closeable {
    /** Writes the whole range or throws. */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size, timeoutMs: Int = 5000)

    /** Reads at least one byte (up to [length]) or throws [UsbTimeoutException]. */
    fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size, timeoutMs: Int = 5000): Int
}

/** Reads exactly [length] bytes, however many USB packets that takes. */
fun BulkTransport.readFully(length: Int, timeoutMs: Int = 5000): ByteArray {
    val out = ByteArray(length)
    var got = 0
    while (got < length) {
        val n = read(out, got, length - got, timeoutMs)
        if (n <= 0) throw IOException("USB read returned $n")
        got += n
    }
    return out
}
