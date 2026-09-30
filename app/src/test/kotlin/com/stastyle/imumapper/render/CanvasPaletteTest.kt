package com.stastyle.imumapper.render

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.survey.StationKind
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * WCAG contrast of every colour the canvases draw against the background it is drawn on: lines and markers at 3:1
 * (graphical objects), text at 4.5:1. Translucent colours are measured as they land, composited over what is under
 * them.
 */
class CanvasPaletteTest {

    private val palettes = listOf(CanvasPalette.Dark, CanvasPalette.Light)

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Int): Double =
        0.2126 * channel(SceneColors.red(c)) + 0.7152 * channel(SceneColors.green(c)) +
            0.0722 * channel(SceneColors.blue(c))

    /** [top] painted over the opaque [under], as the canvas shows it. */
    private fun over(top: Int, under: Int): Int {
        val a = SceneColors.alpha(top) / 255.0
        fun mix(t: Int, u: Int) = Math.round(t * a + u * (1 - a)).toInt()
        return SceneColors.argb(
            255,
            mix(SceneColors.red(top), SceneColors.red(under)),
            mix(SceneColors.green(top), SceneColors.green(under)),
            mix(SceneColors.blue(top), SceneColors.blue(under)),
        )
    }

    /** WCAG contrast ratio of [color] (composited over [background]) against the opaque [background]. */
    private fun contrast(color: Int, background: Int): Double {
        val a = luminance(over(color, background))
        val b = luminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun name(p: CanvasPalette) = if (p.isLight) "Light" else "Dark"

    private fun assertContrast(minimum: Double, color: Int, background: Int, what: String) {
        val ratio = contrast(color, background)
        assertTrue(ratio >= minimum, "$what: %.2f:1, needs %.1f:1".format(ratio, minimum))
    }

    /** Every line and marker fill a palette draws at full strength, by name. */
    private fun linesAndMarkers(p: CanvasPalette): Map<String, Int> = buildMap {
        for (s in PositionSource.entries) put("source $s", p.forSource(s))
        for (k in AnnotationKind.entries) put("annotation $k", p.forAnnotation(k))
        for (k in StationKind.entries) put("station $k", p.forStation(k))
        put("start", p.start)
        put("end", p.end)
        put("keyframe", p.keyframe)
        put("axis east", p.axisEast)
        put("axis north", p.axisNorth)
        put("axis up", p.axisUp)
        put("north arrow", p.northArrow)
        put("selection ring", p.selectionRing)
        put("cursor", p.cursor)
    }

    @Test
    fun everyLineAndMarkerReachesThreeToOneOnTheBackground() {
        for (p in palettes) {
            for ((what, color) in linesAndMarkers(p)) assertContrast(3.0, color, p.background, "${name(p)} $what")
        }
    }

    @Test
    fun everyRampSampledEveryFivePercentReachesThreeToOne() {
        for (p in palettes) {
            val ramps = mapOf("progress" to p.progressStops, "time" to p.timeStops, "altitude" to p.altitudeStops)
            for ((ramp, stops) in ramps) {
                for (k in 0..20) {
                    val t = k / 20.0
                    // Dark's altitude ramp starts at a deep purple that is 2.3:1 on the navy and reaches 3:1 at
                    // 15 %. It is today's look, which the light theme must not change; pinned below.
                    if (!p.isLight && ramp == "altitude" && t < 0.15) continue
                    assertContrast(3.0, SceneColors.gradient(stops, t), p.background, "${name(p)} $ramp at $t")
                }
            }
            // PathProgress, which the thumbnails, the chart and the preview use, walks the same ramp.
            for (k in 0..20) {
                val t = k / 20.0
                assertEquals(SceneColors.gradient(p.progressStops, t), PathProgress.color(t, p))
            }
        }
    }

    @Test
    fun darksAltitudeShortfallIsOnlyItsLowEnd() {
        val stops = CanvasPalette.Dark.altitudeStops
        val background = CanvasPalette.Dark.background
        for (k in 0..2) assertContrast(2.3, SceneColors.gradient(stops, k / 20.0), background, "Dark altitude")
        assertTrue(contrast(stops[0], background) < 3.0, "the exception above is no longer needed")
    }

    @Test
    fun labelTextReachesFourAndAHalfToOne() {
        for (p in palettes) {
            assertContrast(4.5, p.label, p.background, "${name(p)} Start and End labels")
            assertContrast(4.5, p.surveyLabel, p.background, "${name(p)} station names")
            // Chain numbers sit on the station's own fill.
            for (k in StationKind.entries) {
                assertContrast(4.5, p.stationOrder, p.forStation(k), "${name(p)} chain number on $k")
            }
        }
        // Light draws the axis letters in the axis colours, over a halo; they are text there, so 4.5:1.
        val light = CanvasPalette.Light
        for (axis in listOf(light.axisEast, light.axisNorth, light.axisUp, light.northArrow)) {
            assertContrast(4.5, axis, light.background, "Light axis letter %08X".format(axis))
        }
    }

    @Test
    fun theLightHaloAndOutlinesDoTheirJob() {
        val light = CanvasPalette.Light
        // The halo is what text is read against wherever it crosses a line.
        assertContrast(4.5, light.label, light.labelHalo, "label on its halo")
        assertContrast(4.5, light.surveyLabel, light.labelHalo, "station name on its halo")
        for (axis in listOf(light.axisEast, light.axisNorth, light.axisUp, light.northArrow)) {
            assertContrast(4.5, axis, light.labelHalo, "axis letter %08X on its halo".format(axis))
        }
        // A white ring vanishes into the light canvas; the outline round it must not.
        assertContrast(3.0, light.markerOutline, light.background, "Light marker outline")
        assertEquals(0, SceneColors.alpha(CanvasPalette.Dark.markerOutline), "Dark draws no outline")
        assertContrast(3.0, CanvasPalette.Dark.markerRing, CanvasPalette.Dark.background, "Dark marker ring")
        // Text in the light palette has no drop shadow, and the dark palette no halo.
        assertEquals(0, SceneColors.alpha(light.labelShadow))
        assertEquals(0, SceneColors.alpha(CanvasPalette.Dark.labelHalo))
    }

    @Test
    fun chordsReadOverTheirUnderlayAndTheStretch() {
        for (p in palettes) {
            val underlay = over(p.chordUnderlay, p.background)
            assertContrast(3.0, p.chord, underlay, "${name(p)} chord on its underlay")
            val onStretch = over(p.chordUnderlay, over(p.stretch, p.background))
            assertContrast(3.0, p.chord, onStretch, "${name(p)} chord over the stretch")
            // The stretch is a translucent band under which the path must show, so it is a highlight rather than a
            // line: it has to stand out, not to reach 3:1.
            assertContrast(1.5, p.stretch, p.background, "${name(p)} stretch band")
        }
    }

    @Test
    fun theGridAndCloudStayFaintButVisible() {
        for (p in palettes) {
            val minor = contrast(p.gridMinor, p.background)
            val major = contrast(p.gridMajor, p.background)
            val grid = "${name(p)} grid %.2f / %.2f".format(minor, major)
            assertTrue(minor >= 1.25 && major > minor && major < 3.0, grid)
            val cloud = contrast(p.cloud, p.background)
            assertTrue(cloud in 1.5..3.0, "${name(p)} cloud %.2f".format(cloud))
        }
    }

    @Test
    fun theLightChartSitsOnTheCanvasBackground() {
        val light = CanvasPalette.Light
        assertEquals(light.background, light.plotBackground)
        assertContrast(1.3, light.chartGrid, light.plotBackground, "Light chart ticks")
        // Dark draws no plot panel: the chart lies on the card as it always has.
        assertEquals(0, SceneColors.alpha(CanvasPalette.Dark.plotBackground))
    }

    @Test
    fun darkIsTodaysCanvas() {
        val dark = CanvasPalette.Dark
        assertEquals(0xFF0A1424.toInt(), dark.background)
        assertEquals(SceneColors.argb(255, 0x2F, 0x6B, 0xFF), dark.progressStops.first())
        assertEquals(SceneColors.argb(255, 0xEF, 0x44, 0x44), dark.progressStops.last())
        assertEquals(SceneColors.argb(255, 255, 214, 0), dark.timeStops[3])
        assertEquals(SceneColors.argb(255, 255, 241, 118), dark.altitudeStops.last())
        assertEquals(SceneColors.argb(255, 76, 175, 80), dark.start)
        assertEquals(SceneColors.argb(255, 244, 67, 54), dark.end)
        assertEquals(SceneColors.argb(48, 90, 150, 230), dark.gridMinor)
        assertEquals(SceneColors.argb(80, 176, 190, 197), dark.cloud)
        assertEquals(SceneColors.argb(255, 236, 239, 241), dark.forAnnotation(AnnotationKind.NOTE))
        assertEquals(SceneColors.argb(255, 207, 216, 220), dark.forStation(StationKind.CORNER))
        assertEquals(SceneColors.argb(200, 255, 235, 59), dark.stretch)
        assertEquals(0x99000000.toInt(), dark.chordUnderlay)
        assertEquals(0xFFEAF2FF.toInt(), dark.label)
        assertEquals(0xFF000000.toInt(), dark.labelShadow)
        assertEquals(0xFF1E3A5F.toInt(), dark.chartGrid)
        assertEquals(dark.background, dark.stationOrder)
        assertEquals(PathEmphasis.GLOW, dark.pathEmphasis)
        // The older names read the same values, and PathProgress's one-argument colour is the dark one.
        assertSame(dark.progressStops, SceneColors.PROGRESS_STOPS)
        assertEquals(dark.forSource(PositionSource.VIO), SceneColors.SOURCE_VIO)
        assertEquals(PathProgress.color(0.4, dark), PathProgress.color(0.4))
        assertSame(CanvasPalette.Dark, SceneOptions().palette)
    }

    @Test
    fun lightIsSectionFifteensCanvas() {
        val light = CanvasPalette.Light
        assertTrue(light.isLight)
        assertEquals(0xFFEEF2F7.toInt(), light.background)
        val spec = intArrayOf(0x1D4ED8, 0x0891B2, 0x059669, 0xA16207, 0xEA580C, 0xDC2626).map { it or (0xFF shl 24) }
        assertContentEquals(spec.toIntArray(), light.progressStops)
        assertEquals(PathEmphasis.CASING, light.pathEmphasis)
        assertEquals(0xFFFFFFFF.toInt(), light.casing)
        assertEquals(0xFFFFFFFF.toInt(), light.markerRing)
        assertEquals(0xFF0F172A.toInt(), light.label)
        assertEquals(0xFFFFFFFF.toInt(), light.labelHalo)
    }

    /** Hue in degrees, for the hue-order check. */
    private fun hue(c: Int): Double {
        val r = SceneColors.red(c) / 255.0
        val g = SceneColors.green(c) / 255.0
        val b = SceneColors.blue(c) / 255.0
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val d = hi - lo
        if (d == 0.0) return 0.0
        val h = when (hi) {
            r -> 60 * (((g - b) / d) % 6)
            g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }
        return (h + 360) % 360
    }

    @Test
    fun lightRampsKeepDarksHuesInTheSameOrder() {
        // "Blue is the start, red is the end" in both themes: each light stop is a darker tone of the dark one.
        val pairs = mapOf(
            "progress" to (CanvasPalette.Dark.progressStops to CanvasPalette.Light.progressStops),
            "time" to (CanvasPalette.Dark.timeStops to CanvasPalette.Light.timeStops),
            "altitude" to (CanvasPalette.Dark.altitudeStops to CanvasPalette.Light.altitudeStops),
        )
        for ((ramp, stops) in pairs) {
            val (dark, light) = stops
            assertEquals(dark.size, light.size, ramp)
            for (i in dark.indices) {
                val d = abs(hue(dark[i]) - hue(light[i]))
                val apart = min(d, 360 - d)
                assertTrue(apart <= 20.0, "$ramp stop $i: hues %.0f° apart".format(apart))
            }
        }
    }
}
