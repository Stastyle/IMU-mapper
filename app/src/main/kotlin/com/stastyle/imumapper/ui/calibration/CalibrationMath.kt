package com.stastyle.imumapper.ui.calibration

import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure computations behind the calibration flows and the debug plots. No Android or Compose types
 * here so every function runs in a plain JVM unit test (app/src/test/.../calibration).
 *
 * Frames and units follow docs/CONVENTIONS.md: ENU metres, headings in radians clockwise from north.
 */
object CalibrationMath {

    const val GRAVITY: Double = 9.81

    /** A heading walk shorter than this cannot give a usable direction. */
    const val MIN_HEADING_WALK_M: Double = 1.0

    /** Gyro RMS above this while "still" means the phone was moving during the still-bias flow. */
    const val STILL_GYRO_RMS_LIMIT: Double = 0.03

    // --- still bias ---

    /** Component-wise mean; null for an empty list. */
    fun mean(samples: List<Vec3>): Vec3? {
        if (samples.isEmpty()) return null
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (s in samples) {
            x += s.x
            y += s.y
            z += s.z
        }
        val n = samples.size.toDouble()
        return Vec3(x / n, y / n, z / n)
    }

    /** RMS of the deviation from the mean vector (all three axes together); null for fewer than 2 samples. */
    fun noiseRms(samples: List<Vec3>): Double? {
        if (samples.size < 2) return null
        val m = mean(samples) ?: return null
        var sum = 0.0
        for (s in samples) sum += (s - m).lengthSquared
        return sqrt(sum / samples.size)
    }

    /** Standard deviation of the vector magnitude; null for fewer than 2 samples. Used as the accel noise floor. */
    fun magnitudeStdDev(samples: List<Vec3>): Double? {
        if (samples.size < 2) return null
        var sum = 0.0
        for (s in samples) sum += s.length
        val mean = sum / samples.size
        var sq = 0.0
        for (s in samples) {
            val d = s.length - mean
            sq += d * d
        }
        return sqrt(sq / samples.size)
    }

    // --- stride ---

    /** Metres per step; null when nothing was counted or the distance is not positive. */
    fun strideLengthM(distanceM: Double, steps: Int): Double? =
        if (steps <= 0 || distanceM <= 0.0) null else distanceM / steps

    /**
     * Fits the Weinberg gain so `sum(k * swing^(1/4))` over the walk equals [distanceM]. Steps whose
     * swing is not positive fall back to [fallbackStrideM] in the stride model, so their length is
     * subtracted first. Returns null when no step has a usable swing or the fit would not be positive.
     */
    fun fitWeinbergK(distanceM: Double, swings: DoubleArray, fallbackStrideM: Double): Double? {
        var sum = 0.0
        var fallbacks = 0
        for (s in swings) {
            if (s > 0.0) sum += s.pow(0.25) else fallbacks++
        }
        if (sum <= 0.0) return null
        val remaining = distanceM - fallbacks * fallbackStrideM
        if (remaining <= 0.0) return null
        return remaining / sum
    }

    // --- heading ---

    /** Wraps [rad] into (-pi, pi]. */
    fun wrapRad(rad: Double): Double {
        var r = rad % (2.0 * PI)
        if (r > PI) r -= 2.0 * PI else if (r <= -PI) r += 2.0 * PI
        return r
    }

    /** Compass direction of [p] seen from the origin, radians clockwise from north. */
    fun directionRad(p: Vec3): Double = atan2(p.x, p.y)

    /**
     * Heading offset that would have made a straight walk ending at [end] point north (+Y). The path
     * was computed with [offsetUsedRad], so the correction is applied on top of it. Null when the walk
     * was too short for the direction to mean anything.
     */
    fun headingOffsetFromEnd(end: Vec3, offsetUsedRad: Double): Double? {
        val horizontal = sqrt(end.x * end.x + end.y * end.y)
        if (horizontal < MIN_HEADING_WALK_M) return null
        return wrapRad(offsetUsedRad - directionRad(end))
    }

    // --- square test ---

    /** Straight-line gap between the last and the first point; null for an empty path. */
    fun closureErrorM(points: List<Vec3>): Double? =
        if (points.isEmpty()) null else points[points.size - 1].distanceTo(points[0])

