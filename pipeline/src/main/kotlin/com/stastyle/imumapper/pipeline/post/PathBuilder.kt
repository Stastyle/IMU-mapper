package com.stastyle.imumapper.pipeline.post

import com.stastyle.imumapper.pipeline.core.AnnotationRecord
import com.stastyle.imumapper.pipeline.core.KeyframeSample
import com.stastyle.imumapper.pipeline.core.PIPELINE_VERSION
import com.stastyle.imumapper.pipeline.core.PathAnnotation
import com.stastyle.imumapper.pipeline.core.PathKeyframe
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PathStats
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.log.RawLog
import kotlin.math.abs

/**
 * Assembles a [PathResult] from a finished point list: annotations and photo keyframes are placed
 * at the path point nearest in time, and the statistics are computed from the points. Shared by
 * the PDR and VIO processors.
 */
object PathBuilder {

    fun build(
        config: PipelineConfig,
        points: List<PathPoint>,
        log: RawLog,
        stepCount: Int,
        closureErrorM: Double?,
        diagnostics: Map<String, String>,
        pointCloud: List<Vec3> = emptyList(),
        pipelineVersion: Int = PIPELINE_VERSION,
        /** The points before loop closure and smoothing; pass the same list as [points] (or nothing) when they are equal. */
        rawPoints: List<PathPoint> = points,
    ): PathResult = PathResult(
        pipelineVersion = pipelineVersion,
        config = config,
        points = points,
        annotations = placeAnnotations(points, log.annotations),
        keyframes = placeKeyframes(points, log.keyframes),
        pointCloud = pointCloud,
        stats = stats(points, log.durationS, stepCount, closureErrorM),
        diagnostics = diagnostics,
        // Stored only when post-processing moved something, so an untouched path is not written twice.
        rawPoints = if (rawPoints === points) emptyList() else rawPoints,
    )

    fun placeAnnotations(points: List<PathPoint>, annotations: List<AnnotationRecord>): List<PathAnnotation> =
        annotations.map { a ->
            val p = if (points.isEmpty()) Vec3.ZERO else points[nearestIndex(points, a.tNs)].p
            PathAnnotation(a.tNs, a.kind, a.note, p)
        }

    fun placeKeyframes(points: List<PathPoint>, keyframes: List<KeyframeSample>): List<PathKeyframe> =
        keyframes.map { k ->
            val near = if (points.isEmpty()) null else points[nearestIndex(points, k.tNs)]
            PathKeyframe(k.tNs, k.fileName, near?.p ?: Vec3.ZERO, near?.headingRad ?: 0.0)
        }

    fun stats(points: List<PathPoint>, durationS: Double, stepCount: Int, closureErrorM: Double?): PathStats {
        var distance = 0.0
        var minZ = 0.0
        var maxZ = 0.0
        var vio = 0
        for (i in points.indices) {
            val p = points[i].p
            if (i == 0) {
                minZ = p.z
                maxZ = p.z
            } else {
                distance += p.distanceTo(points[i - 1].p)
                if (p.z < minZ) minZ = p.z
                if (p.z > maxZ) maxZ = p.z
            }
            if (points[i].source == PositionSource.VIO) vio++
        }
        val vioFraction = if (points.isEmpty()) 0.0 else vio.toDouble() / points.size
        return PathStats(distance, durationS, stepCount, minZ, maxZ, closureErrorM, vioFraction)
    }

    /** Index of the point whose time is nearest to [tNs] (ties go to the earlier point); -1 for an empty list. */
    fun nearestIndex(points: List<PathPoint>, tNs: Long): Int {
        if (points.isEmpty()) return -1
        var lo = 0
        var hi = points.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (points[mid].tNs < tNs) lo = mid + 1 else hi = mid
        }
        // lo is the first point with time >= tNs; compare with its predecessor.
        if (lo > 0 && abs(points[lo - 1].tNs - tNs) <= abs(points[lo].tNs - tNs)) return lo - 1
        return lo
    }
}
