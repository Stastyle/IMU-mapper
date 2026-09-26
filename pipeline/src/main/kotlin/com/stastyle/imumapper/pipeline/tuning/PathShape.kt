package com.stastyle.imumapper.pipeline.tuning

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.pdr.Angles
import kotlin.math.sqrt

/** A change of walking direction. [angleDeg] is signed, positive clockwise (a right turn). */
class Turn(
    /** Index into the point list where the new direction starts. */
    val pointIndex: Int,
    val tNs: Long,
    val angleDeg: Double,
    /** Distance walked from the start to the turn, metres. */
    val atDistanceM: Double,
)

/** A stretch between two turns (or the start / end) walked in one direction. */
class Leg(
    val fromIndex: Int,
    val toIndex: Int,
    val lengthM: Double,
    /** Mean walking direction, degrees clockwise from north. */
    val headingDeg: Double,
)

class ShapeAnalysis(val turns: List<Turn>, val legs: List<Leg>)

/**
 * Splits a path into legs and turns from the walking heading stored with every point. A turn is
 * declared when the mean heading of the next [window] points differs from the mean heading of
 * the current leg's last [window] points by at least [thresholdDeg]; the transition points are
 * handed to the new leg so a slow corner is still one turn. Pure geometry, works for PDR and
 * VIO paths alike.
 */
object PathShape {

    const val DEFAULT_WINDOW: Int = 4
    const val DEFAULT_THRESHOLD_DEG: Double = 40.0

    fun analyse(
        points: List<PathPoint>,
        window: Int = DEFAULT_WINDOW,
        thresholdDeg: Double = DEFAULT_THRESHOLD_DEG,
    ): ShapeAnalysis {
        val n = points.size
        if (n < 2 || window < 1) return ShapeAnalysis(emptyList(), legs(points, emptyList()))
        val headings = DoubleArray(n) { points[it].headingRad }
        val threshold = Math.toRadians(thresholdDeg)
        val turns = ArrayList<Turn>()
        val boundaries = ArrayList<Int>()
        // The first point is the origin and carries the first step's heading; legs start at index 1.
        var legStart = 1
        var i = 1 + window
        while (i + window <= n) {
            val delta: Double = change(headings, legStart, i, window) ?: 0.0
            if (Math.abs(delta) < threshold) {
                i++
                continue
            }
            // The look-ahead window first straddles the corner; the corner itself is the point
            // within one window where the before/after difference peaks, and that difference
            // is the whole turn rather than the half of it seen from the straddling window.
            var corner = i
            var best = Math.abs(delta)
            var angle = delta
            for (j in i + 1..minOf(i + window, n - window)) {
                val d = change(headings, legStart, j, window) ?: continue
                if (Math.abs(d) > best) {
                    best = Math.abs(d)
                    angle = d
                    corner = j
                }
            }
            turns.add(Turn(corner, points[corner].tNs, Math.toDegrees(angle), distanceTo(points, corner)))
            boundaries.add(corner)
            legStart = corner
            i = corner + window
        }
        return ShapeAnalysis(turns, legs(points, boundaries))
    }

    /** Heading of the [window] points from [at] minus the heading of the [window] points before it (not before [legStart]). */
    private fun change(headings: DoubleArray, legStart: Int, at: Int, window: Int): Double? {
        val before = Angles.circularMean(headings, maxOf(legStart, at - window), at) ?: return null
        val after = Angles.circularMean(headings, at, at + window) ?: return null
        return Angles.diff(after, before)
    }

    private fun legs(points: List<PathPoint>, boundaries: List<Int>): List<Leg> {
        if (points.size < 2) return emptyList()
        val out = ArrayList<Leg>()
        var from = 1
        val headings = DoubleArray(points.size) { points[it].headingRad }
        for (b in boundaries + points.size) {
            if (b > from) {
                var length = 0.0
                for (k in from until b) length += horizontal(points[k - 1], points[k])
                val mean = Angles.circularMean(headings, from, b) ?: headings[from]
                out.add(Leg(from, b - 1, length, Math.toDegrees(mean)))
            }
            from = b
        }
        return out
    }

    /** Path length from the start up to point [index], metres (3D, like the stats). */
    fun distanceTo(points: List<PathPoint>, index: Int): Double {
        var d = 0.0
        for (k in 1..minOf(index, points.size - 1)) d += points[k].p.distanceTo(points[k - 1].p)
        return d
    }

    private fun horizontal(a: PathPoint, b: PathPoint): Double {
        val dx = b.p.x - a.p.x
        val dy = b.p.y - a.p.y
        return sqrt(dx * dx + dy * dy)
    }
}
