package com.stastyle.imumapper.ui.theme

import com.stastyle.imumapper.render.CanvasPalette
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * WCAG 2.1 contrast of the colour pairs the app draws, in both themes: text at 4.5:1, boundaries of
 * controls at 3:1. Translucent fills are composited over what lies under them on screen (the two ends of the
 * page gradient, the canvas), so the check sees the colours a user sees.
 */
class ThemePaletteContrastTest {

    private val palettes = mapOf("dark" to ThemePalettes.Dark, "light" to ThemePalettes.Light)

    /** The overlays are checked against [ThemePalette.canvasBackground], so it must be the colour the maps draw. */
    @Test
    fun canvasBackgroundIsTheMapPalettesBackground() {
        assertEquals(CanvasPalette.Dark.background, ThemePalettes.Dark.canvasBackground)
        assertEquals(CanvasPalette.Light.background, ThemePalettes.Light.canvasBackground)
    }

    @Test
    fun textRolesReadOnEveryBackground() = check { p ->
        val grounds = pageGrounds(p) + cardGrounds(p) + listOf(
            "surfaceContainer" to p.surfaceContainer,
            "surfaceContainerHigh (dialogs)" to p.surfaceContainerHigh,
        )
        for (fg in listOf("onSurface" to p.onSurface, "onBackground" to p.onBackground)) {
            for (ground in grounds) text(fg, ground)
        }
        for (ground in grounds + ("surfaceContainerHighest" to p.surfaceContainerHighest)) {
            text("onSurfaceVariant" to p.onSurfaceVariant, ground)
        }
    }

    @Test
    fun primaryAndErrorAreTextColours() = check { p ->
        // Text buttons, links, focused field labels and about 30 error lines are drawn in these two.
        val grounds = pageGrounds(p) + cardGrounds(p) + listOf(
            "surfaceContainer" to p.surfaceContainer,
            "surfaceContainerHigh (dialogs)" to p.surfaceContainerHigh,
            "secondaryContainer" to p.secondaryContainer,
            "canvasBackground" to p.canvasBackground,
        )
        for (ground in grounds) {
            text("primary" to p.primary, ground)
            text("error" to p.error, ground)
        }
    }

    @Test
    fun successAndWarningReadAsTextOnCardsAndTiles() = check { p ->
        // The recording tiles and the battery line write "OK", "Waiting" and "Battery low" in these colours,
        // on a card or on a framed tile (surfaceContainerHigh at 70 %, see StatTile) over the page or a card.
        val tileFill = withAlpha(p.surfaceContainerHigh, 0.7f)
        val grounds = cardGrounds(p) + pageGrounds(p).map { (name, colour) ->
            "tile over $name" to over(tileFill, colour)
        } + cardGrounds(p).map { (name, colour) -> "tile over $name" to over(tileFill, colour) }
        for (ground in grounds) {
            text("success" to p.success, ground)
            text("warning" to p.warning, ground)
            text("tertiary" to p.tertiary, ground)
        }
    }

    @Test
    fun bottomBarLabelsRead() = check { p ->
        // The bar is surfaceContainerLow at 92 % over the page (AppBottomBar); the selected tab's label is
        // secondary, the others onSurfaceVariant.
        val bar = withAlpha(p.surfaceContainerLow, 0.92f)
        for ((name, page) in pageGrounds(p)) {
            val ground = "bottom bar over $name" to over(bar, page)
            text("secondary" to p.secondary, ground)
            text("onSurfaceVariant" to p.onSurfaceVariant, ground)
        }
    }

    @Test
    fun compassDialLettersRead() = check { p ->
        // CompassDial: while it waits the face is surfaceContainerHighest with "N" in primary; once locked it is
        // secondaryContainer with "N" in secondary. E, S, W and the cardinal line are onSurfaceVariant on either
        // face, and the heading is onSurface or onSecondaryContainer.
        val open = "surfaceContainerHighest (waiting face)" to p.surfaceContainerHighest
        text("primary" to p.primary, open)
        text("onSurfaceVariant" to p.onSurfaceVariant, open)
        text("onSurface" to p.onSurface, open)
        val locked = "secondaryContainer (locked face)" to p.secondaryContainer
        text("secondary" to p.secondary, locked)
        text("onSurfaceVariant" to p.onSurfaceVariant, locked)
        text("onSecondaryContainer" to p.onSecondaryContainer, locked)
    }

    @Test
    fun contentReadsOnItsFill() = check { p ->
        text("onPrimary" to p.onPrimary, "primary" to p.primary)
        text("onSecondary" to p.onSecondary, "secondary" to p.secondary)
        text("onTertiary" to p.onTertiary, "tertiary" to p.tertiary)
        text("onError" to p.onError, "error" to p.error)
        text("onPrimaryContainer" to p.onPrimaryContainer, "primaryContainer" to p.primaryContainer)
        text("onSecondaryContainer" to p.onSecondaryContainer, "secondaryContainer" to p.secondaryContainer)
        text("onTertiaryContainer" to p.onTertiaryContainer, "tertiaryContainer" to p.tertiaryContainer)
        text("onErrorContainer" to p.onErrorContainer, "errorContainer" to p.errorContainer)
        text("onBrandFill" to p.onBrandFill, "brandFill" to p.brandFill)
        text("onStopRed" to p.onStopRed, "stopRed" to p.stopRed)
        // Snackbars: the message and its action.
        text("inverseOnSurface" to p.inverseOnSurface, "inverseSurface" to p.inverseSurface)
        text("inversePrimary" to p.inversePrimary, "inverseSurface" to p.inverseSurface)
    }

