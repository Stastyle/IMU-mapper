package com.stastyle.imumapper.render

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PathThumbnailTest {

    private fun path(positions: List<Vec3>): List<PathPoint> =
        positions.mapIndexed { i, p -> PathPoint(i * 500_000_000L, p, PositionSource.PDR, 0.0) }

    /** A long winding walk: a spiral with a wobble, rising, 5 000 points. */
    private val winding: List<PathPoint> = path(
        (0 until 5_000).map { i ->
            val a = i * 0.004
            val r = 3.0 + i * 0.004 + 0.3 * sin(i * 0.2)
            Vec3(r * cos(a) + 7.0, r * sin(a) - 4.0, i * 0.001)
        },
    )

    @Test
    fun verticesAreFiniteAndInsideTheUnitSquare() {
        val thumb = PathThumbnail.build(winding)
        assertTrue(thumb.size > 2)
        for (k in 0 until thumb.size) {
            for (v in listOf(thumb.x[k], thumb.y[k], thumb.progress[k])) {
                assertTrue(v.isFinite() && v in 0f..1f, "vertex $k: $v")
            }
        }
        // The longer side spans the whole square.
        val xSpan = thumb.x.max() - thumb.x.min()
        val ySpan = thumb.y.max() - thumb.y.min()
        assertEquals(1f, maxOf(xSpan, ySpan), 1e-6f)
    }

    @Test
    fun atMostTheVertexLimit() {
        assertTrue(PathThumbnail.build(winding).size <= PathThumbnail.MAX_VERTICES)
        for (limit in listOf(2, 3, 10, 50)) {
            val thumb = PathThumbnail.build(winding, maxVertices = limit)
            assertTrue(thumb.size in 2..limit, "limit $limit gave ${thumb.size}")
        }
        // A path within the limit keeps every point.
        assertEquals(40, PathThumbnail.build(winding.take(40)).size)
    }

    @Test
    fun theFirstAndLastPointsAreKept() {
        val kept = PathThumbnail.keptIndices(winding, 96)
        assertEquals(0, kept.first())
        assertEquals(winding.size - 1, kept.last())
        for (i in 1 until kept.size) assertTrue(kept[i] > kept[i - 1])
        val thumb = PathThumbnail.build(winding)
        assertEquals(0f, thumb.progress.first())
        assertEquals(1f, thumb.progress.last())
    }

    @Test
    fun aKeptVertexCarriesTheFullResolutionProgress() {
        val kept = PathThumbnail.keptIndices(winding, PathThumbnail.MAX_VERTICES)
        val thumb = PathThumbnail.build(winding)
        val fractions = PathProgress.fractions(winding)
        assertEquals(kept.size, thumb.size)
        for (k in kept.indices) assertEquals(fractions[kept[k]].toFloat(), thumb.progress[k])
    }

    @Test
    fun theColourRampHasNoLongJump() {
        // Half the budget is spread by distance, so no segment spans a big share of the ramp even on a straight line.
        val straight = path((0 until 1_000).map { Vec3(it * 0.1, 0.0, 0.0) })
        val thumb = PathThumbnail.build(straight)
        for (k in 1 until thumb.size) assertTrue(thumb.progress[k] - thumb.progress[k - 1] < 0.03f, "jump at $k")
    }

    @Test
    fun aCornerSurvives() {
        // An L: 300 m east, then 700 m north; the corner is point 600. With 10 vertices the ones spread by distance
        // fall at 250, 500 and 750 m, so only the shape split can pick the corner.
        val positions = (0..600).map { Vec3(it * 0.5, 0.0, 0.0) } + (1..1_400).map { Vec3(300.0, it * 0.5, 0.0) }
        val kept = PathThumbnail.keptIndices(path(positions), 10)
        assertTrue(600 in kept, "kept ${kept.toList()}")
    }

    @Test
    fun northGoesUpAndEastGoesRight() {
        val north = PathThumbnail.build(path((0..10).map { Vec3(0.0, it.toDouble(), 0.0) }))
        assertEquals(1f, north.y.first())
        assertEquals(0f, north.y.last())
        for (k in 1 until north.size) assertTrue(north.y[k] < north.y[k - 1])

        val east = PathThumbnail.build(path((0..10).map { Vec3(it.toDouble(), 0.0, 0.0) }))
        assertEquals(0f, east.x.first())
        assertEquals(1f, east.x.last())
    }

    @Test
    fun theAspectIsKeptAndTheShapeCentred() {
        // 10 m east by 2 m north: x spans the square, y a fifth of it, around the middle.
        val box = listOf(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 0.0, 0.0), Vec3(10.0, 2.0, 0.0), Vec3(0.0, 2.0, 0.0))
        val thumb = PathThumbnail.build(path(box))
        assertEquals(0f, thumb.x.min())
        assertEquals(1f, thumb.x.max())
        assertEquals(0.4f, thumb.y.min(), 1e-6f)
        assertEquals(0.6f, thumb.y.max(), 1e-6f)
        // Height does not enter a plan view.
        val raised = PathThumbnail.build(path(box.mapIndexed { i, p -> Vec3(p.x, p.y, i * 3.0) }))
        assertTrue(raised.x.contentEquals(thumb.x) && raised.y.contentEquals(thumb.y))
    }

    @Test
    fun emptyAndSinglePoint() {
        assertEquals(0, PathThumbnail.build(emptyList()).size)
        val single = PathThumbnail.build(path(listOf(Vec3(3.0, -2.0, 1.0))))
        assertEquals(1, single.size)
        assertEquals(0.5f, single.x[0])
        assertEquals(0.5f, single.y[0])
        assertEquals(0f, single.progress[0])
    }

    @Test
    fun coincidentPointsSitInTheCentre() {
        for (n in listOf(5, 500)) {
            val thumb = PathThumbnail.build(path(List(n) { Vec3(4.0, 4.0, 0.0) }))
            assertTrue(thumb.size in 1..PathThumbnail.MAX_VERTICES)
            assertTrue(thumb.x.all { it == 0.5f } && thumb.y.all { it == 0.5f })
            assertTrue(thumb.progress.all { it.isFinite() })
        }
    }

    @Test
    fun equalityComparesContents() {
        val a = PathThumbnail.build(winding)
        val b = PathThumbnail.build(winding)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, PathThumbnail.build(winding, maxVertices = 20))
        assertEquals(a, a.copy(x = a.x.copyOf()))
    }

    @Test
    fun mismatchedArraysAreRejected() {
        val failure = runCatching { PathThumbnail(FloatArray(2), FloatArray(2), FloatArray(1)) }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun aWindingPathUsesTheWholeBudgetTheSameWayEachTime() {
        val first = PathThumbnail.keptIndices(winding, 30)
        assertEquals(30, first.size)
        repeat(3) { assertTrue(first.contentEquals(PathThumbnail.keptIndices(winding, 30))) }
        assertEquals(PathThumbnail.MAX_VERTICES, PathThumbnail.build(winding).size)
    }
}
