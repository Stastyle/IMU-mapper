package com.stastyle.imumapper.pipeline.post

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.abs

/**
 * Loop closure for a user-declared "back at start". The position error at the closure point is
 * spread over the path in proportion to distance walked, which is where dead-reckoning error
 * accumulates. Several LOOP_CLOSED annotations (laps) are closed in order, each over the stretch
 * since the previous closure; points after the last closure are shifted by the full correction.
 * The reported error is measured on the uncorrected path at the last closure.
 */
object LoopClosure {

    class Result(val points: List<PathPoint>, val closureErrorM: Double)

    /** Null when no closure time lands after the first point (nothing to correct). */
    fun apply(points: List<PathPoint>, closureTimesNs: List<Long>): Result? {
        val n = points.size
        if (n < 2 || closureTimesNs.isEmpty()) return null
        val indices = closureTimesNs.sorted().map { PathBuilder.nearestIndex(points, it) }.filter { it > 0 }.distinct()
        if (indices.isEmpty()) return null

        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + points[i].p.distanceTo(points[i - 1].p)
        val x = DoubleArray(n) { points[it].p.x }
        val y = DoubleArray(n) { points[it].p.y }
        val z = DoubleArray(n) { points[it].p.z }
        val origin = points[0].p
        val closureErrorM = points[indices[indices.size - 1]].p.distanceTo(origin)

        var prev = 0
        for (k in indices) {
            val ex = x[k] - origin.x
            val ey = y[k] - origin.y
            val ez = z[k] - origin.z
            val span = cum[k] - cum[prev]
            for (i in prev + 1..k) {
                // Fall back to an index fraction when the stretch has no length (all points coincide).
                val f = if (abs(span) > 1e-12) (cum[i] - cum[prev]) / span else (i - prev).toDouble() / (k - prev)
                x[i] -= ex * f
                y[i] -= ey * f
                z[i] -= ez * f
            }
            for (i in k + 1 until n) {
                x[i] -= ex
                y[i] -= ey
                z[i] -= ez
            }
            prev = k
        }
        val out = ArrayList<PathPoint>(n)
        for (i in 0 until n) out.add(points[i].copy(p = Vec3(x[i], y[i], z[i])))
        return Result(out, closureErrorM)
    }
}