    @Test
    fun statusPillTonesRead() = check { p ->
        // The pairs of StatusPill's pillColors: Info, Success, Warning, Error, Neutral.
        text("onBrandFill" to p.onBrandFill, "brandFill" to p.brandFill)
        text("success" to p.success, "successContainer" to p.successContainer)
        text("onTertiaryContainer" to p.onTertiaryContainer, "tertiaryContainer" to p.tertiaryContainer)
        text("onErrorContainer" to p.onErrorContainer, "errorContainer" to p.errorContainer)
        text("onSurfaceVariant" to p.onSurfaceVariant, "surfaceContainerHighest" to p.surfaceContainerHighest)
    }

    @Test
    fun overlaysOnTheCanvasRead() = check { p ->
        val canvas = p.canvasBackground
        text("onSurface" to p.onSurface, "buttonFill over canvas" to over(p.buttonFill, canvas))
        text("onSurfaceVariant" to p.onSurfaceVariant, "chipFill over canvas" to over(p.chipFill, canvas))
        val panel = "panelFill over canvas" to over(p.panelFill, canvas)
        text("onSurface" to p.onSurface, panel)
        text("onSurfaceVariant" to p.onSurfaceVariant, panel)
        text("error" to p.error, panel)
    }

    @Test
    fun outlineBoundsControls() = check { p ->
        // Unchecked switches, text fields, outlined buttons and unselected chips draw their edge in outline.
        val grounds = cardGrounds(p) + listOf(
            "surface" to p.surface,
            "surfaceContainer" to p.surfaceContainer,
            "surfaceContainerHigh (dialogs)" to p.surfaceContainerHigh,
            "surfaceContainerHighest" to p.surfaceContainerHighest,
        )
        for (ground in grounds) boundary("outline" to p.outline, ground)
        // The round buttons over the canvas are outlined in cardBorderStrong.
        val button = "buttonFill over canvas" to over(p.buttonFill, p.canvasBackground)
        boundary("cardBorderStrong" to p.cardBorderStrong, button)
    }

    @Test
    fun darkTranslucentTokensMatchWhatTheDarkThemeDrewBefore() {
        // The dark theme used Color.copy(alpha = …) on these before they became tokens; withAlpha rounds the
        // same way, so the dark look is unchanged to the bit.
        val dark = ThemePalettes.Dark
        assertEquals(0xE0111F35.toInt(), dark.cardFill)
        assertEquals(0xD9152741.toInt(), dark.buttonFill)
        assertEquals(0xCC111F35.toInt(), dark.chipFill)
        assertEquals(0xE6111F35.toInt(), dark.panelFill)
        assertTrue(!dark.isLight)
        assertTrue(ThemePalettes.Light.isLight)
    }

    @Test
    fun contrastMathMatchesWcagReferencePoints() {
        assertEquals(21.0, contrast(rgb(0x000000), rgb(0xFFFFFF)), 1e-9)
        assertEquals(1.0, contrast(rgb(0x777777), rgb(0x777777)), 1e-9)
        // #767676 is the classic lightest grey that still passes 4.5:1 on white.
        assertTrue(contrast(rgb(0x767676), rgb(0xFFFFFF)) >= 4.5)
        assertTrue(contrast(rgb(0x777777), rgb(0xFFFFFF)) < 4.5)
        assertEquals(rgb(0x808080), over(withAlpha(rgb(0xFFFFFF), 0.5f), rgb(0x000000)))
    }

    // --- helpers ---

    private class Checker(val theme: String) {
        val failures = mutableListOf<String>()

        fun text(fg: Pair<String, Int>, bg: Pair<String, Int>) = require(fg, bg, TEXT)

        fun boundary(fg: Pair<String, Int>, bg: Pair<String, Int>) = require(fg, bg, BOUNDARY)

        private fun require(fg: Pair<String, Int>, bg: Pair<String, Int>, min: Double) {
            val ratio = contrast(over(fg.second, bg.second), bg.second)
            if (ratio < min) {
                failures += "$theme: ${fg.first} on ${bg.first} is %.2f:1, needs %.1f:1".format(ratio, min)
            }
        }
    }

    private fun check(block: Checker.(ThemePalette) -> Unit) {
        val failures = palettes.flatMap { (name, palette) -> Checker(name).apply { block(palette) }.failures }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /** Both ends of the page gradient and the scheme's own background. */
    private fun pageGrounds(p: ThemePalette) = listOf(
        "background" to p.background,
        "page top" to p.backgroundTop,
        "page bottom" to p.backgroundBottom,
    )

    /** The translucent card as it looks over each end of the page gradient, and the opaque surface. */
    private fun cardGrounds(p: ThemePalette) = listOf(
        "card over page top" to over(p.cardFill, p.backgroundTop),
        "card over page bottom" to over(p.cardFill, p.backgroundBottom),
        "surface" to p.surface,
    )

    private companion object {
        const val TEXT = 4.5
        const val BOUNDARY = 3.0

        fun channel(argb: Int, shift: Int): Int = (argb ushr shift) and 0xFF

        /** [fg] with its alpha laid over the opaque [bg]. */
        fun over(fg: Int, bg: Int): Int {
            val a = channel(fg, 24) / 255.0
            fun mix(shift: Int) = (channel(fg, shift) * a + channel(bg, shift) * (1 - a)).roundToInt()
            return rgb((mix(16) shl 16) or (mix(8) shl 8) or mix(0))
        }

        /** WCAG relative luminance of an opaque colour. */
        fun luminance(argb: Int): Double {
            fun linear(shift: Int): Double {
                val c = channel(argb, shift) / 255.0
                return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
        }

        fun contrast(a: Int, b: Int): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }
    }
}
