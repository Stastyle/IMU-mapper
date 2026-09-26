package com.stastyle.imumapper.pipeline.tuning

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.log.RawLog
import kotlinx.serialization.json.Json

/** Facts about the recording that the log itself does not carry in a readable form. */
class RecordingInfo(
    val tripName: String,
    /** Local date and time text, already formatted by the caller. */
    val recordedAt: String,
    val mode: String,
    val carryPosition: String,
    val deviceModel: String,
    val appVersion: String,
)

/**
 * Renders the Markdown document the user pastes into a chat model: the master prompt, the walk
 * as described, the current config with its schema, the metrics of the current run, the
 * diagnostics, and a decimated path. Every number the model needs is here; no raw samples are.
 */
object PromptBundle {

    /** Upper bound on path points in the bundle; enough for the shape, small enough for a chat. */
    const val MAX_PATH_POINTS: Int = 120

    /** The answer the master prompt asks for, repeated at the end so it is the last thing read. */
    const val ANSWER_FORMAT: String = """Reply with exactly one fenced ```json block and nothing after it:
```json
{
  "config": { "<key>": <new value>, ... },
  "changes": [ { "key": "<key>", "value": <new value>, "reason": "<one sentence>" } ],
  "reasoning": "<what the numbers show and why these changes address it>",
  "expected": "<which metrics should move, and roughly how much>"
}
```
Put only the keys you change inside "config", using the exact key names and units from the parameter table. Leave every other key out."""

