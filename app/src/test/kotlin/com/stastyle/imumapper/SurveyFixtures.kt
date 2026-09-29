package com.stastyle.imumapper

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.PIPELINE_VERSION
import com.stastyle.imumapper.pipeline.core.PathAnnotation
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.pdr.OrientationEstimator
import com.stastyle.imumapper.pipeline.post.PathBuilder
import kotlin.math.PI

/**
 * Survey-mode walks for app tests. Coordinates and times are exact binary numbers, so expected
 * positions and distances can be written as literals.
 */
object SurveyFixtures {
    const val T0: Long = 1_000_000_000L
    const val STEP_NS: Long = 500_000_000L
    const val STRIDE_M: Double = 0.5

    fun tNs(index: Int): Long = T0 + index * STEP_NS

    /**
     * Origin (index 0, stepIndex -1), 20 steps north to (0, 10), 10 steps east to (5, 10), positions
     * computed as STRIDE_M * n (not accumulated); one JUNCTION annotation with an empty note at index 10
     * (0, 5). northReference is MAGNETIC, or OrientationEstimator.NORTH_OFF when [magnetic] is false.
     * Seeding gives Start id 1 (t0), Junction 1 id 2 (index 10), End id 3 (index 30), C1 id 4 (index 20);
     * distances along the path: 0, 5, 10, 15.
     */
    fun lWalk(magnetic: Boolean = true): PathResult {
        // Like PdrSolver: the origin carries the first leg's heading and no step.
        val points = ArrayList<PathPoint>(31)
        points += PathPoint(tNs(0), Vec3.ZERO, PositionSource.PDR, 0.0, -1)
        for (n in 1..20) {
            points += PathPoint(tNs(n), Vec3(0.0, STRIDE_M * n, 0.0), PositionSource.PDR, 0.0, n - 1)
        }
        for (n in 1..10) {
            points += PathPoint(tNs(20 + n), Vec3(STRIDE_M * n, 10.0, 0.0), PositionSource.PDR, PI / 2, 19 + n)
        }
        val north = if (magnetic) OrientationEstimator.MAGNETIC else OrientationEstimator.NORTH_OFF
        return PathResult(
            pipelineVersion = PIPELINE_VERSION,
            config = PipelineConfig(),
            points = points,
            annotations = listOf(PathAnnotation(tNs(10), AnnotationKind.JUNCTION, "", Vec3(0.0, 5.0, 0.0))),
            stats = PathBuilder.stats(points, durationS = (tNs(30) - T0) / 1e9, stepCount = 30, closureErrorM = null),
            diagnostics = mapOf(OrientationEstimator.NORTH_REFERENCE to north),
        )
    }
}
