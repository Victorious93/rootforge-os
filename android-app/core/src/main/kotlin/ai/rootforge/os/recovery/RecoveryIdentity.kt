package ai.rootforge.os.recovery

import ai.rootforge.os.fastboot.TargetIdentity

/**
 * What a phone reports about itself for a recovery install. Built from
 * `getprop` (ADB, local `su`, or a dumped prop file). Blank / "unknown"
 * become null: unknown is not a wildcard.
 */
data class RecoveryIdentity(
    /** `ro.product.device` */
    val device: String? = null,
    /** `ro.build.product` */
    val product: String? = null,
    /** `ro.product.name` */
    val name: String? = null,
    val model: String? = null,
    /** `ro.boot.slot_suffix` normalised to `a` / `b`, or null. */
    val slot: String? = null,
    /** `ro.build.ab_update` parsed as a tri-state. */
    val abUpdate: Boolean? = null,
) {
    /** Codenames to try against TWRP / PitchBlack, device first. */
    fun lookupNames(): List<String> =
        listOfNotNull(device, product, name).map { it.lowercase() }.distinct()

    companion object {
        private val PROP_LINE = Regex("""^\[([^]]+)]:\s*\[(.*)]$""")

        fun fromProps(props: Map<String, String>): RecoveryIdentity = RecoveryIdentity(
            device = TargetIdentity.clean(props["ro.product.device"]),
            product = TargetIdentity.clean(props["ro.build.product"] ?: props["ro.product.product"]),
            name = TargetIdentity.clean(props["ro.product.name"]),
            model = TargetIdentity.clean(props["ro.product.model"]),
            slot = TargetIdentity.normalizeSlot(props["ro.boot.slot_suffix"]),
            abUpdate = parseTri(props["ro.build.ab_update"]),
        )

        /** Parses `getprop` dump lines (`[key]: [value]`). Loose `key: value` lines are ignored. */
        fun parseGetprop(dump: String): Map<String, String> =
            dump.lineSequence().mapNotNull { line ->
                PROP_LINE.matchEntire(line.trim())?.let { it.groupValues[1] to it.groupValues[2] }
            }.toMap()

        private fun parseTri(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }
    }
}

enum class RecoverySource { THIS_PHONE, ADB_TARGET }

data class RecoveryProbe(
    val source: RecoverySource,
    val identity: RecoveryIdentity,
    val rooted: Boolean,
    /** `id` output or the error that told us su is missing. */
    val rootDetail: String,
    val layout: RecoveryLayout,
)