    fun render(
        masterPrompt: String,
        info: RecordingInfo,
        log: RawLog,
        truth: GroundTruth,
        report: TuningReport,
        /** Earlier attempts on the same walk, newest last, so the model sees what already moved. */
        history: List<TuningReport> = emptyList(),
    ): String {
        val sb = StringBuilder(16_000)
        sb.append(masterPrompt.trimEnd()).append("\n\n")

        sb.append("## Recording\n\n")
        row(sb, "Trip", info.tripName)
        row(sb, "Recorded", info.recordedAt)
        row(sb, "Mode", info.mode)
        row(sb, "Phone carried", info.carryPosition)
        row(sb, "Device", info.deviceModel + " · app " + info.appVersion)
        row(sb, "Duration", ConfigSchema.num(log.durationS, 1) + " s")
        row(sb, "Samples", samples(log))
        val periods = log.meta?.sensorPeriodsUs
        if (!periods.isNullOrEmpty()) {
            row(sb, "Sensor periods", periods.entries.joinToString(", ") { it.key.removePrefix("TYPE_").lowercase() + " " + it.value + " µs" })
        }
        if (log.annotations.isNotEmpty()) {
            val t0 = log.firstTimestampNs
            row(sb, "Annotations", log.annotations.joinToString("; ") { seconds(it.tNs, t0) + " " + it.kind.name + (if (it.note.isBlank()) "" else " (" + it.note + ")") })
        }
        if (log.events.isNotEmpty()) {
            val t0 = log.firstTimestampNs
            row(sb, "Events", log.events.joinToString("; ") { seconds(it.tNs, t0) + " " + it.kind.name })
        }
        sb.append('\n')

        sb.append("## What the walker did (ground truth)\n\n")
        row(sb, "Shape", truth.shape.label + " (" + truth.shape.hint + ")")
        row(sb, "Distance walked", truth.distanceM?.let { ConfigSchema.num(it, 1) + " m" } ?: "not measured")
        row(sb, "Turns of 90° or more", truth.turnCount?.toString() ?: "not counted")
        row(sb, "Height change end minus start", truth.heightChangeM?.let { ConfigSchema.num(it, 1) + " m" } ?: "not given")
        sb.append('\n')
        sb.append(if (truth.description.isBlank()) "No further description.\n" else "Description in the walker's words:\n\n> " + truth.description.trim().replace("\n", "\n> ") + "\n")
        sb.append('\n')

        sb.append("## Current configuration\n\n```json\n").append(prettyJson.encodeToString(PipelineConfig.serializer(), report.config)).append("\n```\n\n")
        sb.append("### Parameter reference\n\n| key | type | unit | range | measured by a guided flow | meaning |\n|---|---|---|---|---|---|\n")
        for (f in ConfigSchema.fields) {
            val range = when (f.kind) {
                FieldKind.BOOLEAN -> "true / false"
                FieldKind.ENUM -> f.values.joinToString(" / ")
                else -> ConfigSchema.num(f.min) + " to " + ConfigSchema.num(f.max)
            }
            sb.append("| ").append(f.key).append(" | ").append(f.kind.name.lowercase()).append(" | ").append(f.unit.ifBlank { "-" })
                .append(" | ").append(range).append(" | ").append(if (f.tunable) "no" else "yes").append(" | ")
                .append(f.description.replace("|", "/")).append(" |\n")
        }
        sb.append('\n')

        sb.append("## Pipeline output with the current configuration\n\n")
        metrics(sb, report.metrics)
        sb.append('\n')

        if (history.isNotEmpty()) {
            sb.append("## Earlier attempts on this walk\n\n")
            for ((i, h) in history.withIndex()) {
                val changed = ConfigSchema.changedKeys(report.config, h.config)
                sb.append("### Attempt ").append(i + 1).append("\n\n")
                sb.append("Differs from the current configuration in: ")
                    .append(if (changed.isEmpty()) "nothing" else changed.joinToString(", ") { it + " = " + ConfigSchema.valueText(h.config, it) })
                    .append("\n\n")
                metrics(sb, h.metrics, brief = true)
                sb.append('\n')
            }
        }

        sb.append("## Path before loop closure and smoothing\n\n")
        sb.append("ENU metres from the start, one line per point (decimated to at most ").append(MAX_PATH_POINTS)
            .append(" points), `t` seconds from the start, `heading` degrees clockwise from north:\n\n```\n")
        sb.append("t,x,y,z,heading\n")
        val t0 = report.metrics.rawPoints.firstOrNull()?.tNs ?: 0L
        for (p in decimate(report.metrics.rawPoints, MAX_PATH_POINTS)) {
            sb.append(ConfigSchema.num((p.tNs - t0) / 1e9, 1)).append(',')
                .append(ConfigSchema.num(p.p.x, 2)).append(',').append(ConfigSchema.num(p.p.y, 2)).append(',')
                .append(ConfigSchema.num(p.p.z, 2)).append(',').append(ConfigSchema.num(Math.toDegrees(p.headingRad), 0)).append('\n')
        }
        sb.append("```\n\n")

        sb.append("## Your answer\n\n").append(ANSWER_FORMAT).append('\n')
        return sb.toString()
    }

