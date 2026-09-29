package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.PIPELINE_VERSION
import com.stastyle.imumapper.pipeline.core.PathAnnotation
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.post.PathBuilder
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertTrue

/**
 * Synthetic paths for the survey tests. Stride 0.5 m and step 0.5 s are exact in binary, so a walk due
 * north (and east, whose cos(pi / 2) residue rounds away) has coordinates that can be written as literals.
 */
object SurveyPaths {
    const val T0: Long = 1_000_000_000L
    const val STEP_NS: Long = 500_000_000L

    fun tNs(index: Int, stepNs: Long = STEP_NS): Long = T0 + index * stepNs

    /**
     * Like PdrSolver: the origin at T0 (stepIndex -1, heading of the first leg), then one PDR point per
     * step along legs of (steps, heading rad) with [strideM], one step every [stepNs]. [swayM] shifts
     * each plotted point sideways by swayM * sin(1.3 k) without accumulating; [climbPerStepM] adds height.
     * Point index i is step i - 1, at tNs(i, stepNs).
     */
    fun steps(
        vararg legs: Pair<Int, Double>,
        strideM: Double = 0.5,
        stepNs: Long = STEP_NS,
        swayM: Double = 0.0,
        climbPerStepM: Double = 0.0,
    ): List<PathPoint> {
        val out = ArrayList<PathPoint>()
        out.add(PathPoint(T0, Vec3.ZERO, PositionSource.PDR, legs[0].second, -1))
        var legStartX = 0.0
        var legStartY = 0.0
        var k = 0
        for ((count, heading) in legs) {
            val dirE = sin(heading)
            val dirN = cos(heading)
            for (j in 1..count) {
                // Positions are the leg start plus j strides, not a running sum, so they stay exact.
                val x = legStartX + j * strideM * dirE
                val y = legStartY + j * strideM * dirN
                val side = swayM * sin(1.3 * k)
                // Sideways is to the walker's right: (cos h, -sin h).
                val p = Vec3(x + side * dirN, y - side * dirE, (k + 1) * climbPerStepM)
                out.add(PathPoint(tNs(k + 1, stepNs), p, PositionSource.PDR, heading, k))
                k++
            }
            legStartX += count * strideM * dirE
            legStartY += count * strideM * dirN
        }
        return out
    }

    /** Points at [positions] every [stepNs] from T0 with [source] (VIO by default), for linear placement. */
    fun linear(
        vararg positions: Vec3,
        stepNs: Long = STEP_NS,
        source: PositionSource = PositionSource.VIO,
    ): List<PathPoint> = positions.mapIndexed { i, p -> PathPoint(tNs(i, stepNs), p, source, 0.0, -1) }

    /** A PathResult around [points] with PathBuilder.stats and the given annotations and diagnostics. */
    fun result(
        points: List<PathPoint>,
        annotations: List<PathAnnotation> = emptyList(),
        diagnostics: Map<String, String> = emptyMap(),
    ): PathResult {
        val durationS = if (points.isEmpty()) 0.0 else (points.last().tNs - points.first().tNs) / 1e9
        return PathResult(
            pipelineVersion = PIPELINE_VERSION,
            config = PipelineConfig(),
            points = points,
            annotations = annotations,
            stats = PathBuilder.stats(points, durationS, points.count { it.stepIndex >= 0 }, null),
            diagnostics = diagnostics,
        )
    }
}

/** Component-wise closeness of two positions, with both printed on failure. */
fun assertNear(expected: Vec3, actual: Vec3, tolerance: Double = 1e-9, message: String = "") {
    assertTrue(
        (expected - actual).length <= tolerance,
        "$message expected $expected, got $actual".trim(),
    )
}
