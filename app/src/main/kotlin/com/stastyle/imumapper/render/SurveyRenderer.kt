package com.stastyle.imumapper.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * their names last, so a selection never hides what was selected.
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
    private const val SELECTED_RADIUS_DP = 10f
    private const val RING_DP = 1.5f
    private const val SELECTED_RING_DP = 2.5f
    private const val NAME_MAX_WIDTH_DP = 160f

    private val stretchColor = Color(SurveyColors.STRETCH)
    private val chordColor = Color(SurveyColors.CHORD)

    /** A dark line under each dashed chord keeps it readable on the yellow stretch and the pale grid. */
    private val chordUnderlayColor = Color(0x99000000)
    private val cursorColor = Color(SurveyColors.CURSOR)
    private val ringColor = Color.White
    private val nameStyle = TextStyle(
        color = Color(SurveyColors.LABEL),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        shadow = Shadow(color = Color.Black, offset = Offset(0f, 1f), blurRadius = 3f),
    )

    /** Dark on the station's own colour: every station colour is light enough for it. */
    private val orderStyle = TextStyle(color = PathRenderer.BACKGROUND, fontSize = 11.sp, fontWeight = FontWeight.Bold)

    /** Draws stretch, chords, cursor, stations and names over the scene; call after update(). */
    fun DrawScope.drawSurvey(projected: ProjectedSurvey, textMeasurer: TextMeasurer?) {
        drawStretch(projected)
        drawChords(projected)
        drawCursor(projected)
        drawStations(projected)
        if (textMeasurer != null) drawStationText(projected, textMeasurer)
    }

    private fun DrawScope.drawStretch(projected: ProjectedSurvey) {
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

    private fun DrawScope.drawChords(projected: ProjectedSurvey) {
        val count = projected.layer.chordCount
        if (count == 0) return
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

    private fun DrawScope.drawCursor(projected: ProjectedSurvey) {
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

    private fun DrawScope.drawStations(projected: ProjectedSurvey) {
        val stations = projected.layer.stations
        val screen = projected.stationScreen
        for (i in stations.indices) {
            if (!projected.stationVisible[i]) continue
            val station = stations[i]
            val center = Offset(screen[i * 2], screen[i * 2 + 1])
            val radius = radiusOf(station.selected)
            val ring = (if (station.selected) SELECTED_RING_DP else RING_DP).dp.toPx()
            drawCircle(Color(station.color), radius, center)
            drawCircle(ringColor, radius, center, style = Stroke(width = ring))
        }
    }

    /**
     * Names go through PathRenderer.labelOrigin like the scene's labels, so drawText never gets an
     * origin past the right or bottom edge. The chain number is measured first and drawn from that
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
    private fun DrawScope.drawStationText(projected: ProjectedSurvey, textMeasurer: TextMeasurer) {
        val stations = projected.layer.stations
        val screen = projected.stationScreen
        for (i in stations.indices) {
            if (!projected.stationVisible[i]) continue
            val text = stations[i].text ?: continue
            val x = screen[i * 2]
            val y = screen[i * 2 + 1]
            if (text.order != null && x >= 0f && y >= 0f && x < size.width && y < size.height) {
                val layout = textMeasurer.measure(text.order, orderStyle)
                drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f))
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
            drawText(name, topLeft = origin)
        }
    }

    private fun DrawScope.radiusOf(selected: Boolean): Float =
        (if (selected) SELECTED_RADIUS_DP else STATION_RADIUS_DP).dp.toPx()
}
