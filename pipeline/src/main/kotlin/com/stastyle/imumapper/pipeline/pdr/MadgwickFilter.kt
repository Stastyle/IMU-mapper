package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Madgwick's gradient-descent IMU filter (gyro + accelerometer, no magnetometer). The estimate
 * [q] rotates sensor-frame vectors into a gravity-aligned world frame whose yaw is arbitrary, which
 * is the same contract as Android's game rotation vector. Used only when a log has no rotation
 * vector samples at all.
 *
 * Gyro integration uses the exact rotation for the sample interval instead of the first-order
 * step of the original paper, so sharp turns between samples do not shrink the quaternion.
 */
class MadgwickFilter(
    /** Gradient step gain in rad/s; larger converges faster but follows acceleration noise. */
    private val beta: Double = 0.1,
) {
    var q: Quat = Quat.IDENTITY
        private set
    var initialized: Boolean = false
        private set

    /** Sets the initial tilt so gravity in the sensor frame maps to +Z; yaw stays zero. */
    fun initFromAccel(ax: Double, ay: Double, az: Double) {
        val a = Vec3(ax, ay, az)
        val len = a.length
        q = if (len < 1e-6) {
            Quat.IDENTITY
        } else {
            val an = a / len
            val d = an.z.coerceIn(-1.0, 1.0)
            val axis = an cross Vec3.UNIT_Z
            if (axis.length < 1e-9) {
                if (d > 0.0) Quat.IDENTITY else Quat.fromAxisAngle(Vec3.UNIT_X, Math.PI)
            } else {
                Quat.fromAxisAngle(axis, acos(d))
            }
        }
        initialized = true
    }

    /** One filter step; angular rate in rad/s (sensor frame), acceleration in any unit, [dtS] > 0. */
    fun update(gx: Double, gy: Double, gz: Double, ax: Double, ay: Double, az: Double, dtS: Double) {
        if (!initialized) initFromAccel(ax, ay, az)
        if (dtS <= 0.0) return
        // Exact gyro propagation: q_k+1 = q_k * exp(omega * dt / 2), omega in the sensor frame.
        val rate = sqrt(gx * gx + gy * gy + gz * gz)
        var qg = q
        if (rate > 1e-12) {
            qg = (q * Quat.fromAxisAngle(Vec3(gx, gy, gz), rate * dtS)).normalized()
        }
        val an = sqrt(ax * ax + ay * ay + az * az)
        if (an < 1e-6) {
            q = qg
            return
        }
        val nax = ax / an
        val nay = ay / an
        val naz = az / an
        val w = qg.w
        val x = qg.x
        val y = qg.y
        val z = qg.z
        // Objective: predicted gravity in the sensor frame minus the measured direction.
        val f1 = 2.0 * (x * z - w * y) - nax
        val f2 = 2.0 * (w * x + y * z) - nay
        val f3 = 1.0 - 2.0 * (x * x + y * y) - naz
        var gw = -2.0 * y * f1 + 2.0 * x * f2
        var gxq = 2.0 * z * f1 + 2.0 * w * f2 - 4.0 * x * f3
        var gyq = -2.0 * w * f1 + 2.0 * z * f2 - 4.0 * y * f3
        var gzq = 2.0 * x * f1 + 2.0 * y * f2
        val gn = sqrt(gw * gw + gxq * gxq + gyq * gyq + gzq * gzq)
        if (gn < 1e-12) {
            q = qg
            return
        }
        gw /= gn
        gxq /= gn
        gyq /= gn
        gzq /= gn
        val step = beta * dtS
        q = Quat(w - gw * step, x - gxq * step, y - gyq * step, z - gzq * step).normalized()
    }
}
