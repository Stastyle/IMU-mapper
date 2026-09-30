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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
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
import com.stastyle.imumapper.ui.common.formatDistance
import com.stastyle.imumapper.ui.common.formatHeight
import kotlin.math.roundToInt

/** Space above the top tick and below the bottom one, so a label centred on either still fits. */
private val PlotPadding = 8.dp
private val LineWidth = 2.5.dp

/**
 * Height against distance walked, coloured by distance like the path on the canvas, around a zero line at the
 * start's height. [profile] keeps each sample's lowest and highest point, so a short peak or dip is drawn as a
 * vertical stroke instead of being averaged away. The y labels are Texts (they follow the font scale), and the
 * whole chart is laid out left to right: distance grows to the right whatever the language. TalkBack reads it
 * as one sentence.
 */
@Composable
internal fun ElevationChart(profile: ElevationProfile, modifier: Modifier = Modifier, plotHeight: Dp = 120.dp) {
    val axis = remember(profile) {
        PathProfile.axis(profile.minZ.minOrNull() ?: 0.0, profile.maxZ.maxOrNull() ?: 0.0)
    }
    val colors = remember(profile) { IntArray(profile.size) { PathProgress.color(profile.fraction(it)) } }
    val lowest = profile.minZ.minOrNull() ?: 0.0
    val highest = profile.maxZ.maxOrNull() ?: 0.0
    val description = "Elevation profile over ${formatDistance(profile.totalM)}, " +
        "from ${formatHeight(lowest)} to ${formatHeight(highest)} against the start"
    val labelStyle = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier = modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
            Row(modifier = Modifier.fillMaxWidth().height(plotHeight)) {
                AxisLabels(axis, labelStyle, labelColor)
                Spacer(Modifier.width(8.dp))
                Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    val pad = PlotPadding.toPx()
                    val w = size.width
                    fun y(z: Double): Float = plotY(z, axis, size.height, pad)
                    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                    for (tick in axis.ticks) {
                        val ty = y(tick)
                        drawLine(
                            gridColor,
                            Offset(0f, ty),
                            Offset(w, ty),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = if (tick == 0.0) null else dash,
                        )
                    }
                    val n = profile.size
                    if (n == 0 || profile.totalM <= 0.0) return@Canvas
                    fun x(i: Int): Float = (profile.distanceM[i] / profile.totalM * w).toFloat()
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
