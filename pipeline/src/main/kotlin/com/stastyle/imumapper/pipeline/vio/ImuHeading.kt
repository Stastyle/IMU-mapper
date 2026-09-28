package com.stastyle.imumapper.pipeline.vio

import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.log.RawLog
import com.stastyle.imumapper.pipeline.pdr.OrientationEstimator
import com.stastyle.imumapper.pipeline.pdr.OrientationTrack
import com.stastyle.imumapper.pipeline.pdr.PdrContext

/**
 * The IMU's view of where the back camera points, used to put the ARCore frame into the same yaw
 * as the PDR path. When PDR has prepared an orientation track it is used whatever its source
 * (game vector, fused vector or Madgwick over raw gyro and accel): it is the frame the PDR fill
 * integrates in, and that is what the hand-over headings must match. Without a PDR context (a log
 * with neither accelerometer samples nor steps) the same [OrientationEstimator] runs here, so the
 * camera heading is the one PDR would have used, turned onto magnetic north when the config and
 * the sensors allow it; without any orientation source the ARCore frame is used as is.
 *
 * The track comes from [OrientationEstimator] on both paths, so north is set there exactly once
 * and nothing here turns it again. [northReference] repeats the estimator's `northReference`. The
 * orientation is slerped between the neighbouring samples, which is the nearest sample at frame
 * times far from any sample and better in between.
 */
class ImuHeading private constructor(
    private val track: OrientationTrack,
    val source: OrientationEstimator.Source,
    /** "magnetic" when the camera headings are relative to magnetic north, else "relative: " and the reason. */
    val northReference: String,
) {

    val isAvailable: Boolean get() = source != OrientationEstimator.Source.NONE

    /** Heading of the sensor -Z axis (back camera) at [tNs], radians clockwise from north; null without a source. */
    fun cameraHeadingRad(tNs: Long): Double? = if (!isAvailable) null else track.at(tNs).cameraHeadingRad()

    companion object {
        fun of(log: RawLog, pdr: PdrContext? = null, config: PipelineConfig = PipelineConfig()): ImuHeading {
            if (pdr != null && pdr.orientationSource != OrientationEstimator.Source.NONE && !pdr.orientation.isEmpty) {
                return ImuHeading(pdr.orientation, pdr.orientationSource, northOf(pdr.diagnostics))
            }
            val estimate = OrientationEstimator().estimate(log, config)
            if (estimate.source != OrientationEstimator.Source.NONE && !estimate.track.isEmpty) {
                return ImuHeading(estimate.track, estimate.source, northOf(estimate.diagnostics))
            }
            return ImuHeading(
                OrientationTrack.EMPTY,
                OrientationEstimator.Source.NONE,
                OrientationEstimator.NO_ORIENTATION,
            )
        }

        private fun northOf(diagnostics: Map<String, String>): String =
            diagnostics[OrientationEstimator.NORTH_REFERENCE] ?: OrientationEstimator.NO_ORIENTATION
    }
}
