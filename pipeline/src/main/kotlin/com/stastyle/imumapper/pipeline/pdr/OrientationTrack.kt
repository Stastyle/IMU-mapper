package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.RotationSample

/**
 * Device-to-ENU orientation as a function of time. Samples are stored as parallel primitive arrays
 * so a 10-minute log at several hundred hertz costs a few megabytes, and [at] answers any timestamp
 * by slerp between the two neighbouring samples (clamped to the ends).
 */
class OrientationTrack private constructor(
    private val times: LongArray,
    private val qw: DoubleArray,
    private val qx: DoubleArray,
    private val qy: DoubleArray,
    private val qz: DoubleArray,
) {
    val size: Int get() = times.size
    val isEmpty: Boolean get() = times.isEmpty()
    val firstNs: Long get() = if (times.isEmpty()) 0L else times[0]
    val lastNs: Long get() = if (times.isEmpty()) 0L else times[times.size - 1]

    fun timeAt(i: Int): Long = times[i]

    fun quatAt(i: Int): Quat = Quat(qw[i], qx[i], qy[i], qz[i])

    /** Orientation at [tNs]; identity for an empty track. Prefer a [Cursor] for monotonic sweeps. */
    fun at(tNs: Long): Quat {
        if (times.isEmpty()) return Quat.IDENTITY
        return interpolate(floorIndex(tNs), tNs)
    }

    fun cursor(): Cursor = Cursor()

    /** Index of the last sample with time <= [tNs], or -1 when [tNs] is before the first sample. */
    fun floorIndex(tNs: Long): Int {
        var lo = 0
        var hi = times.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= tNs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun interpolate(i: Int, tNs: Long): Quat {
        if (i < 0) return quatAt(0)
        if (i >= times.size - 1) return quatAt(times.size - 1)
        val t0 = times[i]
        val t1 = times[i + 1]
        if (t1 <= t0) return quatAt(i)
        val f = (tNs - t0).toDouble() / (t1 - t0).toDouble()
        return quatAt(i).slerp(quatAt(i + 1), f)
    }

    /**
     * Sequential lookup that remembers the last index so a time-ordered sweep over another sensor's
     * samples costs O(1) per query instead of a binary search each time. Going backwards in time is
     * allowed and falls back to the binary search.
     */
    inner class Cursor {
        private var index = -1

        fun at(tNs: Long): Quat {
            if (times.isEmpty()) return Quat.IDENTITY
            if (index >= 0 && tNs < times[index]) {
                index = floorIndex(tNs)
            } else {
                while (index + 1 < times.size && times[index + 1] <= tNs) index++
            }
            return interpolate(index, tNs)
        }
    }

    /** Collects samples in time order; samples whose timestamp goes backwards are dropped. */
    class Builder(capacity: Int = 1024) {
        private var times = LongArray(capacity.coerceAtLeast(16))
        private var qw = DoubleArray(times.size)
        private var qx = DoubleArray(times.size)
        private var qy = DoubleArray(times.size)
        private var qz = DoubleArray(times.size)
        private var n = 0
        var dropped: Int = 0
            private set

        val size: Int get() = n

        fun add(tNs: Long, q: Quat): Builder {
            if (n > 0 && tNs < times[n - 1]) {
                dropped++
                return this
            }
            if (n == times.size) grow()
            times[n] = tNs
            qw[n] = q.w
            qx[n] = q.x
            qy[n] = q.y
            qz[n] = q.z
            n++
            return this
        }

        private fun grow() {
            val cap = times.size * 2
            times = times.copyOf(cap)
            qw = qw.copyOf(cap)
            qx = qx.copyOf(cap)
            qy = qy.copyOf(cap)
            qz = qz.copyOf(cap)
        }

        fun build(): OrientationTrack =
            OrientationTrack(times.copyOf(n), qw.copyOf(n), qx.copyOf(n), qy.copyOf(n), qz.copyOf(n))
    }

    companion object {
        val EMPTY: OrientationTrack = Builder(16).build()

        fun fromRotationSamples(samples: List<RotationSample>): OrientationTrack {
            val b = Builder(samples.size)
            for (s in samples) b.add(s.tNs, s.toQuat())
            return b.build()
        }
    }
}
