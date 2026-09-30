package com.stastyle.imumapper.render

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stastyle.imumapper.ui.theme.imuColors
import kotlin.math.max

/**
 * Draws a [ProjectedScene] onto a Compose Canvas. Nothing here allocates per primitive: the
 * projected scene already holds pixel coordinates in flat arrays, colours are ARGB ints turned into
 * the inline [Color] class, and the point cloud goes to the native canvas in one call. The scene
 * carries its own line, marker and label colours; the [CanvasPalette] passed to [drawScene] must be
 * the one it was built with, and gives the background, the rings and the text around them.
 */
object PathRenderer {
    const val HIT_RADIUS_DP = 24f

    /** Start and End markers closer than this on screen share one "Start / End" label; see [mergeEndLabels]. */
    const val END_LABEL_MERGE_DP = 24f

    private const val MARKER_RADIUS_DP = 7f
    private const val SELECTED_RADIUS_DP = 11f
    private const val SELECTION_RING_GAP_DP = 4f
    private const val MARKER_RING_DP = 1.5f
    private const val MARKER_OUTLINE_DP = 1f
    private const val CLOUD_POINT_DP = 2.5f

    /** The main path's glow: a stroke this many times the line's width, in the line's colour at this opacity. */
    private const val GLOW_WIDTH_FACTOR = 3f
    private const val GLOW_ALPHA = 0.22f

    /**
     * The main path's casing: a stroke this many times the line's width, so it thins with distance as the line
     * does and a far stretch is not buried in white.
     */
    private const val CASING_WIDTH_FACTOR = 1.8f

    /** The width of the stroke round each letter when the palette has a halo; half of it shows outside the glyph. */
    private const val HALO_WIDTH_DP = 3f

    /** Space between the edge of a Start or End marker and its label. */
    private const val END_LABEL_GAP_DP = 4f

    /** The Start and End labels' style in [palette]; the shadow only where the palette has one. */
    private fun endLabelStyle(palette: CanvasPalette): TextStyle = TextStyle(
        color = Color(palette.label),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        shadow = labelShadow(palette),
    )

