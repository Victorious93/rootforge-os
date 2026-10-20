package ai.rootforge.os.core

import ai.rootforge.os.fastboot.FlashManifest
import ai.rootforge.os.fastboot.ImageClaim

enum class FileKind { BOOT_IMAGE, VENDOR_BOOT, SPARSE_IMAGE, APK, ZIP, MANIFEST, UNKNOWN }

data class FileInspection(
    val name: String,
    val size: Long,
    val sha256: String,
    val kind: FileKind,
    val notes: List<String>,
    val claim: ImageClaim?,
)

/** Read-only facts about a library file. Nothing here writes or talks to USB. */
object FileInspector {
    fun inspect(
        name: String,
        size: Long,
        head: ByteArray,
        sha256: String,
        sidecarJson: String? = null,
    ): FileInspection {
        val kind = kindOf(name, head)
        val notes = mutableListOf<String>()
        notes += "Size ${humanSize(size)}"
        notes += "SHA-256 $sha256"
        when (kind) {
            FileKind.BOOT_IMAGE -> notes += "Android boot image (ANDROID! header)."
            FileKind.VENDOR_BOOT -> notes += "vendor_boot image (VNDRBOOT header)."
            FileKind.SPARSE_IMAGE -> notes += "Android sparse image."
            FileKind.APK -> notes += "Looks like an APK (ZIP container, .apk name)."
            FileKind.ZIP -> notes += "ZIP archive."
            FileKind.MANIFEST -> notes += "Sidecar device-claim manifest."
            FileKind.UNKNOWN -> notes += "Unrecognised header; flashing this still requires a matching sidecar."
        }
        val claim = when {
            kind == FileKind.MANIFEST -> runCatching { FlashManifest.parse(String(head, Charsets.UTF_8).trim()) }.getOrNull()
            sidecarJson != null -> runCatching { FlashManifest.parse(sidecarJson) }.getOrNull()
            else -> null
        }
        if (sidecarJson != null && claim == null && kind != FileKind.MANIFEST) {
            notes += "Sidecar was present but was not a valid manifest."
        } else if (claim != null) {
            notes += "Claim: product=${claim.product ?: "—"} codename=${claim.codename ?: "—"} slot=${claim.slot ?: "—"}"
        } else if (kind != FileKind.MANIFEST && kind != FileKind.APK) {
            notes += "No sidecar claim. A flash of this file will be refused until you import one."
        }
        return FileInspection(name, size, sha256, kind, notes, claim)
    }

    internal fun kindOf(name: String, head: ByteArray): FileKind {
        val lower = name.lowercase()
        if (lower.endsWith(".manifest.json") || (lower.endsWith(".json") && looksLikeManifest(head))) {
            return FileKind.MANIFEST
        }
        if (head.size >= 8 && ascii(head, 0, 8) == "ANDROID!") return FileKind.BOOT_IMAGE
        if (head.size >= 8 && ascii(head, 0, 8) == "VNDRBOOT") return FileKind.VENDOR_BOOT
        if (head.size >= 4 &&
            head[0] == 0x3A.toByte() && head[1] == 0xFF.toByte() &&
            head[2] == 0x26.toByte() && head[3] == 0xED.toByte()
        ) return FileKind.SPARSE_IMAGE
        if (head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
        ) {
            return if (lower.endsWith(".apk")) FileKind.APK else FileKind.ZIP
        }
        return FileKind.UNKNOWN
    }

    private fun looksLikeManifest(head: ByteArray): Boolean {
        val text = String(head, Charsets.UTF_8)
        return text.contains("\"product\"") || text.contains("\"codename\"") || text.contains("\"fingerprint\"")
    }

    private fun ascii(data: ByteArray, offset: Int, length: Int): String =
        String(data, offset, minOf(length, data.size - offset), Charsets.ISO_8859_1)
}
