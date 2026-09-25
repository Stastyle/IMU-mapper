package com.stastyle.imumapper.pipeline.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuatTest {

    private fun assertNear(expected: Double, actual: Double, eps: Double = 1e-9) =
        assertTrue(abs(expected - actual) < eps, "expected $expected but was $actual")

    private fun assertNear(expected: Vec3, actual: Vec3, eps: Double = 1e-9) {
        assertNear(expected.x, actual.x, eps)
        assertNear(expected.y, actual.y, eps)
        assertNear(expected.z, actual.z, eps)
    }

    @Test
    fun identityLeavesVectorsAlone() {
        assertNear(Vec3(1.0, 2.0, 3.0), Quat.IDENTITY.rotate(Vec3(1.0, 2.0, 3.0)))
        assertNear(0.0, Quat.IDENTITY.forwardHeadingRad())
    }

    @Test
    fun yawRotatesNorthTowardsWest() {
        // +90 degrees about Z (counter-clockwise from above) turns north (+Y) into west (-X).
        val q = Quat.yaw(PI / 2)
        assertNear(Vec3(-1.0, 0.0, 0.0), q.rotate(Vec3.UNIT_Y))
        // Compass heading of west is -90 degrees.
        assertNear(-PI / 2, q.forwardHeadingRad())
    }

    @Test
    fun headingIsClockwiseFromNorth() {
        assertNear(0.0, Quat.headingOf(Vec3(0.0, 1.0, 0.0)))
        assertNear(PI / 2, Quat.headingOf(Vec3(1.0, 0.0, 0.0)))
        assertNear(PI, abs(Quat.headingOf(Vec3(0.0, -1.0, 0.0))))
    }

    @Test
    fun androidRotationVectorLayoutIsXyzw() {
        val q = Quat.fromAndroidRotationVector(0.0, 0.0, 0.7071067811865476, 0.7071067811865476)
        assertNear(PI / 2, Quat.yaw(PI / 2).angleTo(Quat.IDENTITY))
        assertNear(0.0, q.angleTo(Quat.yaw(PI / 2)), 1e-6)
    }

    @Test
    fun conjugateUndoesRotation() {
        val q = Quat.fromAxisAngle(Vec3(1.0, 1.0, 0.0), 1.2345)
        val v = Vec3(0.3, -0.7, 2.0)
        assertNear(v, q.conjugate().rotate(q.rotate(v)))
    }

    @Test
    fun slerpEndpointsAndMidpoint() {
        val a = Quat.IDENTITY
        val b = Quat.yaw(PI / 2)
        assertNear(0.0, a.slerp(b, 0.0).angleTo(a), 1e-9)
        assertNear(0.0, a.slerp(b, 1.0).angleTo(b), 1e-9)
        assertNear(PI / 4, a.slerp(b, 0.5).angleTo(a), 1e-9)
    }

    @Test
    fun vec3Basics() {
        val a = Vec3(1.0, 2.0, 3.0)
        assertEquals(Vec3(2.0, 4.0, 6.0), a * 2.0)
        assertNear(14.0, a dot a)
        assertNear(Vec3.UNIT_Z, Vec3.UNIT_X cross Vec3.UNIT_Y)
        assertNear(5.0, Vec3(3.0, 4.0, 0.0).length)
        assertNear(Vec3(0.5, 1.0, 1.5), Vec3.ZERO.lerp(a, 0.5))
    }
}
