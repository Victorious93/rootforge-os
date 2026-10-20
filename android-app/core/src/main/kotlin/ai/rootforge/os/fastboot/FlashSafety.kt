package ai.rootforge.os.fastboot

import ai.rootforge.os.core.DigestCheck
import ai.rootforge.os.core.FileDigest
import ai.rootforge.os.core.Library
import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.core.OutputLog
import java.io.File
import java.io.IOException

data class FlashSafetyResult(
    val claim: ImageClaim?,
    val sha256: String,
    val digest: DigestCheck?,
    val match: DeviceMatch,
) {
    val blockers: List<String>
        get() {
            val out = mutableListOf<String>()
            if (digest != null && !digest.ok) out += digest.message
            if (match is DeviceMatch.Mismatch) out += match.reasons
            return out
        }
    val allowed: Boolean get() = blockers.isEmpty()

    fun planLines(identity: TargetIdentity): List<String> = listOf(
        "Device match: product ${identity.product ?: "unknown"} / " +
            "codename ${identity.codename ?: "unknown"}, " +
            "slot ${identity.slot ?: "unknown"}" +
            (identity.fingerprint?.let { ", fingerprint set" } ?: ""),
        "Image SHA-256: $sha256",
        "Claim: " + (claim?.let { c ->
            listOfNotNull(
                c.product?.let { "product $it" },
                c.codename?.let { "codename $it" },
                c.slot?.let { "slot $it" },
                if (c.fingerprint != null) "fingerprint declared" else "declared manifest (no fingerprint)",
            ).joinToString(", ")
        } ?: "none"),
    )
}

/**
 * The extra flash gate: sidecar claim, optional SHA-256 pin, then
 * [DeviceMatchGate]. Callers still run [FlashPlanner] and the typed phrase.
 */
object FlashSafety {
    fun evaluate(identity: TargetIdentity, imageName: String, imageFile: File, sidecarJson: String?): FlashSafetyResult {
        val claim = sidecarJson?.let { text ->
            runCatching { FlashManifest.parse(text) }.getOrElse {
                return FlashSafetyResult(
                    claim = null,
                    sha256 = FileDigest.sha256Hex(imageFile),
                    digest = null,
                    match = DeviceMatch.Mismatch(listOf("Image sidecar is not a valid manifest: ${it.message}")),
                )
            }
        }
        val sha = FileDigest.sha256Hex(imageFile)
        val digest = TargetIdentity.clean(claim?.sha256)?.let { FileDigest.verify(it, sha) }
        val match = DeviceMatchGate.check(identity, claim)
        return FlashSafetyResult(claim, sha, digest, match)
    }

    fun evaluateLibrary(library: Library, image: LibraryFile, identity: TargetIdentity): FlashSafetyResult {
        val json = loadSidecar(library, image.name)
        return evaluate(identity, image.name, image.file, json)
    }

    fun loadSidecar(library: Library, imageName: String): String? {
        for (name in FlashManifest.sidecarNames(imageName)) {
            val file = library.get(name) ?: continue
            return file.file.readText()
        }
        return null
    }

    fun refuse(log: OutputLog, result: FlashSafetyResult) {
        if (result.allowed) return
        result.blockers.forEach { log.error("Refused: $it") }
        throw IOException("Refused: " + result.blockers.joinToString(" "))
    }
}
