package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Which device axis is projected onto the horizontal plane to get the "device heading" that the
 * heading offset is added to. The forward axis (+Y, the top of the phone) is the documented default;
 * when the phone is carried close to vertical (typical pocket), +Y is nearly parallel to gravity and
 * its projection flips with every sway, so the camera axis (-Z) is used instead. The choice is made
 * once per heading segment (trip start and every REORIENT) from the mean tilt at the step times, so
 * the offset keeps one meaning throughout a segment.
 */
enum class HeadingAxis { FORWARD, CAMERA }

object DeviceHeading {
    /** Cosine of the +Y tilt from vertical above which the phone counts as "upright". */
    const val UPRIGHT_COS: Double = 0.8

    fun headingRad(q: Quat, axis: HeadingAxis): Double =
        if (axis == HeadingAxis.FORWARD) q.forwardHeadingRad() else q.cameraHeadingRad()

    /** Picks the axis for the orientations at [times][from, to); FORWARD for an empty range. */
    fun chooseAxis(track: OrientationTrack, times: LongArray, from: Int, to: Int): HeadingAxis {
        if (from >= to) return HeadingAxis.FORWARD
        var sum = 0.0
        val cursor = track.cursor()
        for (i in from until to) sum += abs(cursor.at(times[i]).rotate(Vec3.UNIT_Y).z)
        return if (sum / (to - from) > UPRIGHT_COS) HeadingAxis.CAMERA else HeadingAxis.FORWARD
    }
}

/**
 * Re-estimates the heading offset (walking direction minus device heading) after a REORIENT
 * annotation from the horizontal acceleration of the next few steps.
 *
 * Method: the horizontal (east, north) acceleration while walking oscillates mostly along the
 * walking direction (braking at heel strike, push-off after it) and less across it, so the
 * principal axis of its 2x2 covariance is the walking axis. The axis has a 180-degree ambiguity;
 * of the two candidate headings the one closest to the walking heading of the steps just before
 * the REORIENT is chosen (the user is told to keep walking straight through a re-orientation), or,
 * without earlier steps, the one whose offset is closest to [fallbackOffsetRad]. Returns null when
 * the window has too few samples or no dominant axis, in which case the caller keeps the old offset.
 */
object HeadingOffsetEstimator {
    const val MIN_SAMPLES: Int = 50
    /** The principal axis must carry this fraction more variance than the minor axis to be trusted. */
    const val MIN_AXIS_RATIO: Double = 1.3

    fun estimate(
        accel: WorldAccel,
        fromNs: Long,
        toNs: Long,
        deviceHeadingRad: Double,
        previousWalkingHeadingRad: Double?,
        fallbackOffsetRad: Double,
    ): Double? {
        val from = accel.lowerBound(fromNs)
        val to = accel.lowerBound(toNs)
        val n = to - from
        if (n < MIN_SAMPLES) return null
        var me = 0.0
        var mn = 0.0
        for (i in from until to) {
            me += accel.east[i]
            mn += accel.north[i]
        }
        me /= n
        mn /= n
        var see = 0.0
        var sen = 0.0
        var snn = 0.0
        for (i in from until to) {
            val e = accel.east[i] - me
            val no = accel.north[i] - mn
            see += e * e
            sen += e * no
            snn += no * no
        }
        // Eigen-decomposition of the symmetric 2x2 covariance.
        val theta = 0.5 * atan2(2.0 * sen, see - snn)
        val c = cos(theta)
        val s = sin(theta)
        val major = see * c * c + 2.0 * sen * c * s + snn * s * s
        val minor = see * s * s - 2.0 * sen * c * s + snn * c * c
        if (major <= 0.0 || major < MIN_AXIS_RATIO * minor) return null
        // theta is a maths angle from east; heading is clockwise from north.
        val h1 = Angles.wrap(atan2(c, s))
        val h2 = Angles.wrap(h1 + Math.PI)
        val chosen = if (previousWalkingHeadingRad != null) {
            val d1 = abs(Angles.diff(h1, previousWalkingHeadingRad))
            val d2 = abs(Angles.diff(h2, previousWalkingHeadingRad))
            if (d1 <= d2) h1 else h2
        } else {
            val o1 = Angles.diff(h1, deviceHeadingRad)
            val o2 = Angles.diff(h2, deviceHeadingRad)
            if (abs(Angles.diff(o1, fallbackOffsetRad)) <= abs(Angles.diff(o2, fallbackOffsetRad))) h1 else h2
        }
        return Angles.diff(chosen, deviceHeadingRad)
    }
}
