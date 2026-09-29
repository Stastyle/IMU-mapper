package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3

/**
 * Places moments of the walk on a path and measures distance along it. A PDR path has one point per
 * step, and between two steps separated by a long gap (standing, or a pause) the walker stayed at the
 * earlier step: in the segment from point i to i+1, when point i+1 is a PDR point, the position holds at
 * p(i) and moves to p(i+1) over the last min(gap, HOLD_STEP_FACTOR x medianStepNs) of the gap. Other
 * segments (VIO, INTERPOLATED) are linear in time. Distances are 3D and cumulative from the first point.
 */
class PathTimeline(val points: List<PathPoint>) {
    init {
        require(points.isNotEmpty()) { "a timeline needs at least one point" }
    }

    private val n = points.size
    private val t = LongArray(n) { points[it].tNs }
    private val cum = DoubleArray(n).also { c ->
        for (i in 1 until n) c[i] = c[i - 1] + points[i].p.distanceTo(points[i - 1].p)
    }

    val startNs: Long = t[0]
    val endNs: Long = t[n - 1]

    /** 3D length of the whole path, metres. */
    val lengthM: Double = cum[n - 1]

    /**
     * Median positive gap between consecutive step points (both stepIndex >= 0, later one PDR);
     * DEFAULT_STEP_NS when there is none.
     */
    val medianStepNs: Long = medianStep()

    /** When the walker leaves p(i) in segment i; t[i] for a linear segment. */
    private val moveStart = LongArray(n - 1).also { m ->
        val window = Math.round(HOLD_STEP_FACTOR * medianStepNs)
        for (i in 0 until n - 1) {
            m[i] = if (points[i + 1].source == PositionSource.PDR) t[i + 1] - minOf(t[i + 1] - t[i], window) else t[i]
        }
    }

    /** Distance along the path at point [index]. */
    fun distanceOfPoint(index: Int): Double = cum[index]

    /** Where the walker was at [tNs]; clamped to the path's time range. */
    fun positionAt(tNs: Long): Vec3 {
        if (tNs <= startNs) return points[0].p
        if (tNs >= endNs) return points[n - 1].p
        val i = firstAfter(tNs) - 1
        return points[i].p.lerp(points[i + 1].p, fraction(i, tNs))
    }

    /** Metres along the path at [tNs]; clamped to [0, lengthM]. */
    fun distanceAt(tNs: Long): Double {
        if (tNs <= startNs) return 0.0
        if (tNs >= endNs) return lengthM
        val i = firstAfter(tNs) - 1
        return cum[i] + (cum[i + 1] - cum[i]) * fraction(i, tNs)
    }

    /** The point [distanceM] along the path (linear by distance inside a segment); clamped. */
    fun positionAtDistance(distanceM: Double): Vec3 {
        val s = distanceM.coerceIn(0.0, lengthM)
        val j = firstReaching(s)
        if (j == 0 || cum[j] == s) return points[j].p
        val f = (s - cum[j - 1]) / (cum[j] - cum[j - 1])
        return points[j - 1].p.lerp(points[j].p, f)
    }

    /** The earliest moment the walker reached [distanceM]; clamped. Standing still takes no distance. */
    fun timeAtDistance(distanceM: Double): Long {
        val s = distanceM.coerceIn(0.0, lengthM)
        val j = firstReaching(s)
        if (j == 0) return startNs
        if (cum[j] == s) return t[j]
        // Inside segment j - 1 the distance only grows while the walker moves, from moveStart to t[j].
        val from = moveStart[j - 1]
        val f = (s - cum[j - 1]) / (cum[j] - cum[j - 1])
        return from + Math.round(f * (t[j] - from))
    }

    /**
     * positionAt(min), every point strictly between the two times, positionAt(max), in time order; the
     * ends may be given in either order.
     */
    fun samplesBetween(fromNs: Long, toNs: Long): List<Vec3> {
        val lo = minOf(fromNs, toNs)
        val hi = maxOf(fromNs, toNs)
        val out = ArrayList<Vec3>()
        out.add(positionAt(lo))
        var k = firstAfter(lo)
        while (k < n && t[k] < hi) out.add(points[k++].p)
        out.add(positionAt(hi))
        return out
    }

    /** Time of the last point strictly before [tNs], or startNs. Used by the scrubber's back button. */
    fun previousPointNs(tNs: Long): Long {
        val k = firstNotBefore(tNs) - 1
        return if (k < 0) startNs else t[k]
    }

    /** Time of the first point strictly after [tNs], or endNs. */
    fun nextPointNs(tNs: Long): Long {
        val k = firstAfter(tNs)
        return if (k >= n) endNs else t[k]
    }

    /** Share of segment [i] covered at [tNs]: 0 while the walker still stands at p(i). */
    private fun fraction(i: Int, tNs: Long): Double {
        val end = t[i + 1]
        val from = moveStart[i]
        return when {
            tNs >= end -> 1.0
            tNs <= from -> 0.0
            else -> (tNs - from).toDouble() / (end - from)
        }
    }

    /** First index whose time is after [tNs], or n. */
    private fun firstAfter(tNs: Long): Int {
        var lo = 0
        var hi = n
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] <= tNs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index whose time is at or after [tNs], or n. */
    private fun firstNotBefore(tNs: Long): Int {
        var lo = 0
        var hi = n
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] < tNs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index whose cumulative distance is at least [s] (s within [0, lengthM]). */
    private fun firstReaching(s: Double): Int {
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] < s) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun medianStep(): Long {
        val gaps = ArrayList<Long>()
        for (i in 0 until n - 1) {
            val a = points[i]
            val b = points[i + 1]
            val gap = b.tNs - a.tNs
            if (a.stepIndex >= 0 && b.stepIndex >= 0 && b.source == PositionSource.PDR && gap > 0) gaps.add(gap)
        }
        if (gaps.isEmpty()) return DEFAULT_STEP_NS
        gaps.sort()
        // Standing gaps are few and long, so the median is the walking cadence.
        return (gaps[(gaps.size - 1) / 2] + gaps[gaps.size / 2]) / 2
    }

    companion object {
        const val HOLD_STEP_FACTOR: Double = 1.5

        /** A typical walking step period, used when a path has too few steps to measure one. */
        const val DEFAULT_STEP_NS: Long = 550_000_000L
    }
}
