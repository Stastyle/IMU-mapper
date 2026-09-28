package com.stastyle.imumapper.capture

import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Pure Kotlin: no Android types, so app/src/test can drive it with synthetic sensor streams.

/** What the compass preview shows before a recording starts, in order of precedence. */
enum class CompassStatus {
    /** No fused rotation sample yet, or none for [CompassLock.STALE_NS]. */
    WAITING,

    /** The magnetometer reports unreliable or low accuracy: the user should wave the phone in a figure 8. */
    CALIBRATE,

    /** The field strength is not the Earth's, or it changes: metal or electronics are close. */
    INTERFERENCE,

    /** North is being measured; [CompassReading.progress] fills while it holds steady. */
    SETTLING,

    /** North has held steady long enough; stays until CALIBRATE or INTERFERENCE releases it. */
    LOCKED,
}

/** One snapshot of [CompassLock] for the compass dialog. */
data class CompassReading(
    val status: CompassStatus,
    /** 0..1 while [CompassStatus.SETTLING], 1 when [CompassStatus.LOCKED], 0 otherwise. */
    val progress: Float = 0f,
    /**
     * Where the phone points, degrees clockwise from magnetic north in [0, 360): the top edge when the
     * phone is flat, the back camera when it is upright. Null before the first fused sample.
     */
    val headingDeg: Double? = null,
    /** Heading accuracy the fused rotation vector reports, degrees; null when the sensor gives none. */
    val accuracyDeg: Double? = null,
    /** Magnetic field strength, microtesla; null before the first magnetometer sample. */
    val fieldUt: Double? = null,
    /**
     * Mean yaw of fused relative to game rotation over the current window, degrees counter-clockwise
     * about up: the turn that puts this session's game-vector frame onto magnetic north, the value the
     * pipeline reports as `northOffsetDeg`. Null before the first paired sample.
     */
    val offsetDeg: Double? = null,
)

/**
 * Decides when north is settled before a recording starts. The pipeline takes north from the yaw
 * difference between the fused (magnetometer-referenced) and the game rotation vector at the start of
 * the trip, so that difference is what has to hold still here: the fused vector's yaw converges over
 * a few seconds after its listener is registered, and swings near metal.
 *
 * Feed it the live sensor streams in arrival order and call [reading] for the current state. Not
 * thread-safe; the caller confines it to one thread or locks around it.
 */
class CompassLock {

    private class Stamped(val tNs: Long, val q: Quat)

    private class FieldSample(val tNs: Long, val magnitudeUt: Double)

    private val games = ArrayDeque<Stamped>()
    private val pendingFused = ArrayDeque<Stamped>()
    private val field = ArrayDeque<FieldSample>()
    private var latestNs = Long.MIN_VALUE

    private var lastFusedNs = Long.MIN_VALUE
    private var lastFused: Quat? = null
    private var headingAccuracyRad = -1.0

    private var magAccuracy: Int? = null
    private var calibrating = false
    private var interference = false

    // The current window of steady deltas: circular sums, first and last sample time, sample count.
    private var sumC = 0.0
    private var sumS = 0.0
    private var windowCount = 0
    private var windowStartNs = 0L
    private var windowEndNs = 0L
    private var locked = false

    fun onGame(tNs: Long, q: Quat) {
        games.addLast(Stamped(tNs, q))
        noteTime(tNs)
        // Pending fused samples are at most PAIR_WINDOW_NS older than the newest sample, so games
        // further back than GAME_KEEP_NS can never be the nearest one again.
        while (games.size > 1 && games.first().tNs < latestNs - GAME_KEEP_NS) games.removeFirst()
        resolveFused()
    }

    /** [headingAccuracyRad] is the rotation vector's accuracy value, negative when the sensor gives none. */
    fun onFused(tNs: Long, q: Quat, headingAccuracyRad: Double) {
        lastFusedNs = tNs
        lastFused = q
        this.headingAccuracyRad = headingAccuracyRad
        pendingFused.addLast(Stamped(tNs, q))
        noteTime(tNs)
        resolveFused()
    }

