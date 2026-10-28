package ai.rootforge.os.ui.theme

import ai.rootforge.os.theme.FleetPalette
import ai.rootforge.os.theme.FleetThemeMode
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeFleetTest {
    @Test
    fun missingPaletteKeyKeepsTheConsumerDefault() {
        val palette = FleetPalette(
            background = "",
            surface = "#FF161821",
            primary = "#FF00E5FF",
            secondary = "",
            onBackground = "",
            muted = "",
            danger = "",
            stale = "",
            tile = "",
        )
        val dark = fleetColorScheme(palette, FleetThemeMode.NEON_FLEET)
        assertEquals(ForgeColors.Surface0, dark.background)
        assertEquals(Color(0xFF161821), dark.surface)
        assertEquals(Color(0xFF00E5FF), dark.primary)
        assertEquals(ForgeColors.Steel, dark.secondary)

        val light = fleetColorScheme(palette, FleetThemeMode.LIGHT)
        val lightDefault = lightColorScheme(
            primary = Color(0xFF9A4F00),
            secondary = Color(0xFF006879),
        )
        assertEquals(lightDefault.background, light.background)
        assertEquals(Color(0xFF00E5FF), light.primary)
    }
}
