package com.stastyle.imumapper.pipeline.tuning

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Processor
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.log.RawLog
import com.stastyle.imumapper.pipeline.pdr.Angles
import com.stastyle.imumapper.pipeline.pdr.PdrSolver
import kotlin.math.sqrt

/** Five-number style summary of a sample of values; null fields when the sample is empty. */
class Distribution(val count: Int, val min: Double, val p10: Double, val median: Double, val p90: Double, val max: Double) {
    companion object {
        fun of(values: DoubleArray): Distribution? {
            if (values.isEmpty()) return null
            val s = values.copyOf()
            s.sort()
            fun q(f: Double): Double = s[(f * (s.size - 1)).toInt().coerceIn(0, s.size - 1)]
            return Distribution(s.size, s[0], q(0.1), q(0.5), q(0.9), s[s.size - 1])
        }
    }
}

/**
 * Everything the assisted-tuning prompt says about one processing run, reduced to numbers a
 * reader (human or model) can reason about without the samples: counts, distances, the shape of
 * the path, and the distributions the step detector's thresholds act on.
 */
class TuningMetrics(
    val distanceM: Double,
    val durationS: Double,
    val stepCount: Int,
    /** Steps per second over the stretches where the walker was moving (gaps over 2 s excluded). */
    val cadenceHz: Double?,
    val meanStrideM: Double?,
    /** Time between consecutive steps, seconds. */
    val stepIntervalS: Distribution?,
    /** Fraction of step intervals under 0.4 s: a large value points at double counting. */
    val shortIntervalFraction: Double?,
    /** Peak-to-valley vertical acceleration swing per step, m/s^2, as the step detector measured it. */
    val stepSwing: Distribution?,
    /** Straight-line distance from the last point back to the first, before loop closure, metres (horizontal). */
    val closureM: Double,
    val closurePercent: Double?,
    /** Displacement from start to end as a fraction of distance walked: 1 for a perfectly straight walk. */
    val straightness: Double?,
    /** Direction from the start to the end point, degrees clockwise from north; null under one metre. */
    val endDirectionDeg: Double?,
    val netHeightM: Double,
    val minZ: Double,
    val maxZ: Double,
    val shape: ShapeAnalysis,
    /** Sum of the signed turn angles, degrees: about +-360 for a loop, 0 for a straight walk. */
    val totalTurnDeg: Double,
    val vioFraction: Double,
    /** The path as it was dead-reckoned, before loop closure and smoothing. */
    val rawPoints: List<PathPoint>,
    val diagnostics: Map<String, String>,
) {
    companion object {
        /** Intervals longer than this are stops, not slow steps, for the cadence. */
        const val STOP_GAP_S: Double = 2.0
        const val SHORT_INTERVAL_S: Double = 0.4

        fun of(result: PathResult, swings: DoubleArray?, strides: DoubleArray?): TuningMetrics {
            val points = result.points
            val raw = if (result.rawPoints.isEmpty()) points else result.rawPoints
            val steps = raw.filter { it.stepIndex >= 0 }
            val intervals = DoubleArray(maxOf(0, steps.size - 1)) { (steps[it + 1].tNs - steps[it].tNs) / 1e9 }
            var walkingS = 0.0
            var walkingSteps = 0
            var short = 0
            for (dt in intervals) {
                if (dt <= STOP_GAP_S) {
                    walkingS += dt
                    walkingSteps++
                }
                if (dt < SHORT_INTERVAL_S) short++
            }
            val first = raw.firstOrNull()?.p ?: Vec3.ZERO
            val last = raw.lastOrNull()?.p ?: Vec3.ZERO
            val closure = horizontal(first, last)
            val distance = result.stats.distanceM
            val shape = PathShape.analyse(raw)
            return TuningMetrics(
                distanceM = distance,
                durationS = result.stats.durationS,
                stepCount = result.stats.stepCount,
                cadenceHz = if (walkingS > 0.0) walkingSteps / walkingS else null,
                meanStrideM = strides?.takeIf { it.isNotEmpty() }?.let { it.sum() / it.size }
                    ?: if (result.stats.stepCount > 0) distance / result.stats.stepCount else null,
                stepIntervalS = Distribution.of(intervals),
                shortIntervalFraction = if (intervals.isEmpty()) null else short.toDouble() / intervals.size,
                stepSwing = swings?.let { Distribution.of(it) },
                closureM = closure,
                closurePercent = if (distance > 0.0) closure / distance * 100.0 else null,
                straightness = if (distance > 0.0) closure / distance else null,
                endDirectionDeg = if (closure < 1.0) null else Math.toDegrees(Angles.wrap(Math.atan2(last.x - first.x, last.y - first.y))),
                netHeightM = last.z - first.z,
                minZ = result.stats.minZ,
                maxZ = result.stats.maxZ,
                shape = shape,
                totalTurnDeg = shape.turns.sumOf { it.angleDeg },
                vioFraction = result.stats.vioFraction,
                rawPoints = raw,
                diagnostics = result.diagnostics,
            )
        }

        private fun horizontal(a: Vec3, b: Vec3): Double {
            val dx = b.x - a.x
            val dy = b.y - a.y
            return sqrt(dx * dx + dy * dy)
        }
    }
}

/** One processing run of a log with the numbers the tuning loop compares. */
class TuningReport(val config: PipelineConfig, val result: PathResult, val metrics: TuningMetrics)

/**
 * Runs the processor and the step analysis over a log, or just the analysis when the result was
 * already produced elsewhere (the app stores it as a run first). The step detector's swings come
 * from [PdrSolver.prepare], which is where the pipeline itself takes them from.
 */
object TuningAnalysis {

    fun analyse(log: RawLog, config: PipelineConfig, processor: Processor): TuningReport =
        report(log, config, processor.process(log, config))

    fun report(log: RawLog, config: PipelineConfig, result: PathResult): TuningReport {
        val ctx = runCatching { PdrSolver().prepare(log, config) }.getOrNull()
        val metrics = TuningMetrics.of(result, ctx?.steps?.swing, ctx?.stepStrideM)
        return TuningReport(config, result, metrics)
    }
}
