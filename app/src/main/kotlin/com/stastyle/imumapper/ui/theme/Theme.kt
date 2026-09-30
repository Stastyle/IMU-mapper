package com.stastyle.imumapper.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * All 36 roles of a scheme, set explicitly: no dynamic colour, which would replace the brand palette on
 * Android 12 and later, and no baseline purple in a dialog, sheet, menu or snackbar. The values and their
 * contrast rules are in [ThemePalettes].
 */
private fun colorSchemeOf(p: ThemePalette) = ColorScheme(
    primary = Color(p.primary),
    onPrimary = Color(p.onPrimary),
    primaryContainer = Color(p.primaryContainer),
    onPrimaryContainer = Color(p.onPrimaryContainer),
    inversePrimary = Color(p.inversePrimary),
    secondary = Color(p.secondary),
    onSecondary = Color(p.onSecondary),
    secondaryContainer = Color(p.secondaryContainer),
    onSecondaryContainer = Color(p.onSecondaryContainer),
    tertiary = Color(p.tertiary),
    onTertiary = Color(p.onTertiary),
    tertiaryContainer = Color(p.tertiaryContainer),
    onTertiaryContainer = Color(p.onTertiaryContainer),
    background = Color(p.background),
    onBackground = Color(p.onBackground),
    surface = Color(p.surface),
    onSurface = Color(p.onSurface),
    surfaceVariant = Color(p.surfaceVariant),
    onSurfaceVariant = Color(p.onSurfaceVariant),
    surfaceTint = Color(p.surfaceTint),
    inverseSurface = Color(p.inverseSurface),
    inverseOnSurface = Color(p.inverseOnSurface),
    error = Color(p.error),
    onError = Color(p.onError),
    errorContainer = Color(p.errorContainer),
    onErrorContainer = Color(p.onErrorContainer),
    outline = Color(p.outline),
    outlineVariant = Color(p.outlineVariant),
    scrim = Color(p.scrim),
    surfaceBright = Color(p.surfaceBright),
    surfaceContainer = Color(p.surfaceContainer),
    surfaceContainerHigh = Color(p.surfaceContainerHigh),
    surfaceContainerHighest = Color(p.surfaceContainerHighest),
    surfaceContainerLow = Color(p.surfaceContainerLow),
    surfaceContainerLowest = Color(p.surfaceContainerLowest),
    surfaceDim = Color(p.surfaceDim),
)

private val DarkColorScheme = colorSchemeOf(ThemePalettes.Dark)

private val LightColorScheme = colorSchemeOf(ThemePalettes.Light)

private val BaseTypography = Typography()

/**
 * Text behaves as it does in Android's own views: each paragraph takes its direction from its own text,
 * and lines align to the start of the layout. By default Compose uses the layout direction for both, so on
 * a Hebrew phone an English line such as "3.8 % of distance" or "Search trips…" was laid out right to left
 * and its numbers and punctuation moved; a direction from the content alone would instead push English
 * lines to the left edge of a mirrored screen. Number-only texts have no letters to go by, so their styles
 * set `TextDirection.Ltr` themselves.
 */
private fun appTypography(layoutDirection: LayoutDirection): Typography {
    val start = if (layoutDirection == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left
    fun TextStyle.directed(): TextStyle = copy(textDirection = TextDirection.Content, textAlign = start)
    return Typography(
        displayLarge = BaseTypography.displayLarge.directed(),
        displayMedium = BaseTypography.displayMedium.directed(),
        displaySmall = BaseTypography.displaySmall.directed(),
        headlineLarge = BaseTypography.headlineLarge.directed(),
        headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.Bold).directed(),
        headlineSmall = BaseTypography.headlineSmall.directed(),
        titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold).directed(),
        titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold).directed(),
        titleSmall = BaseTypography.titleSmall.directed(),
        bodyLarge = BaseTypography.bodyLarge.directed(),
        bodyMedium = BaseTypography.bodyMedium.directed(),
        bodySmall = BaseTypography.bodySmall.directed(),
        labelLarge = BaseTypography.labelLarge.directed(),
        labelMedium = BaseTypography.labelMedium.directed(),
        labelSmall = BaseTypography.labelSmall.directed(),
    )
}

/**
 * The layout's start or end as an absolute alignment, for a Text that must line up with the screen
 * rather than with its own words (table columns, a hint under a right-hand button). `TextAlign.Start`
 * and `End` follow the text's own direction, which differs from the layout's for English on a Hebrew
 * phone.
 */
@Composable
@ReadOnlyComposable
fun layoutTextAlign(end: Boolean = false): TextAlign {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return if (rtl != end) TextAlign.Right else TextAlign.Left
}

/** The letter-spaced line under a screen title ("INDOOR PATH TRACKING"); M3 has no slot for it. */
val Typography.tagline: TextStyle
    get() = labelLarge.copy(letterSpacing = 3.sp)

// Chips and pills use CircleShape in their own wrappers, whatever these say.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * The app theme, dark navy when [dark] and light otherwise; `MainActivity` picks [dark] from the Appearance
 * setting and matches the system bar icons to it. The camera preview's overlay nests a dark theme of its
 * own, because the camera image does not follow the setting.
 */
@Composable
fun ImuMapperTheme(dark: Boolean, content: @Composable () -> Unit) {
    val layoutDirection = LocalLayoutDirection.current
    val typography = remember(layoutDirection) { appTypography(layoutDirection) }
    val scheme = if (dark) DarkColorScheme else LightColorScheme
    CompositionLocalProvider(LocalImuColors provides if (dark) DarkImuColors else LightImuColors) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = AppShapes) {
            // MaterialTheme leaves the content colour at black, so text drawn outside a Surface or Scaffold
            // would vanish on the dark background; both themes name their own text colour instead.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
