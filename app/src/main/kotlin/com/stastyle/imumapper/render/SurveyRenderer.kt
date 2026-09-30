package com.stastyle.imumapper.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Draws a [ProjectedSurvey] over the path scene with the same camera. The stretch goes under
 * everything so the path shows through it, the chords and the cursor over it, and the stations and
 * their names last, so a selection never hides what was selected. Colours come from the same
 * [CanvasPalette] the scene under it was drawn with.
 */
object SurveyRenderer {
    private const val STRETCH_WIDTH_DP = 10f
    private const val CHORD_WIDTH_DP = 2f
    private const val CHORD_UNDERLAY_DP = 4f
    private const val CHORD_DASH_DP = 8f
    private const val CHORD_GAP_DP = 6f
    private const val CURSOR_RADIUS_DP = 11f
    private const val CURSOR_ARM_DP = 16f
    private const val CURSOR_WIDTH_DP = 2f
    private const val STATION_RADIUS_DP = 6f
    private const val RING_DP = 1.5f
    private const val OUTLINE_DP = 1f
    private const val NAME_MAX_WIDTH_DP = 160f

    // Internal so the tests fit chain numbers into the dot actually drawn: every chain station is ringed.
    internal const val SELECTED_RADIUS_DP = 10f
    internal const val SELECTED_RING_DP = 2.5f

