package com.stastyle.imumapper.render

import com.stastyle.imumapper.pipeline.core.PathPoint
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Height against distance along a path, reduced to at most a few hundred samples for a chart. Each sample keeps the
 * lowest and the highest height of the points it stands for, so a short peak or dip survives the reduction.
 */
class ElevationProfile(
    /** Distance along the path of each sample in metres, ascending from 0 at the first to [totalM] at the last. */
    val distanceM: DoubleArray,
    /** Lowest height (ENU z, metres) among the points of each sample. */
    val minZ: DoubleArray,
    /** Highest height (ENU z, metres) among the points of each sample. */
    val maxZ: DoubleArray,
    /** The path's 3D length in metres, as [PathProgress.cumulative] measures it. */
    val totalM: Double,
) {
    val size: Int get() = distanceM.size

    /** The PROGRESS fraction of sample [i], so the chart takes the path's colours; 0 for a path with no length. */
    fun fraction(i: Int): Double =
        if (totalM >= PathProgress.MIN_LENGTH_M) (distanceM[i] / totalM).coerceIn(0.0, 1.0) else 0.0
}

/** A height axis symmetric about the start's height (z = 0) from [minZ] to [maxZ], labelled at [ticks]. */
class HeightAxis(val minZ: Double, val maxZ: Double, val ticks: DoubleArray)

/** Metres climbed and descended along a path, each at least a dead band at a time. */
data class ClimbTotals(val climbM: Double, val descentM: Double)

/** Pure helpers behind the viewer's elevation chart and its height tiles. */
object PathProfile {
    const val DEFAULT_MAX_BUCKETS = 256

    /** The smallest half-range of [axis]: a flat path still gets a band of ±1 m around its start. */
    const val MIN_AXIS_HALF_RANGE_M = 1.0

    /** Mantissas of the half-ranges [axis] picks from: round numbers that still hug the data. */
    private val NICE_MANTISSAS = doubleArrayOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)

    /**
     * Height against distance for [points], with distances from [PathProgress.cumulative] over every point. A path
     * of at most [maxBuckets] points gives one sample per point. A longer one is gathered into [maxBuckets] evenly
     * spaced distances, each point going to the nearest; a distance no point is near (a long straight segment) has no
     * sample, and the chart's line bridges it as the path does. A path with no length is one sample at distance 0.
     */
    fun elevation(points: List<PathPoint>, maxBuckets: Int = DEFAULT_MAX_BUCKETS): ElevationProfile {
        val n = points.size
        if (n == 0) return ElevationProfile(DoubleArray(0), DoubleArray(0), DoubleArray(0), 0.0)
        val cumulative = PathProgress.cumulative(points)
        val total = cumulative[n - 1]
        if (!(total >= PathProgress.MIN_LENGTH_M && total.isFinite())) {
            var lo = points[0].p.z
            var hi = lo
            for (p in points) {
                lo = min(lo, p.p.z)
                hi = max(hi, p.p.z)
            }
            return ElevationProfile(DoubleArray(1), doubleArrayOf(lo), doubleArrayOf(hi), 0.0)
        }
        val buckets = maxBuckets.coerceAtLeast(2)
        if (n <= buckets) {
            val z = DoubleArray(n) { points[it].p.z }
            return ElevationProfile(cumulative, z, z.copyOf(), total)
        }

        val lo = DoubleArray(buckets) { Double.POSITIVE_INFINITY }
        val hi = DoubleArray(buckets) { Double.NEGATIVE_INFINITY }
        val last = buckets - 1
        for (i in 0 until n) {
            val b = (cumulative[i] / total * last).roundToInt().coerceIn(0, last)
            val z = points[i].p.z
            if (z < lo[b]) lo[b] = z
            if (z > hi[b]) hi[b] = z
        }
        var used = 0
        for (b in 0..last) if (lo[b] <= hi[b]) used++
        val distance = DoubleArray(used)
        val minZ = DoubleArray(used)
        val maxZ = DoubleArray(used)
        var k = 0
        for (b in 0..last) {
            if (lo[b] > hi[b]) continue
            // The last bucket sits exactly on the total, not on a product that rounds a hair short of it.
            distance[k] = if (b == last) total else total * b / last
            minZ[k] = lo[b]
            maxZ[k] = hi[b]
            k++
        }
        return ElevationProfile(distance, minZ, maxZ, total)
    }

    /**
     * An axis symmetric about the start's height, as the chart draws it with the zero line in the middle: the
     * half-range is the larger of |[minZ]| and |[maxZ]| (at least [MIN_AXIS_HALF_RANGE_M]) rounded up to a round
     * number, with ticks at its two ends and at zero. A non-finite bound counts as zero.
     */
    fun axis(minZ: Double, maxZ: Double): HeightAxis {
        fun magnitude(z: Double) = if (z.isFinite()) abs(z) else 0.0
        val half = niceCeil(max(max(magnitude(minZ), magnitude(maxZ)), MIN_AXIS_HALF_RANGE_M))
        return HeightAxis(-half, half, doubleArrayOf(-half, 0.0, half))
    }

    /**
     * Metres climbed and descended over the full-resolution [points], ignoring any wobble smaller than [deadBandM].
     * Summing every rise between neighbouring points would count the bob of each step and barometer noise as climb,
     * hundreds of metres over a long walk. Instead a turning point is confirmed only once the height has moved at
     * least [deadBandM] back the other way, and only the legs between confirmed turning points are counted; the
     * final leg counts when it too spans the dead band.
     */
    fun climbs(points: List<PathPoint>, deadBandM: Double = 0.5): ClimbTotals {
        if (points.size < 2) return ClimbTotals(0.0, 0.0)
        var climb = 0.0
        var descent = 0.0
        // The last confirmed turning point, and the extreme reached since it in the current direction.
        var turn = points[0].p.z
        var extreme = turn
        // Before the first leg is confirmed, the lowest and highest heights so far are both candidates.
        var low = turn
        var high = turn
        var direction = 0
        for (i in 1 until points.size) {
            val z = points[i].p.z
            when (direction) {
                0 -> {
                    low = min(low, z)
                    high = max(high, z)
                    if (z - low >= deadBandM) {
                        turn = low
                        extreme = z
                        direction = 1
                    } else if (high - z >= deadBandM) {
                        turn = high
                        extreme = z
                        direction = -1
                    }
                }
                1 -> if (z > extreme) {
                    extreme = z
                } else if (extreme - z >= deadBandM) {
                    climb += extreme - turn
                    turn = extreme
                    extreme = z
                    direction = -1
                }
                else -> if (z < extreme) {
                    extreme = z
                } else if (z - extreme >= deadBandM) {
                    descent += turn - extreme
                    turn = extreme
                    extreme = z
                    direction = 1
                }
            }
        }
        // The open leg already spans the dead band: it was confirmed when it started.
        if (direction == 1) climb += extreme - turn
        if (direction == -1) descent += turn - extreme
        return ClimbTotals(climb, descent)
    }

    /** The smallest value of the form mantissa × 10^k, with a mantissa from [NICE_MANTISSAS], at or above [x] > 0. */
    private fun niceCeil(x: Double): Double {
        val scale = 10.0.pow(floor(log10(x)))
        val mantissa = x / scale
        for (m in NICE_MANTISSAS) {
            // The tolerance keeps a value a rounding error above a round number on that number.
            if (mantissa <= m * (1 + 1e-9)) return m * scale
        }
        return 10.0 * scale
    }
}
