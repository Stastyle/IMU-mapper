package com.stastyle.imumapper.render

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.post.PathBuilder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathProgressTest {

    private fun point(i: Int, p: Vec3) = PathPoint(i * 500_000_000L, p, PositionSource.PDR, 0.0)

    /** An uneven 3D spiral: segment lengths differ, so fractions are not the index fractions. */
    private fun spiral(n: Int): List<Vec3> = (0 until n).map { i ->
        val a = i * 0.37
        Vec3((1.0 + i * 0.05) * cos(a), (1.0 + i * 0.05) * sin(a), 0.02 * i * i / n)
    }

    @Test
    fun fractionsRunFromZeroToOneAndNeverDecrease() {
        val f = PathProgress.fractions(spiral(200))
        assertEquals(0.0, f.first())
        assertEquals(1.0, f.last())
        for (i in 1 until f.size) assertTrue(f[i] >= f[i - 1], "fraction falls at $i")
        // Uneven segments: the middle point is not at the middle of the length.
        assertTrue(abs(f[100] - 100.0 / 199) > 0.01)
    }

    @Test
    fun cumulativeMatchesThePipelinesDistance() {
        val points = spiral(500).mapIndexed(::point)
        val cumulative = PathProgress.cumulative(points)
        assertEquals(0.0, cumulative.first())
        val distance = PathBuilder.stats(points, 0.0, 0, null).distanceM
        assertEquals(distance, cumulative.last(), 0.0)
    }

    @Test
    fun cumulativeIsThreeDimensional() {
        val c = PathProgress.cumulative(listOf(Vec3.ZERO, Vec3(3.0, 0.0, 4.0), Vec3(3.0, 0.0, 4.0)))
        assertContentEquals(doubleArrayOf(0.0, 5.0, 5.0), c)
    }

    @Test
    fun pathPointAndVec3OverloadsAgree() {
        val positions = spiral(50)
        val points = positions.mapIndexed(::point)
        assertContentEquals(PathProgress.cumulative(positions), PathProgress.cumulative(points))
        assertContentEquals(PathProgress.fractions(positions), PathProgress.fractions(points))
    }

    @Test
    fun aStandingPauseKeepsItsFraction() {
        val stop = Vec3(2.0, 0.0, 0.0)
        val positions = listOf(Vec3.ZERO, stop, stop, stop, Vec3(4.0, 0.0, 0.0))
        assertContentEquals(doubleArrayOf(0.0, 0.5, 0.5, 0.5, 1.0), PathProgress.fractions(positions))
    }

    @Test
    fun coincidentPointsGiveIndexFractions() {
        val here = Vec3(1.0, 2.0, 3.0)
        val f = PathProgress.fractions(List(5) { here })
        assertContentEquals(doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0), f)
    }

    @Test
    fun emptyAndSinglePoint() {
        assertEquals(0, PathProgress.cumulative(emptyList<Vec3>()).size)
        assertEquals(0, PathProgress.fractions(emptyList<PathPoint>()).size)
        assertContentEquals(doubleArrayOf(0.0), PathProgress.cumulative(listOf(Vec3(1.0, 1.0, 1.0))))
        assertContentEquals(doubleArrayOf(0.0), PathProgress.fractions(listOf(Vec3(1.0, 1.0, 1.0))))
    }

    @Test
    fun aNonFiniteLengthFallsBackToTheIndex() {
        val f = PathProgress.fractions(doubleArrayOf(0.0, 1.0, Double.NaN))
        assertContentEquals(doubleArrayOf(0.0, 0.5, 1.0), f)
    }

    @Test
    fun colourRunsFromTheFirstStopToTheLast() {
        assertEquals(SceneColors.PROGRESS_STOPS.first(), PathProgress.color(0.0))
        assertEquals(SceneColors.PROGRESS_STOPS.last(), PathProgress.color(1.0))
        assertEquals(6, SceneColors.PROGRESS_STOPS.size)
        assertEquals(SceneColors.PROGRESS_STOPS[1], PathProgress.color(0.2))
    }
}
