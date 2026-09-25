package com.stastyle.imumapper.pipeline.vio

import com.stastyle.imumapper.pipeline.core.RotationSource
import com.stastyle.imumapper.pipeline.log.RawLog
import com.stastyle.imumapper.pipeline.pdr.OrientationEstimator
import com.stastyle.imumapper.pipeline.pdr.OrientationTrack
import com.stastyle.imumapper.pipeline.pdr.PdrContext

/**
 * The IMU's view of where the back camera points, used to put the ARCore frame into the same yaw
 * as the PDR path. The game rotation vector is preferred because that is the frame PDR works in
 * (its yaw is arbitrary but shared); the fused vector is the fallback, and without either the
 * ARCore frame is used as is. The orientation is slerped between the neighbouring samples, which
 * is the nearest sample at frame times far from any sample and better in between. When PDR has
 * prepared its own track from the same samples (with its drift correction), that track is used so
 * the hand-over headings match what the PDR fill will integrate.
 */
class ImuHeading private constructor(private val track: OrientationTrack, val source: RotationSource?) {

    val isAvailable: Boolean get() = source != null

    /** Heading of the sensor -Z axis (back camera) at [tNs], radians clockwise from north; null without samples. */
    fun cameraHeadingRad(tNs: Long): Double? = if (source == null) null else track.at(tNs).cameraHeadingRad()

    companion object {
        /** [pdr] is used instead of the raw samples when it was built from rotation-vector samples. */
        fun of(log: RawLog, pdr: PdrContext? = null): ImuHeading {
            val fromPdr = pdr != null && (
                pdr.orientationSource == OrientationEstimator.Source.GAME ||
                    pdr.orientationSource == OrientationEstimator.Source.FUSED
                )
            val game = log.gameRotation
            if (game.isNotEmpty()) {
                val track = if (fromPdr && pdr != null) pdr.orientation else OrientationTrack.fromRotationSamples(game)
                return ImuHeading(track, RotationSource.GAME)
            }
            val fused = log.fusedRotation
            if (fused.isNotEmpty()) {
                val track = if (fromPdr && pdr != null) pdr.orientation else OrientationTrack.fromRotationSamples(fused)
                return ImuHeading(track, RotationSource.FUSED)
            }
            return ImuHeading(OrientationTrack.EMPTY, null)
        }
    }
}
