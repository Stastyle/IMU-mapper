package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.pdr.Angles
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** A turning vertex of the simplified plan. The vertex is always an existing path point: a place the walker stood. */
data class Corner(val pointIndex: Int, val tNs: Long, val turnDeg: Double)

/**
 * Finds the turns of a walk where nothing was marked: Ramer-Douglas-Peucker on the plan, then a turn
 * filter so a gentle bend or step sway is not a corner, then spacing so no leg is shorter than
 * MIN_SPACING_M of path.
 */
object CornerDetector {
    const val MIN_TURN_DEG: Double = 20.0
    const val MIN_SPACING_M: Double = 1.5

    /** Plan segments shorter than this (1 micrometre) have no direction. */
    private const val MIN_SEGMENT_SQUARED_M2: Double = 1e-12

    /** Ramer-Douglas-Peucker on the plan (x, y) of [points], iterative; sorted indices, first and last always kept. */
    fun simplify(points: List<PathPoint>, toleranceM: Double): IntArray {
        val n = points.size
        if (n <= 2) return IntArray(n) { it }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        // An explicit stack instead of recursion: an hour's walk has thousands of points.
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (lo, hi) = stack.removeLast()
            var best = -1
            var bestDistance = toleranceM
            for (k in lo + 1 until hi) {
                val d = planDistance(points[k].p, points[lo].p, points[hi].p)
                if (d > bestDistance) {
                    best = k
                    bestDistance = d
                }
            }
            if (best < 0) continue
            keep[best] = true
            stack.addLast(intArrayOf(lo, best))
            stack.addLast(intArrayOf(best, hi))
        }
        return (0 until n).filter { keep[it] }.toIntArray()
    }

    /**
     * The interior vertices whose plan direction turns by at least [minTurnDeg], in path order. The
     * weakest vertex below the limit is removed and its neighbours re-checked until every one passes.
     * A vertex next to a zero-length plan segment turns by 0.
     */
    fun turning(points: List<PathPoint>, vertices: IntArray, minTurnDeg: Double = MIN_TURN_DEG): List<Corner> {
        val kept = vertices.toMutableList()
        // turns[k] belongs to kept[k]; the ends never turn.
        val turns = MutableList(kept.size) { k ->
            if (k == 0 || k == kept.size - 1) Double.MAX_VALUE else turnDeg(points, kept[k - 1], kept[k], kept[k + 1])
        }
        while (true) {
            var weakest = -1
            for (k in 1 until kept.size - 1) {
                if (turns[k] < minTurnDeg && (weakest < 0 || turns[k] < turns[weakest])) weakest = k
            }
            if (weakest < 0) break
            kept.removeAt(weakest)
            turns.removeAt(weakest)
            // Removing a vertex straightens its neighbours' segments, so only they change.
            for (k in weakest - 1..weakest) {
                if (k >= 1 && k <= kept.size - 2) turns[k] = turnDeg(points, kept[k - 1], kept[k], kept[k + 1])
            }
        }
        return (1 until kept.size - 1).map { k -> Corner(kept[k], points[kept[k]].tNs, turns[k]) }
    }

    /**
     * Corner times for [detail] on [timeline]: turning vertices taken strongest first (ties: earlier
     * first), each dropped when it lies within MIN_SPACING_M of path distance of a kept time or of a
     * corner already taken; returned in time order.
     */
    fun corners(timeline: PathTimeline, detail: Detail, keptTimesNs: List<Long>): List<Long> {
        val points = timeline.points
        val candidates = turning(points, simplify(points, detail.toleranceM))
        val anchors = keptTimesNs.map { timeline.distanceAt(it) }
        val takenAt = ArrayList<Double>()
        val taken = ArrayList<Long>()
        for (corner in candidates.sortedWith(compareByDescending<Corner> { it.turnDeg }.thenBy { it.tNs })) {
            val d = timeline.distanceAt(corner.tNs)
            if (anchors.any { abs(it - d) < MIN_SPACING_M } || takenAt.any { abs(it - d) < MIN_SPACING_M }) continue
            takenAt.add(d)
            taken.add(corner.tNs)
        }
        return taken.sorted()
    }

    /** Degrees the plan direction turns at [b] between the segments from [a] and to [c]. */
    private fun turnDeg(points: List<PathPoint>, a: Int, b: Int, c: Int): Double {
        val p = points[a].p
        val q = points[b].p
        val r = points[c].p
        val x1 = q.x - p.x
        val y1 = q.y - p.y
        val x2 = r.x - q.x
        val y2 = r.y - q.y
        if (x1 * x1 + y1 * y1 < MIN_SEGMENT_SQUARED_M2 || x2 * x2 + y2 * y2 < MIN_SEGMENT_SQUARED_M2) return 0.0
        return Math.toDegrees(abs(Angles.diff(atan2(x2, y2), atan2(x1, y1))))
    }

    /** Plan distance from [p] to the segment [a]-[b], or to [a] when they coincide. */
    private fun planDistance(p: Vec3, a: Vec3, b: Vec3): Double {
        val ex = b.x - a.x
        val ey = b.y - a.y
        val px = p.x - a.x
        val py = p.y - a.y
        val len2 = ex * ex + ey * ey
        // A segment, not a line: an out-and-back ends where it started, and its far end must still count.
        val f = if (len2 > 0.0) ((px * ex + py * ey) / len2).coerceIn(0.0, 1.0) else 0.0
        return hypot(px - f * ex, py - f * ey)
    }
}
