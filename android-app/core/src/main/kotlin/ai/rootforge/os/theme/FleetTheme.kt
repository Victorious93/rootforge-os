package ai.rootforge.os.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Read-only consumer of VictorLaunch's fleet-wide theme
 * (`docs/specs/FLEET_THEME.md`, schemaVersion 1).
 *
 * VictorLaunch is the only writer. This module never builds an insert /
 * update / delete. Auth is package name + signing-certificate pin, not a
 * signature-level permission (the fleet does not share a signing key).
 */

object FleetThemeContract {
    const val SCHEMA_VERSION = 1
    const val AUTHORITY = "com.victorious.launch.theme"
    const val PATH_CURRENT = "current"
    const val URI = "content://$AUTHORITY/$PATH_CURRENT"
    const val MIME = "vnd.android.cursor.item/vnd.$AUTHORITY.current"
    const val ACTION_CHANGED = "com.victorious.launch.action.FLEET_THEME_CHANGED"
    const val EXTRA_SCHEMA_VERSION = "schemaVersion"
    const val EXTRA_MODE = "mode"
    const val EXTRA_ICON_STYLE = "iconStyle"
    const val EXTRA_UPDATED_AT = "updatedAtMs"

    const val COL_SCHEMA_VERSION = "schemaVersion"
    const val COL_MODE = "mode"
    const val COL_ICON_STYLE = "iconStyle"
    const val COL_ICON_PACK = "iconPackPackage"
    const val COL_JSON = "json"
    const val COL_UPDATED_AT = "updatedAtMs"
    const val COL_BACKGROUND = "background"
    const val COL_SURFACE = "surface"
    const val COL_PRIMARY = "primary"
    const val COL_SECONDARY = "secondary"
    const val COL_ON_BACKGROUND = "onBackground"
    const val COL_MUTED = "muted"
    const val COL_DANGER = "danger"
    const val COL_STALE = "stale"
    const val COL_TILE = "tile"

    val PALETTE_COLUMNS: List<String> = listOf(
        COL_BACKGROUND, COL_SURFACE, COL_PRIMARY, COL_SECONDARY,
        COL_ON_BACKGROUND, COL_MUTED, COL_DANGER, COL_STALE, COL_TILE,
    )
}

/**
 * VictorLaunch identity. The debug SHA-256 is the public value from
 * Victorious93/victorlaunch `keystore/README.md` and `FleetPins.VICTORLAUNCH_DEBUG`
 * (checked 2026-10-27). The old committed debug cert is revoked and must not match.
 */
object FleetThemePins {
    const val PACKAGE = "com.victorious.launch"
    const val CERT_SHA256 =
        "B9:9A:AF:E2:C1:41:8B:5D:64:AA:0D:4B:AC:61:FC:F7:A7:BA:81:44:35:E0:EF:89:E8:AA:55:BE:56:4E:4A:69"
    const val REVOKED_DEBUG_CERT_SHA256 =
        "1B:06:A8:9E:7F:F5:BA:21:C9:A4:F8:F1:F0:4F:95:E5:62:78:A1:22:18:AD:37:33:28:CE:E7:83:1D:4F:BA:CA"

    fun normalize(cert: String): String = cert.replace(":", "").replace(" ", "").lowercase()

    fun matches(packageName: String?, certs: Collection<String>): Boolean {
        if (packageName != PACKAGE) return false
        val pinned = normalize(CERT_SHA256)
        val revoked = normalize(REVOKED_DEBUG_CERT_SHA256)
        return certs.any { cert ->
            val n = normalize(cert)
            n == pinned && n != revoked
        }
    }
}

enum class FleetThemeMode {
    LIGHT, DARK, NEON_FLEET, MATERIAL_YOU,
    ;

    companion object {
        fun parse(raw: String?): FleetThemeMode {
            val key = raw?.trim()?.uppercase()?.replace('-', '_') ?: return NEON_FLEET
            return entries.firstOrNull { it.name == key } ?: NEON_FLEET
        }
    }
}

enum class FleetThemeIconStyle {
    SYSTEM, MONOCHROME, TINTED, ICON_PACK,
    ;

