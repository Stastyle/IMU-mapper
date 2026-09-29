package com.stastyle.imumapper.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * Draws a [ProjectedScene] onto a Compose Canvas. Nothing here allocates per primitive: the
 * projected scene already holds pixel coordinates in flat arrays, colours are ARGB ints turned into
 * the inline [Color] class, and the point cloud goes to the native canvas in one call.
 */
object PathRenderer {
    /**
     * Canvas background; fixed dark so the colour ramps read the same in both themes. The survey layer writes
     * station order numbers in this colour on the stations' light fills, so it has to stay dark.
     */
    val BACKGROUND = Color(0xFF0A1424)

    const val HIT_RADIUS_DP = 24f

    /** Start and End markers closer than this on screen share one "Start / End" label; see [mergeEndLabels]. */
    const val END_LABEL_MERGE_DP = 24f

    private const val MARKER_RADIUS_DP = 7f
    private const val SELECTED_RADIUS_DP = 11f
    private const val SELECTION_RING_GAP_DP = 4f
    private const val CLOUD_POINT_DP = 2.5f

    /** The main path's glow: a stroke this many times the line's width, in the line's colour at this opacity. */
    private const val GLOW_WIDTH_FACTOR = 3f
    private const val GLOW_ALPHA = 0.22f

    /** Space between the edge of a Start or End marker and its label. */
    private const val END_LABEL_GAP_DP = 4f

    private val ringColor = Color.White
    private val selectionColor = Color(0xFFFFFFFF)
    private val endLabelStyle = TextStyle(
        color = Color(0xFFEAF2FF),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        shadow = Shadow(color = Color.Black, offset = Offset(0f, 1f), blurRadius = 3f),
    )

