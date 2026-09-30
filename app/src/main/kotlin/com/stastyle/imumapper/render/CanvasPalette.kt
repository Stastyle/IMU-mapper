package com.stastyle.imumapper.render

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.survey.StationKind

/** How the main path is set off from the grid and the lines under it. */
enum class PathEmphasis {
    /** A wide, faint stroke in the line's own colour under it: light spilling onto a dark canvas. */
    GLOW,

    /**
     * A wider opaque stroke in [CanvasPalette.casing] under it, the casing of light maps. A glow on a light canvas
     * reads as a smudge.
     */
    CASING,
}

/**
 * Every colour the viewer's map, the survey layer, the trip thumbnails, the elevation chart and the calibration
 * preview draw, as ARGB ints so the scene code stays free of Compose. [Dark] is the navy canvas the app shipped with,
 * colour for colour. [Light] is the light theme's (docs/UI-REDESIGN.md section 15): the same hues in the same order,
 * so "blue is the start, red is the end" holds in both, but darker, so that each line and marker reaches 3:1 on the
 * light background and label text 4.5:1. `CanvasPaletteTest` holds both palettes to that.
 *
 * A colour whose alpha is 0 means "not drawn": [markerOutline], [labelShadow], [labelHalo] and [plotBackground] are
 * each used by one palette only. There are exactly two instances and they compare by identity, which is all a scene
 * cache keyed on [SceneOptions] needs.
 */