    companion object {
        fun parse(raw: String?): FleetThemeIconStyle {
            val key = raw?.trim()?.uppercase()?.replace('-', '_') ?: return SYSTEM
            return entries.firstOrNull { it.name == key } ?: SYSTEM
        }
    }
}

enum class FleetThemeStatus {
    APPLIED,
    NOT_AVAILABLE,
    NOT_PINNED,
    SCHEMA_UNSUPPORTED,
}

data class FleetPalette(
    val background: String,
    val surface: String,
    val primary: String,
    val secondary: String,
    val onBackground: String,
    val muted: String,
    val danger: String,
    val stale: String,
    val tile: String,
)

data class FleetThemeSnapshot(
    val schemaVersion: Int = FleetThemeContract.SCHEMA_VERSION,
    val mode: FleetThemeMode,
    val iconStyle: FleetThemeIconStyle,
    val iconPackPackage: String,
    val palette: FleetPalette,
    val updatedAtMs: Long,
)

data class FleetThemeState(
    val status: FleetThemeStatus,
    val snapshot: FleetThemeSnapshot? = null,
    val message: String,
) {
    val palette: FleetPalette? get() = snapshot?.palette
}

data class FleetThemePayload(
    val schemaVersion: Int?,
    val mode: String? = null,
    val iconStyle: String? = null,
    val iconPackPackage: String? = null,
    val json: String? = null,
    val updatedAtMs: Long = 0L,
    val columns: Map<String, String> = emptyMap(),
)

object FleetThemeCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseArgb(hex: String): Long? {
        val h = hex.trim().removePrefix("#")
        val value = h.toLongOrNull(16) ?: return null
        return when (h.length) {
            6 -> 0xFF000000L or value
            8 -> value
            else -> null
        }
    }

    fun parse(payload: FleetThemePayload): FleetThemeSnapshot? {
        val fromJson = payload.json?.takeIf { it.isNotBlank() }?.let { parseJson(it) }
        if (fromJson != null) return fromJson
        return parseColumns(payload)
    }

    fun parseJson(text: String): FleetThemeSnapshot? {
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val schema = readSchema(obj["schemaVersion"]) ?: return null
        if (schema != FleetThemeContract.SCHEMA_VERSION) return null
        val paletteObj = obj["palette"] as? JsonObject ?: return null
        val palette = paletteFrom({ key -> paletteObj[key]?.jsonPrimitive?.contentOrNull }) ?: return null
        return FleetThemeSnapshot(
            schemaVersion = schema,
            mode = FleetThemeMode.parse(obj["mode"]?.jsonPrimitive?.contentOrNull),
            iconStyle = FleetThemeIconStyle.parse(obj["iconStyle"]?.jsonPrimitive?.contentOrNull),
            iconPackPackage = obj["iconPackPackage"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            palette = palette,
            updatedAtMs = obj["updatedAtMs"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    fun parseColumns(payload: FleetThemePayload): FleetThemeSnapshot? {
        val schema = payload.schemaVersion ?: return null
        if (schema != FleetThemeContract.SCHEMA_VERSION) return null
        val palette = paletteFrom(payload.columns::get) ?: return null
        return FleetThemeSnapshot(
            schemaVersion = schema,
            mode = FleetThemeMode.parse(payload.mode),
            iconStyle = FleetThemeIconStyle.parse(payload.iconStyle),
            iconPackPackage = payload.iconPackPackage.orEmpty(),
            palette = palette,
            updatedAtMs = payload.updatedAtMs,
        )
    }

    fun jsonSchemaVersion(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return readSchema(obj["schemaVersion"])
    }

    private fun paletteFrom(get: (String) -> String?): FleetPalette? {
        val background = hexOrNull(get(FleetThemeContract.COL_BACKGROUND)).orEmpty()
        val surface = hexOrNull(get(FleetThemeContract.COL_SURFACE)).orEmpty()
        val primary = hexOrNull(get(FleetThemeContract.COL_PRIMARY)).orEmpty()
        val secondary = hexOrNull(get(FleetThemeContract.COL_SECONDARY)).orEmpty()
        val onBackground = hexOrNull(get(FleetThemeContract.COL_ON_BACKGROUND)).orEmpty()
        val muted = hexOrNull(get(FleetThemeContract.COL_MUTED)).orEmpty()
        val danger = hexOrNull(get(FleetThemeContract.COL_DANGER)).orEmpty()
        val stale = hexOrNull(get(FleetThemeContract.COL_STALE)).orEmpty()
        val tile = hexOrNull(get(FleetThemeContract.COL_TILE)).orEmpty()
        val palette = FleetPalette(
            background = background,
            surface = surface,
            primary = primary,
            secondary = secondary,
            onBackground = onBackground,
            muted = muted,
            stale = stale,
            danger = danger,
            tile = tile,
        )
        val anyValid = listOf(
            background, surface, primary, secondary, onBackground, muted, danger, stale, tile,
        ).any { it.isNotEmpty() }
        return palette.takeIf { anyValid }
    }

    private fun hexOrNull(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (parseArgb(value) != null) value else null
    }

    private fun readSchema(el: kotlinx.serialization.json.JsonElement?): Int? {
        val prim = el as? JsonPrimitive ?: return null
        return prim.intOrNull ?: prim.contentOrNull?.toIntOrNull()
    }
}

/**
 * JVM-safe client. The Android layer supplies the provider identity and the
 * query lambda (which may throw [SecurityException]). Nothing here writes.
 */
object FleetThemeClient {
    const val MESSAGE_NOT_AVAILABLE = "Fleet theme not available"
    const val MESSAGE_NOT_PINNED = "Fleet theme not pinned"
    const val MESSAGE_APPLIED = "Fleet theme applied"

    fun defaultState(): FleetThemeState = FleetThemeState(
        status = FleetThemeStatus.NOT_AVAILABLE,
        message = MESSAGE_NOT_AVAILABLE,
    )

    fun shouldHonorBroadcast(senderPackage: String?, senderCerts: Collection<String>): Boolean =
        FleetThemePins.matches(senderPackage, senderCerts)

    /**
     * API 34+ sender of the explicit change broadcast. Older APIs cannot
     * prove the sender, so this returns null and the ContentObserver is
     * the live path. When both package and uid are present they must agree.
     */
    fun resolveBroadcastPackage(
        sentFromPackage: String?,
        sentFromUid: Int,
        packagesForUid: Array<String>?,
        sdkInt: Int,
    ): String? {
        if (sdkInt < 34) return null
        val fromPackage = sentFromPackage?.takeIf { it.isNotBlank() }
        if (fromPackage != null) {
            if (sentFromUid >= 0 && (packagesForUid == null || fromPackage !in packagesForUid)) {
                return null
            }
            return fromPackage
        }
        if (sentFromUid < 0) return null
        return packagesForUid?.singleOrNull()
    }

    fun consume(
        providerPackage: String?,
        providerCerts: Collection<String>,
        query: () -> FleetThemePayload,
    ): FleetThemeState {
        if (providerPackage.isNullOrBlank()) return defaultState()
        if (!FleetThemePins.matches(providerPackage, providerCerts)) {
            return FleetThemeState(FleetThemeStatus.NOT_PINNED, message = MESSAGE_NOT_PINNED)
        }
        val payload = try {
            query()
        } catch (_: SecurityException) {
            return FleetThemeState(FleetThemeStatus.NOT_PINNED, message = MESSAGE_NOT_PINNED)
        } catch (_: RuntimeException) {
            return defaultState()
        }
        val jsonSchema = FleetThemeCodec.jsonSchemaVersion(payload.json)
        val schema = jsonSchema ?: payload.schemaVersion
        if (schema != FleetThemeContract.SCHEMA_VERSION) {
            return FleetThemeState(FleetThemeStatus.SCHEMA_UNSUPPORTED, message = MESSAGE_NOT_AVAILABLE)
        }
        val snapshot = FleetThemeCodec.parse(payload) ?: return defaultState()
        if (snapshot.schemaVersion != FleetThemeContract.SCHEMA_VERSION) {
            return FleetThemeState(FleetThemeStatus.SCHEMA_UNSUPPORTED, message = MESSAGE_NOT_AVAILABLE)
        }
        return FleetThemeState(
            status = FleetThemeStatus.APPLIED,
            snapshot = snapshot,
            message = MESSAGE_APPLIED,
        )
    }
}
