package com.stastyle.imumapper.ui.theme

/**
 * Every colour of one theme as an ARGB `Int`, with no Compose types, so the JVM tests can check the
 * contrast of the very values [ImuMapperTheme] draws with. The first 36 fields are the Material 3
 * `ColorScheme` roles, the rest are [ImuColors].
 *
 * `primary`, `secondary`, `error`, `success` and `warning` are also text colours (links and text buttons,
 * the selected tab label, error lines, the recording tiles), so each reaches 4.5:1 on the backgrounds,
 * cards and dialogs; strong fills under white text use [brandFill] and [stopRed] instead. `outline` draws
 * interactive boundaries and reaches 3:1; `outlineVariant` and [cardBorder] are decoration only.
 */
internal data class ThemePalette(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val inversePrimary: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val background: Int,
    val onBackground: Int,
    val surface: Int,
    val onSurface: Int,
    val surfaceVariant: Int,
    val onSurfaceVariant: Int,
    val surfaceTint: Int,
    val inverseSurface: Int,
    val inverseOnSurface: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val outline: Int,
    val outlineVariant: Int,
    val scrim: Int,
    val surfaceBright: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val surfaceContainerLow: Int,
    val surfaceContainerLowest: Int,
    val surfaceDim: Int,
    val backgroundTop: Int,
    val backgroundBottom: Int,
    val cardFill: Int,
    val cardBorder: Int,
    val cardBorderStrong: Int,
    val brandFill: Int,
    val onBrandFill: Int,
    val success: Int,
    val successContainer: Int,
    val warning: Int,
    val stopRed: Int,
    val onStopRed: Int,
    val canvasBackground: Int,
    val onCameraOverlay: Int,
    val buttonFill: Int,
    val chipFill: Int,
    val panelFill: Int,
    val isLight: Boolean,
)

/** The opaque ARGB colour of [hex], written `0xRRGGBB`. */
internal fun rgb(hex: Int): Int = hex or OPAQUE

/**
 * [color] with its alpha replaced by [fraction], rounded as Compose's `Color.copy(alpha = fraction)` rounds,
 * so a token written this way is the same colour the dark theme drew with `copy` before.
 */
internal fun withAlpha(color: Int, fraction: Float): Int =
    (color and 0xFFFFFF) or ((fraction * 255f + 0.5f).toInt() shl 24)

private const val OPAQUE = 0xFF shl 24

internal object ThemePalettes {

    /** The navy brand scheme (docs/UI-REDESIGN.md section 4.1). */
    val Dark = ThemePalette(
        primary = rgb(0x64B5F6),
        onPrimary = rgb(0x00233A),
        primaryContainer = rgb(0x0F3A66),
        onPrimaryContainer = rgb(0xD6E9FF),
        inversePrimary = rgb(0x1565C0),
        secondary = rgb(0x4FC3F7),
        onSecondary = rgb(0x00233A),
        secondaryContainer = rgb(0x143357),
        onSecondaryContainer = rgb(0xCFE6FF),
        tertiary = rgb(0xFFB74D),
        onTertiary = rgb(0x3A2600),
        tertiaryContainer = rgb(0x4A3310),
        onTertiaryContainer = rgb(0xFFE0B2),
        background = rgb(0x07111F),
        onBackground = rgb(0xEAF2FF),
        surface = rgb(0x0B1628),
        onSurface = rgb(0xEAF2FF),
        surfaceVariant = rgb(0x16263F),
        onSurfaceVariant = rgb(0x8FA3BF),
        surfaceTint = rgb(0x64B5F6),
        inverseSurface = rgb(0xDDE7F5),
        inverseOnSurface = rgb(0x0B1628),
        error = rgb(0xFF8A80),
        onError = rgb(0x690005),
        errorContainer = rgb(0x4A1518),
        onErrorContainer = rgb(0xFFDAD6),
        outline = rgb(0x6F86A6),
        outlineVariant = rgb(0x1E3A5F),
        scrim = rgb(0x000000),
        surfaceBright = rgb(0x1E3352),
        surfaceContainer = rgb(0x111F35),
        surfaceContainerHigh = rgb(0x152741),
        surfaceContainerHighest = rgb(0x1A2E4C),
        surfaceContainerLow = rgb(0x0D1A2E),
        surfaceContainerLowest = rgb(0x060E1A),
        surfaceDim = rgb(0x07111F),
        backgroundTop = rgb(0x0C1D34),
        backgroundBottom = rgb(0x050B16),
        // surfaceContainer, translucent so the page gradient shows through faintly.
        cardFill = withAlpha(rgb(0x111F35), 0.88f),
        cardBorder = rgb(0x1E3A5F),
        cardBorderStrong = rgb(0x2F6FB5),
        brandFill = rgb(0x1976D2),
        onBrandFill = rgb(0xFFFFFF),
        success = rgb(0x34D399),
        successContainer = rgb(0x0B3326),
        warning = rgb(0xFBBF24),
        stopRed = rgb(0xD32F2F),
        onStopRed = rgb(0xFFFFFF),
        canvasBackground = rgb(0x0A1424),
        onCameraOverlay = rgb(0xFFFFFF),
        // The three over-canvas fills are the surfaceContainerHigh and surfaceContainer glass the dark
        // components drew before these tokens existed.
        buttonFill = withAlpha(rgb(0x152741), 0.85f),
        chipFill = withAlpha(rgb(0x111F35), 0.8f),
        panelFill = withAlpha(rgb(0x111F35), 0.9f),
        isLight = false,
    )

