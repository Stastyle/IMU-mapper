package com.stastyle.imumapper.pipeline.survey

import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Numbers for the straight line (chord) from A, the path at [fromNs], to B, the path at [toNs]. */
data class LegMeasure(
    val fromNs: Long,
    val toNs: Long,
    val a: Vec3,
    val b: Vec3,
    val lengthM: Double,
    val horizontalM: Double,
    /** b.z - a.z, signed. */
    val heightChangeM: Double,
    /** Degrees clockwise from north in [0, 360); null when horizontalM < Measure.MIN_HORIZONTAL_M. */
    val azimuthDeg: Double?,
    /** atan2(dz, H) in degrees, up positive; +90 / -90 for a vertical leg, 0 for no movement. */
    val slopeDeg: Double,
    /** 100 * dz / H; null when horizontalM < Measure.MIN_HORIZONTAL_M. */
    val gradePct: Double?,
    /** 3D length along the path between the two moments. */
    val pathM: Double,
    /** The path strays from the chord by more than max(CURVE_MIN_M, CURVE_FRACTION x lengthM). */
    val curved: Boolean,
) {
    /** Over a short horizontal run barometer noise dominates the slope, so it is shown greyed. */
    val slopeUncertain: Boolean get() = horizontalM < Measure.MIN_SLOPE_HORIZONTAL_M

    /** lengthM / pathM, 1 for a straight stretch; null when pathM is 0. */
    val straightness: Double? get() = if (pathM > 0.0) lengthM / pathM else null
}

/**
 * Survey numbers between two moments. The headline is always the chord, never a fitted line:
 * dead-reckoning error accumulates along the walk instead of scattering about a line, and chords chain,
 * so the legs of a traverse add up to the net displacement.
 */
object Measure {
    const val MIN_HORIZONTAL_M: Double = 0.3
    const val MIN_SLOPE_HORIZONTAL_M: Double = 5.0
    const val CURVE_MIN_M: Double = 0.3
    const val CURVE_FRACTION: Double = 0.02
    const val FIT_SPACING_M: Double = 0.25

    /** The chord numbers for two moments of [timeline], in the order given (A may be later than B). */
    fun leg(timeline: PathTimeline, fromNs: Long, toNs: Long): LegMeasure {
        val a = timeline.positionAt(fromNs)
        val b = timeline.positionAt(toNs)
        val d = b - a
        val length = d.length
        val horizontal = hypot(d.x, d.y)
        val flat = horizontal < MIN_HORIZONTAL_M
        val deviation = maxDeviationM(timeline.samplesBetween(fromNs, toNs), a, b)
        return LegMeasure(
            fromNs = fromNs,
            toNs = toNs,
            a = a,
            b = b,
            lengthM = length,
            horizontalM = horizontal,
            heightChangeM = d.z,
            azimuthDeg = if (flat) null else SurveyAngles.azimuthDeg(d.x, d.y),
            // + 0.0 keeps a level leg from reading -0.
            slopeDeg = Math.toDegrees(atan2(d.z, horizontal)) + 0.0,
            gradePct = if (flat) null else 100.0 * d.z / horizontal,
            pathM = abs(timeline.distanceAt(toNs) - timeline.distanceAt(fromNs)),
            curved = deviation > max(CURVE_MIN_M, CURVE_FRACTION * length),
        )
    }

    /** Largest 3D distance from [samples] to the segment [a]-[b] (to [a] when they coincide). */
    fun maxDeviationM(samples: List<Vec3>, a: Vec3, b: Vec3): Double {
        val e = b - a
        val len2 = e.lengthSquared
        var worst = 0.0
        for (s in samples) {
            val r = s - a
            val f = if (len2 > 0.0) ((r dot e) / len2).coerceIn(0.0, 1.0) else 0.0
            worst = max(worst, (r - e * f).length)
        }
        return worst
    }

    /**
     * Total-least-squares direction of the stretch's plan points, resampled every FIT_SPACING_M of path.
     * Resampling by distance keeps a pause or a slow stretch from weighing more; height is left out so
     * barometer noise cannot tilt the line. Oriented from A to B.
     */
    fun fittedAzimuthDeg(timeline: PathTimeline, fromNs: Long, toNs: Long): Double? {
        val a = timeline.positionAt(fromNs)
        val b = timeline.positionAt(toNs)
        val dE = b.x - a.x
        val dN = b.y - a.y
        if (hypot(dE, dN) < MIN_HORIZONTAL_M) return null
        val d1 = timeline.distanceAt(fromNs)
        val d2 = timeline.distanceAt(toNs)
        val lo = min(d1, d2)
        val hi = max(d1, d2)
        val samples = ArrayList<Vec3>()
        val count = floor((hi - lo) / FIT_SPACING_M).toInt()
        for (k in 0..count) {
            val s = lo + k * FIT_SPACING_M
            if (s < hi) samples.add(timeline.positionAtDistance(s))
        }
        samples.add(timeline.positionAtDistance(hi))
        val meanE = samples.sumOf { it.x } / samples.size
        val meanN = samples.sumOf { it.y } / samples.size
        var cEE = 0.0
        var cNN = 0.0
        var cEN = 0.0
        for (p in samples) {
            val e = p.x - meanE
            val n = p.y - meanN
            cEE += e * e
            cNN += n * n
            cEN += e * n
        }
        cEE /= samples.size
        cNN /= samples.size
        cEN /= samples.size
        if (cEE + cNN < 1e-12) return null
        // The major axis, counter-clockwise from east; the fit has no direction of its own, so take A to B's.
        val phi = 0.5 * atan2(2.0 * cEN, cEE - cNN)
        var uE = cos(phi)
        var uN = sin(phi)
        if (uE * dE + uN * dN < 0.0) {
            uE = -uE
            uN = -uN
        }
        return SurveyAngles.azimuthDeg(uE, uN)
    }

    /** leg() plus fittedAzimuthDeg(). */
    fun stretch(timeline: PathTimeline, fromNs: Long, toNs: Long): StretchMeasure =
        StretchMeasure(leg(timeline, fromNs, toNs), fittedAzimuthDeg(timeline, fromNs, toNs))
}

/** A stretch selection: the chord plus the direction of the passage fitted through it. */
data class StretchMeasure(
    val leg: LegMeasure,
    /** Degrees in [0, 360), oriented from A to B; null when the chord has no azimuth or the fit is degenerate. */
    val fittedAzimuthDeg: Double?,
)
