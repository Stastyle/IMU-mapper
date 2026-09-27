package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Which device axis is projected onto the horizontal plane to get the "device heading" that the
 * heading offset is added to. The forward axis (+Y, the top of the phone) is the documented default;
 * when the phone is carried close to vertical (typical pocket), +Y is nearly parallel to gravity and
 * its projection flips with every sway, so the camera axis (-Z) is used instead. The choice is made
 * once per heading segment (trip start and after every move of the phone) from the mean tilt at the
 * step times, so the offset keeps one meaning throughout a segment; the first segment takes the axis
 * the offset was calibrated on when [com.stastyle.imumapper.pipeline.core.PipelineConfig.headingAxis]
 * names one.
 */
enum class HeadingAxis { FORWARD, CAMERA }

object DeviceHeading {
    /** Cosine of the +Y tilt from vertical above which the phone counts as "upright". */
    const val UPRIGHT_COS: Double = 0.8

    /** Spacing of the orientation samples [meanHeadingRad] averages. */
    const val MEAN_SAMPLE_PERIOD_NS: Long = 50_000_000L

    private val CAMERA_AXIS = Vec3(0.0, 0.0, -1.0)

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

    /**
     * Mean device heading on [axis] over [fromNs, toNs), sampled every [MEAN_SAMPLE_PERIOD_NS]; an
     * empty range is sampled once at [fromNs]. The horizontal projections of the axis are summed
     * rather than their angles, so a moment when the axis stands near vertical, and its heading means
     * little, counts for little. Over a second or more the gait sway of the heading averages out.
     */
    fun meanHeadingRad(track: OrientationTrack, axis: HeadingAxis, fromNs: Long, toNs: Long): Double {
        val v = if (axis == HeadingAxis.FORWARD) Vec3.UNIT_Y else CAMERA_AXIS
        val cursor = track.cursor()
        var sx = 0.0
        var sy = 0.0
        var t = fromNs
        do {
            val d = cursor.at(t).rotate(v)
            sx += d.x
            sy += d.y
            t += MEAN_SAMPLE_PERIOD_NS
        } while (t < toNs)
        return atan2(sx, sy)
    }
}
