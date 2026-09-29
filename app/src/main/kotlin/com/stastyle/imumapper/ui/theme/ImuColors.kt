package com.stastyle.imumapper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Brand colours that have no Material 3 role. [brandFill] is separate from `primary` because
 * `primary` is also a text colour and must stay light enough to read on the cards, while white text
 * needs a darker blue under it.
 */
@Immutable
data class ImuColors(
    /** Top of the page gradient. */
    val backgroundTop: Color,
    /** Bottom of the page gradient. */
    val backgroundBottom: Color,
    /** Translucent card body, so the page gradient shows through faintly. */
    val cardFill: Color,
    /** Decorative card border; never used for an interactive boundary. */
    val cardBorder: Color,
    /** Border of a highlighted card (recording or busy) and of the round canvas buttons. */
    val cardBorderStrong: Color,
    /** Strong blue fill for filled buttons, selected chips and tabs. */
    val brandFill: Color,
    /** Text and icons on [brandFill] (4.6:1). */
    val onBrandFill: Color,
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    /** The Stop Recording pill. */
    val stopRed: Color,
    /** Text and icons on [stopRed] (5.0:1). */
    val onStopRed: Color,
    /** Behind the path canvas; dark, because survey order numbers are drawn in it. */
    val canvasBackground: Color,
)

internal val DarkImuColors = ImuColors(
    backgroundTop = Color(0xFF0C1D34),
    backgroundBottom = Color(0xFF050B16),
    // surfaceContainer at 88 % alpha.
    cardFill = Color(0xFF111F35).copy(alpha = 0.88f),
    cardBorder = Color(0xFF1E3A5F),
    cardBorderStrong = Color(0xFF2F6FB5),
    brandFill = Color(0xFF1976D2),
    onBrandFill = Color(0xFFFFFFFF),
    success = Color(0xFF34D399),
    successContainer = Color(0xFF0B3326),
    warning = Color(0xFFFBBF24),
    stopRed = Color(0xFFD32F2F),
    onStopRed = Color(0xFFFFFFFF),
    canvasBackground = Color(0xFF0A1424),
)

/** Provided by [ImuMapperTheme]; the default keeps previews and tests outside the theme usable. */
val LocalImuColors = staticCompositionLocalOf { DarkImuColors }

/** The app's extended colours, next to `MaterialTheme.colorScheme`. */
val MaterialTheme.imuColors: ImuColors
    @Composable
    @ReadOnlyComposable
    get() = LocalImuColors.current
