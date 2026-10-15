package ai.rootforge.os.fastboot

import ai.rootforge.os.core.TargetInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the connected target currently is, as far as ADB props or fastboot
 * getvar reported. Blank / "unknown" become null: unknown is not a wildcard.
 */
data class TargetIdentity(
    val product: String? = null,
    val codename: String? = null,
    val slot: String? = null,
    val fingerprint: String? = null,
) {
    fun names(): Set<String> = listOfNotNull(product, codename).map { it.lowercase() }.toSet()

    companion object {
        fun fromFastboot(vars: Map<String, String>): TargetIdentity = TargetIdentity(
            product = clean(vars["product"]),
            codename = clean(vars["device"] ?: vars["product"]),
            slot = normalizeSlot(vars["current-slot"]),
            fingerprint = clean(vars["fingerprint"]),
        )

        fun fromAdbProps(props: Map<String, String>): TargetIdentity = TargetIdentity(
            product = clean(props["ro.product.name"]),
            codename = clean(props["ro.product.device"]),
            slot = normalizeSlot(props["ro.boot.slot_suffix"]),
            fingerprint = clean(props["ro.build.fingerprint"]),
        )

        fun fromTargetInfo(info: TargetInfo): TargetIdentity {
            val rows = info.rows.toMap()
            return TargetIdentity(
                product = clean(rows["Product"]),
                codename = clean(rows["Codename"]),
                slot = normalizeSlot(rows["Slot"] ?: rows["Current slot"]),
                fingerprint = clean(rows["Build"]),
            )
        }

        /** Non-null fields on [primary] win; [fallback] fills the gaps. */
        fun merge(primary: TargetIdentity?, fallback: TargetIdentity?): TargetIdentity = TargetIdentity(
            product = primary?.product ?: fallback?.product,
            codename = primary?.codename ?: fallback?.codename,
            slot = primary?.slot ?: fallback?.slot,
            fingerprint = primary?.fingerprint ?: fallback?.fingerprint,
        )

        internal fun clean(raw: String?): String? {
            val t = raw?.trim().orEmpty()
            if (t.isEmpty() || t.equals("unknown", ignoreCase = true) || t.equals("none / unknown", ignoreCase = true)) return null
            return t
        }

        internal fun normalizeSlot(raw: String?): String? {
            val t = clean(raw) ?: return null
            val s = t.trim().trimStart('_').lowercase()
            return when {
                s == "*" || s == "any" -> "*"
                s == "a" || s == "b" -> s
                s.startsWith("a") && s.length <= 2 -> "a"
                s.startsWith("b") && s.length <= 2 -> "b"
                else -> s.takeIf { it.isNotEmpty() }
            }
        }
    }
}

/**
 * What an image claims to be for. Loaded from a sidecar JSON next to the
 * library file. A claim with product or codename is a declared manifest:
 * that satisfies the fingerprint clause when the image itself has none.
 */
@Serializable
data class ImageClaim(
    val product: String? = null,
    val codename: String? = null,
    val slot: String? = null,
    val fingerprint: String? = null,
    val sha256: String? = null,
) {
    fun names(): Set<String> = listOfNotNull(
        TargetIdentity.clean(product),
        TargetIdentity.clean(codename),
    ).map { it.lowercase() }.toSet()

    fun declared(): Boolean = names().isNotEmpty()
}

sealed class DeviceMatch {
    data object Ok : DeviceMatch()
    data class Mismatch(val reasons: List<String>) : DeviceMatch() {
        init { require(reasons.isNotEmpty()) { "a mismatch needs at least one reason" } }
    }
}

/**
 * Refuses a flash unless the image matches the target. Unknown is not safe.
 *
 * Required: product/codename overlap. Slot if the claim names one (not `*`).
 * Fingerprint if the claim names one; otherwise a declared sidecar (product
 * or codename present) is enough.
 */
object DeviceMatchGate {
    const val NO_CLAIM =
        "Image has no device claim. Import a sidecar <image>.manifest.json declaring product or codename, slot, and either fingerprint or a declared product/slot match."
    const val NO_PRODUCT = "Manifest does not declare product or codename."
    const val TARGET_UNKNOWN = "Target product/codename is unknown; cannot confirm the image is for this device."
    const val TARGET_SLOT_UNKNOWN_PREFIX = "Image is for slot '"
    const val TARGET_FP_UNKNOWN = "Image fingerprint is set; target build fingerprint is unknown."
    const val FP_MISMATCH = "Image fingerprint does not match the target build."

    fun check(target: TargetIdentity, claim: ImageClaim?): DeviceMatch {
        if (claim == null) return DeviceMatch.Mismatch(listOf(NO_CLAIM))
        val reasons = mutableListOf<String>()

        val claimNames = claim.names()
        if (claimNames.isEmpty()) {
            reasons += NO_PRODUCT
        } else {
            val targetNames = target.names()
            if (targetNames.isEmpty()) {
                reasons += TARGET_UNKNOWN
            } else if (claimNames.intersect(targetNames).isEmpty()) {
                val want = (listOfNotNull(claim.product, claim.codename).distinct()).joinToString(" / ")
                val gotProduct = target.product ?: "unknown"
                val gotCodename = target.codename ?: "unknown"
                reasons += "Image is for product/codename '$want'; target reports product '$gotProduct' / codename '$gotCodename'."
            }
        }

        val claimSlot = TargetIdentity.normalizeSlot(claim.slot)
        if (claimSlot != null && claimSlot != "*") {
            val targetSlot = target.slot
            if (targetSlot == null) {
                reasons += "${TARGET_SLOT_UNKNOWN_PREFIX}$claimSlot'; target slot is unknown."
            } else if (targetSlot != claimSlot) {
                reasons += "Image is for slot '$claimSlot'; target is on slot '$targetSlot'."
            }
        }

        val claimFp = TargetIdentity.clean(claim.fingerprint)
        if (claimFp != null) {
            val targetFp = target.fingerprint
            if (targetFp == null) {
                reasons += TARGET_FP_UNKNOWN
            } else if (targetFp != claimFp) {
                reasons += FP_MISMATCH
            }
        }

        return if (reasons.isEmpty()) DeviceMatch.Ok else DeviceMatch.Mismatch(reasons)
    }
}

object FlashManifest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Names looked up in the library next to [imageName], first match wins. */
    fun sidecarNames(imageName: String): List<String> {
        val stem = imageName.substringBeforeLast('.', imageName)
        return listOf(
            "$imageName.manifest.json",
            "$stem.manifest.json",
            "$imageName.json",
        ).distinct()
    }

    fun parse(text: String): ImageClaim = json.decodeFromString(ImageClaim.serializer(), text)
}
