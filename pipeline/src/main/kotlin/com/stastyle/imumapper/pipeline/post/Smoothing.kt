package com.stastyle.imumapper.pipeline.post

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.Vec3

/** Centred moving average over positions; the first and last points stay where they are. */
object Smoothing {

    fun movingAverage(points: List<PathPoint>, window: Int): List<PathPoint> {
        val n = points.size
        if (window <= 1 || n < 3) return points
        val half = window / 2
        val out = ArrayList<PathPoint>(n)
        out.add(points[0])
        for (i in 1 until n - 1) {
            val from = maxOf(0, i - half)
            val to = minOf(n - 1, i + half)
            var sx = 0.0
            var sy = 0.0
            var sz = 0.0
            for (j in from..to) {
                val p = points[j].p
                sx += p.x
                sy += p.y
                sz += p.z
            }
            val count = (to - from + 1).toDouble()
            out.add(points[i].copy(p = Vec3(sx / count, sy / count, sz / count)))
        }
        out.add(points[n - 1])
        return out
    }
}