    /**
     * The light scheme (docs/UI-REDESIGN.md section 15): white cards on a pale grey-blue page, the same blues
     * in darker tones. Two values differ from that table because the contrast test found them short:
     * `secondary` is #0269A4 rather than #0277BD (the selected tab label and the locked compass's "N" are
     * text in it), and `tertiary` and `warning` are #A64D08 rather than #B45309 (warning text on the
     * recording tiles and on dialogs).
     */
    val Light = ThemePalette(
        primary = rgb(0x1565C0),
        onPrimary = rgb(0xFFFFFF),
        primaryContainer = rgb(0xD6E6FA),
        onPrimaryContainer = rgb(0x0B2F5C),
        inversePrimary = rgb(0x90CAF9),
        secondary = rgb(0x0269A4),
        onSecondary = rgb(0xFFFFFF),
        secondaryContainer = rgb(0xDCEBFA),
        onSecondaryContainer = rgb(0x0B2F5C),
        tertiary = rgb(0xA64D08),
        onTertiary = rgb(0xFFFFFF),
        tertiaryContainer = rgb(0xFDECD3),
        onTertiaryContainer = rgb(0x5A2E02),
        background = rgb(0xF5F7FA),
        onBackground = rgb(0x0F1B2D),
        surface = rgb(0xFFFFFF),
        onSurface = rgb(0x0F1B2D),
        surfaceVariant = rgb(0xE6ECF3),
        onSurfaceVariant = rgb(0x4A5B72),
        surfaceTint = rgb(0x1565C0),
        inverseSurface = rgb(0x1B2838),
        inverseOnSurface = rgb(0xEEF2F7),
        error = rgb(0xC62828),
        onError = rgb(0xFFFFFF),
        errorContainer = rgb(0xFDE3E3),
        onErrorContainer = rgb(0x6B1010),
        outline = rgb(0x6B7C93),
        outlineVariant = rgb(0xD5DEEA),
        scrim = rgb(0x000000),
        surfaceBright = rgb(0xFFFFFF),
        surfaceContainer = rgb(0xF1F4F9),
        surfaceContainerHigh = rgb(0xEBEFF5),
        surfaceContainerHighest = rgb(0xE4E9F0),
        surfaceContainerLow = rgb(0xF7F9FC),
        surfaceContainerLowest = rgb(0xFFFFFF),
        surfaceDim = rgb(0xDDE3EB),
        backgroundTop = rgb(0xEEF3FA),
        backgroundBottom = rgb(0xF8FAFD),
        cardFill = withAlpha(rgb(0xFFFFFF), 0.92f),
        cardBorder = rgb(0xD5DEEA),
        cardBorderStrong = rgb(0x1976D2),
        brandFill = rgb(0x1976D2),
        onBrandFill = rgb(0xFFFFFF),
        success = rgb(0x047857),
        successContainer = rgb(0xD1FAE5),
        warning = rgb(0xA64D08),
        stopRed = rgb(0xD32F2F),
        onStopRed = rgb(0xFFFFFF),
        canvasBackground = rgb(0xEEF2F7),
        onCameraOverlay = rgb(0xFFFFFF),
        // White glass, as map controls are drawn on light maps; a grey fill would melt into the grey canvas.
        buttonFill = withAlpha(rgb(0xFFFFFF), 0.92f),
        chipFill = withAlpha(rgb(0xFFFFFF), 0.85f),
        panelFill = withAlpha(rgb(0xFFFFFF), 0.94f),
        isLight = true,
    )
}