    /** Calibrated magnetometer reading in microtesla, sensor frame. */
    fun onMag(tNs: Long, x: Double, y: Double, z: Double) {
        field.addLast(FieldSample(tNs, sqrt(x * x + y * y + z * z)))
        while (field.size > 1 && field.first().tNs < tNs - LOCK_WINDOW_NS) field.removeFirst()
        noteTime(tNs)
        updateDisturbance()
        resolveFused()
    }

    /** The magnetometer's SENSOR_STATUS_* value, null when unknown. */
    fun onMagAccuracy(accuracy: Int?) {
        magAccuracy = accuracy
        updateDisturbance()
    }

    fun reading(nowNs: Long): CompassReading {
        val fused = lastFused
        val status = when {
            fused == null || nowNs - lastFusedNs > STALE_NS -> CompassStatus.WAITING
            calibrating -> CompassStatus.CALIBRATE
            interference -> CompassStatus.INTERFERENCE
            locked -> CompassStatus.LOCKED
            else -> CompassStatus.SETTLING
        }
        val progress = when (status) {
            CompassStatus.LOCKED -> 1f
            CompassStatus.SETTLING ->
                if (windowCount == 0) 0f else ((windowEndNs - windowStartNs).toFloat() / LOCK_WINDOW_NS).coerceIn(0f, 1f)
            else -> 0f
        }
        return CompassReading(
            status = status,
            progress = progress,
            headingDeg = fused?.let { normalizeDegrees(Math.toDegrees(phoneHeadingRad(it))) },
            accuracyDeg = if (fused != null && headingAccuracyRad >= 0.0) Math.toDegrees(headingAccuracyRad) else null,
            fieldUt = field.lastOrNull()?.magnitudeUt,
            offsetDeg = if (windowCount > 0) Math.toDegrees(atan2(sumS, sumC)) else null,
        )
    }

    private fun noteTime(tNs: Long) {
        if (tNs > latestNs) latestNs = tNs
    }

    /**
     * Pairs each fused sample with the nearest game sample. A fused sample waits until a game sample at
     * or after its time has arrived (the nearest one may still be on its way) or until the streams have
     * moved on by more than [PAIR_WINDOW_NS]; without a game sample that close it is ignored.
     */
    private fun resolveFused() {
        while (pendingFused.isNotEmpty()) {
            val f = pendingFused.first()
            val gameAfter = games.isNotEmpty() && games.last().tNs >= f.tNs
            val expired = latestNs - f.tNs > PAIR_WINDOW_NS
            if (!gameAfter && !expired) return
            pendingFused.removeFirst()
            val g = nearestGame(f.tNs) ?: continue
            // Exactly the pipeline's yaw difference (OrientationEstimator.correctYaw).
            onDelta(f.tNs, (f.q * g.q.inverse()).eulerZXY().first)
        }
    }

    private fun nearestGame(tNs: Long): Stamped? {
        var best: Stamped? = null
        var bestGap = Long.MAX_VALUE
        for (g in games) {
            val gap = abs(g.tNs - tNs)
            if (gap < bestGap) {
                best = g
                bestGap = gap
            }
        }
        return if (bestGap <= PAIR_WINDOW_NS) best else null
    }

    private fun onDelta(tNs: Long, delta: Double) {
        if (calibrating || interference) {
            // Settling starts over once the field is clean again.
            clearWindow()
            return
        }
        if (windowCount == 0 || abs(wrapRad(delta - atan2(sumS, sumC))) > MAX_DEVIATION_RAD) {
            sumC = cos(delta)
            sumS = sin(delta)
            windowCount = 1
            windowStartNs = tNs
            windowEndNs = tNs
        } else {
            sumC += cos(delta)
            sumS += sin(delta)
            windowCount++
            if (tNs > windowEndNs) windowEndNs = tNs
        }
        if (windowEndNs - windowStartNs >= LOCK_WINDOW_NS) locked = true
    }

