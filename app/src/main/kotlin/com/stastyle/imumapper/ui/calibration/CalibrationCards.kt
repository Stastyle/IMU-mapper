package com.stastyle.imumapper.ui.calibration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.render.PathProgress
import com.stastyle.imumapper.render.SceneColors
import com.stastyle.imumapper.ui.common.BrandButton
import com.stastyle.imumapper.ui.common.GlassCard
import com.stastyle.imumapper.ui.common.GridScaleChip
import com.stastyle.imumapper.ui.common.SectionHeader
import com.stastyle.imumapper.ui.theme.imuColors
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A card of the calibration screens: a heading with its icon over [content], padded like every other
 * card of the redesign. [action] sits at the end of the heading.
 */
@Composable
fun CalibrationCard(
    title: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(title = title, icon = icon, action = action)
            content()
        }
    }
}

/** The heading icon of each guided flow. */
private fun FlowKind.icon(): ImageVector = when (this) {
    FlowKind.STILL -> Icons.Filled.Timer
    FlowKind.STRIDE -> Icons.AutoMirrored.Filled.DirectionsWalk
    FlowKind.HEADING -> Icons.Filled.Explore
    FlowKind.SQUARE -> Icons.Filled.CropSquare
}

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
    CalibrationCard(title = kind.title, icon = kind.icon()) {
        when (phase) {
            is FlowPhase.Idle -> {
                Text(instructions, style = MaterialTheme.typography.bodyMedium)
                setup()
                BrandButton(onClick = onStart, enabled = enabled) { Text("Start") }
            }
            // The compass dialog covers the screen meanwhile; this shows behind it.
            is FlowPhase.Compass -> Text("Waiting for the compass to find north…")
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
                    BrandButton(onClick = onSave) { Text(saveLabel) }
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
                    BrandButton(onClick = onStart, enabled = enabled) { Text("Try again") }
                    TextButton(onClick = onDiscard) { Text("Dismiss") }
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
        BrandButton(onClick = onStop) { Text("Stop") }
    }
}

/**
 * Label / value line used by every result body, read by TalkBack as one item. The value is laid out
 * left to right with even digit widths, so signs and columns of numbers stay put on any phone. The label
 * sits at the start and the value at the end; when they do not fit on one line the widths follow
 * [CalibrationMath.valueRowValueWidth], so whichever is longer wraps and neither disappears.
 */
@Composable
fun ValueRow(label: String, value: String, highlight: Boolean = false) {
    Layout(
        content = {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDirection = TextDirection.Ltr,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    ) { measurables, constraints ->
        val (labelText, valueText) = measurables
        val gap = VALUE_ROW_GAP.roundToPx()
        val labelWants = labelText.maxIntrinsicWidth(Constraints.Infinity)
        val valueWants = valueText.maxIntrinsicWidth(Constraints.Infinity)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else labelWants + gap + valueWants
        val valueWidth = CalibrationMath.valueRowValueWidth(width, gap, labelWants, valueWants)
        val valuePlaced = valueText.measure(Constraints(maxWidth = valueWidth))
        val labelPlaced = labelText.measure(Constraints(maxWidth = max(width - gap - valuePlaced.width, 0)))
        layout(width, max(labelPlaced.height, valuePlaced.height)) {
            // Relative, so a right-to-left phone mirrors the row as it would a Row.
            labelPlaced.placeRelative(0, 0)
            valuePlaced.placeRelative(width - valuePlaced.width, 0)
        }
    }
}

/** Space kept between a [ValueRow]'s label and its value. */
private val VALUE_ROW_GAP = 12.dp

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
        Hint("Saving stores both; the pipeline uses the Weinberg model when k is above zero.")
    } else {
        Hint("Weinberg k could not be fitted (no usable acceleration swing); it stays as it is.")
    }
}

@Composable
fun HeadingResult(r: FlowResult.Heading) {
    ValueRow("Path direction now", Fmt.degrees(r.endDirectionRad))
    ValueRow("New heading offset", Fmt.degrees(r.offsetRad), highlight = true)
    ValueRow("Measured on axis", Fmt.axis(r.axis))
    ValueRow("Walked", Fmt.metres(r.walkedM) + " · ${r.steps} steps")
    Hint(
        "The offset is the angle between where the phone points and where you walk, for the pose the phone " +
            "was in when the walk started. It is applied to every recording, so every recording must start in " +
            "that pose. It is right only if you walked toward magnetic north: it turns the walk you just made " +
            "into north on the map. With the phone held in front of you it should be near 0°.",
    )
}