    /**
     * [paint] is reused across frames for the point cloud; create it once with `remember { Paint() }`.
     * [selectedMarker] is the index in `scene.markers` to highlight, or -1.
     */
    @OptIn(ExperimentalTextApi::class)
    fun DrawScope.drawScene(
        projected: ProjectedScene,
        textMeasurer: TextMeasurer?,
        selectedMarker: Int,
        paint: Paint,
    ) {
        val scene = projected.scene
        drawRect(BACKGROUND, Offset.Zero, Size(size.width, size.height))

        if (projected.cloudVisibleCount > 0) {
            paint.color = Color(scene.cloudColor)
            paint.strokeWidth = CLOUD_POINT_DP.dp.toPx()
            paint.strokeCap = StrokeCap.Round
            paint.style = PaintingStyle.Stroke
            drawContext.canvas.drawRawPoints(PointMode.Points, projected.cloudScreen, paint)
        }

        drawGlow(projected)

        val lineScreen = projected.lineScreen
        val markerScreen = projected.markerScreen
        val markerRadius = MARKER_RADIUS_DP.dp.toPx()
        val selectedRadius = SELECTED_RADIUS_DP.dp.toPx()
        val ring = 1.5f.dp.toPx()
        val px = density
        // Far to near so nearer segments and markers paint over farther ones.
        for (k in projected.orderCount - 1 downTo 0) {
            val item = projected.orderItem(k)
            if (projected.isLineItem(item)) {
                val s = item * 4
                drawLine(
                    color = Color(scene.lineColors[item]),
                    start = Offset(lineScreen[s], lineScreen[s + 1]),
                    end = Offset(lineScreen[s + 2], lineScreen[s + 3]),
                    strokeWidth = projected.lineWidth[item] * px,
                    cap = StrokeCap.Round,
                )
            } else {
                val m = item - projected.lineCount
                val marker = scene.markers[m]
                val center = Offset(markerScreen[m * 2], markerScreen[m * 2 + 1])
                val selected = m == selectedMarker
                val radius = if (selected) selectedRadius else markerRadius
                if (marker.kind == MarkerKind.KEYFRAME) {
                    val half = radius * 0.9f
                    drawRect(Color(marker.color), Offset(center.x - half, center.y - half), Size(half * 2, half * 2))
                    drawRect(
                        ringColor, Offset(center.x - half, center.y - half), Size(half * 2, half * 2),
                        style = Stroke(width = ring),
                    )
                } else {
                    drawCircle(Color(marker.color), radius, center)
                    drawCircle(ringColor, radius, center, style = Stroke(width = ring))
                }
                if (selected) {
                    val selectionRadius = radius + SELECTION_RING_GAP_DP.dp.toPx()
                    drawCircle(selectionColor, selectionRadius, center, style = Stroke(width = ring))
                }
            }
        }

        if (textMeasurer != null) {
            val labelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)
            for (i in 0 until projected.labelCount) {
                if (!projected.labelVisible[i]) continue
                val label = scene.labels[i]
                val origin = labelOrigin(
                    projected.labelScreen[i * 2], projected.labelScreen[i * 2 + 1], size.width, size.height,
                ) ?: continue
                drawText(
                    textMeasurer = textMeasurer,
                    text = label.text,
                    topLeft = origin,
                    style = labelStyle.copy(color = Color(label.color)),
                )
            }
            drawEndLabels(projected, textMeasurer, selectedMarker)
        }
    }

    /**
     * A wide, faint stroke under each visible segment of the main path. All of them go down before any line, so the
     * glow never veils a stretch of path it crosses. Butt caps, because the segments of a long path are often
     * shorter on screen than the glow is wide, and round caps would stack their overlaps into a solid band.
     */
    private fun DrawScope.drawGlow(projected: ProjectedScene) {
        val scene = projected.scene
        val lineScreen = projected.lineScreen
        val px = density
        for (i in scene.pathLineStart until scene.pathLineEnd) {
            if (!projected.lineVisible[i]) continue
            val s = i * 4
            drawLine(
                color = Color(scene.lineColors[i]),
                start = Offset(lineScreen[s], lineScreen[s + 1]),
                end = Offset(lineScreen[s + 2], lineScreen[s + 3]),
                strokeWidth = projected.lineWidth[i] * px * GLOW_WIDTH_FACTOR,
                cap = StrokeCap.Butt,
                alpha = GLOW_ALPHA,
            )
        }
    }

    /**
     * "Start" and "End" beside their markers, or one "Start / End" when the two nearly coincide on screen. Found in
     * the scene's markers, so the labels go wherever the markers go (markers switched off, Survey mode).
     */
    private fun DrawScope.drawEndLabels(projected: ProjectedScene, textMeasurer: TextMeasurer, selectedMarker: Int) {
        val markers = projected.scene.markers
        var start = -1
        var end = -1
        for (i in markers.indices) {
            if (!projected.markerVisible[i]) continue
            when (markers[i].kind) {
                MarkerKind.START -> start = i
                MarkerKind.END -> end = i
                else -> Unit
            }
        }
        val s = projected.markerScreen
        val merged = start >= 0 && end >= 0 &&
            mergeEndLabels(s[start * 2], s[start * 2 + 1], s[end * 2], s[end * 2 + 1], END_LABEL_MERGE_DP.dp.toPx())
        if (merged) {
            // Right of whichever marker is further right and level with their middle, so the text clears both.
            val clearance = max(endLabelClearance(start, selectedMarker), endLabelClearance(end, selectedMarker))
            val x = max(s[start * 2], s[end * 2])
            val y = (s[start * 2 + 1] + s[end * 2 + 1]) / 2f
            drawEndLabel(textMeasurer, "Start / End", x, y, clearance)
            return
        }
        if (start >= 0) {
            val clearance = endLabelClearance(start, selectedMarker)
            drawEndLabel(textMeasurer, "Start", s[start * 2], s[start * 2 + 1], clearance)
        }
        if (end >= 0) {
            val clearance = endLabelClearance(end, selectedMarker)
            drawEndLabel(textMeasurer, "End", s[end * 2], s[end * 2 + 1], clearance)
        }
    }

    /** Pixels from a marker's centre to where its label may start: past the marker and any selection ring. */
    private fun DrawScope.endLabelClearance(marker: Int, selectedMarker: Int): Float {
        val radiusDp = if (marker == selectedMarker) SELECTED_RADIUS_DP + SELECTION_RING_GAP_DP else MARKER_RADIUS_DP
        return (radiusDp + END_LABEL_GAP_DP).dp.toPx()
    }

    /** Draws [text] to the right of the marker at ([x], [y]), centred on it vertically. */
    private fun DrawScope.drawEndLabel(textMeasurer: TextMeasurer, text: String, x: Float, y: Float, clearance: Float) {
        // Measured without a width limit, so the layout is reused across frames and never wraps at the canvas edge.
        val layout = textMeasurer.measure(text, endLabelStyle)
        val origin = labelOrigin(x, y, size.width, size.height, dx = clearance, dy = -layout.size.height / 2f)
            ?: return
        drawText(layout, topLeft = origin)
    }

    /**
     * Where the text of a label anchored at ([x], [y]) is drawn, or null when it is not drawn: well
     * off the canvas, or past its right or bottom edge. The last case is not only a waste: drawText
     * lays the text out in the space between its origin and the canvas edge, and an origin past the
     * edge makes that space negative, which Compose rejects with an exception and the app dies. A
     * label ends up there whenever an orbit or pan carries an axis letter within a few pixels of the
     * right edge, which a drag does within seconds. The test is written as one
     * positive condition so a NaN coordinate fails it as well.
     *
     * [dx] and [dy] put the text's top-left corner relative to the anchor; by default a little right of it and
     * above it.
     */
    fun labelOrigin(
        x: Float,
        y: Float,
        canvasWidthPx: Float,
        canvasHeightPx: Float,
        dx: Float = LABEL_DX_PX,
        dy: Float = LABEL_DY_PX,
    ): Offset? {
        val tx = x + dx
        val ty = y + dy
        val drawn = tx > -LABEL_CULL_LEFT_PX && ty > -LABEL_CULL_TOP_PX && tx < canvasWidthPx && ty < canvasHeightPx
        return if (drawn) Offset(tx, ty) else null
    }

    /**
     * Whether the Start and End labels become one "Start / End" label: when the two markers are within
     * [thresholdPx] of each other on screen, as at the ends of every closed loop, two labels would print over each
     * other. A NaN coordinate never merges.
     */
    fun mergeEndLabels(startX: Float, startY: Float, endX: Float, endY: Float, thresholdPx: Float): Boolean {
        val dx = endX - startX
        val dy = endY - startY
        return dx * dx + dy * dy <= thresholdPx * thresholdPx
    }

    /** Marker index under the touch within [HIT_RADIUS_DP] (scaled by [density]), or -1. */
    fun hitTest(projected: ProjectedScene, xPx: Float, yPx: Float, density: Float): Int =
        projected.hitTestMarker(xPx, yPx, HIT_RADIUS_DP * density)

    /** Text origin relative to the label's world anchor: a little right of it and above it. */
    private const val LABEL_DX_PX = 4f
    private const val LABEL_DY_PX = -8f
    /** How far past the left and top edges a label may start and still show its tail. */
    private const val LABEL_CULL_LEFT_PX = 200f
    private const val LABEL_CULL_TOP_PX = 50f
}
