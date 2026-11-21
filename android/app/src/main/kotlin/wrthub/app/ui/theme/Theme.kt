package wrthub.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Colors that carry meaning and therefore stay fixed regardless of the dynamic (wallpaper) scheme. */
@Immutable
data class SemanticColors(
    val download: Color,
    val upload: Color,
    val signalGood: Color,
    val signalFair: Color,
    val signalPoor: Color,
    val inactive: Color,
)

private val LightSemantic = SemanticColors(
    download = Color(0xFF0A9FD8),
    upload = Color(0xFF9B51E0),
    signalGood = Color(0xFF2FA84F),
    signalFair = Color(0xFFD99A00),
    signalPoor = Color(0xFFE5484D),
    inactive = Color(0xFF8E8E93),
)

private val DarkSemantic = SemanticColors(
    download = Color(0xFF64D2FF),
    upload = Color(0xFFBF5AF2),
    signalGood = Color(0xFF30D158),
    signalFair = Color(0xFFFFD60A),
    signalPoor = Color(0xFFFF6961),
    inactive = Color(0xFF98989D),
)

val LocalSemanticColors = staticCompositionLocalOf { LightSemantic }

fun SemanticColors.forSignal(dbm: Int?): Color = when {
    dbm == null -> inactive
    dbm >= -60 -> signalGood
    dbm >= -72 -> signalFair
    else -> signalPoor
}

@Composable
fun WrtHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    CompositionLocalProvider(LocalSemanticColors provides if (darkTheme) DarkSemantic else LightSemantic) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
