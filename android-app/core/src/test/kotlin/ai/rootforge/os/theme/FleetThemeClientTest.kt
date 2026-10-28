package ai.rootforge.os.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetThemeClientTest {
    private val pin = FleetThemePins.CERT_SHA256
    private val neonJson = """
        {
          "schemaVersion": 1,
          "mode": "NEON_FLEET",
          "iconStyle": "SYSTEM",
          "iconPackPackage": "",
          "updatedAtMs": 42,
          "palette": {
            "background": "#FF0B0C12",
            "surface": "#FF161821",
            "primary": "#FF00E5FF",
            "secondary": "#FFE840FF",
            "onBackground": "#FFF5F7FF",
            "muted": "#FF9AA3B5",
            "danger": "#FFFF6B8A",
            "stale": "#FFFFC14D",
            "tile": "#CC1A1D2B"
          },
          "futureColumn": "ignored"
        }
    """.trimIndent()

    @Test
    fun pinsVictorLaunchDebugCertFromThePublishedSpec() {
        assertEquals("com.victorious.launch", FleetThemePins.PACKAGE)
        assertEquals(1, FleetThemeContract.SCHEMA_VERSION)
        assertEquals("content://com.victorious.launch.theme/current", FleetThemeContract.URI)
        assertEquals("com.victorious.launch.action.FLEET_THEME_CHANGED", FleetThemeContract.ACTION_CHANGED)
        assertTrue(FleetThemePins.matches(FleetThemePins.PACKAGE, listOf(pin)))
        assertTrue(FleetThemePins.matches(FleetThemePins.PACKAGE, listOf(pin.replace(":", "").lowercase())))
        assertFalse(FleetThemePins.matches(FleetThemePins.PACKAGE, listOf(FleetThemePins.REVOKED_DEBUG_CERT_SHA256)))
        assertFalse(FleetThemePins.matches("com.evil.app", listOf(pin)))
        assertFalse(FleetThemePins.matches(FleetThemePins.PACKAGE, emptyList()))
        assertFalse(FleetThemePins.matches(null, listOf(pin)))
    }

    @Test
    fun schemaVersionOtherThanOneIsIgnored() {
        val json = """{"schemaVersion":2,"mode":"DARK","palette":{"primary":"#FF00E5FF"}}"""
        val state = FleetThemeClient.consume(FleetThemePins.PACKAGE, listOf(pin)) {
            FleetThemePayload(schemaVersion = 2, json = json)
        }
        assertEquals(FleetThemeStatus.SCHEMA_UNSUPPORTED, state.status)
        assertNull(state.snapshot)
        assertNull(state.palette)
        assertTrue(state.message.contains("not available", ignoreCase = true))
    }

    @Test
    fun jsonSchemaWinsOverAMatchingColumnVersion() {
        val state = FleetThemeClient.consume(FleetThemePins.PACKAGE, listOf(pin)) {
            FleetThemePayload(
                schemaVersion = 1,
                json = """{"schemaVersion":99,"palette":{"primary":"#FF00E5FF"}}""",
            )
        }
        assertEquals(FleetThemeStatus.SCHEMA_UNSUPPORTED, state.status)
        assertNull(state.snapshot)
    }

    @Test
    fun missingSchemaVersionIsRejected() {
        val state = FleetThemeClient.consume(FleetThemePins.PACKAGE, listOf(pin)) {
            FleetThemePayload(schemaVersion = null, json = """{"mode":"DARK"}""")
        }
        assertEquals(FleetThemeStatus.SCHEMA_UNSUPPORTED, state.status)
        assertNull(state.snapshot)
    }

    @Test
    fun paletteParsesFromJsonIncludingRgbAndArgb() {
        val state = FleetThemeClient.consume(FleetThemePins.PACKAGE, listOf(pin)) {
            FleetThemePayload(schemaVersion = 1, json = neonJson)
        }
        assertEquals(FleetThemeStatus.APPLIED, state.status)
        val palette = state.palette!!
        assertEquals("#FF0B0C12", palette.background)
        assertEquals("#FF00E5FF", palette.primary)
        assertEquals("#CC1A1D2B", palette.tile)
        assertEquals(FleetThemeMode.NEON_FLEET, state.snapshot!!.mode)
        assertEquals(0xFF00E5FF, FleetThemeCodec.parseArgb("#00E5FF"))
        assertEquals(0xFF00E5FF, FleetThemeCodec.parseArgb("#FF00E5FF"))
        assertEquals(0xCC1A1D2B, FleetThemeCodec.parseArgb("#CC1A1D2B"))
        assertNull(FleetThemeCodec.parseArgb("red"))
        assertNull(FleetThemeCodec.parseArgb("#GG0000"))
        assertNull(FleetThemeCodec.parseArgb("#123"))
        assertNull(FleetThemeCodec.parseArgb(""))
    }

    @Test
    fun paletteParsesFromColumnsWhenJsonIsAbsent() {
        val columns = mapOf(
            FleetThemeContract.COL_BACKGROUND to "#FF0B0C12",
            FleetThemeContract.COL_SURFACE to "#FF161821",
            FleetThemeContract.COL_PRIMARY to "#00E5FF",
            FleetThemeContract.COL_SECONDARY to "#FFE840FF",
            FleetThemeContract.COL_ON_BACKGROUND to "#FFF5F7FF",
            FleetThemeContract.COL_MUTED to "#FF9AA3B5",
            FleetThemeContract.COL_DANGER to "#FFFF6B8A",
            FleetThemeContract.COL_STALE to "#FFFFC14D",
            FleetThemeContract.COL_TILE to "#CC1A1D2B",
            "unknownColumn" to "ignored",
        )
        val state = FleetThemeClient.consume(FleetThemePins.PACKAGE, listOf(pin)) {
            FleetThemePayload(
                schemaVersion = 1,
                mode = "LIGHT",
                iconStyle = "MONOCHROME",
                iconPackPackage = "com.example.pack",
                updatedAtMs = 7L,
                columns = columns,
            )
        }
        assertEquals(FleetThemeStatus.APPLIED, state.status)
        assertEquals("#00E5FF", state.palette!!.primary)
        assertEquals(FleetThemeMode.LIGHT, state.snapshot!!.mode)
        assertEquals(FleetThemeIconStyle.MONOCHROME, state.snapshot!!.iconStyle)
        assertEquals("com.example.pack", state.snapshot!!.iconPackPackage)
        assertEquals(7L, state.snapshot!!.updatedAtMs)
        assertEquals(0xFF00E5FF, FleetThemeCodec.parseArgb(state.palette!!.primary))
    }

    @Test
    fun invalidPaletteHexIsNotInvented() {
        val state = FleetThemeClient.consume(FleetThemePins.PACKAGE, listOf(pin)) {
            FleetThemePayload(
                schemaVersion = 1,
                json = """{"schemaVersion":1,"palette":{"background":"nope","surface":"#FF161821","primary":"#FF00E5FF","secondary":"#FFE840FF","onBackground":"#FFF5F7FF","muted":"#FF9AA3B5","danger":"#FFFF6B8A","stale":"#FFFFC14D","tile":"#CC1A1D2B"}}""",
            )
        }
        assertEquals(FleetThemeStatus.APPLIED, state.status)
        val palette = state.palette!!
        assertEquals("", palette.background)
        assertEquals("#FF00E5FF", palette.primary)
        assertEquals("#FF161821", palette.surface)
        assertNull(FleetThemeCodec.parseArgb(palette.background))
        assertEquals(0xFF00E5FF, FleetThemeCodec.parseArgb(palette.primary))
    }

    @Test
    fun malformedJsonFallsBackToValidColumns() {
        val columns = mapOf(
            FleetThemeContract.COL_BACKGROUND to "#FF0B0C12",
            FleetThemeContract.COL_SURFACE to "#FF161821",
            FleetThemeContract.COL_PRIMARY to "#FF00E5FF",
            FleetThemeContract.COL_SECONDARY to "#FFE840FF",
            FleetThemeContract.COL_ON_BACKGROUND to "#FFF5F7FF",
            FleetThemeContract.COL_MUTED to "#FF9AA3B5",
            FleetThemeContract.COL_DANGER to "#FFFF6B8A",
            FleetThemeContract.COL_STALE to "#FFFFC14D",
            FleetThemeContract.COL_TILE to "#CC1A1D2B",
        )
        val state = FleetThemeClient.consume(FleetThemePins.PACKAGE, listOf(pin)) {
            FleetThemePayload(
                schemaVersion = 1,
                json = """{"schemaVersion":1,"palette":{""",
                columns = columns,
            )
        }
        assertEquals(FleetThemeStatus.APPLIED, state.status)
        assertEquals("#FF00E5FF", state.palette!!.primary)
    }

    @Test
    fun incompleteJsonPaletteStillAppliesValidKeys() {
        val state = FleetThemeClient.consume(FleetThemePins.PACKAGE, listOf(pin)) {
            FleetThemePayload(
                schemaVersion = 1,
                json = """{"schemaVersion":1,"mode":"DARK","palette":{"primary":"#FF00E5FF"}}""",
            )
        }
        assertEquals(FleetThemeStatus.APPLIED, state.status)
        assertEquals("#FF00E5FF", state.palette!!.primary)
        assertEquals("", state.palette!!.background)
        assertEquals(FleetThemeMode.DARK, state.snapshot!!.mode)
    }

    @Test
    fun securityExceptionFallsBackQuietlyToNotPinned() {
        val state = FleetThemeClient.consume(FleetThemePins.PACKAGE, listOf(pin)) {
            throw SecurityException("untrusted fleet-theme caller")
        }
        assertEquals(FleetThemeStatus.NOT_PINNED, state.status)
        assertNull(state.snapshot)
        assertNull(state.palette)
        assertEquals("Fleet theme not pinned", state.message)
        assertFalse(state.message.contains("SecurityException"))
        assertFalse(state.message.contains("denied", ignoreCase = true))
        assertFalse(state.message.contains("error", ignoreCase = true))
    }

    @Test
    fun missingProviderFallsBackQuietlyToNotAvailable() {
        var queried = false
        val state = FleetThemeClient.consume(null, emptyList()) {
            queried = true
            error("must not query when the provider is missing")
        }
        assertFalse(queried)
        assertEquals(FleetThemeStatus.NOT_AVAILABLE, state.status)
        assertNull(state.snapshot)
        assertEquals("Fleet theme not available", state.message)
    }

    @Test
    fun unpinnedProviderIsNotQueried() {
        var queried = false
        val state = FleetThemeClient.consume("com.evil.app", listOf(pin)) {
            queried = true
            FleetThemePayload(schemaVersion = 1, json = neonJson)
        }
        assertFalse("an untrusted authority must not be read", queried)
        assertEquals(FleetThemeStatus.NOT_PINNED, state.status)
        assertNull(state.snapshot)
        assertEquals("Fleet theme not pinned", state.message)
    }

    @Test
    fun revokedVictorLaunchCertIsNotTrusted() {
        var queried = false
        val state = FleetThemeClient.consume(
            FleetThemePins.PACKAGE,
            listOf(FleetThemePins.REVOKED_DEBUG_CERT_SHA256),
        ) {
            queried = true
            FleetThemePayload(schemaVersion = 1, json = neonJson)
        }
        assertFalse(queried)
        assertEquals(FleetThemeStatus.NOT_PINNED, state.status)
    }

    @Test
    fun otherQueryFailuresAreUnavailableNotACrash() {
        val state = FleetThemeClient.consume(FleetThemePins.PACKAGE, listOf(pin)) {
            throw IllegalStateException("provider died")
        }
        assertEquals(FleetThemeStatus.NOT_AVAILABLE, state.status)
        assertNull(state.snapshot)
        assertEquals("Fleet theme not available", state.message)
    }

    @Test
    fun unverifiedBroadcastSenderIsIgnored() {
        assertFalse(FleetThemeClient.shouldHonorBroadcast(null, emptyList()))
        assertFalse(FleetThemeClient.shouldHonorBroadcast("com.evil.app", listOf(pin)))
        assertFalse(FleetThemeClient.shouldHonorBroadcast(FleetThemePins.PACKAGE, listOf("aa".repeat(32))))
        assertFalse(
            FleetThemeClient.shouldHonorBroadcast(
                FleetThemePins.PACKAGE,
                listOf(FleetThemePins.REVOKED_DEBUG_CERT_SHA256),
            ),
        )
        assertTrue(FleetThemeClient.shouldHonorBroadcast(FleetThemePins.PACKAGE, listOf(pin)))
        assertNotNull(FleetThemeClient.defaultState().palette == null)
        assertEquals(FleetThemeStatus.NOT_AVAILABLE, FleetThemeClient.defaultState().status)
        assertNull(FleetThemeClient.resolveBroadcastPackage(FleetThemePins.PACKAGE, 1000, arrayOf(FleetThemePins.PACKAGE), 33))
        assertNull(FleetThemeClient.resolveBroadcastPackage(null, -1, null, 34))
        assertEquals(
            FleetThemePins.PACKAGE,
            FleetThemeClient.resolveBroadcastPackage(FleetThemePins.PACKAGE, 1000, arrayOf(FleetThemePins.PACKAGE), 34),
        )
        assertNull(
            FleetThemeClient.resolveBroadcastPackage(FleetThemePins.PACKAGE, 1000, arrayOf("com.evil.app"), 34),
        )
        assertNull(
            FleetThemeClient.resolveBroadcastPackage(FleetThemePins.PACKAGE, 1000, null, 34),
        )
        assertEquals(
            FleetThemePins.PACKAGE,
            FleetThemeClient.resolveBroadcastPackage(null, 1000, arrayOf(FleetThemePins.PACKAGE), 34),
        )
        assertNull(
            FleetThemeClient.resolveBroadcastPackage(null, 1000, arrayOf(FleetThemePins.PACKAGE, "com.other.app"), 34),
        )
    }
}