    fun metrics(sb: StringBuilder, m: TuningMetrics, brief: Boolean = false) {
        row(sb, "Distance", ConfigSchema.num(m.distanceM, 2) + " m")
        row(sb, "Steps", m.stepCount.toString())
        row(sb, "Mean stride", m.meanStrideM?.let { ConfigSchema.num(it, 3) + " m" } ?: "n/a")
        row(sb, "Cadence while moving", m.cadenceHz?.let { ConfigSchema.num(it, 2) + " steps/s" } ?: "n/a")
        m.stepIntervalS?.let { row(sb, "Step interval (s)", dist(it, 2)) }
        m.shortIntervalFraction?.let { row(sb, "Intervals under 0.4 s", ConfigSchema.num(it * 100.0, 1) + " %") }
        m.stepSwing?.let { row(sb, "Step swing (m/s²)", dist(it, 2)) }
        row(sb, "End to start gap", ConfigSchema.num(m.closureM, 2) + " m" + (m.closurePercent?.let { " (" + ConfigSchema.num(it, 1) + " % of distance)" } ?: ""))
        row(sb, "Straightness", m.straightness?.let { ConfigSchema.num(it, 2) + " (1 = straight line)" } ?: "n/a")
        row(sb, "Direction start to end", m.endDirectionDeg?.let { ConfigSchema.num(it, 0) + "°" } ?: "under 1 m")
        row(sb, "Height", "end " + ConfigSchema.num(m.netHeightM, 2) + " m above start, range " + ConfigSchema.num(m.minZ, 2) + " to " + ConfigSchema.num(m.maxZ, 2) + " m")
        row(sb, "Turns found", m.shape.turns.size.toString() + " (sum " + ConfigSchema.num(m.totalTurnDeg, 0) + "°)")
        if (m.vioFraction > 0.0) row(sb, "ARCore tracked points", ConfigSchema.num(m.vioFraction * 100.0, 0) + " %")
        if (brief) return
        if (m.shape.turns.isNotEmpty()) {
            sb.append("\nTurns (positive = right / clockwise):\n\n| # | at distance | at time | angle |\n|---|---|---|---|\n")
            val t0 = m.rawPoints.firstOrNull()?.tNs ?: 0L
            for ((i, t) in m.shape.turns.withIndex()) {
                sb.append("| ").append(i + 1).append(" | ").append(ConfigSchema.num(t.atDistanceM, 1)).append(" m | ")
                    .append(ConfigSchema.num((t.tNs - t0) / 1e9, 1)).append(" s | ").append(ConfigSchema.num(t.angleDeg, 0)).append("° |\n")
            }
        }
        if (m.shape.legs.isNotEmpty()) {
            sb.append("\nLegs between turns:\n\n| # | length | heading |\n|---|---|---|\n")
            for ((i, l) in m.shape.legs.withIndex()) {
                sb.append("| ").append(i + 1).append(" | ").append(ConfigSchema.num(l.lengthM, 1)).append(" m | ")
                    .append(ConfigSchema.num(l.headingDeg, 0)).append("° |\n")
            }
        }
        if (m.diagnostics.isNotEmpty()) {
            sb.append("\nPipeline diagnostics:\n\n```\n")
            for ((k, v) in m.diagnostics) sb.append(k).append(" = ").append(v).append('\n')
            sb.append("```\n")
        }
    }

    /** Every [MAX_PATH_POINTS]-th point or so, always keeping the first and the last. */
    fun decimate(points: List<PathPoint>, max: Int): List<PathPoint> {
        if (points.size <= max || max < 2) return points
        val out = ArrayList<PathPoint>(max)
        val step = (points.size - 1).toDouble() / (max - 1)
        for (i in 0 until max) out.add(points[Math.round(i * step).toInt().coerceIn(0, points.size - 1)])
        return out
    }

    private fun samples(log: RawLog): String {
        val parts = ArrayList<String>()
        if (log.accel.isNotEmpty()) parts.add("accel " + log.accel.size)
        if (log.gyro.isNotEmpty()) parts.add("gyro " + log.gyro.size)
        if (log.mag.isNotEmpty()) parts.add("mag " + log.mag.size)
        if (log.baro.isNotEmpty()) parts.add("baro " + log.baro.size)
        if (log.gameRotation.isNotEmpty()) parts.add("game rotation " + log.gameRotation.size)
        if (log.fusedRotation.isNotEmpty()) parts.add("rotation vector " + log.fusedRotation.size)
        if (log.steps.isNotEmpty()) parts.add("hardware steps " + log.steps.size)
        if (log.poses.isNotEmpty()) parts.add("ARCore poses " + log.poses.size)
        return if (parts.isEmpty()) "none" else parts.joinToString(", ")
    }

    private fun dist(d: Distribution, decimals: Int): String =
        "min " + ConfigSchema.num(d.min, decimals) + ", p10 " + ConfigSchema.num(d.p10, decimals) + ", median " +
            ConfigSchema.num(d.median, decimals) + ", p90 " + ConfigSchema.num(d.p90, decimals) + ", max " +
            ConfigSchema.num(d.max, decimals) + " (n = " + d.count + ")"

    private fun row(sb: StringBuilder, label: String, value: String) {
        sb.append("- **").append(label).append(":** ").append(value).append('\n')
    }

    private fun seconds(tNs: Long, t0: Long): String = "[" + ConfigSchema.num((tNs - t0) / 1e9, 0) + " s]"

    private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }
}
