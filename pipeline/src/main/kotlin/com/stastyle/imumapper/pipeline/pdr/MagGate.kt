package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.MagSample
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Decides whether a magnetometer reading looks like the undisturbed Earth field. The reference is
 * the field averaged over the first second of the log (in the world frame, so dip is meaningful);
 * a reading passes when its magnitude is within [tolerance] (relative) of the reference magnitude
 * and its dip angle is within [tolerance] radians of the reference dip. Iron-rich rock or metal
 * gear fails one of the two, and the reading is then ignored for yaw correction.
 */
class MagGate(val refMagnitudeUt: Double, val refDipRad: Double, val tolerance: Double) {

    fun passes(worldField: Vec3): Boolean {
        val mag = worldField.length
        if (refMagnitudeUt <= 0.0 || mag <= 0.0) return false
        if (abs(mag - refMagnitudeUt) / refMagnitudeUt > tolerance) return false
        return abs(dipOf(worldField) - refDipRad) <= tolerance
    }

    companion object {
        /** Dip angle in radians, positive when the field points below the horizon (ENU z up). */
        fun dipOf(worldField: Vec3): Double =
            atan2(-worldField.z, sqrt(worldField.x * worldField.x + worldField.y * worldField.y))

        /**
         * Reference from the samples inside the first [windowS] seconds of [mag], rotated by
         * [orientation]. Returns null when there are no magnetometer samples.
         */
        fun fromStart(
            mag: List<MagSample>,
            orientation: OrientationTrack,
            tolerance: Double,
            windowS: Double = 1.0,
        ): MagGate? {
            if (mag.isEmpty()) return null
            val cursor = orientation.cursor()
            val end = mag[0].tNs + (windowS * 1e9).toLong()
            var sumMag = 0.0
            var sumDip = 0.0
            var n = 0
            for (s in mag) {
                if (s.tNs > end && n > 0) break
                val w = cursor.at(s.tNs).rotate(Vec3.of(s.x, s.y, s.z))
                sumMag += w.length
                sumDip += dipOf(w)
                n++
            }
            return MagGate(sumMag / n, sumDip / n, tolerance)
        }
    }
}