    private fun updateDisturbance() {
        val acc = magAccuracy
        calibrating = acc == ACCURACY_UNRELIABLE || acc == ACCURACY_LOW
        interference = false
        if (field.isNotEmpty()) {
            var min = Double.MAX_VALUE
            var max = -Double.MAX_VALUE
            for (s in field) {
                if (s.magnitudeUt < min) min = s.magnitudeUt
                if (s.magnitudeUt > max) max = s.magnitudeUt
            }
            val latest = field.last().magnitudeUt
            interference = latest < FIELD_MIN_UT || latest > FIELD_MAX_UT || max - min > FIELD_VARIATION_UT
        }
        if (calibrating || interference) {
            locked = false
            clearWindow()
        }
    }

    private fun clearWindow() {
        sumC = 0.0
        sumS = 0.0
        windowCount = 0
    }

    companion object {
        /** Longest gap to the game sample a fused sample is paired with; farther ones are ignored. */
        const val PAIR_WINDOW_NS: Long = 100_000_000L

        /** Game samples kept for pairing, well beyond [PAIR_WINDOW_NS] so late fused samples still pair. */
        private const val GAME_KEEP_NS: Long = 500_000_000L

        /** The fused vector counts as silent (status WAITING) after this long without a sample. */
        const val STALE_NS: Long = 1_000_000_000L

        /**
         * How long the yaw difference has to hold within [MAX_DEVIATION_RAD] for a lock. The fused
         * vector settles over a few seconds after registration; two quiet seconds show it has.
         */
        const val LOCK_WINDOW_NS: Long = 2_000_000_000L

        /**
         * Largest deviation from the window's running mean before the window starts over. Three
         * degrees is a few metres of sideways error per hundred metres walked, and above the jitter of
         * a settled fused vector.
         */
        val MAX_DEVIATION_RAD: Double = Math.toRadians(3.0)

        /** The Earth's field is 25 to 65 microtesla everywhere; outside a margin around that, something else is measured. */
        const val FIELD_MIN_UT: Double = 20.0
        const val FIELD_MAX_UT: Double = 70.0

        /**
         * Largest change of field strength over [LOCK_WINDOW_NS] still taken as the Earth's field. Above
         * the noise and the orientation-dependent residual of a calibrated magnetometer (a couple of
         * microtesla), below the swings that nearby steel or a car cause while the phone moves.
         */
        const val FIELD_VARIATION_UT: Double = 6.0

        /** SENSOR_STATUS_UNRELIABLE and SENSOR_STATUS_ACCURACY_LOW: the magnetometer asks for a figure 8. */
        private const val ACCURACY_UNRELIABLE = 0
        private const val ACCURACY_LOW = 1

        /** Same test as the pipeline's DeviceHeading.UPRIGHT_COS: +Y within about 37 degrees of vertical. */
        private const val UPRIGHT_COS = 0.8

        private val CAMERA_AXIS = Vec3(0.0, 0.0, -1.0)

        /**
         * Heading the dial shows for [q], radians clockwise from north: the top edge when the phone is
         * flat, the back camera when it is upright and the top edge's heading is meaningless.
         */
        fun phoneHeadingRad(q: Quat): Double {
            val upright = abs(q.rotate(Vec3.UNIT_Y).z) > UPRIGHT_COS
            return Quat.headingOf(q.rotate(if (upright) CAMERA_AXIS else Vec3.UNIT_Y))
        }

        /** [deg] folded into [0, 360). */
        fun normalizeDegrees(deg: Double): Double {
            val r = deg % 360.0
            return if (r < 0.0) r + 360.0 else r
        }

        /**
         * The angle closest to [previousUnwrapped] that shows [headingDeg], so an animated dial turns
         * from 359 to 1 degree through north instead of the long way round.
         */
        fun unwrapDegrees(previousUnwrapped: Double, headingDeg: Double): Double {
            var d = (headingDeg - previousUnwrapped) % 360.0
            if (d > 180.0) d -= 360.0 else if (d <= -180.0) d += 360.0
            return previousUnwrapped + d
        }

        private fun wrapRad(rad: Double): Double {
            var r = rad % (2.0 * PI)
            if (r > PI) r -= 2.0 * PI else if (r <= -PI) r += 2.0 * PI
            return r
        }
    }
}