    /** Closure as a percentage of the distance walked; null when no distance was walked. */
    fun closurePercent(closureM: Double, distanceM: Double): Double? =
        if (distanceM <= 0.0) null else closureM / distanceM * 100.0

    // --- live plots ---

    /** Up component of a sensor-frame acceleration rotated into ENU, minus gravity. */
    fun verticalAccel(q: Quat, accelSensor: Vec3): Double = q.rotate(accelSensor).z - GRAVITY

    /** Heading of the phone's top edge in degrees clockwise from north, [-180, 180]. */
    fun headingDeg(q: Quat): Double = Math.toDegrees(q.forwardHeadingRad())

    // --- top-down preview ---

    data class PlanBounds(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double) {
        val width: Double get() = maxX - minX
        val height: Double get() = maxY - minY
    }

    /** Horizontal extent of the path; null for an empty list. */
    fun planBounds(points: List<Vec3>): PlanBounds? {
        if (points.isEmpty()) return null
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (p in points) {
            minX = min(minX, p.x)
            maxX = max(maxX, p.x)
            minY = min(minY, p.y)
            maxY = max(maxY, p.y)
        }
        return PlanBounds(minX, maxX, minY, maxY)
    }

    /** Maps ENU metres to canvas pixels: uniform scale, centred, north up (canvas y grows downwards). */
    data class PlanTransform(val scale: Double, val offsetX: Double, val offsetY: Double) {
        fun x(eastM: Double): Float = (offsetX + eastM * scale).toFloat()
        fun y(northM: Double): Float = (offsetY - northM * scale).toFloat()
    }

    /**
     * Transform that fits [bounds] into a [widthPx] x [heightPx] box with [paddingPx] on every side.
     * A degenerate extent (single point) gets a nominal one-metre span so it still draws.
     */
    fun fitPlan(bounds: PlanBounds, widthPx: Double, heightPx: Double, paddingPx: Double): PlanTransform {
        val spanX = max(bounds.width, 1e-3)
        val spanY = max(bounds.height, 1e-3)
        val usableW = max(widthPx - 2 * paddingPx, 1.0)
        val usableH = max(heightPx - 2 * paddingPx, 1.0)
        val scale = min(usableW / spanX, usableH / spanY)
        val centreX = (bounds.minX + bounds.maxX) / 2.0
        val centreY = (bounds.minY + bounds.maxY) / 2.0
        return PlanTransform(scale, widthPx / 2.0 - centreX * scale, heightPx / 2.0 + centreY * scale)
    }

    // --- ARCore vs PDR ---

    data class Comparison(
        val pdrDistanceM: Double,
        val vioDistanceM: Double,
        /** PDR minus VIO, metres. */
        val distanceDiffM: Double,
        /** Distance difference as a percentage of the VIO distance; null when VIO walked nothing. */
        val distanceDiffPct: Double?,
        /** Gap between the two end points, metres. */
        val endGapM: Double,
        /** Difference of the end-point compass directions in degrees, null when either path is too short. */
        val endDirectionDiffDeg: Double?,
    )

    fun compare(pdrPoints: List<Vec3>, pdrDistanceM: Double, vioPoints: List<Vec3>, vioDistanceM: Double): Comparison {
        val pdrEnd = pdrPoints.lastOrNull() ?: Vec3.ZERO
        val vioEnd = vioPoints.lastOrNull() ?: Vec3.ZERO
        val diff = pdrDistanceM - vioDistanceM
        val pct = if (vioDistanceM > 0.0) diff / vioDistanceM * 100.0 else null
        val pdrLen = sqrt(pdrEnd.x * pdrEnd.x + pdrEnd.y * pdrEnd.y)
        val vioLen = sqrt(vioEnd.x * vioEnd.x + vioEnd.y * vioEnd.y)
        val dirDiff = if (pdrLen < MIN_HEADING_WALK_M || vioLen < MIN_HEADING_WALK_M) {
            null
        } else {
            Math.toDegrees(wrapRad(directionRad(pdrEnd) - directionRad(vioEnd)))
        }
        return Comparison(pdrDistanceM, vioDistanceM, diff, pct, pdrEnd.distanceTo(vioEnd), dirDiff)
    }
}
