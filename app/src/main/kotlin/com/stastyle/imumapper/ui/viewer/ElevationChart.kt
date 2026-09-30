package com.stastyle.imumapper.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.render.ElevationProfile
import com.stastyle.imumapper.render.HeightAxis
import com.stastyle.imumapper.render.PathProfile
import com.stastyle.imumapper.render.PathProgress
import com.stastyle.imumapper.render.SceneColors
import com.stastyle.imumapper.render.canvasPalette
import com.stastyle.imumapper.ui.common.formatDistance
import com.stastyle.imumapper.ui.common.formatHeight
import com.stastyle.imumapper.ui.theme.imuColors
import kotlin.math.roundToInt

/** Space above the top tick and below the bottom one, so a label centred on either still fits. */
private val PlotPadding = 8.dp
private val LineWidth = 2.5.dp

/**
 * Where the plot has a background of its own: its corners, and the room left and right of the line so its round
 * caps stay inside the panel. Without a background the line runs edge to edge, as it always has on the dark card.
 */
private val PlotCorner = 8.dp
private val PlotInset = 6.dp

/**
 * Height against distance walked, coloured by distance like the path on the canvas, around a zero line at the
 * start's height. [profile] keeps each sample's lowest and highest point, so a short peak or dip is drawn as a
 * vertical stroke instead of being averaged away. The y labels are Texts (they follow the font scale), and the
 * whole chart is laid out left to right: distance grows to the right whatever the language. TalkBack reads it
 * as one sentence.
 *
 * The line and the ticks take the canvas palette's colours. The light palette's ramp is tuned for the light
 * canvas, so there the plot sits on a bordered panel of the canvas background and reads like the map; the dark
 * palette has none and the card shows through, as before.
 */
@Composable
internal fun ElevationChart(profile: ElevationProfile, modifier: Modifier = Modifier, plotHeight: Dp = 120.dp) {
    val axis = remember(profile) {
        PathProfile.axis(profile.minZ.minOrNull() ?: 0.0, profile.maxZ.maxOrNull() ?: 0.0)
    }
    val palette = canvasPalette()
    val colors = remember(profile, palette) {
        IntArray(profile.size) { PathProgress.color(profile.fraction(it), palette) }
    }
    val lowest = profile.minZ.minOrNull() ?: 0.0
    val highest = profile.maxZ.maxOrNull() ?: 0.0
    val description = "Elevation profile over ${formatDistance(profile.totalM)}, " +
        "from ${formatHeight(lowest)} to ${formatHeight(highest)} against the start"
    val labelStyle = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = Color(palette.chartGrid)
    val hasPlotBackground = SceneColors.alpha(palette.plotBackground) != 0
    val plotBorder = MaterialTheme.imuColors.cardBorder
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
            Row(modifier = Modifier.fillMaxWidth().height(plotHeight)) {
                AxisLabels(axis, labelStyle, labelColor)
                Spacer(Modifier.width(8.dp))
                Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    val pad = PlotPadding.toPx()
                    if (hasPlotBackground) {
                        // A panel with the hairline border the map has, so it reads as one and not as a hole.
                        val corner = CornerRadius(PlotCorner.toPx())
                        drawRoundRect(Color(palette.plotBackground), cornerRadius = corner)
                        val border = 1.dp.toPx()
                        drawRoundRect(
                            plotBorder,
                            topLeft = Offset(border / 2f, border / 2f),
                            size = Size(size.width - border, size.height - border),
                            cornerRadius = corner,
                            style = Stroke(width = border),
                        )
                    }
                    val inset = if (hasPlotBackground) PlotInset.toPx() else 0f
                    val w = size.width - 2f * inset
                    fun y(z: Double): Float = plotY(z, axis, size.height, pad)
                    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                    for (tick in axis.ticks) {
                        val ty = y(tick)
                        drawLine(
                            gridColor,
                            Offset(inset, ty),
                            Offset(inset + w, ty),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = if (tick == 0.0) null else dash,
                        )
                    }
                    val n = profile.size
                    if (n == 0 || profile.totalM <= 0.0) return@Canvas
                    fun x(i: Int): Float = inset + (profile.distanceM[i] / profile.totalM * w).toFloat()
                    fun mid(i: Int): Double = (profile.minZ[i] + profile.maxZ[i]) / 2.0
                    val stroke = LineWidth.toPx()
                    for (i in 1 until n) {
                        drawLine(
                            Color(colors[i - 1]),
                            Offset(x(i - 1), y(mid(i - 1))),
                            Offset(x(i), y(mid(i))),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                    for (i in 0 until n) {
                        if (profile.maxZ[i] <= profile.minZ[i]) continue
                        drawLine(
                            Color(colors[i]),
                            Offset(x(i), y(profile.minZ[i])),
                            Offset(x(i), y(profile.maxZ[i])),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
            // The distance scale's two ends, under the plot.
            Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Spacer(Modifier.weight(1f))
                Text(formatDistance(profile.totalM), style = labelStyle, color = labelColor)
            }
        }
    }
}

/**
 * The tick labels, each centred on its tick (and kept inside the plot's height), right-aligned against the plot.
 * A Layout rather than a Column, because the ticks sit where the axis puts them, not at even text rows.
 */
@Composable
private fun AxisLabels(axis: HeightAxis, style: TextStyle, color: Color) {
    val padPx = with(LocalDensity.current) { PlotPadding.toPx() }
    Layout(
        content = { for (tick in axis.ticks) Text(axisLabel(tick), style = style, color = color, maxLines = 1) },
        modifier = Modifier.fillMaxHeight(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = placeables.maxOfOrNull { it.width } ?: 0
        val height = constraints.maxHeight
        layout(width, height) {
            placeables.forEachIndexed { i, p ->
                val centre = plotY(axis.ticks[i], axis, height.toFloat(), padPx)
                val top = (centre - p.height / 2f).roundToInt().coerceIn(0, (height - p.height).coerceAtLeast(0))
                p.place(width - p.width, top)
            }
        }
    }
}

/** The y of height [z] in a plot [heightPx] tall with [padPx] above the top tick and below the bottom one. */
private fun plotY(z: Double, axis: HeightAxis, heightPx: Float, padPx: Float): Float {
    val span = axis.maxZ - axis.minZ
    val f = if (span > 0.0) (axis.maxZ - z) / span else 0.5
    return (padPx + f * (heightPx - 2f * padPx)).toFloat()
}
