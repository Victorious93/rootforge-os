package ai.rootforge.os.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

fun leBuffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

fun ByteArray.asLe(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd-length hex string" }
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KiB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MiB".format(bytes / 1048576.0)
    else -> "%.2f GiB".format(bytes / 1073741824.0)
}
