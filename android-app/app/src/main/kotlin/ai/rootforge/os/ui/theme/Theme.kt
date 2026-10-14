package ai.rootforge.os.ui.theme

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
fun RootForgeTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = Typography(),
        content = content,
    )
}