    private val nameStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium)

    /**
     * A station's place in the chain, on the station's own fill: dark on the dark palette's light fills, white on the
     * light palette's dark ones (CanvasPalette.stationOrder).
     */
    private val orderStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold)

    /** Draws stretch, chords, cursor, stations and names over the scene; call after update(). */
    fun DrawScope.drawSurvey(projected: ProjectedSurvey, textMeasurer: TextMeasurer?, palette: CanvasPalette) {
        drawStretch(projected, Color(palette.stretch))
        drawChords(projected, palette)
        drawCursor(projected, Color(palette.cursor))
        drawStations(projected, palette)
        if (textMeasurer != null) drawStationText(projected, textMeasurer, palette)
    }

    private fun DrawScope.drawStretch(projected: ProjectedSurvey, stretchColor: Color) {
        val count = projected.layer.stretchCount
        if (count < 2) return
        val screen = projected.stretchScreen
        // One path rather than a line per segment: the band is translucent, and overlapping segment
        // ends would show as darker beads at every vertex.
        val path = Path()
        var open = false
        for (i in 0 until count) {
            if (!projected.stretchVisible[i]) {
                open = false
                continue
            }
            val x = screen[i * 2]
            val y = screen[i * 2 + 1]
            if (open) path.lineTo(x, y) else path.moveTo(x, y)
            open = true
        }
        drawPath(
            path = path,
            color = stretchColor,
            style = Stroke(width = STRETCH_WIDTH_DP.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    /**
     * Each chord is a dashed line over a wider solid one in the palette's underlay colour, dark under the dark
     * palette's white chords and white under the light palette's dark ones, which keeps it readable across the
     * stretch, the path and the grid.
     */
    private fun DrawScope.drawChords(projected: ProjectedSurvey, palette: CanvasPalette) {
        val count = projected.layer.chordCount
        if (count == 0) return
        val chordColor = Color(palette.chord)
        val chordUnderlayColor = Color(palette.chordUnderlay)
        val screen = projected.chordScreen
        val width = CHORD_WIDTH_DP.dp.toPx()
        val underlay = CHORD_UNDERLAY_DP.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(CHORD_DASH_DP.dp.toPx(), CHORD_GAP_DP.dp.toPx()))
        for (i in 0 until count) {
            if (!projected.chordVisible[i]) continue
            val o = i * 4
            val a = Offset(screen[o], screen[o + 1])
            val b = Offset(screen[o + 2], screen[o + 3])
            drawLine(chordUnderlayColor, a, b, strokeWidth = underlay, cap = StrokeCap.Round)
            drawLine(chordColor, a, b, strokeWidth = width, pathEffect = dash)
        }
    }

    private fun DrawScope.drawCursor(projected: ProjectedSurvey, cursorColor: Color) {
        if (!projected.cursorVisible) return
        val x = projected.cursorScreen[0]
        val y = projected.cursorScreen[1]
        val width = CURSOR_WIDTH_DP.dp.toPx()
        val arm = CURSOR_ARM_DP.dp.toPx()
        drawCircle(cursorColor, CURSOR_RADIUS_DP.dp.toPx(), Offset(x, y), style = Stroke(width = width))
        // The cross reaches past the ring, so the exact spot still shows when a station is drawn over it.
        drawLine(cursorColor, Offset(x - arm, y), Offset(x + arm, y), strokeWidth = width)
        drawLine(cursorColor, Offset(x, y - arm), Offset(x, y + arm), strokeWidth = width)
    }

    /** Rings and outlines as on the scene's markers, so a station reads as the marker it stands in for. */
    private fun DrawScope.drawStations(projected: ProjectedSurvey, palette: CanvasPalette) {
        val stations = projected.layer.stations
        val screen = projected.stationScreen
        val ringColor = Color(palette.markerRing)
        val outlined = SceneColors.alpha(palette.markerOutline) != 0
        val outlineColor = Color(palette.markerOutline)
        val outline = OUTLINE_DP.dp.toPx()
        for (i in stations.indices) {
            if (!projected.stationVisible[i]) continue
            val station = stations[i]
            val center = Offset(screen[i * 2], screen[i * 2 + 1])
            val radius = radiusOf(station.selected)
            val ring = ringOf(station.selected)
            drawCircle(Color(palette.forStation(station.kind)), radius, center)
            drawCircle(ringColor, radius, center, style = Stroke(width = ring))
            if (outlined) {
                drawCircle(outlineColor, radius + ring / 2f + outline / 2f, center, style = Stroke(width = outline))
            }
        }
    }

    /**
     * Names and chain numbers go through PathRenderer.labelOrigin like the scene's labels, so drawText
     * never gets an origin past the right or bottom edge. Names are drawn with PathRenderer.drawCanvasText,
     * so they get the palette's halo or shadow. The chain number is measured first and drawn from that
     * layout, which lays nothing out against the canvas edge; it is drawn only while the station's
     * centre is on the canvas, a positive test that a NaN fails as well. Text is drawn after every
     * circle so no station covers another one's name. Stations on one spot (End on Start after a
     * closed loop) draw one SpotText between them, so their names and numbers never overprint.
     *
     * A mark station is named after its note, which the recorder lets run to several lines, so a
     * name is cut to one line of at most [NAME_MAX_WIDTH_DP] with an ellipsis; drawn whole it would be
     * a block of text over the path and the other stations. The width is a maximum, not a fixed box:
     * in a fixed box a right-to-left name would be pushed to its far end, away from the station.
     */
    @OptIn(ExperimentalTextApi::class)
    private fun DrawScope.drawStationText(
        projected: ProjectedSurvey,
        textMeasurer: TextMeasurer,
        palette: CanvasPalette,
    ) {
        val stations = projected.layer.stations
        val screen = projected.stationScreen
        val orderColor = Color(palette.stationOrder)
        val nameColor = Color(palette.surveyLabel)
        val nameShadow = PathRenderer.labelShadow(palette)
        for (i in stations.indices) {
            if (!projected.stationVisible[i]) continue
            val text = stations[i].text ?: continue
            val x = screen[i * 2]
            val y = screen[i * 2 + 1]
            if (text.order != null && x >= 0f && y >= 0f && x < size.width && y < size.height) {
                val layout = textMeasurer.measure(text.order, orderStyle)
                val w = layout.size.width.toFloat()
                val h = layout.size.height.toFloat()
                val origin = PathRenderer.labelOrigin(x, y, size.width, size.height, dx = -w / 2f, dy = -h / 2f)
                if (origin != null) {
                    // On the station's own fill, which is its contrast: no halo, which would cover the dot. Shrunk
                    // about the centre to stay off the ring, which the light palette draws white like the number.
                    val fill = radiusOf(text.selected) - ringOf(text.selected) / 2f
                    scale(chainNumberScale(w, h, fill), Offset(x, y)) {
                        drawText(layout, orderColor, origin, shadow = Shadow.None, drawStyle = Fill)
                    }
                }
            }
            val radius = radiusOf(text.selected)
            val origin = PathRenderer.labelOrigin(x + radius, y, size.width, size.height) ?: continue
            val name = textMeasurer.measure(
                text = text.names,
                style = nameStyle,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                constraints = Constraints(maxWidth = NAME_MAX_WIDTH_DP.dp.roundToPx()),
            )
            with(PathRenderer) { drawCanvasText(name, origin, nameColor, nameShadow, palette) }
        }
    }

    private fun DrawScope.radiusOf(selected: Boolean): Float =
        (if (selected) SELECTED_RADIUS_DP else STATION_RADIUS_DP).dp.toPx()

    /** The ring's stroke width, centred on [radiusOf]. */
    private fun DrawScope.ringOf(selected: Boolean): Float = (if (selected) SELECTED_RING_DP else RING_DP).dp.toPx()
}
