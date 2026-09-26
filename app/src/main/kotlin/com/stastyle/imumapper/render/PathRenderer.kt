package com.stastyle.imumapper.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.PointMode
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

/**
 * Draws a [ProjectedScene] onto a Compose Canvas. Nothing here allocates per primitive: the
 * projected scene already holds pixel coordinates in flat arrays, colours are ARGB ints turned into
 * the inline [Color] class, and the point cloud goes to the native canvas in one call.
 */
object PathRenderer {
    /** Canvas background; fixed dark so the colour ramps read the same in both themes. */
    val BACKGROUND = Color(0xFF10151B)

    const val HIT_RADIUS_DP = 24f
    private const val MARKER_RADIUS_DP = 7f
    private const val SELECTED_RADIUS_DP = 11f
    private const val CLOUD_POINT_DP = 2.5f

    private val ringColor = Color.White
    private val selectionColor = Color(0xFFFFFFFF)

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
                    drawCircle(selectionColor, radius + 4f.dp.toPx(), center, style = Stroke(width = ring))
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
        }
    }

    /**
     * Where the text of a label anchored at ([x], [y]) is drawn, or null when it is not drawn: well
     * off the canvas, or past its right or bottom edge. The last case is not only a waste: drawText
     * lays the text out in the space between its origin and the canvas edge, and an origin past the
     * edge makes that space negative, which Compose rejects with an exception and the app dies. A
     * label ends up there whenever an orbit or pan carries an axis letter or the grid text within a
     * few pixels of the right edge, which a drag does within seconds. The test is written as one
     * positive condition so a NaN coordinate fails it as well.
     */
    fun labelOrigin(x: Float, y: Float, canvasWidthPx: Float, canvasHeightPx: Float): Offset? {
        val tx = x + LABEL_DX_PX
        val ty = y + LABEL_DY_PX
        val drawn = tx > -LABEL_CULL_LEFT_PX && ty > -LABEL_CULL_TOP_PX && tx < canvasWidthPx && ty < canvasHeightPx
        return if (drawn) Offset(tx, ty) else null
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
