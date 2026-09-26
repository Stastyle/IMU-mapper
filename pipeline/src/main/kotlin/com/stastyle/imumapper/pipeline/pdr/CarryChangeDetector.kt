package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.acos
import kotlin.math.roundToInt

/**
 * A stretch [startNs, endNs) during which the phone was moved to another carry position (hand to
 * pocket, pocket to vest, a re-grip). [returned] is true when the phone came to rest in the same
 * orientation it left, a glance at the screen for instance: the move still has to be bridged, but
 * the heading offset has not changed.
 */
class CarryChange(val startNs: Long, val endNs: Long, val returned: Boolean)

/**
 * Finds carry changes from the direction of gravity in the device frame: it is what changes when
 * the phone goes from a hand to a pocket or a vest, and it is steady inside one carry position (the
 * gait sways it a little, which the low-pass removes). The orientation's yaw is left out, so a turn
 * of the walker never counts as a change; a phone spun flat about the vertical, which keeps the same
 * gravity direction, is the one move this cannot see.
 *
 * Method: the gravity direction is sampled at [SAMPLE_PERIOD_NS], low-passed with [smoothingS], and
 * compared with the direction it settled at. When it departs by more than [minTiltRad] a change
 * starts, at the last moment the direction was still within a third of that tolerance of the old
 * one, moved earlier by the low-pass time constant since the filter reports every move that late.
 * It ends when the direction has been steady (every sample within the same tolerance of the
 * window mean) for [settleS] seconds; the mean over the last third of that window, where the
 * filter has caught up furthest, becomes the new reference. A change that never
 * settles before the data ends runs to its end. The choice of a reference-based test over a
 * before-after comparison is deliberate: a slow slide of the phone is still caught once it has
 * moved far enough.
 */
object CarryChangeDetector {
    /** 10 Hz is plenty for a movement that takes a second. */
    const val SAMPLE_PERIOD_NS: Long = 100_000_000L

    /** Fraction of [minTiltRad] within which the direction counts as unchanged or settled. */
    const val SETTLE_FRACTION: Double = 1.0 / 3.0

    fun detect(
        track: OrientationTrack,
        fromNs: Long,
        toNs: Long,
        minTiltRad: Double,
        settleS: Double,
        smoothingS: Double = 1.0,
    ): List<CarryChange> {
        if (track.isEmpty || toNs <= fromNs || minTiltRad <= 0.0) return emptyList()
        val n = ((toNs - fromNs) / SAMPLE_PERIOD_NS).toInt() + 1
        val window = ((settleS * 1e9 / SAMPLE_PERIOD_NS).roundToInt()).coerceAtLeast(2)
        if (n < 2 * window) return emptyList()

        // Low-passed gravity direction in the device frame, one unit vector per sample.
        val gx = DoubleArray(n)
        val gy = DoubleArray(n)
        val gz = DoubleArray(n)
        val alpha = SAMPLE_PERIOD_NS / (smoothingS * 1e9 + SAMPLE_PERIOD_NS)
        val cursor = track.cursor()
        var sx = 0.0
        var sy = 0.0
        var sz = 0.0
        for (k in 0 until n) {
            val g = cursor.at(fromNs + k * SAMPLE_PERIOD_NS).conjugate().rotate(Vec3.UNIT_Z)
            if (k == 0) {
                sx = g.x
                sy = g.y
                sz = g.z
            } else {
                sx += alpha * (g.x - sx)
                sy += alpha * (g.y - sy)
                sz += alpha * (g.z - sz)
            }
            val len = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz)
            gx[k] = sx / len
            gy[k] = sy / len
            gz[k] = sz / len
        }

        fun angle(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double =
            acos((ax * bx + ay * by + az * bz).coerceIn(-1.0, 1.0))

        // Mean direction over [from, to) and the largest deviation of a sample in it from that mean.
        var mx = 0.0
        var my = 0.0
        var mz = 0.0
        fun mean(from: Int, to: Int) {
            mx = 0.0
            my = 0.0
            mz = 0.0
            for (j in from until to) {
                mx += gx[j]
                my += gy[j]
                mz += gz[j]
            }
            val len = kotlin.math.sqrt(mx * mx + my * my + mz * mz)
            if (len > 0.0) {
                mx /= len
                my /= len
                mz /= len
            }
        }
        fun maxDeviation(from: Int, to: Int): Double {
            var worst = 0.0
            for (j in from until to) worst = maxOf(worst, angle(gx[j], gy[j], gz[j], mx, my, mz))
            return worst
        }

        val settleTol = minTiltRad * SETTLE_FRACTION
        val lag = (smoothingS * 1e9 / SAMPLE_PERIOD_NS).roundToInt()
        val out = ArrayList<CarryChange>()
        mean(0, window)
        var rx = mx
        var ry = my
        var rz = mz
        var k = window
        while (k < n) {
            if (angle(gx[k], gy[k], gz[k], rx, ry, rz) <= minTiltRad) {
                k++
                continue
            }
            // Departed: the move began where the direction was last close to the reference.
            var start = k
            while (start > 0 && angle(gx[start - 1], gy[start - 1], gz[start - 1], rx, ry, rz) > settleTol) start--
            start = maxOf(0, start - lag)
            // Ended once a whole settle window is steady; that window's mean is the new reference.
            var end = -1
            var m = k
            while (m + window <= n) {
                mean(m, m + window)
                if (maxDeviation(m, m + window) <= settleTol) {
                    end = m
                    break
                }
                m++
            }
            if (end < 0) {
                out.add(CarryChange(fromNs + start * SAMPLE_PERIOD_NS, fromNs + n * SAMPLE_PERIOD_NS, false))
                break
            }
            mean(end + window - window / 3, end + window)
            val returned = angle(mx, my, mz, rx, ry, rz) <= settleTol
            out.add(CarryChange(fromNs + start * SAMPLE_PERIOD_NS, fromNs + end * SAMPLE_PERIOD_NS, returned))
            rx = mx
            ry = my
            rz = mz
            k = end + window
        }
        return out
    }
}
