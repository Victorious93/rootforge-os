package ai.rootforge.os.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Names that must never appear in a settings backup. Refusal is
 * `invalid_parameters`, not a silent strip (UNIFIED_BACKUP.md §3).
 */
object ConfigSecrets {
    private val EXACT = setOf(
        "token", "secret", "password", "passwd", "cookie", "twilio",
        "keystore", "superkey", "allowlist", "allow-list", "allow_list",
    )

    private val CONTAINS = listOf(
        "apikey", "apitoken", "bearertoken", "authtoken", "accesstoken",
        "privatekey", "privkey", "signingkey", "certpem", "certificatepem",
        "numberpool", "magisktoken", "kernelsutoken", "ksutoken", "apatchtoken",
        "rootpassword", "pairingkey", "pairingsecret", "victorsuitepin",
        "api_token", "api-token",
    )

    fun isForbiddenName(name: String): Boolean {
        val lower = name.lowercase()
        val compact = compact(lower)
        if (lower in EXACT || compact in EXACT) return true
        if (CONTAINS.any { needle -> compact.contains(compact(needle)) && compact(needle).length >= 6 }) {
            return true
        }
        if (lower.endsWith("token") && compact != "autconnect" && compact != "terminalapp") {
            // Bare "token" and *token names used for credentials. Preference ids we export
            // (autoConnect, terminalApp) do not end with "token".
            return compact == "token" || compact.endsWith("token")
        }
        if (lower.contains("password") || lower.contains("passwd")) return true
        if ((lower.contains("pairing") || lower.contains("allowlist") || lower.contains("allow-list")) &&
            (lower.contains("key") || lower.contains("secret") || lower.contains("cert"))
        ) {
            return true
        }
        return false
    }

    fun firstForbiddenName(root: JsonElement): String? {
        val found = ArrayList<String>()
        walk(root, found)
        return found.firstOrNull()
    }

    private fun walk(el: JsonElement, found: MutableList<String>) {
        when (el) {
            is JsonObject -> {
                for ((key, value) in el) {
                    if (isForbiddenName(key)) found += key
                    if (key == "id" && value is JsonPrimitive && value.isString && isForbiddenName(value.content)) {
                        found += value.content
                    }
                    walk(value, found)
                }
            }
            is JsonArray -> el.forEach { walk(it, found) }
            else -> Unit
        }
    }

    private fun compact(name: String): String =
        name.lowercase().filter { it != '_' && it != '-' }
}
