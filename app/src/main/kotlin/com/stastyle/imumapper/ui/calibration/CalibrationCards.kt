package com.stastyle.imumapper.ui.calibration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.pipeline.core.Vec3

/**
 * One guided flow: instructions and Start, then a countdown or elapsed time, then the result with
 * Save / Discard. The per-flow result body is passed in so this card knows nothing about the values.
 */
@Composable
fun FlowCard(
    kind: FlowKind,
    instructions: String,
    phase: FlowPhase,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    saveLabel: String = "Save",
    setup: @Composable ColumnScope.() -> Unit = {},
    result: @Composable ColumnScope.(FlowResult) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(kind.title, style = MaterialTheme.typography.titleMedium)
            when (phase) {
                is FlowPhase.Idle -> {
                    Text(instructions, style = MaterialTheme.typography.bodyMedium)
                    setup()
                    Button(onClick = onStart, enabled = enabled) { Text("Start") }
                }
                is FlowPhase.Running -> RunningBody(kind, phase, onStop)
                is FlowPhase.Computing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Computing…")
                    }
                }
                is FlowPhase.Done -> {
                    result(phase.result)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSave) { Text(saveLabel) }
                        OutlinedButton(onClick = onDiscard) { Text("Discard") }
                    }
                }
                is FlowPhase.Failed -> {
                    Text(
                        phase.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    setup()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStart, enabled = enabled) { Text("Try again") }
                        TextButton(onClick = onDiscard) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunningBody(kind: FlowKind, phase: FlowPhase.Running, onStop: () -> Unit) {
    val remaining = phase.remainingS
    if (remaining != null) {
        Text("Hold still… $remaining s", style = MaterialTheme.typography.headlineSmall)
        val total = CalibrationViewModel.STILL_SECONDS.toFloat()
        val fraction = ((total - remaining) / total).coerceIn(0f, 1f)
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    } else {
        Text("Recording ${phase.elapsedS} s · ${phase.steps} steps", style = MaterialTheme.typography.headlineSmall)
        Text(
            when (kind) {
                FlowKind.STRIDE -> "Walk the measured distance, then tap Stop."
                FlowKind.HEADING -> "Walk straight ahead for about ten steps, then tap Stop."
                FlowKind.SQUARE -> "Walk the square and come back to the exact start, then tap Stop."
                FlowKind.STILL -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onStop) { Text("Stop") }
    }
}

/** Label / value line used by every result body. */
@Composable
fun ValueRow(label: String, value: String, highlight: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
fun StillBiasResult(r: FlowResult.StillBias) {
    ValueRow("Gyro bias (rad/s)", Fmt.vec3(r.gyroBias), highlight = true)
    ValueRow("Gyro noise RMS", Fmt.radS(r.gyroNoiseRadS))
    ValueRow("Accel noise (std of |a|)", Fmt.num(r.accelNoiseMs2, 4) + " m/s²")
    ValueRow("Samples", "${r.gyroSamples} gyro · ${r.accelSamples} accel")
    if (r.moved) {
        Text(
            "The phone seems to have moved; put it on a solid surface and try again before saving.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun StrideResult(r: FlowResult.Stride) {
    ValueRow("Distance", Fmt.metres(r.distanceM))
    ValueRow("Steps detected", r.steps.toString())
    ValueRow("Stride length", Fmt.metres(r.strideM), highlight = true)
    val k = r.weinbergK
    if (k != null) {
        ValueRow("Weinberg k", Fmt.num(k, 3), highlight = true)
        Text(
            "Saving stores both; the pipeline uses the Weinberg model when k is above zero.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(
            "Weinberg k could not be fitted (no usable acceleration swing); it stays as it is.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun HeadingResult(r: FlowResult.Heading) {
    ValueRow("Path direction now", Fmt.degrees(r.endDirectionRad))
    ValueRow("New heading offset", Fmt.degrees(r.offsetRad), highlight = true)
    ValueRow("Measured on axis", Fmt.axis(r.axis))
    ValueRow("Walked", Fmt.metres(r.walkedM) + " · ${r.steps} steps")
    Text(
        "The offset is relative to the walking direction: it turns the direction you just walked into " +
            "\"north\" (up) on the map for this carry position. Walk towards real north if you want the map " +
            "oriented; otherwise headings are relative to this walk.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
fun SquareResult(r: FlowResult.Square) {
    ValueRow("Closure error", Fmt.metres(r.closureM), highlight = true)
    ValueRow("Of distance walked", Fmt.percent(r.closurePct), highlight = true)
    ValueRow("Distance · steps", Fmt.metres(r.distanceM) + " · ${r.steps}")
    PathPreview(points = r.points, modifier = Modifier.fillMaxWidth().height(160.dp))
    Text(
        "This is the baseline number to improve. Saving keeps it as a note with the calibration.",
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Tiny top-down view of a path: north up, start marked green, end marked red. */
@Composable
fun PathPreview(points: List<Vec3>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        val bounds = CalibrationMath.planBounds(points) ?: return@Canvas
        val t = CalibrationMath.fitPlan(bounds, size.width.toDouble(), size.height.toDouble(), 12.dp.toPx().toDouble())
        // One-metre grid so the scale is readable without axes.
        val step = 1.0
        var gx = Math.floor(bounds.minX) - step
        while (gx <= bounds.maxX + step) {
            val x = t.x(gx)
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            gx += step
        }
        var gy = Math.floor(bounds.minY) - step
        while (gy <= bounds.maxY + step) {
            val y = t.y(gy)
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            gy += step
        }
        val path = Path()
        for (i in points.indices) {
            val p = points[i]
            if (i == 0) path.moveTo(t.x(p.x), t.y(p.y)) else path.lineTo(t.x(p.x), t.y(p.y))
        }
        drawPath(path, lineColor, style = Stroke(width = 3f))
        val first = points[0]
        val last = points[points.size - 1]
        drawCircle(Color(0xFF2E7D32), radius = 6f, center = Offset(t.x(first.x), t.y(first.y)))
        drawCircle(Color(0xFFC62828), radius = 6f, center = Offset(t.x(last.x), t.y(last.y)))
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun VioComparisonBody(r: VioComparison) {
    Text(r.tripName, style = MaterialTheme.typography.titleSmall)
    val c = r.comparison
    if (c != null) {
        ValueRow("PDR distance", Fmt.metres(c.pdrDistanceM) + " · ${r.pdrSteps ?: 0} steps")
        ValueRow("ARCore distance", Fmt.metres(c.vioDistanceM))
        ValueRow(
            "Distance difference",
            Fmt.metres(c.distanceDiffM) + " (" + Fmt.percent(c.distanceDiffPct) + ")",
            highlight = true,
        )
        ValueRow("End-point gap", Fmt.metres(c.endGapM), highlight = true)
        val dir = c.endDirectionDiffDeg
        ValueRow("End direction difference", if (dir == null) "n/a" else Fmt.num(dir, 1) + "°")
        val fraction = r.vioFraction
        if (fraction != null) ValueRow("Tracked fraction", Fmt.percent(fraction * 100.0))
    }
    if (r.pdrError != null) ErrorLine("PDR failed: " + r.pdrError)
    if (r.vioError != null) ErrorLine("ARCore path failed: " + r.vioError)
    Spacer(modifier = Modifier.height(4.dp))
}
