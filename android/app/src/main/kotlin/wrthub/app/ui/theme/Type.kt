package wrthub.app.ui.theme

import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontStyle
import android.graphics.fonts.FontFamily as PlatformFontFamily
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import wrthub.app.R

/**
 * Barlow for Latin and digits, falling back to LINE Seed JP for Japanese. Compose cannot fall back from one bundled
 * font to another and ignores the weight of a platform typeface, so there is one chained typeface per weight.
 */
@Immutable
class AppFonts(val regular: FontFamily, val medium: FontFamily, val semiBold: FontFamily) {
    fun of(weight: FontWeight?): FontFamily = when {
        weight == null || weight.weight < 500 -> regular
        weight.weight < 600 -> medium
        else -> semiBold
    }
}

fun loadAppFonts(resources: Resources): AppFonts {
    val japanese = listOf(
        Font.Builder(resources, R.font.line_seed_jp_regular).setWeight(400).build(),
        Font.Builder(resources, R.font.line_seed_jp_bold).setWeight(700).build(),
    ).let { fonts -> PlatformFontFamily.Builder(fonts[0]).addFont(fonts[1]).build() }
    fun chain(latinRes: Int, weight: Int): FontFamily {
        val latin = PlatformFontFamily.Builder(Font.Builder(resources, latinRes).setWeight(weight).build()).build()
        val typeface = Typeface.CustomFallbackBuilder(latin)
            .addCustomFallback(japanese)
            .setSystemFallback("sans-serif")
            .setStyle(FontStyle(weight, FontStyle.FONT_SLANT_UPRIGHT))
            .build()
        return FontFamily(androidx.compose.ui.text.font.Typeface(typeface))
    }
    return AppFonts(
        regular = chain(R.font.barlow_regular, 400),
        medium = chain(R.font.barlow_medium, 500),
        semiBold = chain(R.font.barlow_semibold, 600),
    )
}

val LocalAppFonts = staticCompositionLocalOf<AppFonts?> { null }

/** Use instead of copy(fontWeight = …): the weight lives in the font family, see [AppFonts]. */
@Composable
fun TextStyle.weight(weight: FontWeight): TextStyle =
    copy(fontWeight = weight, fontFamily = LocalAppFonts.current?.of(weight) ?: fontFamily)

/** Digits are tabular so values that refresh every second do not shift sideways. */
fun appTypography(fonts: AppFonts): Typography {
    fun style(size: Int, weight: FontWeight, lineHeight: Int, letterSpacing: TextUnit = 0.sp) = TextStyle(
        fontFamily = fonts.of(weight),
        fontWeight = weight,
        fontSynthesis = FontSynthesis.None,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = letterSpacing,
        fontFeatureSettings = "tnum",
    )
    return Typography(
        displaySmall = style(40, FontWeight.SemiBold, 46),
        headlineMedium = style(30, FontWeight.SemiBold, 34),
        headlineSmall = style(24, FontWeight.SemiBold, 30),
        titleLarge = style(19, FontWeight.SemiBold, 26),
        titleMedium = style(15, FontWeight.Medium, 21),
        titleSmall = style(14, FontWeight.SemiBold, 20),
        bodyLarge = style(15, FontWeight.Normal, 22),
        bodyMedium = style(14, FontWeight.Normal, 20),
        bodySmall = style(12, FontWeight.Normal, 17),
        labelLarge = style(15, FontWeight.SemiBold, 20),
        labelMedium = style(12, FontWeight.Medium, 16),
        labelSmall = style(11, FontWeight.Medium, 14, 0.2.sp),
    )
}
