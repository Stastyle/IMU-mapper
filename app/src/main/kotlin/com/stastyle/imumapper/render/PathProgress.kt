package com.stastyle.imumapper.render

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.sqrt

/**
 * How far along its path each point is: the [ColorMode.PROGRESS] colouring, shared by the viewer's scene, the trip
 * list's thumbnails, the elevation chart and the calibration preview so they all agree on which colour a place gets.
 *
 * Always computed over the full, undecimated point list. A decimated list would put the colours of a thumbnail and of
 * the viewer, which keep different points, at different places on the same path.
 */
object PathProgress {
    /** Below this 3D length, in metres, a path has no length to divide by and [fractions] falls back to the index. */
    const val MIN_LENGTH_M = 1e-9

    /**
     * Distance walked up to each point, in metres along the 3D polyline, starting at 0. The last value equals
     * `PathStats.distanceM` for the same points: the segments are summed in the same order with the same arithmetic.
     */
    fun cumulative(points: List<Vec3>): DoubleArray = cumulativeOf(points) { it }

    /** [cumulative] for path points. */
    @JvmName("cumulativeOfPathPoints")
    fun cumulative(points: List<PathPoint>): DoubleArray = cumulativeOf(points) { it.p }

    /**
     * Each point's share of the whole length, from 0 at the first point to 1 at the last. A path shorter than
     * [MIN_LENGTH_M] (every point in one place) gets the index fraction instead, so its colours still run from the
     * first stop to the last; a single point gets 0.
     */
    fun fractions(cumulative: DoubleArray): DoubleArray {
        val n = cumulative.size
        if (n == 0) return DoubleArray(0)
        if (n == 1) return DoubleArray(1)
        val total = cumulative[n - 1]
        // Written as one positive test so a NaN or infinite length takes the index fallback too.
        return if (total >= MIN_LENGTH_M && total.isFinite()) {
            DoubleArray(n) { i -> cumulative[i] / total }
        } else {
            DoubleArray(n) { i -> i.toDouble() / (n - 1) }
        }
    }

    /** [fractions] of the [cumulative] distances of [points]. */
    fun fractions(points: List<Vec3>): DoubleArray = fractions(cumulative(points))

    /** [fractions] of the [cumulative] distances of path [points]. */
    @JvmName("fractionsOfPathPoints")
    fun fractions(points: List<PathPoint>): DoubleArray = fractions(cumulative(points))

    /** The PROGRESS colour (ARGB) of a point at [fraction] of the path. */
    fun color(fraction: Double): Int = SceneColors.gradient(SceneColors.PROGRESS_STOPS, fraction)

    private inline fun <T> cumulativeOf(points: List<T>, position: (T) -> Vec3): DoubleArray {
        val out = DoubleArray(points.size)
        if (points.isEmpty()) return out
        var previous = position(points[0])
        for (i in 1 until points.size) {
            val p = position(points[i])
            // Vec3.distanceTo without the temporary vector; the same operations in the same order, so the sum
            // matches PathBuilder.stats to the last bit.
            val dx = p.x - previous.x
            val dy = p.y - previous.y
            val dz = p.z - previous.z
            out[i] = out[i - 1] + sqrt(dx * dx + dy * dy + dz * dz)
            previous = p
        }
        return out
    }
}
