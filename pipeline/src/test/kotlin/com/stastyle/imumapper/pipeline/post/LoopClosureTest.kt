package com.stastyle.imumapper.pipeline.post

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoopClosureTest {

    private fun pt(i: Int, x: Double, y: Double, z: Double = 0.0) =
        PathPoint(i * 1_000_000_000L, Vec3(x, y, z), PositionSource.PDR, 0.0, i - 1)

    @Test
    fun distributesErrorByDistanceAndShiftsTail() {
        // Four 1 m legs that should form a square but end 0.4 m east and 0.2 m up.
        val points = listOf(
            pt(0, 0.0, 0.0), pt(1, 0.0, 1.0), pt(2, 1.0, 1.0), pt(3, 1.0, 0.0),
            pt(4, 0.4, 0.0, 0.2), pt(5, 0.4, 1.0, 0.2),
        )
        val r = assertNotNull(LoopClosure.apply(points, listOf(4_000_000_000L)))
        assertTrue(abs(r.closureErrorM - Vec3(0.4, 0.0, 0.2).length) < 1e-12)
        assertEquals(Vec3.ZERO, r.points[0].p)
        assertTrue(r.points[4].p.length < 1e-12, "closure point moved to the origin")
        // The correction grows with walked distance: at 1 m of the total length it is that fraction of the error.
        val f = 1.0 / (3.0 + Vec3(0.6, 0.0, 0.2).length)
        assertTrue(abs(r.points[1].p.x - (0.0 - 0.4 * f)) < 1e-12)
        assertTrue(abs(r.points[1].p.z - (0.0 - 0.2 * f)) < 1e-12)
        // Points after the closure keep their shape, shifted by the full error.
        assertTrue((r.points[5].p - Vec3(0.0, 1.0, 0.0)).length < 1e-12)
        assertEquals(points.map { it.tNs }, r.points.map { it.tNs })
        assertEquals(points.map { it.stepIndex }, r.points.map { it.stepIndex })
    }

    @Test
    fun nothingToCloseReturnsNull() {
        assertNull(LoopClosure.apply(listOf(pt(0, 0.0, 0.0)), listOf(5L)))
        assertNull(LoopClosure.apply(listOf(pt(0, 0.0, 0.0), pt(1, 1.0, 0.0)), emptyList()))
        // A closure annotated before any step maps to the start point: nothing to correct.
        assertNull(LoopClosure.apply(listOf(pt(0, 0.0, 0.0), pt(1, 1.0, 0.0)), listOf(0L)))
    }

    @Test
    fun twoLapsAreClosedInOrder() {
        val lap = listOf(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 1.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(0.1, 0.0, 0.0))
        val points = ArrayList<PathPoint>()
        points.add(pt(0, 0.0, 0.0))
        var i = 1
        var offset = Vec3.ZERO
        repeat(2) {
            for (p in lap) {
                points.add(pt(i, p.x + offset.x, p.y + offset.y))
                i++
            }
            offset = Vec3(offset.x + 0.1, 0.0, 0.0)
        }
        val r = assertNotNull(LoopClosure.apply(points, listOf(4_000_000_000L, 8_000_000_000L)))
        assertTrue(r.points[4].p.length < 1e-12)
        assertTrue(r.points[8].p.length < 1e-12)
        assertTrue(abs(r.closureErrorM - 0.2) < 1e-12, "error reported before any correction")
    }
}
