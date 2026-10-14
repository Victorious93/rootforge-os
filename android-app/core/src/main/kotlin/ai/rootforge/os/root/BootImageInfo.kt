package ai.rootforge.os.root

import ai.rootforge.os.core.asLe

/** Read-only facts from an Android boot image header; nothing here modifies the image. */
data class BootImageInfo(
    val headerVersion: Int,
    val kernelSize: Long,
    val ramdiskSize: Long,
    val pageSize: Int,
    val osVersion: String?,
    val osPatchLevel: String?,
    val cmdline: String,
    val hasAvbFooter: Boolean,
    val totalSize: Long,
)

object BootImageParser {
    private val MAGIC = "ANDROID!".toByteArray(Charsets.US_ASCII)

    sealed class Result {
        data class Ok(val info: BootImageInfo) : Result()
        data class Invalid(val reason: String) : Result()
    }

    /** [head] is the first bytes of the image (at least 2 KiB); [tail] the last 64 KiB or so, for the AVB footer. */
    fun parse(head: ByteArray, tail: ByteArray, totalSize: Long): Result {
        if (head.size < 1648 || !head.copyOfRange(0, 8).contentEquals(MAGIC)) {
            return Result.Invalid(
                if (head.size >= 8 && String(head, 0, 8, Charsets.ISO_8859_1) == "VNDRBOOT") {
                    "This is a vendor_boot image, not a boot image. Root patching targets boot or init_boot."
                } else {
                    "Not an Android boot image (missing ANDROID! magic)."
                },
            )
        }
        val b = head.asLe()
        val headerVersion = b.getInt(40)
        if (headerVersion !in 0..4) return Result.Invalid("Unsupported boot header version $headerVersion.")

        val kernelSize = b.getInt(8).toLong() and 0xFFFFFFFFL
        val ramdiskSize: Long
        val pageSize: Int
        val osVersionRaw: Int
        val cmdline: String
        if (headerVersion >= 3) {
            ramdiskSize = b.getInt(12).toLong() and 0xFFFFFFFFL
            osVersionRaw = b.getInt(16)
            pageSize = 4096
            cmdline = cString(head, 44, 1536)
        } else {
            ramdiskSize = b.getInt(16).toLong() and 0xFFFFFFFFL
            pageSize = b.getInt(36)
            osVersionRaw = b.getInt(44)
            cmdline = cString(head, 64, 512) + cString(head, 608, 1024)
        }
        val (ver, patch) = decodeOsVersion(osVersionRaw)
        return Result.Ok(
            BootImageInfo(headerVersion, kernelSize, ramdiskSize, pageSize, ver, patch, cmdline.trim(), hasAvbFooter(tail), totalSize),
        )
    }

    /** A signed image ends with a 64-byte AVB footer that begins with "AVBf". */
    private fun hasAvbFooter(tail: ByteArray): Boolean {
        val magic = "AVBf".toByteArray(Charsets.ISO_8859_1)
        if (tail.size < 64) return false
        val start = tail.size - 64
        return (0 until 4).all { tail[start + it] == magic[it] }
    }

    private fun cString(data: ByteArray, offset: Int, max: Int): String {
        var end = offset
        while (end < offset + max && end < data.size && data[end] != 0.toByte()) end++
        return String(data, offset, end - offset, Charsets.ISO_8859_1)
    }

    /** os_version packs A.B.C in the high 21 bits and year/month in the low 11. */
    internal fun decodeOsVersion(raw: Int): Pair<String?, String?> {
        if (raw == 0) return null to null
        val v = raw ushr 11
        val p = raw and 0x7FF
        val a = (v ushr 14) and 0x7F
        val b = (v ushr 7) and 0x7F
        val c = v and 0x7F
        val year = (p ushr 4) + 2000
        val month = p and 0xF
        val patch = if (month in 1..12) "%04d-%02d".format(year, month) else null
        return (if (a in 1..99) "$a.$b.$c" else null) to patch
    }
}
