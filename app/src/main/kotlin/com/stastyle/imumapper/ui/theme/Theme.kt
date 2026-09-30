package com.stastyle.imumapper.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The one colour scheme: dark navy, and no dynamic colour, which would replace the brand palette on
 * Android 12 and later. Every role is set, so no baseline purple reaches a dialog, sheet, menu or
 * snackbar. `primary` and `error` are also text colours and reach 4.5:1 on the background, the card
 * and dialog containers, `secondaryContainer` and the canvas.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF64B5F6),
    onPrimary = Color(0xFF00233A),
    primaryContainer = Color(0xFF0F3A66),
    onPrimaryContainer = Color(0xFFD6E9FF),
    inversePrimary = Color(0xFF1565C0),
    secondary = Color(0xFF4FC3F7),
    onSecondary = Color(0xFF00233A),
    secondaryContainer = Color(0xFF143357),
    onSecondaryContainer = Color(0xFFCFE6FF),
    tertiary = Color(0xFFFFB74D),
    onTertiary = Color(0xFF3A2600),
    tertiaryContainer = Color(0xFF4A3310),
    onTertiaryContainer = Color(0xFFFFE0B2),
    background = Color(0xFF07111F),
    onBackground = Color(0xFFEAF2FF),
    surface = Color(0xFF0B1628),
    onSurface = Color(0xFFEAF2FF),
    surfaceVariant = Color(0xFF16263F),
    onSurfaceVariant = Color(0xFF8FA3BF),
    surfaceTint = Color(0xFF64B5F6),
    inverseSurface = Color(0xFFDDE7F5),
    inverseOnSurface = Color(0xFF0B1628),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF4A1518),
    onErrorContainer = Color(0xFFFFDAD6),
    // outline draws interactive boundaries (switch track, text fields, unselected chips) and needs
    // 3:1; outlineVariant is only for decoration such as dividers.
    outline = Color(0xFF6F86A6),
    outlineVariant = Color(0xFF1E3A5F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF1E3352),
    surfaceContainer = Color(0xFF111F35),
    surfaceContainerHigh = Color(0xFF152741),
    surfaceContainerHighest = Color(0xFF1A2E4C),
    surfaceContainerLow = Color(0xFF0D1A2E),
    surfaceContainerLowest = Color(0xFF060E1A),
    surfaceDim = Color(0xFF07111F),
)

private val BaseTypography = Typography()

/**
 * Every style takes its paragraph direction from its own text. By default Compose follows the layout
 * direction, so on a Hebrew phone an English line such as "3.8 % of distance" or "Search trips…" is laid
 * out right to left and its numbers and punctuation move. Hebrew trip names still read right to left.
 * Number-only texts have no letters to go by, so their styles set `TextDirection.Ltr` themselves.
 */
private fun TextStyle.contentDirected(): TextStyle = copy(textDirection = TextDirection.Content)

private val AppTypography = Typography(
    displayLarge = BaseTypography.displayLarge.contentDirected(),
    displayMedium = BaseTypography.displayMedium.contentDirected(),
    displaySmall = BaseTypography.displaySmall.contentDirected(),
    headlineLarge = BaseTypography.headlineLarge.contentDirected(),
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.Bold).contentDirected(),
    headlineSmall = BaseTypography.headlineSmall.contentDirected(),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold).contentDirected(),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold).contentDirected(),
    titleSmall = BaseTypography.titleSmall.contentDirected(),
    bodyLarge = BaseTypography.bodyLarge.contentDirected(),
    bodyMedium = BaseTypography.bodyMedium.contentDirected(),
    bodySmall = BaseTypography.bodySmall.contentDirected(),
    labelLarge = BaseTypography.labelLarge.contentDirected(),
    labelMedium = BaseTypography.labelMedium.contentDirected(),
    labelSmall = BaseTypography.labelSmall.contentDirected(),
)

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
 * The app theme. It is dark whatever the system setting, because the brand look is dark and a dark
 * screen suits caves; `MainActivity` keeps the system bar icons light to match.
 */
@Composable
fun ImuMapperTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalImuColors provides DarkImuColors) {
        MaterialTheme(colorScheme = DarkColorScheme, typography = AppTypography, shapes = AppShapes) {
            // MaterialTheme leaves the content colour at black; text drawn outside a Surface or
            // Scaffold would vanish on the navy background.
            CompositionLocalProvider(LocalContentColor provides DarkColorScheme.onBackground, content = content)
        }
    }
}
