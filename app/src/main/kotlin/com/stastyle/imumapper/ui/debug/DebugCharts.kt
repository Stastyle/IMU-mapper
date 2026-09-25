package com.stastyle.imumapper.ui.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.ui.calibration.Fmt
import kotlin.math.max

/**
 * One live line chart: newest sample at the right edge, auto-scaled to the visible window (or to
 * [fixedMin]..[fixedMax] when given, for headings), a zero line when zero is in range and vertical
 * ticks at [ChartData.marks] (hardware steps).
 */
@Composable
fun LineChart(
    title: String,
    unit: String,
    data: ChartData,
    modifier: Modifier = Modifier,
    decimals: Int = 2,
    fixedMin: Float? = null,
    fixedMax: Float? = null,
    secondary: ChartData? = null,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.tertiary
    val zeroColor = MaterialTheme.colorScheme.outlineVariant
    val markColor = MaterialTheme.colorScheme.error
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            val latest = data.latest
            Text(
                if (latest == null) "–" else Fmt.num(latest.toDouble(), decimals) + " " + unit,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
            )
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
            val values = data.values
            if (values.size < 2) return@Canvas
            var lo = fixedMin ?: data.min()
            var hi = fixedMax ?: data.max()
            if (fixedMin == null && secondary != null && secondary.values.isNotEmpty()) {
                lo = minOf(lo, secondary.min())
                hi = maxOf(hi, secondary.max())
            }
            if (hi - lo < 1e-3f) {
                // A flat signal still needs a visible band, otherwise the scale explodes on noise.
                lo -= 0.5f
                hi += 0.5f
            } else if (fixedMin == null) {
                val pad = (hi - lo) * 0.1f
                lo -= pad
                hi += pad
            }
            val w = size.width
            val h = size.height
            val range = hi - lo
            val bottom = lo
            val yOf: (Float) -> Float = { v -> h - (v - bottom) / range * h }
            if (lo < 0f && hi > 0f) {
                drawLine(zeroColor, Offset(0f, yOf(0f)), Offset(w, yOf(0f)), strokeWidth = 1f)
            }
            val capacity = max(values.size, 2)
            val dx = w / (capacity - 1)
            for (m in data.marks) {
                val x = m * dx
                drawLine(markColor, Offset(x, 0f), Offset(x, h), strokeWidth = 2f)
            }
            if (secondary != null && secondary.values.size >= 2) {
                drawSeries(secondary.values, dx, yOf, secondaryColor, 1.5f)
            }
            drawSeries(values, dx, yOf, lineColor, 2f)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val lo = fixedMin ?: if (data.values.isEmpty()) null else data.min()
            val hi = fixedMax ?: if (data.values.isEmpty()) null else data.max()
            Text(
                if (lo == null) "" else "min " + Fmt.num(lo.toDouble(), decimals),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                if (hi == null) "" else "max " + Fmt.num(hi.toDouble(), decimals),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun DrawScope.drawSeries(
    values: FloatArray,
    dx: Float,
    yOf: (Float) -> Float,
    color: Color,
    width: Float,
) {
    val path = Path()
    for (i in values.indices) {
        val x = i * dx
        val y = yOf(values[i])
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = width))
}