class CanvasPalette private constructor(
    val isLight: Boolean,
    /** Behind the map, the thumbnails and the calibration preview. */
    val background: Int,
    /** [ColorMode.PROGRESS] stops: blue, cyan, green, yellow, orange, red. See [PathProgress]. */
    val progressStops: IntArray,
    /** [ColorMode.TIME] stops: blue, cyan, green, yellow, red. */
    val timeStops: IntArray,
    /** [ColorMode.ALTITUDE] stops, low to high: purple, blue, green, orange, yellow. */
    val altitudeStops: IntArray,
    val sourcePdr: Int,
    val sourceVio: Int,
    val sourceInterpolated: Int,
    /** The Start marker, the start station and the green dot of thumbnails and previews. */
    val start: Int,
    /** The End marker, the end station and the red dot of thumbnails and previews. */
    val end: Int,
    val keyframe: Int,
    val waypoint: Int,
    val junction: Int,
    val chamber: Int,
    val note: Int,
    val loopClosed: Int,
    val reorient: Int,
    val cloud: Int,
    val gridMinor: Int,
    /** Every fifth grid line. */
    val gridMajor: Int,
    val axisEast: Int,
    val axisNorth: Int,
    val axisUp: Int,
    val northArrow: Int,
    val pathEmphasis: PathEmphasis,
    /** Under the main path when [pathEmphasis] is [PathEmphasis.CASING]. */
    val casing: Int,
    /** Round every marker and station, between its fill and the canvas. */
    val markerRing: Int,
    /** A thin line outside [markerRing], where the ring alone would vanish into a light canvas. */
    val markerOutline: Int,
    /** The ring a gap outside the selected marker. */
    val selectionRing: Int,
    /** The Start and End labels. */
    val label: Int,
    /** A soft drop shadow under the Start and End labels and the station names. */
    val labelShadow: Int,
    /** A stroke round every letter drawn on the canvas, so text stays readable over any line under it. */
    val labelHalo: Int,
    /** Behind the elevation chart's plot, so it reads like the map; the card shows through where it is 0. */
    val plotBackground: Int,
    /** The elevation chart's tick lines. */
    val chartGrid: Int,
    val surveyMark: Int,
    val surveyCorner: Int,
    val surveyUser: Int,
    /** The dashed chords between chained stations. */
    val chord: Int,
    /** A wider line under each chord that keeps it readable over the stretch, the path and the grid. */
    val chordUnderlay: Int,
    /** The translucent band over the highlighted stretch of path; the path must show through it. */
    val stretch: Int,
    val cursor: Int,
    /** Station names. */
    val surveyLabel: Int,
    /**
     * A station's place in the chain, written on the station's own fill and shrunk to stay inside it
     * ([chainNumberScale]): only the fill is its contrast, not the ring or the background.
     */
    val stationOrder: Int,
) {
    fun forSource(source: PositionSource): Int = when (source) {
        PositionSource.PDR -> sourcePdr
        PositionSource.VIO -> sourceVio
        PositionSource.INTERPOLATED -> sourceInterpolated
    }

    fun forAnnotation(kind: AnnotationKind): Int = when (kind) {
        AnnotationKind.WAYPOINT -> waypoint
        AnnotationKind.JUNCTION -> junction
        AnnotationKind.CHAMBER -> chamber
        AnnotationKind.NOTE -> note
        AnnotationKind.LOOP_CLOSED -> loopClosed
        AnnotationKind.REORIENT -> reorient
    }

    /** A station's fill. Start and End are the scene's own, so a station reads as the marker it replaces. */
    fun forStation(kind: StationKind): Int = when (kind) {
        StationKind.START -> start
        StationKind.END -> end
        StationKind.MARK -> surveyMark
        StationKind.CORNER -> surveyCorner
        StationKind.USER -> surveyUser
    }

    override fun toString(): String = if (isLight) "CanvasPalette.Light" else "CanvasPalette.Dark"

    companion object {
        private fun argb(a: Int, r: Int, g: Int, b: Int): Int = SceneColors.argb(a, r, g, b)

        /** An opaque colour from its 0xRRGGBB value. */
        private fun rgb(value: Int): Int = value or (0xFF shl 24)

        private const val WHITE = 0xFFFFFF
        private const val TRANSPARENT = 0

        /** The navy canvas, exactly as the app drew it before it had a light theme. */
        val Dark = CanvasPalette(
            isLight = false,
            background = rgb(0x0A1424),
            progressStops = intArrayOf(
                argb(255, 0x2F, 0x6B, 0xFF),
                argb(255, 0x22, 0xD3, 0xEE),
                argb(255, 0x34, 0xD3, 0x99),
                argb(255, 0xFA, 0xCC, 0x15),
                argb(255, 0xFB, 0x92, 0x3C),
                argb(255, 0xEF, 0x44, 0x44),
            ),
            timeStops = intArrayOf(
                argb(255, 66, 133, 244),
                argb(255, 38, 198, 218),
                argb(255, 102, 187, 106),
                argb(255, 255, 214, 0),
                argb(255, 239, 83, 80),
            ),
            // The deep purple low end is 2.3:1 on the navy: kept, because the dark look does not change.
            altitudeStops = intArrayOf(
                argb(255, 94, 53, 177),
                argb(255, 30, 136, 229),
                argb(255, 67, 160, 71),
                argb(255, 251, 140, 0),
                argb(255, 255, 241, 118),
            ),
            sourcePdr = argb(255, 255, 167, 38),
            sourceVio = argb(255, 38, 198, 218),
            sourceInterpolated = argb(255, 158, 158, 158),
            start = argb(255, 76, 175, 80),
            end = argb(255, 244, 67, 54),
            keyframe = argb(255, 100, 181, 246),
            waypoint = argb(255, 255, 202, 40),
            junction = argb(255, 171, 71, 188),
            chamber = argb(255, 38, 198, 218),
            note = argb(255, 236, 239, 241),
            loopClosed = argb(255, 102, 187, 106),
            reorient = argb(255, 255, 112, 67),
            cloud = argb(80, 176, 190, 197),
            // Blue-tinted so the floor reads as part of the navy canvas rather than a grey mesh over it.
            gridMinor = argb(48, 90, 150, 230),
            gridMajor = argb(104, 100, 165, 245),
            axisEast = argb(255, 229, 57, 53),
            axisNorth = argb(255, 67, 160, 71),
            axisUp = argb(255, 66, 165, 245),
            northArrow = argb(255, 255, 193, 7),
            pathEmphasis = PathEmphasis.GLOW,
            casing = TRANSPARENT,
            markerRing = rgb(WHITE),
            markerOutline = TRANSPARENT,
            selectionRing = rgb(WHITE),
            label = rgb(0xEAF2FF),
            labelShadow = rgb(0x000000),
            labelHalo = TRANSPARENT,
            plotBackground = TRANSPARENT,
            // The dark scheme's outlineVariant, which the chart drew its ticks in before it had a palette.
            chartGrid = rgb(0x1E3A5F),
            surveyMark = argb(255, 255, 202, 40),
            surveyCorner = argb(255, 207, 216, 220),
            surveyUser = argb(255, 38, 198, 218),
            chord = argb(255, 255, 255, 255),
            chordUnderlay = argb(0x99, 0, 0, 0),
            stretch = argb(200, 255, 235, 59),
            cursor = argb(255, 255, 64, 129),
            surveyLabel = argb(230, 255, 255, 255),
            // Dark on every station fill, each light enough for 4.5:1 under it.
            stationOrder = rgb(0x0A1424),
        )

        /**
         * The light canvas: a very light grey-blue that a hairline border sets off from the white cards. The dark
         * palette's yellows, cyans and near-whites fall far below 3:1 on it, so every hue here is a darker tone of
         * the dark palette's, and yellow becomes an ochre.
         */
        val Light = CanvasPalette(
            isLight = true,
            background = rgb(0xEEF2F7),
            // 3.2 to 6.0:1 on the background.
            progressStops = intArrayOf(
                rgb(0x1D4ED8),
                rgb(0x0891B2),
                rgb(0x059669),
                rgb(0xA16207),
                rgb(0xEA580C),
                rgb(0xDC2626),
            ),
            timeStops = intArrayOf(
                rgb(0x1565C0),
                rgb(0x00838F),
                rgb(0x2E7D32),
                rgb(0xA16207),
                rgb(0xC62828),
            ),
            altitudeStops = intArrayOf(
                rgb(0x5E35B1),
                rgb(0x1565C0),
                rgb(0x2E7D32),
                rgb(0xC2410C),
                rgb(0xA16207),
            ),
            sourcePdr = rgb(0xC2410C),
            sourceVio = rgb(0x0E7490),
            sourceInterpolated = rgb(0x64748B),
            start = rgb(0x15803D),
            end = rgb(0xDC2626),
            keyframe = rgb(0x2563EB),
            // The dark palette's amber waypoint and near-white note would all but vanish here.
            waypoint = rgb(0xA16207),
            junction = rgb(0x7E22CE),
            chamber = rgb(0x0E7490),
            note = rgb(0x475569),
            loopClosed = rgb(0x047857),
            reorient = rgb(0xC2410C),
            cloud = argb(110, 0x47, 0x55, 0x69),
            // Slate, about as faint against the light canvas as the dark grid is against the navy.
            gridMinor = argb(56, 0x64, 0x74, 0x8B),
            gridMajor = argb(136, 0x64, 0x74, 0x8B),
            // At least 4.5:1, because the axis letters are drawn in these colours too.
            axisEast = rgb(0xC62828),
            axisNorth = rgb(0x2E7D32),
            axisUp = rgb(0x1565C0),
            northArrow = rgb(0xAD5008),
            pathEmphasis = PathEmphasis.CASING,
            casing = rgb(WHITE),
            markerRing = rgb(WHITE),
            markerOutline = argb(0x99, 0x0F, 0x17, 0x2A),
            selectionRing = rgb(0x0F172A),
            label = rgb(0x0F172A),
            labelShadow = TRANSPARENT,
            labelHalo = rgb(WHITE),
            plotBackground = rgb(0xEEF2F7),
            chartGrid = argb(96, 0x64, 0x74, 0x8B),
            surveyMark = rgb(0xA16207),
            surveyCorner = rgb(0x475569),
            surveyUser = rgb(0x0E7490),
            // Dark chords on a white underlay, the dark palette's pair turned over.
            chord = rgb(0x0F172A),
            chordUnderlay = argb(0xCC, 0xFF, 0xFF, 0xFF),
            // A highlighter band: saturated amber, translucent so the path shows through it.
            stretch = argb(166, 0xF5, 0x9E, 0x0B),
            cursor = rgb(0xC2185B),
            surveyLabel = rgb(0x0F172A),
            // White on every station fill, each dark enough for 4.5:1 under it.
            stationOrder = rgb(WHITE),
        )
    }
}
