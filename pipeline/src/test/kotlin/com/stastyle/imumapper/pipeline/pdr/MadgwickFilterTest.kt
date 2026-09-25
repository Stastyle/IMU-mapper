package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class MadgwickFilterTest {

    @Test
    fun convergesToGravityAlignedTilt() {
        val f = MadgwickFilter(beta = 0.2)
        // Phone pitched 30 degrees: gravity has a component along sensor -Y.
        val ax = 0.0
        val ay = -9.81 * sin(PI / 6)
        val az = 9.81 * cos(PI / 6)
        f.initFromAccel(ax, ay, 0.0) // deliberately wrong start so the gradient step has to work
        repeat(2000) { f.update(0.0, 0.0, 0.0, ax, ay, az, 0.005) }
        val up = f.q.rotate(Vec3(ax, ay, az)).normalized()
        assertTrue(up.z > 0.999, "gravity should map to +Z, got $up")
    }

    @Test
    fun gyroAboutZTurnsHeadingClockwiseForNegativeRate() {
        val f = MadgwickFilter()
        f.initFromAccel(0.0, 0.0, 9.81)
        // -pi/2 rad/s about sensor Z for one second: a clockwise turn seen from above.
        repeat(200) { f.update(0.0, 0.0, -PI / 2, 0.0, 0.0, 9.81, 0.005) }
        assertTrue(abs(f.q.forwardHeadingRad() - PI / 2) < 0.02, "heading ${f.q.forwardHeadingRad()}")
    }
}
