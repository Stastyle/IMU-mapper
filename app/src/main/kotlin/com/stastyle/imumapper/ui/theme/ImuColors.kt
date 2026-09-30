package com.stastyle.imumapper.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Brand colours that have no Material 3 role. [brandFill] is separate from `primary` because
 * `primary` is also a text colour and must keep 4.5:1 on the cards, while white text needs its own
 * blue under it. The values live in [ThemePalettes], where the contrast test reads them.
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
    /** "OK" states, also as text on the cards and tiles. */
    val success: Color,
    /** Behind [success] text in a pill. */
    val successContainer: Color,
    /** Low battery, "Waiting" and other cautions, also as text on the cards and tiles. */
    val warning: Color,
    /** The Stop Recording pill. */
    val stopRed: Color,
    /** Text and icons on [stopRed] (5.0:1). */
    val onStopRed: Color,
    /** Behind the path canvas, the trip thumbnails and the calibration preview. */
    val canvasBackground: Color,
    /**
     * Text and the spinner over the camera preview, on a half-`scrim` chip or on the black camera
     * background. The camera image does not follow the theme, so this stays light in any scheme, where
     * `onSurface` would turn dark under a light theme and vanish on the scrim.
     */
    val onCameraOverlay: Color,
    /** The translucent circle of a round icon button, over the canvas or the page. */
    val buttonFill: Color,
    /** The read-only pill over the canvas ("5 m grid"). */
    val chipFill: Color,
    /**
     * A sheet laid over the canvas, such as the Survey mode panel: mostly opaque, so its readout stays legible
     * over a bright path while the plan still shows through.
     */
    val panelFill: Color,
    /** True for the light scheme; screens that draw their own canvas pick the matching `CanvasPalette` with it. */
    val isLight: Boolean = false,
)

private fun imuColorsOf(p: ThemePalette) = ImuColors(
    backgroundTop = Color(p.backgroundTop),
    backgroundBottom = Color(p.backgroundBottom),
    cardFill = Color(p.cardFill),
    cardBorder = Color(p.cardBorder),
    cardBorderStrong = Color(p.cardBorderStrong),
    brandFill = Color(p.brandFill),
    onBrandFill = Color(p.onBrandFill),
    success = Color(p.success),
    successContainer = Color(p.successContainer),
    warning = Color(p.warning),
    stopRed = Color(p.stopRed),
    onStopRed = Color(p.onStopRed),
    canvasBackground = Color(p.canvasBackground),
    onCameraOverlay = Color(p.onCameraOverlay),
    buttonFill = Color(p.buttonFill),
    chipFill = Color(p.chipFill),
    panelFill = Color(p.panelFill),
    isLight = p.isLight,
)

internal val DarkImuColors = imuColorsOf(ThemePalettes.Dark)

internal val LightImuColors = imuColorsOf(ThemePalettes.Light)

/** Provided by [ImuMapperTheme]; the default keeps previews and tests outside the theme usable. */
val LocalImuColors = staticCompositionLocalOf { DarkImuColors }

/** The app's extended colours, next to `MaterialTheme.colorScheme`. */
val MaterialTheme.imuColors: ImuColors
    @Composable
    @ReadOnlyComposable
    get() = LocalImuColors.current
