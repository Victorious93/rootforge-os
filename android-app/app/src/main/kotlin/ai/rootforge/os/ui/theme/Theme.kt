package ai.rootforge.os.ui.theme

import ai.rootforge.os.theme.FleetPalette
import ai.rootforge.os.theme.FleetThemeCodec
import ai.rootforge.os.theme.FleetThemeMode
import ai.rootforge.os.theme.FleetThemeSnapshot
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

object ForgeColors {
    val Ember = Color(0xFFFFA24C)
    val EmberDark = Color(0xFF7A3E00)
    val Steel = Color(0xFF7FD4E8)
    val Danger = Color(0xFFFF6B6B)
    val Ok = Color(0xFF6FD18B)
    val Warn = Color(0xFFFFD166)
    val Surface0 = Color(0xFF0E1116)
    val Surface1 = Color(0xFF151A21)
    val Surface2 = Color(0xFF1C232C)
    val Surface3 = Color(0xFF252E3A)
    val Terminal = Color(0xFF0A0D11)
}

private val DarkScheme = darkColorScheme(
    primary = ForgeColors.Ember,
    onPrimary = Color(0xFF2A1400),
    primaryContainer = ForgeColors.EmberDark,
    onPrimaryContainer = Color(0xFFFFDCBE),
    secondary = ForgeColors.Steel,
    onSecondary = Color(0xFF00363F),
    secondaryContainer = Color(0xFF004E5B),
    onSecondaryContainer = Color(0xFFB8EBFA),
    tertiary = ForgeColors.Ok,
    error = ForgeColors.Danger,
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = ForgeColors.Surface0,
    onBackground = Color(0xFFE3E7EC),
    surface = ForgeColors.Surface0,
    onSurface = Color(0xFFE3E7EC),
    surfaceVariant = ForgeColors.Surface2,
    onSurfaceVariant = Color(0xFFB4BDC8),
    surfaceContainer = ForgeColors.Surface1,
    surfaceContainerHigh = ForgeColors.Surface2,
    surfaceContainerHighest = ForgeColors.Surface3,
    outline = Color(0xFF6B7684),
    outlineVariant = Color(0xFF33404E),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF9A4F00),
    secondary = Color(0xFF006879),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

/** Dark is the default and the intended look; light exists only so the app follows an explicit system choice. */
@Composable
fun RootForgeTheme(
    dark: Boolean = true,
    fleet: FleetThemeSnapshot? = null,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = colorSchemeFor(dark, fleet),
        typography = Typography(),
        content = content,
    )
}

internal fun colorSchemeFor(dark: Boolean, fleet: FleetThemeSnapshot?): ColorScheme {
    val palette = fleet?.palette
    if (palette == null) return if (dark) DarkScheme else LightScheme
    return fleetColorScheme(palette, fleet.mode)
}

internal fun fleetColorScheme(palette: FleetPalette, mode: FleetThemeMode): ColorScheme {
    val light = mode == FleetThemeMode.LIGHT
    val base = if (light) LightScheme else DarkScheme
    val background = palette.color(palette.background) ?: base.background
    val surface = palette.color(palette.surface) ?: base.surface
    val primary = palette.color(palette.primary) ?: base.primary
    val secondary = palette.color(palette.secondary) ?: base.secondary
    val onBackground = palette.color(palette.onBackground) ?: base.onBackground
    val muted = palette.color(palette.muted) ?: base.onSurfaceVariant
    val danger = palette.color(palette.danger) ?: base.error
    val stale = palette.color(palette.stale) ?: base.tertiary
    val tile = palette.color(palette.tile) ?: base.surfaceVariant
    return if (light) {
        lightColorScheme(
            primary = primary,
            onPrimary = background,
            secondary = secondary,
            onSecondary = background,
            tertiary = stale,
            error = danger,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onBackground,
            surfaceVariant = tile,
            onSurfaceVariant = muted,
            surfaceContainer = tile,
            surfaceContainerHigh = surface,
            surfaceContainerHighest = tile,
            outline = muted,
        )
    } else {
        darkColorScheme(
            primary = primary,
            onPrimary = background,
            secondary = secondary,
            onSecondary = background,
            tertiary = stale,
            error = danger,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onBackground,
            surfaceVariant = tile,
            onSurfaceVariant = muted,
            surfaceContainer = tile,
            surfaceContainerHigh = surface,
            surfaceContainerHighest = tile,
            outline = muted,
        )
    }
}

private fun FleetPalette.color(hex: String): Color? =
    FleetThemeCodec.parseArgb(hex)?.let { Color(it) }
