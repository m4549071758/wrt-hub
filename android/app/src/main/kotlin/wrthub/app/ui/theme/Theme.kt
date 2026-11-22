package wrthub.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Colors that carry a state (signal, health); everything else is neutral and comes from the color scheme. */
@Immutable
data class SemanticColors(
    val signalGood: Color,
    val signalFair: Color,
    val signalPoor: Color,
    val inactive: Color,
)

/** Each is at least 4.5:1 against the light card and background, as these colors also paint small text. */
private val LightSemantic = SemanticColors(
    signalGood = Color(0xFF1E7B34),
    signalFair = Color(0xFF8A5A00),
    signalPoor = Color(0xFFC62828),
    inactive = Color(0xFFA3A7AD),
)

private val DarkSemantic = SemanticColors(
    signalGood = Color(0xFF5BD27A),
    signalFair = Color(0xFFF2C14E),
    signalPoor = Color(0xFFFF6B5E),
    inactive = Color(0xFF5E5E5E),
)

val LocalSemanticColors = staticCompositionLocalOf { LightSemantic }

fun SemanticColors.forSignal(dbm: Int?): Color = when {
    dbm == null -> inactive
    dbm >= -60 -> signalGood
    dbm >= -72 -> signalFair
    else -> signalPoor
}

/**
 * background: the screen; surface: cards; surfaceVariant: fields and the selected-segment track; outline: card
 * borders and dividers; onSurfaceVariant: secondary text; primary: the filled button.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B0B0C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6F7F8),
    onPrimaryContainer = Color(0xFF0B0B0C),
    secondary = Color(0xFF5F6368),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9EBEE),
    onSecondaryContainer = Color(0xFF0B0B0C),
    background = Color(0xFFF2F3F5),
    onBackground = Color(0xFF0B0B0C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B0B0C),
    surfaceVariant = Color(0xFFF6F7F8),
    onSurfaceVariant = Color(0xFF5F6368),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3F5),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFF6F7F8),
    outline = Color(0xFFE3E5E8),
    outlineVariant = Color(0xFFE3E5E8),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCEAEA),
    onErrorContainer = Color(0xFF8E1B1B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD2E9F8),
    onPrimary = Color(0xFF050505),
    primaryContainer = Color(0xFF1D1D1D),
    onPrimaryContainer = Color(0xFFF4F4F4),
    secondary = Color(0xFF9B9B9B),
    onSecondary = Color(0xFF050505),
    secondaryContainer = Color(0xFF262626),
    onSecondaryContainer = Color(0xFFF4F4F4),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF4F4F4),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFF4F4F4),
    surfaceVariant = Color(0xFF1D1D1D),
    onSurfaceVariant = Color(0xFF9B9B9B),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF000000),
    surfaceContainer = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF1D1D1D),
    outline = Color(0xFF2A2A2A),
    outlineVariant = Color(0xFF2A2A2A),
    error = Color(0xFFFF6B5E),
    onError = Color(0xFF050505),
    errorContainer = Color(0xFF3A1210),
    onErrorContainer = Color(0xFFFFB4AB),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun WrtHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val resources = LocalContext.current.resources
    val fonts = remember(resources) { loadAppFonts(resources) }
    val typography = remember(fonts) { appTypography(fonts) }
    val colors = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(
        LocalSemanticColors provides if (darkTheme) DarkSemantic else LightSemantic,
        LocalAppFonts provides fonts,
    ) {
        MaterialTheme(colorScheme = colors, typography = typography, shapes = AppShapes) {
            CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
        }
    }
}