    /** The soft shadow under the Start and End labels and the station names, or null when [palette] has none. */
    internal fun labelShadow(palette: CanvasPalette): Shadow? =
        if (SceneColors.alpha(palette.labelShadow) == 0) {
            null
        } else {
            Shadow(color = Color(palette.labelShadow), offset = Offset(0f, 1f), blurRadius = 3f)
        }

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
        palette: CanvasPalette,
    ) {
        val scene = projected.scene
        drawRect(Color(palette.background), Offset.Zero, Size(size.width, size.height))

        if (projected.cloudVisibleCount > 0) {
            paint.color = Color(scene.cloudColor)
            paint.strokeWidth = CLOUD_POINT_DP.dp.toPx()
            paint.strokeCap = StrokeCap.Round
            paint.style = PaintingStyle.Stroke
            drawContext.canvas.drawRawPoints(PointMode.Points, projected.cloudScreen, paint)
        }

        when (palette.pathEmphasis) {
            PathEmphasis.GLOW -> drawGlow(projected)
            PathEmphasis.CASING -> drawCasing(projected, Color(palette.casing))
        }

        val lineScreen = projected.lineScreen
        val markerScreen = projected.markerScreen
        val markerRadius = MARKER_RADIUS_DP.dp.toPx()
        val selectedRadius = SELECTED_RADIUS_DP.dp.toPx()
        val ring = MARKER_RING_DP.dp.toPx()
        val ringColor = Color(palette.markerRing)
        val outlined = SceneColors.alpha(palette.markerOutline) != 0
        val outlineColor = Color(palette.markerOutline)
        val outline = MARKER_OUTLINE_DP.dp.toPx()
        // Centred just outside the ring, so it hugs the ring without covering it.
        val outlineGap = ring / 2f + outline / 2f
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
                    if (outlined) {
                        val o = half + outlineGap
                        drawRect(
                            outlineColor, Offset(center.x - o, center.y - o), Size(o * 2, o * 2),
                            style = Stroke(width = outline),
                        )
                    }
                } else {
                    drawCircle(Color(marker.color), radius, center)
                    drawCircle(ringColor, radius, center, style = Stroke(width = ring))
                    if (outlined) drawCircle(outlineColor, radius + outlineGap, center, style = Stroke(width = outline))
                }
                if (selected) {
                    val selectionRadius = radius + SELECTION_RING_GAP_DP.dp.toPx()
                    drawCircle(Color(palette.selectionRing), selectionRadius, center, style = Stroke(width = ring))
                }
            }
        }

        if (textMeasurer != null) {
            val labelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)
            val haloed = hasHalo(palette)
            for (i in 0 until projected.labelCount) {
                if (!projected.labelVisible[i]) continue
                val label = scene.labels[i]
                val origin = labelOrigin(
                    projected.labelScreen[i * 2], projected.labelScreen[i * 2 + 1], size.width, size.height,
                ) ?: continue
                if (haloed) {
                    // Measured once and drawn twice, halo and then letters; see drawCanvasText.
                    val layout = textMeasurer.measure(label.text, labelStyle)
                    drawCanvasText(layout, origin, Color(label.color), null, palette)
                } else {
                    drawText(
                        textMeasurer = textMeasurer,
                        text = label.text,
                        topLeft = origin,
                        style = labelStyle.copy(color = Color(label.color)),
                    )
                }
            }
            drawEndLabels(projected, textMeasurer, selectedMarker, palette)
        }
    }

    private fun hasHalo(palette: CanvasPalette): Boolean = SceneColors.alpha(palette.labelHalo) != 0

    /**
     * Draws [layout] at [origin] in [color] with [shadow], over a stroke of the palette's halo colour round each
     * letter when it has one. The caller places [origin] with [labelOrigin], or checks it the same way.
     *
     * Every paint setting is passed rather than left to the layout: a TextMeasurer hands layouts that differ only in
     * colour, shadow or stroke one shared paint, and a setting passed as null keeps whatever the last draw set. The
     * halo's stroke would then fill the letters' next draw, and the dark theme's shadow would outlive a switch to
     * light.
     */
    internal fun DrawScope.drawCanvasText(
        layout: TextLayoutResult,
        origin: Offset,
        color: Color,
        shadow: Shadow?,
        palette: CanvasPalette,
    ) {
        if (hasHalo(palette)) {
            drawText(
                layout,
                color = Color(palette.labelHalo),
                topLeft = origin,
                shadow = Shadow.None,
                drawStyle = Stroke(width = HALO_WIDTH_DP.dp.toPx(), join = StrokeJoin.Round),
            )
        }
        drawText(layout, color = color, topLeft = origin, shadow = shadow ?: Shadow.None, drawStyle = Fill)
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
     * An opaque stroke in [color], wider than the line, under each visible segment of the main path: on a light
     * canvas it lifts the path off the grid and keeps it whole where it crosses the axes or an overlaid run. All of
     * them go down before any line, as the glow does, so where the path crosses itself both stretches stay unbroken.
     * Round caps close the joints; the casing is opaque, so overlapping caps cannot stack into a darker band.
     */
    private fun DrawScope.drawCasing(projected: ProjectedScene, color: Color) {
        val scene = projected.scene
        val lineScreen = projected.lineScreen
        val px = density
        for (i in scene.pathLineStart until scene.pathLineEnd) {
            if (!projected.lineVisible[i]) continue
            val s = i * 4
            drawLine(
                color = color,
                start = Offset(lineScreen[s], lineScreen[s + 1]),
                end = Offset(lineScreen[s + 2], lineScreen[s + 3]),
                strokeWidth = projected.lineWidth[i] * px * CASING_WIDTH_FACTOR,
                cap = StrokeCap.Round,
            )
        }
    }

    /**
     * "Start" and "End" beside their markers, or one "Start / End" when the two nearly coincide on screen. Found in
     * the scene's markers, so the labels go wherever the markers go (markers switched off, Survey mode). Each label
     * sits right of its marker unless it would cover the other marker or label; see [endLabelPlacement].
     */
    private fun DrawScope.drawEndLabels(
        projected: ProjectedScene,
        textMeasurer: TextMeasurer,
        selectedMarker: Int,
        palette: CanvasPalette,
    ) {
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
        if (start < 0 && end < 0) return
        val s = projected.markerScreen
        // Measured without a width limit, so the layouts are reused across frames and never wrap at the canvas edge.
        // Measured before placing, because whether a label reaches the other marker depends on its width in sp.
        val style = endLabelStyle(palette)
        val startText = textMeasurer.measure("Start", style)
        val endText = textMeasurer.measure("End", style)
        if (start < 0 || end < 0) {
            if (start >= 0) {
                val clear = endLabelClearance(start, selectedMarker)
                drawEndLabel(startText, s[start * 2], s[start * 2 + 1], clear, palette)
            }
            if (end >= 0) {
                drawEndLabel(endText, s[end * 2], s[end * 2 + 1], endLabelClearance(end, selectedMarker), palette)
            }
            return
        }
        val startX = s[start * 2]
        val startY = s[start * 2 + 1]
        val endX = s[end * 2]
        val endY = s[end * 2 + 1]
        val startClear = endLabelClearance(start, selectedMarker)
        val endClear = endLabelClearance(end, selectedMarker)
        val startW = startText.size.width.toFloat()
        val endW = endText.size.width.toFloat()
        val placement = endLabelPlacement(
            startX = startX,
            startY = startY,
            startReach = endMarkerReach(start, selectedMarker),
            startClear = startClear,
            startW = startW,
            endX = endX,
            endY = endY,
            endReach = endMarkerReach(end, selectedMarker),
            endClear = endClear,
            endW = endW,
            labelH = max(startText.size.height, endText.size.height).toFloat(),
            mergePx = END_LABEL_MERGE_DP.dp.toPx(),
        )
        if (placement == EndLabelPlacement.MERGED) {
            // Right of whichever marker is further right and level with their middle, so the text clears both.
            val merged = textMeasurer.measure("Start / End", style)
            drawEndLabel(merged, max(startX, endX), (startY + endY) / 2f, max(startClear, endClear), palette)
            return
        }
        val startDx = if (placement == EndLabelPlacement.START_LEFT) -(startClear + startW) else startClear
        val endDx = if (placement == EndLabelPlacement.END_LEFT) -(endClear + endW) else endClear
        drawEndLabel(startText, startX, startY, startDx, palette)
        drawEndLabel(endText, endX, endY, endDx, palette)
    }

    /** Pixels from a marker's centre to the edge of the marker, or of its selection ring when it is selected. */
    private fun DrawScope.endMarkerReach(marker: Int, selectedMarker: Int): Float {
        val radiusDp = if (marker == selectedMarker) SELECTED_RADIUS_DP + SELECTION_RING_GAP_DP else MARKER_RADIUS_DP
        return radiusDp.dp.toPx()
    }

    /** Pixels from a marker's centre to where its label may start: past the marker and any selection ring. */
    private fun DrawScope.endLabelClearance(marker: Int, selectedMarker: Int): Float =
        endMarkerReach(marker, selectedMarker) + END_LABEL_GAP_DP.dp.toPx()

    /**
     * Draws [layout] with its left edge [dx] from the marker at ([x], [y]), centred on it vertically. A negative [dx]
     * puts the text left of the marker.
     */
    private fun DrawScope.drawEndLabel(
        layout: TextLayoutResult,
        x: Float,
        y: Float,
        dx: Float,
        palette: CanvasPalette,
    ) {
        val origin = labelOrigin(x, y, size.width, size.height, dx = dx, dy = -layout.size.height / 2f) ?: return
        drawCanvasText(layout, origin, Color(palette.label), labelShadow(palette), palette)
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

    /** Where [endLabelPlacement] puts the Start and End labels. */
    enum class EndLabelPlacement {
        /** Each label right of its own marker. */
        RIGHT,

        /** "Start" left of its marker, "End" right of its own. */
        START_LEFT,

        /** "End" left of its marker, "Start" right of its own. */
        END_LEFT,

        /** One "Start / End" label right of both markers. */
        MERGED,
    }

    /**
     * Where the Start and End labels go, all in pixels. Markers within [mergePx] share one label, as at the ends of
     * every closed loop. Past that, a nearly closed loop can still leave "Start" printed across the End dot and into
     * the "End" label (or the mirror), so a label that would cover the other marker or label moves to the left of its
     * own marker. Labels extend rightwards, so it is the left marker's label that reaches across the other one, and
     * that is the one moved. When the pair still collides, which takes a selection ring and a large font squeezing
     * the labels from both sides, the two merge after all.
     *
     * The reaches are the markers' radii, including the selection ring when selected; the clearances are where each
     * label starts right of its marker (see [endLabelCollides]). A NaN coordinate never collides, so the labels stay
     * right and [labelOrigin] drops the one that cannot be placed.
     */
    fun endLabelPlacement(
        startX: Float,
        startY: Float,
        startReach: Float,
        startClear: Float,
        startW: Float,
        endX: Float,
        endY: Float,
        endReach: Float,
        endClear: Float,
        endW: Float,
        labelH: Float,
        mergePx: Float,
    ): EndLabelPlacement {
        if (mergeEndLabels(startX, startY, endX, endY, mergePx)) return EndLabelPlacement.MERGED
        fun clear(startDx: Float, endDx: Float): Boolean =
            !endLabelCollides(startX, startY, startDx, startW, labelH, endX, endY, endReach, endDx, endW) &&
                !endLabelCollides(endX, endY, endDx, endW, labelH, startX, startY, startReach, startDx, startW)
        if (clear(startClear, endClear)) return EndLabelPlacement.RIGHT
        return if (startX <= endX) {
            if (clear(-(startClear + startW), endClear)) EndLabelPlacement.START_LEFT else EndLabelPlacement.MERGED
        } else {
            if (clear(startClear, -(endClear + endW))) EndLabelPlacement.END_LEFT else EndLabelPlacement.MERGED
        }
    }

    /**
     * Whether one end label covers the other marker or the other marker's label, all in pixels. Both labels are
     * [labelH] tall and centred on their marker's y. A label's left edge sits [selfDx] (or [otherDx]) from its
     * marker's x: the clearance when it is right of the marker, minus clearance and width when it is left of it. The
     * other marker counts as a square [otherReach] from its centre each way, its radius or its selection ring's.
     * Edges that only touch do not collide, and the test is written as positive conditions so a NaN coordinate never
     * collides.
     */
    fun endLabelCollides(
        selfX: Float,
        selfY: Float,
        selfDx: Float,
        selfW: Float,
        labelH: Float,
        otherX: Float,
        otherY: Float,
        otherReach: Float,
        otherDx: Float,
        otherW: Float,
    ): Boolean {
        val left = selfX + selfDx
        val right = left + selfW
        val top = selfY - labelH / 2f
        val bottom = selfY + labelH / 2f
        val coversMarker = left < otherX + otherReach && right > otherX - otherReach &&
            top < otherY + otherReach && bottom > otherY - otherReach
        val otherLeft = otherX + otherDx
        val coversLabel = left < otherLeft + otherW && right > otherLeft &&
            top < otherY + labelH / 2f && bottom > otherY - labelH / 2f
        return coversMarker || coversLabel
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

/**
 * The canvas palette of the theme in use. Everything that draws a map, a thumbnail or a chart takes its colours from
 * this, so they switch with the app's theme together.
 */
@Composable
@ReadOnlyComposable
fun canvasPalette(): CanvasPalette = if (MaterialTheme.imuColors.isLight) CanvasPalette.Light else CanvasPalette.Dark
