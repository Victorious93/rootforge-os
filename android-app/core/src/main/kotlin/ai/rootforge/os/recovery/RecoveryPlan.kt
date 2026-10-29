package ai.rootforge.os.recovery

import ai.rootforge.os.core.FileDigest
import ai.rootforge.os.core.humanSize

data class RecoveryPlan(
    val dryRun: Boolean,
    val layout: RecoveryLayout,
    val imageName: String,
    val imageSize: Long,
    val payload: RecoveryPayload,
    val sha256: String,
    val md5: String?,
    val lines: List<String>,
    val blockers: List<String>,
) {
    val allowed: Boolean get() = blockers.isEmpty()
    /** Typed confirmation for a real write. Dry-run has none. */
    val confirmationPhrase: String? get() = if (dryRun || payload != RecoveryPayload.IMAGE) null else "FLASH RECOVERY"
}

enum class RecoveryPayload { IMAGE, ZIP, OTHER }

/**
 * Decides whether an image/zip may be written with `dd`, shown as an
 * installer-zip path, or refused. A zip is never written to a block
 * device. `boot` / `vendor_boot` cannot appear as a write target because
 * [RecoveryLayoutDetector] will not set [RecoveryLayout.writePath] for them.
 */
object RecoveryPlanner {
    const val ANDROID_MAGIC = "ANDROID!"

    fun plan(
        layout: RecoveryLayout,
        rooted: Boolean,
        imageName: String,
        imageSize: Long,
        imageHead: ByteArray,
        sha256: String,
        md5: String? = null,
        publishedMd5: String? = null,
        publishedSha256: String? = null,
        partitionSize: Long? = null,
        dryRun: Boolean,
    ): RecoveryPlan {
        val blockers = mutableListOf<String>()
        val lines = mutableListOf<String>()
        val payload = payloadOf(imageName, imageHead)

        if (!rooted) blockers += "su is not available (uid=0 was not reported). This installer only writes on a rooted phone."
        if (payload == RecoveryPayload.OTHER) {
            blockers += "'$imageName' is not a recovery.img (ANDROID! header) or a zip."
        }
        if (payload == RecoveryPayload.ZIP) {
            if (InstallFlow.INSTALLER_ZIP !in layout.allowedFlows) {
                blockers += "Installer zip is not offered for this layout."
            }
            if (!dryRun) {
                blockers += "A zip is not written with dd. Use it from an already-running recovery (sideload / Install), or dry-run to inspect it."
            }
        }
        if (payload == RecoveryPayload.IMAGE) {
            if (layout.writePath == null) {
                blockers += "No dedicated recovery partition. Will not write boot or vendor_boot. Use Boot once from the Recovery screen, or the official installer zip from inside recovery."
            }
            if (imageSize <= 0) blockers += "The image is empty."
            if (partitionSize == null || partitionSize <= 0) {
                blockers += "Could not read the recovery partition size."
            }
            if (partitionSize != null && partitionSize > RecoveryCommands.MAX_PARTITION_BYTES) {
                blockers += "Recovery partition is ${humanSize(partitionSize)}, larger than the ${humanSize(RecoveryCommands.MAX_PARTITION_BYTES)} safety cap."
            }
            if (partitionSize != null && imageSize > partitionSize) {
                blockers += "Image (${humanSize(imageSize)}) is larger than the recovery partition (${humanSize(partitionSize)})."
            }
        }
        publishedMd5?.let { expected ->
            val actual = md5
            if (actual == null) blockers += "Published MD5 was provided but the file was not hashed."
            else {
                val check = FileDigest.verifyMd5(expected, actual)
                if (!check.ok) blockers += check.message
            }
        }
        publishedSha256?.let { expected ->
            val check = FileDigest.verify(expected, sha256)
            if (!check.ok) blockers += check.message
        }

        lines += if (dryRun) "Mode:       dry-run (nothing will be written)" else "Mode:       WRITE recovery"
        lines += "Flows:      ${layout.allowedFlows.joinToString(", ")}"
        lines += "Layout:     ${layout.kind} · slot ${layout.currentSlot ?: "none / unknown"} · ${layout.slotScheme}"
        lines += "Partition:  ${layout.writePath ?: "none (no dedicated recovery)"}"
        lines += "Image:      $imageName (${humanSize(imageSize)}, $payload)"
        lines += "SHA-256:    $sha256"
        if (md5 != null) lines += "MD5:        $md5"
        if (publishedMd5 != null) lines += "Published MD5: $publishedMd5"
        if (partitionSize != null) lines += "Partition size: ${humanSize(partitionSize)}"
        layout.notes.forEach { lines += it }
        if (!dryRun && payload == RecoveryPayload.IMAGE) {
            lines += "A wrong recovery image can leave this phone unable to boot to recovery or, on some devices, unable to boot at all. A backup of the current partition is taken first, but restoring it is not automatic."
            lines += "Type FLASH RECOVERY to write. There is no setting that skips this."
        }
        if (dryRun && layout.writePath != null && payload == RecoveryPayload.IMAGE) {
            lines += "Would back up ${layout.writePath} then dd the image onto it. Not executed."
        }

        return RecoveryPlan(
            dryRun = dryRun,
            layout = layout,
            imageName = imageName,
            imageSize = imageSize,
            payload = payload,
            sha256 = sha256,
            md5 = md5,
            lines = lines,
            blockers = blockers,
        )
    }

    fun payloadOf(name: String, head: ByteArray): RecoveryPayload = when {
        isAndroidImage(head) -> RecoveryPayload.IMAGE
        isZipLocalHeader(head) || name.endsWith(".zip", ignoreCase = true) -> RecoveryPayload.ZIP
        else -> RecoveryPayload.OTHER
    }

    /** Leading bytes of an Android boot or recovery image (`ANDROID!`). The file name is ignored. */
    fun isAndroidImage(head: ByteArray): Boolean =
        head.size >= ANDROID_MAGIC.length &&
            String(head, 0, ANDROID_MAGIC.length, Charsets.ISO_8859_1) == ANDROID_MAGIC

    /**
     * Zip local-file header (`PK\x03\x04`). A name ending in `.zip` is not a zip:
     * dl.twrp.me returns an HTML page for that name when the Referer is missing.
     */
    fun isZipLocalHeader(head: ByteArray): Boolean =
        head.size >= 4 &&
            head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
}
