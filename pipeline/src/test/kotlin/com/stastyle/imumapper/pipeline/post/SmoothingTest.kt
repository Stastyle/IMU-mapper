package com.stastyle.imumapper.pipeline.post

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SmoothingTest {

    private fun pt(i: Int, x: Double) = PathPoint(i.toLong(), Vec3(x, 0.0, 0.0), PositionSource.PDR, 0.1 * i, i)

    @Test
    fun averagesInteriorKeepsEnds() {
        val points = listOf(pt(0, 0.0), pt(1, 3.0), pt(2, 0.0), pt(3, 3.0), pt(4, 0.0))
        val s = Smoothing.movingAverage(points, 3)
        assertEquals(points[0], s[0])
        assertEquals(points[4], s[4])
        assertTrue(abs(s[1].p.x - 1.0) < 1e-12)
        assertTrue(abs(s[2].p.x - 2.0) < 1e-12)
        assertEquals(0.2, s[2].headingRad)
        assertEquals(2, s[2].stepIndex)
    }

    @Test
    fun windowOneOrTinyPathIsUntouched() {
        val points = listOf(pt(0, 0.0), pt(1, 3.0), pt(2, 0.0))
        assertSame(points, Smoothing.movingAverage(points, 1))
        val two = points.take(2)
        assertSame(two, Smoothing.movingAverage(two, 5))
        // An even window uses the same half-width as the next odd one.
        assertEquals(Smoothing.movingAverage(points, 3), Smoothing.movingAverage(points, 2))
    }
}
