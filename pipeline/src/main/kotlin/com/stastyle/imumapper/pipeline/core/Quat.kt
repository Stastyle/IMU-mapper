package com.stastyle.imumapper.pipeline.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unit quaternion (Hamilton convention, w + xi + yj + zk) describing a rotation.
 *
 * Throughout the pipeline a quaternion rotates vectors from the device (sensor) frame into the
 * world frame, and the world frame is ENU: x = east, y = north, z = up. This is exactly the frame
 * Android's rotation-vector sensors report in.
 *
 * Android sensor frame: x = right edge of the screen, y = top of the phone, z = out of the screen.
 */
@Serializable
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {

    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    val norm: Double get() = sqrt(w * w + x * x + y * y + z * z)

    fun normalized(): Quat {
        val n = norm
        return if (n > 0.0) Quat(w / n, x / n, y / n, z / n) else IDENTITY
    }

    fun conjugate() = Quat(w, -x, -y, -z)

    /** Inverse of a unit quaternion (equals the conjugate). */
    fun inverse() = conjugate()

    infix fun dot(o: Quat): Double = w * o.w + x * o.x + y * o.y + z * o.z

    /** Rotates [v] by this quaternion: q * v * q^-1. */
    fun rotate(v: Vec3): Vec3 {
        // Efficient form: v' = v + 2 * cross(q.xyz, cross(q.xyz, v) + w * v)
        val qv = Vec3(x, y, z)
        val t = (qv cross v) * 2.0
        return v + t * w + (qv cross t)
    }

    /** Angle in radians between this rotation and [o]. */
    fun angleTo(o: Quat): Double {
        val d = abs((this dot o).coerceIn(-1.0, 1.0))
        return 2.0 * acos(d)
    }

    /**
     * Spherical linear interpolation, t in [0, 1]. Takes the short path.
     */
    fun slerp(o: Quat, t: Double): Quat {
        var d = this dot o
        var other = o
        if (d < 0.0) {
            d = -d
            other = Quat(-o.w, -o.x, -o.y, -o.z)
        }
        if (d > 0.9995) {
            return Quat(
                w + (other.w - w) * t,
                x + (other.x - x) * t,
                y + (other.y - y) * t,
                z + (other.z - z) * t,
            ).normalized()
        }
        val theta0 = acos(d)
        val theta = theta0 * t
        val s0 = cos(theta) - d * sin(theta) / sin(theta0)
        val s1 = sin(theta) / sin(theta0)
        return Quat(
            w * s0 + other.w * s1,
            x * s0 + other.x * s1,
            y * s0 + other.y * s1,
            z * s0 + other.z * s1,
        ).normalized()
    }

    /**
     * Heading of the device's forward axis (sensor +Y, the top of the phone) projected onto the
     * horizontal plane, in radians clockwise from north, range (-pi, pi].
     */
    fun forwardHeadingRad(): Double = headingOf(rotate(Vec3.UNIT_Y))

    /**
     * Heading of the direction the screen faces away from (sensor -Z, i.e. where the back camera
     * looks), in radians clockwise from north. Useful when the phone is held upright.
     */
    fun cameraHeadingRad(): Double = headingOf(rotate(Vec3(0.0, 0.0, -1.0)))

    /**
     * Tait-Bryan angles (yaw about Z, pitch about X', roll about Y'') of this rotation, radians.
     * Yaw follows the maths convention (counter-clockwise from east); prefer the heading helpers
     * for compass-style angles.
     */
    fun eulerZXY(): Triple<Double, Double, Double> {
        // Rotation matrix elements
        val m20 = 2 * (x * z - w * y)
        val m21 = 2 * (y * z + w * x)
        val m22 = 1 - 2 * (x * x + y * y)
        val m01 = 2 * (x * y - w * z)
        val m11 = 1 - 2 * (x * x + z * z)
        val pitch = asin(m21.coerceIn(-1.0, 1.0))
        val yaw = atan2(-m01, m11)
        val roll = atan2(-m20, m22)
        return Triple(yaw, pitch, roll)
    }

    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

        fun fromAxisAngle(axis: Vec3, angleRad: Double): Quat {
            val a = axis.normalized()
            val h = angleRad / 2.0
            val s = sin(h)
            return Quat(cos(h), a.x * s, a.y * s, a.z * s)
        }

        /**
         * Builds a quaternion from Android's rotation-vector layout (x*sin(θ/2), y*sin(θ/2),
         * z*sin(θ/2), cos(θ/2)). Game and geomagnetic rotation vectors use the same layout.
         */
        fun fromAndroidRotationVector(rx: Double, ry: Double, rz: Double, rw: Double): Quat =
            Quat(rw, rx, ry, rz).normalized()

        /** Quaternion from ARCore's (qx, qy, qz, qw) ordering. */
        fun fromXyzw(x: Double, y: Double, z: Double, w: Double): Quat = Quat(w, x, y, z).normalized()

        /** Rotation about the world Z (up) axis by [rad], positive counter-clockwise seen from above. */
        fun yaw(rad: Double): Quat = fromAxisAngle(Vec3.UNIT_Z, rad)

        /** Compass heading of a world-frame direction: radians clockwise from north (+Y). */
        fun headingOf(dir: Vec3): Double = atan2(dir.x, dir.y)
    }
}
