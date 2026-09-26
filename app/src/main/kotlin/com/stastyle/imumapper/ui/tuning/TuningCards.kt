package com.stastyle.imumapper.ui.tuning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.pipeline.tuning.GroundTruth
import com.stastyle.imumapper.pipeline.tuning.Proposal
import com.stastyle.imumapper.pipeline.tuning.Score
import com.stastyle.imumapper.pipeline.tuning.TuningMetrics
import com.stastyle.imumapper.pipeline.tuning.TuningReport
import com.stastyle.imumapper.ui.calibration.Fmt
import com.stastyle.imumapper.ui.calibration.PathPreview
import com.stastyle.imumapper.ui.calibration.ValueRow

/** The handful of numbers that tell whether a run looks right, with a top-down preview. */
@Composable
fun MetricsSummary(m: TuningMetrics) {
    ValueRow("Distance · steps", Fmt.metres(m.distanceM) + " · " + m.stepCount)
    ValueRow("Cadence", m.cadenceHz?.let { Fmt.num(it, 2) + " steps/s" } ?: "n/a")
    ValueRow("End to start gap", Fmt.metres(m.closureM) + (m.closurePercent?.let { " (" + Fmt.percent(it) + ")" } ?: ""))
    ValueRow("Turns", m.shape.turns.size.toString() + " · " + m.shape.turns.joinToString(", ") { Fmt.num(it.angleDeg, 0) + "°" }.ifEmpty { "none" })
    ValueRow("Height", "end " + Fmt.metres(m.netHeightM) + ", range " + Fmt.metres(m.maxZ - m.minZ))
    PathPreview(points = m.rawPoints.map { it.p }, modifier = Modifier.fillMaxWidth().height(160.dp))
}

/** The deviations from the ground truth; nothing when the walker gave nothing to score against. */
@Composable
fun ScoreBody(truth: GroundTruth, m: TuningMetrics) {
    val score = Score.of(truth, m)
    if (score.deviations.isEmpty()) {
        Text(
            "Nothing to score yet: fill in the distance, the shape or the turns to get a score.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    Text("Against your description", style = MaterialTheme.typography.labelLarge)
    for (d in score.deviations) ValueRow(d.name + " (expected " + d.expected + ")", d.actual)
    ValueRow("Score (lower is better)", Fmt.num(score.mean ?: 0.0, 3), highlight = true)
}

@Composable
fun ProposalBody(p: Proposal) {
    if (p.changes.isEmpty()) {
        Text("The answer proposes no change.", style = MaterialTheme.typography.bodyMedium)
    } else {
        Text("Proposed changes", style = MaterialTheme.typography.labelLarge)
        for (c in p.changes) {
            ValueRow(c.key, c.from + " → " + c.to, highlight = true)
            if (c.reason != null) Text(c.reason, style = MaterialTheme.typography.bodySmall)
        }
    }
    p.reasoning?.let {
        Text("Reasoning", style = MaterialTheme.typography.labelLarge)
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
    p.expected?.let {
        Text("Expected effect", style = MaterialTheme.typography.labelLarge)
        Text(it, style = MaterialTheme.typography.bodySmall)
    }
    for (w in p.warnings) Text(w, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
}

/** Baseline and proposal side by side on the scored items and the headline metrics. */
@Composable
fun ComparisonBody(truth: GroundTruth, before: TuningReport, after: TuningReport, runId: Int) {
    val b = Score.of(truth, before.metrics)
    val a = Score.of(truth, after.metrics)
    HorizontalDivider()
    Text("Before vs after (stored as run $runId)", style = MaterialTheme.typography.labelLarge)
    CompareRow("", "before", "after", header = true)
    val bm = before.metrics
    val am = after.metrics
    CompareRow("Distance", Fmt.metres(bm.distanceM), Fmt.metres(am.distanceM))
    CompareRow("Steps", bm.stepCount.toString(), am.stepCount.toString())
    CompareRow("End to start gap", Fmt.metres(bm.closureM), Fmt.metres(am.closureM))
    CompareRow("Turns", bm.shape.turns.size.toString(), am.shape.turns.size.toString())
    CompareRow("Height (end)", Fmt.metres(bm.netHeightM), Fmt.metres(am.netHeightM))
    for (i in b.deviations.indices) {
        val bd = b.deviations[i]
        val ad = a.deviations.getOrNull(i) ?: continue
        CompareRow(bd.name + " error", Fmt.num(bd.error, 3), Fmt.num(ad.error, 3))
    }
    val bs = b.mean
    val am2 = a.mean
    if (bs != null && am2 != null) {
        CompareRow("Score", Fmt.num(bs, 3), Fmt.num(am2, 3), bold = true)
        Text(
            when {
                am2 < bs - 1e-6 -> "The proposal scores better."
                am2 > bs + 1e-6 -> "The proposal scores worse; keep the current calibration or ask again."
                else -> "No difference on the scored items."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (am2 <= bs) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
    PathPreview(points = am.rawPoints.map { it.p }, modifier = Modifier.fillMaxWidth().height(160.dp))
}

@Composable
private fun CompareRow(label: String, before: String, after: String, header: Boolean = false, bold: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.2f))
        Text(
            before,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (header) FontFamily.Default else FontFamily.Monospace,
            fontWeight = if (bold || header) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text(
            after,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (header) FontFamily.Default else FontFamily.Monospace,
            fontWeight = if (bold || header) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}
