package com.stastyle.imumapper.pipeline.vio

import com.stastyle.imumapper.pipeline.log.RawLog
import com.stastyle.imumapper.pipeline.pdr.OrientationEstimator
import com.stastyle.imumapper.pipeline.pdr.OrientationTrack
import com.stastyle.imumapper.pipeline.pdr.PdrContext

/**
 * The IMU's view of where the back camera points, used to put the ARCore frame into the same yaw
 * as the PDR path. When PDR has prepared an orientation track it is used whatever its source
 * (game vector, fused vector or Madgwick over raw gyro and accel): its yaw may be arbitrary, but
 * it is the frame the PDR fill integrates in, and that is what the hand-over headings must match.
 * Without a PDR context the raw game rotation vector is preferred and the fused vector is the
 * fallback; without either the ARCore frame is used as is. The orientation is slerped between the
 * neighbouring samples, which is the nearest sample at frame times far from any sample and better
 * in between.
 */
class ImuHeading private constructor(private val track: OrientationTrack, val source: OrientationEstimator.Source) {

    val isAvailable: Boolean get() = source != OrientationEstimator.Source.NONE

    /** Heading of the sensor -Z axis (back camera) at [tNs], radians clockwise from north; null without a source. */
    fun cameraHeadingRad(tNs: Long): Double? = if (!isAvailable) null else track.at(tNs).cameraHeadingRad()

    companion object {
        fun of(log: RawLog, pdr: PdrContext? = null): ImuHeading {
            if (pdr != null && pdr.orientationSource != OrientationEstimator.Source.NONE && !pdr.orientation.isEmpty) {
                return ImuHeading(pdr.orientation, pdr.orientationSource)
            }
            val game = log.gameRotation
            if (game.isNotEmpty()) {
                return ImuHeading(OrientationTrack.fromRotationSamples(game), OrientationEstimator.Source.GAME)
            }
            val fused = log.fusedRotation
            if (fused.isNotEmpty()) {
                return ImuHeading(OrientationTrack.fromRotationSamples(fused), OrientationEstimator.Source.FUSED)
            }
            return ImuHeading(OrientationTrack.EMPTY, OrientationEstimator.Source.NONE)
        }
    }
}
