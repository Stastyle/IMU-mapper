package com.stastyle.imumapper.render

import com.stastyle.imumapper.pipeline.core.PathPoint
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min

/**
 * A small plan view of a path for a trip card: at most [MAX_VERTICES] vertices in the unit square, north up and never
 * mirrored, the aspect ratio kept and the shape centred. Each vertex carries its PROGRESS fraction from the full,
 * undecimated path, so the card's colours sit where the viewer's do.
 *
 * Plain arrays so a cache can store it in any format; [equals] compares their contents.
 */
data class PathThumbnail(
    /** Vertex x in [0, 1], east to the right. */
    val x: FloatArray,
    /** Vertex y in [0, 1], growing downwards as on a canvas, so north is towards 0. */
    val y: FloatArray,
    /** Each vertex's [PathProgress] fraction in [0, 1]. */
    val progress: FloatArray,
) {
    init {
        require(x.size == y.size && y.size == progress.size) {
            "vertex arrays differ in length: ${x.size}, ${y.size}, ${progress.size}"
        }
    }

    val size: Int get() = x.size

    override fun equals(other: Any?): Boolean =
        other is PathThumbnail && x.contentEquals(other.x) && y.contentEquals(other.y) &&
            progress.contentEquals(other.progress)

    override fun hashCode(): Int = (x.contentHashCode() * 31 + y.contentHashCode()) * 31 + progress.contentHashCode()

    companion object {
        const val MAX_VERTICES = 96

        /**
         * The thumbnail of [points], with at most [maxVertices] (at least 2) vertices. Empty points give an empty
         * thumbnail; a single point, or points all in one place, sit in the centre.
         */
        fun build(points: List<PathPoint>, maxVertices: Int = MAX_VERTICES): PathThumbnail {
            val kept = keptIndices(points, maxVertices)
            if (kept.isEmpty()) return PathThumbnail(FloatArray(0), FloatArray(0), FloatArray(0))
            val fractions = PathProgress.fractions(points)
            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (i in kept) {
                val p = points[i].p
                minX = min(minX, p.x)
                maxX = max(maxX, p.x)
                minY = min(minY, p.y)
                maxY = max(maxY, p.y)
            }
            // One scale for both axes keeps the aspect; the longer side spans the square and the shorter is centred.
            val span = max(maxX - minX, maxY - minY)
            val centreX = (minX + maxX) / 2
            val centreY = (minY + maxY) / 2
            val flat = !(span >= PathProgress.MIN_LENGTH_M && span.isFinite())
            val xs = FloatArray(kept.size)
            val ys = FloatArray(kept.size)
            val progress = FloatArray(kept.size)
            for (k in kept.indices) {
                val p = points[kept[k]].p
                xs[k] = if (flat) 0.5f else (0.5 + (p.x - centreX) / span).toFloat().coerceIn(0f, 1f)
                // North is +y in the world and up on the card, where y grows downwards.
                ys[k] = if (flat) 0.5f else (0.5 - (p.y - centreY) / span).toFloat().coerceIn(0f, 1f)
                progress[k] = fractions[kept[k]].toFloat().coerceIn(0f, 1f)
            }
            return PathThumbnail(xs, ys, progress)
        }

        /**
         * The indices of the points a thumbnail keeps, ascending, the first and the last always among them. Half the
         * budget goes to points evenly spaced by distance, so no segment spans much of the colour ramp; the rest
         * goes to the points that most change the plan-view shape (Douglas-Peucker, one split at a time, largest
         * deviation first), so corners survive where evenly spaced points would round them off.
         */
        internal fun keptIndices(points: List<PathPoint>, maxVertices: Int): IntArray {
            val n = points.size
            val limit = maxVertices.coerceAtLeast(2)
            if (n <= limit) return IntArray(n) { it }

            val kept = BooleanArray(n)
            kept[0] = true
            kept[n - 1] = true
            val cumulative = PathProgress.cumulative(points)
            val total = cumulative[n - 1]
            if (total >= PathProgress.MIN_LENGTH_M && total.isFinite()) {
                val even = limit / 2
                var i = 0
                for (k in 1 until even - 1) {
                    val target = total * k / (even - 1)
                    while (i < n - 1 && cumulative[i] < target) i++
                    kept[i] = true
                }
            }
            var count = kept.count { it }

            // Ties go to the earlier split so the result never depends on the queue's internal order.
            val queue = PriorityQueue(compareByDescending<Split> { it.deviation }.thenBy { it.index })
            var from = 0
            for (i in 1 until n) {
                if (!kept[i]) continue
                farthest(points, from, i)?.let(queue::add)
                from = i
            }
            while (count < limit) {
                val split = queue.poll() ?: break
                // Every remaining point lies on the line drawn through it: more vertices would change nothing.
                if (split.deviation <= 0.0) break
                kept[split.index] = true
                count++
                farthest(points, split.from, split.index)?.let(queue::add)
                farthest(points, split.index, split.to)?.let(queue::add)
            }

            val out = IntArray(count)
            var k = 0
            for (i in 0 until n) if (kept[i]) out[k++] = i
            return out
        }

        /** A candidate vertex: the point strictly between [from] and [to] that deviates most from their chord. */
        private class Split(val from: Int, val to: Int, val index: Int, val deviation: Double)

        /**
         * The point between [from] and [to] farthest in plan view from the segment joining them, or null when there
         * is no point between. The deviation is squared, which orders the same and skips a square root per point;
         * distance to the segment, not the line, so a loop whose ends meet still has a farthest point.
         */
        private fun farthest(points: List<PathPoint>, from: Int, to: Int): Split? {
            if (to - from < 2) return null
            val a = points[from].p
            val b = points[to].p
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length2 = dx * dx + dy * dy
            var best = -1
            var bestDeviation = -1.0
            for (i in from + 1 until to) {
                val p = points[i].p
                val t = if (length2 > 0.0) (((p.x - a.x) * dx + (p.y - a.y) * dy) / length2).coerceIn(0.0, 1.0) else 0.0
                val ex = a.x + t * dx - p.x
                val ey = a.y + t * dy - p.y
                val deviation = ex * ex + ey * ey
                if (deviation > bestDeviation) {
                    bestDeviation = deviation
                    best = i
                }
            }
            // Only NaN coordinates leave no candidate; such a stretch is not worth a vertex.
            return if (best < 0) null else Split(from, to, best, bestDeviation)
        }
    }
}
