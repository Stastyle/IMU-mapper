package com.stastyle.imumapper.render

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathProfileTest {

    /** Points 10 per second, one per position. */
    private fun path(positions: List<Vec3>): List<PathPoint> =
        positions.mapIndexed { i, p -> PathPoint(i * 100_000_000L, p, PositionSource.PDR, 0.0) }

    /** A walk east at 1 m/s sampled at 10 Hz with height [z] at each sample index. */
    private fun walk(n: Int, z: (Int) -> Double): List<PathPoint> = path((0 until n).map { Vec3(it * 0.1, 0.0, z(it)) })

    /** The walking bob: ±3 cm at 2 Hz, sampled at 10 Hz. */
    private fun bob(i: Int): Double = 0.03 * sin(2 * PI * 2.0 * i / 10.0)

    private fun assertMonotonicFromZeroToTotal(profile: ElevationProfile) {
        assertEquals(0.0, profile.distanceM.first())
        assertEquals(profile.totalM, profile.distanceM.last())
        for (i in 1 until profile.size) {
            assertTrue(profile.distanceM[i] >= profile.distanceM[i - 1], "distance falls at $i")
        }
    }

    @Test
    fun aShortPathGivesOneSamplePerPoint() {
        val points = walk(50) { it * 0.02 }
        val profile = PathProfile.elevation(points)
        assertEquals(50, profile.size)
        assertMonotonicFromZeroToTotal(profile)
        assertContentEquals(PathProgress.cumulative(points), profile.distanceM)
        assertEquals(PathProgress.cumulative(points).last(), profile.totalM)
        for (i in 0 until 50) {
            assertEquals(points[i].p.z, profile.minZ[i])
            assertEquals(points[i].p.z, profile.maxZ[i])
        }
    }

    @Test
    fun aLongPathIsBucketedWithPeaksAndDipsKept() {
        // A 1 km wiggly walk with one sharp spike up and one sharp dip.
        val points = path(
            (0 until 10_000).map { i ->
                val z = when (i) {
                    3_333 -> 7.0
                    6_666 -> -4.0
                    else -> 0.5 * sin(i * 0.01)
                }
                Vec3(i * 0.1, 2.0 * sin(i * 0.003), z)
            },
        )
        val profile = PathProfile.elevation(points, maxBuckets = 256)
        assertTrue(profile.size in 2..256, "size ${profile.size}")
        assertMonotonicFromZeroToTotal(profile)
        assertEquals(PathProgress.cumulative(points).last(), profile.totalM)
        assertEquals(7.0, profile.maxZ.max())
        assertEquals(-4.0, profile.minZ.min())
        for (i in 0 until profile.size) assertTrue(profile.minZ[i] <= profile.maxZ[i])
        assertEquals(0.0, profile.fraction(0))
        assertEquals(1.0, profile.fraction(profile.size - 1))
    }

    @Test
    fun theBucketLimitHolds() {
        val points = walk(1_000) { sin(it * 0.05) }
        for (limit in listOf(2, 10, 100, 999)) {
            val profile = PathProfile.elevation(points, maxBuckets = limit)
            assertTrue(profile.size <= limit, "limit $limit gave ${profile.size}")
            assertMonotonicFromZeroToTotal(profile)
        }
    }

    @Test
    fun aLongStraightSegmentLeavesAGapNotAnInventedHeight() {
        // 300 points close together, then one far point 100 m on: the buckets in between have no point.
        val positions = (0 until 300).map { Vec3(it * 0.01, 0.0, 0.0) } + Vec3(103.0, 0.0, 5.0)
        val profile = PathProfile.elevation(path(positions), maxBuckets = 100)
        assertTrue(profile.size < 100)
        assertEquals(5.0, profile.maxZ.last())
        assertTrue(profile.maxZ.dropLast(1).all { it == 0.0 })
        assertMonotonicFromZeroToTotal(profile)
    }

    @Test
    fun emptySingleAndCoincidentPoints() {
        val empty = PathProfile.elevation(emptyList())
        assertEquals(0, empty.size)
        assertEquals(0.0, empty.totalM)

        val single = PathProfile.elevation(path(listOf(Vec3(1.0, 2.0, 3.0))))
        assertEquals(1, single.size)
        assertEquals(0.0, single.distanceM[0])
        assertEquals(3.0, single.minZ[0])
        assertEquals(0.0, single.fraction(0))

        for (n in listOf(5, 1_000)) {
            val coincident = PathProfile.elevation(path(List(n) { Vec3(1.0, 1.0, 1.0) }))
            assertEquals(1, coincident.size)
            assertEquals(0.0, coincident.totalM)
            assertEquals(1.0, coincident.minZ[0])
            assertEquals(1.0, coincident.maxZ[0])
            assertEquals(0.0, coincident.fraction(0))
        }
    }

    @Test
    fun noNaNAnywhere() {
        val cases = listOf(
            walk(3) { 0.0 },
            walk(2_000) { it * 0.001 },
            path(List(300) { Vec3.ZERO } + Vec3(1.0, 0.0, 0.0)),
            path(listOf(Vec3.ZERO, Vec3.ZERO)),
        )
        for (points in cases) {
            val profile = PathProfile.elevation(points, maxBuckets = 64)
            for (i in 0 until profile.size) {
                assertTrue(profile.distanceM[i].isFinite() && profile.minZ[i].isFinite() && profile.maxZ[i].isFinite())
                assertTrue(profile.fraction(i).isFinite())
            }
        }
    }

    @Test
    fun theAxisIsSymmetricAndRound() {
        val axis = PathProfile.axis(-0.7, 5.2)
        assertEquals(-6.0, axis.minZ)
        assertEquals(6.0, axis.maxZ)
        assertContentEquals(doubleArrayOf(-6.0, 0.0, 6.0), axis.ticks)
        // A round extreme is its own limit.
        assertContentEquals(doubleArrayOf(-5.0, 0.0, 5.0), PathProfile.axis(-0.7, 5.0).ticks)
        assertContentEquals(doubleArrayOf(-10.0, 0.0, 10.0), PathProfile.axis(-10.0, 0.0).ticks)
        assertContentEquals(doubleArrayOf(-15.0, 0.0, 15.0), PathProfile.axis(-12.0, 3.0).ticks)
        assertContentEquals(doubleArrayOf(-2.0, 0.0, 2.0), PathProfile.axis(0.0, 2.0).ticks)
        assertContentEquals(doubleArrayOf(-2.5, 0.0, 2.5), PathProfile.axis(-2.1, 0.0).ticks)
        assertContentEquals(doubleArrayOf(-80.0, 0.0, 80.0), PathProfile.axis(-61.0, 20.0).ticks)
        assertContentEquals(doubleArrayOf(-100.0, 0.0, 100.0), PathProfile.axis(-81.0, 20.0).ticks)
    }

    @Test
    fun theAxisKeepsAOneMetreBand() {
        assertContentEquals(doubleArrayOf(-1.0, 0.0, 1.0), PathProfile.axis(0.0, 0.0).ticks)
        assertContentEquals(doubleArrayOf(-1.0, 0.0, 1.0), PathProfile.axis(-0.2, 0.3).ticks)
        assertContentEquals(doubleArrayOf(-1.0, 0.0, 1.0), PathProfile.axis(Double.NaN, Double.POSITIVE_INFINITY).ticks)
        assertContentEquals(doubleArrayOf(-1.5, 0.0, 1.5), PathProfile.axis(-1.2, 0.3).ticks)
    }

    @Test
    fun theWalkingBobIsNoClimb() {
        val climbs = PathProfile.climbs(walk(600) { bob(it) })
        assertEquals(0.0, climbs.climbM)
        assertEquals(0.0, climbs.descentM)
    }

    @Test
    fun aStaircaseUpAndDownCountsItsHeightOnce() {
        // 5 s flat, ten 0.3 m steps up a second apart, 5 s on top, ten steps down, 5 s flat; bobbing throughout.
        val heights = buildList {
            repeat(50) { add(0.0) }
            for (step in 1..10) repeat(10) { add(step * 0.3) }
            repeat(50) { add(3.0) }
            for (step in 9 downTo 0) repeat(10) { add(step * 0.3) }
            repeat(50) { add(0.0) }
        }
        val climbs = PathProfile.climbs(walk(heights.size) { heights[it] + bob(it) })
        assertEquals(3.0, climbs.climbM, 0.1)
        assertEquals(3.0, climbs.descentM, 0.1)
    }

    @Test
    fun aRiseInsideTheDeadBandIsNoClimb() {
        val climbs = PathProfile.climbs(walk(300) { (it / 100.0).coerceAtMost(0.4) + bob(it) })
        assertEquals(0.0, climbs.climbM)
        assertEquals(0.0, climbs.descentM)
    }

    @Test
    fun aDescentFirstIsCountedAsDescent() {
        // Down 2 m from the start, then back up 0.99 m.
        val climbs = PathProfile.climbs(walk(200) { if (it < 100) -it * 0.02 else -2.0 + (it - 100) * 0.01 })
        assertEquals(2.0, climbs.descentM, 1e-9)
        assertEquals(0.99, climbs.climbM, 1e-9)
    }

    @Test
    fun tooFewPointsHaveNoClimb() {
        assertEquals(ClimbTotals(0.0, 0.0), PathProfile.climbs(emptyList()))
        assertEquals(ClimbTotals(0.0, 0.0), PathProfile.climbs(walk(1) { 5.0 }))
    }

    @Test
    fun theCircleUsedByTheSceneHasItsRise() {
        val points = path((0 until 20).map { i -> Vec3(5 * cos(i * 0.3), 5 * sin(i * 0.3), 2.0 * i / 19) })
        assertEquals(2.0, PathProfile.climbs(points).climbM, 1e-9)
    }
}
