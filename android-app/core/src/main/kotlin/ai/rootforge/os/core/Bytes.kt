package ai.rootforge.os.core

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun leBuffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

fun ByteArray.asLe(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd-length hex string" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/**
 * First [maxBytes] of a stream. Uses [InputStream.read], which exists on
 * Android API 26; [InputStream.readNBytes] is API 33 and crashes below that.
 */
fun InputStream.readPrefix(maxBytes: Int): ByteArray {
    require(maxBytes >= 0) { "maxBytes must be >= 0" }
    if (maxBytes == 0) return ByteArray(0)
    val buf = ByteArray(maxBytes)
    var n = 0
    while (n < maxBytes) {
        val r = read(buf, n, maxBytes - n)
        if (r < 0) break
        n += r
    }
    return if (n == maxBytes) buf else buf.copyOf(n)
}

fun File.readPrefix(maxBytes: Int): ByteArray = inputStream().use { it.readPrefix(maxBytes) }

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MiB".format(bytes / 1048576.0)
    else -> "%.2f GiB".format(bytes / 1073741824.0)
}
