package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.AccelSample
import com.stastyle.imumapper.pipeline.core.Vec3

/**
 * Accelerometer samples rotated into ENU with gravity removed, as parallel arrays: [vertical] is
 * the up component minus g (drives step detection), [east] and [north] are the horizontal
 * components (drive the stride model and the heading-offset estimate). Samples whose timestamp
 * goes backwards are dropped and counted in [dropped].
 */
class WorldAccel private constructor(
    val tNs: LongArray,
    val vertical: DoubleArray,
    val east: DoubleArray,
    val north: DoubleArray,
    val dropped: Int,
) {
    val size: Int get() = tNs.size
    val isEmpty: Boolean get() = tNs.isEmpty()

    /** Index of the first sample with time >= [t]; equals [size] when none. */
    fun lowerBound(t: Long): Int {
        var lo = 0
        var hi = tNs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tNs[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Peak-to-valley swing of [vertical] over the sample range [from, to); 0 for an empty range. */
    fun verticalSwing(from: Int, to: Int): Double {
        if (from >= to) return 0.0
        var mx = Double.NEGATIVE_INFINITY
        var mn = Double.POSITIVE_INFINITY
        for (i in from until to) {
            val v = vertical[i]
            if (v > mx) mx = v
            if (v < mn) mn = v
        }
        return mx - mn
    }

    companion object {
        const val GRAVITY: Double = 9.81

        fun compute(accel: List<AccelSample>, orientation: OrientationTrack, gravity: Double = GRAVITY): WorldAccel {
            val n = accel.size
            val t = LongArray(n)
            val v = DoubleArray(n)
            val e = DoubleArray(n)
            val no = DoubleArray(n)
            val cursor = orientation.cursor()
            var k = 0
            var dropped = 0
            var last = Long.MIN_VALUE
            for (s in accel) {
                if (s.tNs < last) {
                    dropped++
                    continue
                }
                last = s.tNs
                val w = cursor.at(s.tNs).rotate(Vec3.of(s.x, s.y, s.z))
                t[k] = s.tNs
                v[k] = w.z - gravity
                e[k] = w.x
                no[k] = w.y
                k++
            }
            return WorldAccel(t.copyOf(k), v.copyOf(k), e.copyOf(k), no.copyOf(k), dropped)
        }
    }
}