@Composable
fun SquareResult(r: FlowResult.Square) {
    ValueRow("Closure error", Fmt.metres(r.closureM), highlight = true)
    ValueRow("Of distance walked", Fmt.percent(r.closurePct), highlight = true)
    ValueRow("Distance · steps", Fmt.metres(r.distanceM) + " · ${r.steps}")
    PathPreview(points = r.points, modifier = Modifier.fillMaxWidth().height(180.dp))
    Hint("This is the baseline number to improve. Saving keeps it as a note with the calibration.")
}

/** A quiet explanatory line under a result. */
@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Small top-down view of a path, like the trip viewer's: north up and never mirrored, coloured from
 * blue to red by the distance walked (the viewer's Progress colours), the start marked green and the
 * end red, over a grid whose spacing the chip names: 1 m for the square test, wider when the canvas
 * shows more ground ([CalibrationMath.previewGridSpacing]).
 */
@Composable
fun PathPreview(points: List<Vec3>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.imuColors
    val shape = MaterialTheme.shapes.small
    val fractions = remember(points) { PathProgress.fractions(points) }
    val runs = remember(fractions) { CalibrationMath.progressRuns(fractions) }
    val bounds = remember(points) { CalibrationMath.planBounds(points) }
    val density = LocalDensity.current
    val paddingPx = with(density) { PREVIEW_PADDING.toPx().toDouble() }
    val minGapPx = with(density) { PREVIEW_MIN_GRID_GAP.toPx().toDouble() }
    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(colors.canvasBackground)
            .border(1.dp, colors.cardBorder, shape),
    ) {
        // Measured here rather than in the Canvas, so the chip can name the spacing the Canvas draws.
        val widthPx = constraints.maxWidth.toDouble()
        val heightPx = constraints.maxHeight.toDouble()
        val sized = constraints.hasBoundedWidth && constraints.hasBoundedHeight
        val plan = remember(bounds, widthPx, heightPx, paddingPx) {
            if (bounds == null || !sized) null else CalibrationMath.fitPlan(bounds, widthPx, heightPx, paddingPx)
        }
        val gridM = remember(plan, minGapPx) {
            plan?.let { CalibrationMath.previewGridSpacing(it, widthPx, heightPx, minGapPx) }
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = plan ?: return@Canvas
            if (gridM != null) drawPlanGrid(t, gridM)
            val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (r in runs.indices) {
                val from = runs[r]
                val to = if (r + 1 < runs.size) runs[r + 1] else points.size - 1
                if (to <= from) continue
                val path = Path()
                path.moveTo(t.x(points[from].x), t.y(points[from].y))
                for (i in from + 1..to) path.lineTo(t.x(points[i].x), t.y(points[i].y))
                drawPath(path, Color(PathProgress.color(fractions[from])), style = stroke)
            }
            val first = points[0]
            val last = points[points.size - 1]
            val marker = 5.dp.toPx()
            drawCircle(Color(SceneColors.START), radius = marker, center = Offset(t.x(first.x), t.y(first.y)))
            drawCircle(Color(SceneColors.END), radius = marker, center = Offset(t.x(last.x), t.y(last.y)))
        }
        if (gridM != null) {
            // Absolute, like the viewer's canvas overlays: the drawn map never mirrors, so neither does its chip.
            GridScaleChip(
                text = "${gridM.roundToInt()} m grid",
                modifier = Modifier
                    .align(AbsoluteAlignment.BottomLeft)
                    .padding(8.dp),
            )
        }
    }
}

/**
 * Grid lines at whole multiples of [spacing] metres over the whole canvas, not only the path's extent,
 * so the scale reads everywhere; every fifth line is stronger, as on the viewer's floor grid.
 */
private fun DrawScope.drawPlanGrid(t: CalibrationMath.PlanTransform, spacing: Double) {
    val minor = Color(SceneColors.GRID_MINOR)
    val major = Color(SceneColors.GRID_MAJOR)
    // Line k lies at k * spacing metres from the trip's origin.
    val west = floor(-t.offsetX / t.scale / spacing).toInt()
    val east = floor((size.width - t.offsetX) / t.scale / spacing).toInt()
    for (k in west..east) {
        val x = t.x(k * spacing)
        drawLine(if (k % 5 == 0) major else minor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
    }
    val south = floor((t.offsetY - size.height) / t.scale / spacing).toInt()
    val north = floor(t.offsetY / t.scale / spacing).toInt()
    for (k in south..north) {
        val y = t.y(k * spacing)
        drawLine(if (k % 5 == 0) major else minor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
    }
}

/** Space between the preview's edge and the path, so the end markers are never cut. */
private val PREVIEW_PADDING = 16.dp

/** Grid lines closer than this would merge into a fill; the preview then draws none. */
private val PREVIEW_MIN_GRID_GAP = 4.dp

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
