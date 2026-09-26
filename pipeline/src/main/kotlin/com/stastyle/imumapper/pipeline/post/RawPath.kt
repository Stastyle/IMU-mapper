package com.stastyle.imumapper.pipeline.post

import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.Vec3

/**
 * The "raw path" view of a result: [PathResult.rawPoints] in place of the loop-closed and smoothed
 * [PathResult.points], with markers and statistics moved along. Stride, heading offset and
 * altitude are untouched, so metres and shape are the pipeline's; only the corrections that spread
 * the closure error over the walk and average neighbouring points are undone.
 */
object RawPath {

    /** True when [result] stores a raw path that differs from its final one. */
    fun isAvailable(result: PathResult): Boolean = result.rawPoints.isNotEmpty()

    /**
     * Returns [result] itself when it stores no raw points (nothing was moved, or the run predates
     * pipeline version 2). Otherwise a copy whose points are the raw ones; annotations and keyframes
     * are shifted by whatever post-processing moved the path point nearest in time, which puts a
     * marker placed on the path back on the raw path and keeps a keyframe placed by its own ARCore
     * pose at that pose. The stats are recomputed from the raw points; step count, duration and the
     * closure error (always measured on the raw path) are kept.
     */
    fun view(result: PathResult): PathResult {
        val raw = result.rawPoints
        if (raw.isEmpty()) return result
        val final = result.points
        // Post-processing never adds or drops points, so the same index names the same moment.
        val aligned = final.size == raw.size
        fun shiftAt(tNs: Long): Vec3 {
            val i = PathBuilder.nearestIndex(raw, tNs)
            return if (aligned) raw[i].p - final[i].p else Vec3.ZERO
        }
        val annotations = result.annotations.map { a -> a.copy(p = a.p + shiftAt(a.tNs)) }
        val keyframes = result.keyframes.map { k -> k.copy(p = k.p + shiftAt(k.tNs)) }
        val stats = PathBuilder.stats(raw, result.stats.durationS, result.stats.stepCount, result.stats.closureErrorM)
        return result.copy(points = raw, annotations = annotations, keyframes = keyframes, stats = stats, rawPoints = emptyList())
    }
}
