package com.stastyle.imumapper.capture

import com.stastyle.imumapper.pipeline.core.LogRecord
import com.stastyle.imumapper.pipeline.core.MagSample
import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.RotationSample
import com.stastyle.imumapper.pipeline.core.RotationSource
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

    /**
     * North has held steady long enough. Stays until CALIBRATE or INTERFERENCE, a gap in the samples, a
     * jump of the yaw difference or a drift of it away from the locked north releases it.
     */
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
     * pipeline reports as `northOffsetDeg`. Null while the window is empty.
     */
    val offsetDeg: Double? = null,
)

/**
 * Decides when north is settled before a recording starts. The pipeline takes north from the yaw
 * difference between the fused (magnetometer-referenced) and the game rotation vector at the start of
 * the trip, so that difference is what has to hold still here: the fused vector's yaw converges over
 * a few seconds after its listener is registered, and swings near metal.
 *
 * Each paired sample adds one yaw difference to a sliding window of the last [LOCK_WINDOW_NS]. While
 * the window is not steady its oldest samples are dropped, so the progress shrinks only as far as
 * needed instead of starting over. Steady means both a small spread (every difference within
 * [MAX_DEVIATION_RAD] of the window's circular mean) and no trend (the means of the older and the
 * newer half within [MAX_TREND_RAD]): a spread limit alone lets through a fused vector that is still
 * converging. A steady window that spans [LOCK_WINDOW_NS] with at least [MIN_WINDOW_SAMPLES] samples
 * locks.
 *
 * One difference far from a window of at least [MIN_TREND_SAMPLES] is skipped as a glitch: added, it
 * would fail the spread and trim away the steady samples before it, so a single spike would cost the
 * whole wait. A second one in a row is a real change and goes in, so a step still restarts the window,
 * one sample late.
 *
 * The lock latches until the magnetometer asks for a figure 8, interference shows, the samples stop
 * for longer than [MAX_GAP_NS], two differences in a row land more than [RELEASE_DEVIATION_RAD] from
 * the locked mean, or north drifts: on most samples of the last [DRIFT_RELEASE_NS] the window has a
 * small spread but a trend, or a mean more than [MAX_DEVIATION_RAD] from the locked mean. That catches
 * a fused vector re-converging by less than the jump limit, and the debounce keeps hand sway, which
 * fails those tests only now and then, from flickering the lock. A drift release keeps the window, so
 * the lock returns as soon as the samples since the drift began are steady again.
 *
 * Feed it the live sensor streams in arrival order and call [reading] for the current state. Not
 * thread-safe; the caller confines it to one thread or locks around it. [CompassFeed] does both.
 */
class CompassLock {

    private class Stamped(val tNs: Long, val q: Quat)

    private class FieldSample(val tNs: Long, val magnitudeUt: Double)

    /** One yaw difference with the cosine and sine the circular means sum. */
    private class Delta(val tNs: Long, val rad: Double) {
        val c: Double = cos(rad)
        val s: Double = sin(rad)
    }

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

    /** One drift test while locked: whether the window looked like north moving away from the lock. */
    private class DriftCheck(val tNs: Long, val drifting: Boolean)

    /** The steady yaw differences, oldest first. */
    private val window = ArrayDeque<Delta>()
    private var lastDeltaNs = Long.MIN_VALUE
    private var locked = false

    /** Circular mean of the window when it last qualified for the lock. */
    private var lockedMeanRad = 0.0

    /** The previous yaw difference was skipped as a lone outlier, so the next outlier goes in. */
    private var skippedOutlier = false

    /** Drift tests of the last [DRIFT_RELEASE_NS] while locked, oldest first, and how many found a drift. */
    private val driftChecks = ArrayDeque<DriftCheck>()
    private var driftingChecks = 0

    /** Routes one record of the live stream to [onGame], [onFused] or [onMag]; other records are ignored. */
    fun feed(record: LogRecord) {
        when (record) {
            is RotationSample -> when (record.source) {
                RotationSource.GAME -> onGame(record.tNs, record.toQuat())
                RotationSource.FUSED -> onFused(record.tNs, record.toQuat(), record.headingAccuracyRad.toDouble())
            }
            is MagSample -> onMag(record.tNs, record.x.toDouble(), record.y.toDouble(), record.z.toDouble())
            else -> Unit
        }
    }

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
        if (lastFusedNs != Long.MIN_VALUE && tNs - lastFusedNs > MAX_GAP_NS) {
            // The fused vector went quiet: the app may have been in the background and the fusion may
            // have restarted, so what settled before the gap says nothing about north now. Samples
            // still waiting for a pair are from before the gap.
            pendingFused.clear()
            release()
        }
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
            CompassStatus.SETTLING -> windowProgress()
            else -> 0f
        }
        return CompassReading(
            status = status,
            progress = progress,
            headingDeg = fused?.let { normalizeDegrees(Math.toDegrees(phoneHeadingRad(it))) },
            accuracyDeg = if (fused != null && headingAccuracyRad >= 0.0) Math.toDegrees(headingAccuracyRad) else null,
            fieldUt = field.lastOrNull()?.magnitudeUt,
            offsetDeg = if (window.isNotEmpty()) Math.toDegrees(meanRad(0, window.size)) else null,
        )
    }

    private fun windowProgress(): Float =
        if (window.isEmpty()) 0f else (windowSpanNs().toFloat() / LOCK_WINDOW_NS).coerceIn(0f, 1f)

    private fun windowSpanNs(): Long = window.last().tNs - window.first().tNs

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
        // A hole in the pairs (the game stream stalled) counts like one in the fused stream.
        val gap = lastDeltaNs != Long.MIN_VALUE && tNs - lastDeltaNs > MAX_GAP_NS
        lastDeltaNs = tNs
        if (gap) release()
        if (calibrating || interference) {
            // Settling starts over once the field is clean again.
            window.clear()
            skippedOutlier = false
            return
        }
        // Far from the north that was locked: the fused vector has re-anchored, so the lock no longer
        // describes it, and the window starts over from this sample.
        val jumped = locked && abs(wrapRad(delta - lockedMeanRad)) > RELEASE_DEVIATION_RAD
        // A lone glitch is skipped; a second outlier in a row goes through, released or trimmed as usual.
        val outlier = jumped ||
            (window.size >= MIN_TREND_SAMPLES && abs(wrapRad(delta - meanRad(0, window.size))) > MAX_DEVIATION_RAD)
        if (outlier && !skippedOutlier) {
            skippedOutlier = true
            return
        }
        skippedOutlier = outlier
        if (jumped) release()
        window.addLast(Delta(tNs, delta))
        // The newest sample at or before the window's start stays too, so a steady stream spans the
        // whole LOCK_WINDOW_NS whatever the sample times are.
        while (window.size >= 2 && window[1].tNs <= tNs - LOCK_WINDOW_NS) window.removeFirst()
        if (locked) checkDrift(tNs)
        while (window.size > 1 && !(spreadOk() && trendOk())) window.removeFirst()
        if (window.size >= MIN_WINDOW_SAMPLES && windowSpanNs() >= LOCK_WINDOW_NS) {
            if (!locked) clearDriftChecks()
            locked = true
            lockedMeanRad = meanRad(0, window.size)
        }
    }

    /**
     * While locked, tests the window before the steadiness trim hides a drift: a small spread with a
     * trend, or with a mean off the locked north by more than the spread allows, means north is moving.
     * Releases the lock, and keeps the window, when that held on most samples of the last
     * [DRIFT_RELEASE_NS]: sway fails a test now and then, a re-converging fused vector keeps failing it.
     */
    private fun checkDrift(tNs: Long) {
        val drifting = window.size >= MIN_TREND_SAMPLES && spreadOk() &&
            (!trendOk() || abs(wrapRad(meanRad(0, window.size) - lockedMeanRad)) > MAX_DEVIATION_RAD)
        driftChecks.addLast(DriftCheck(tNs, drifting))
        if (drifting) driftingChecks++
        // As in the window, the newest test at or before the start stays, so full history spans the time.
        while (driftChecks.size >= 2 && driftChecks[1].tNs <= tNs - DRIFT_RELEASE_NS) {
            if (driftChecks.removeFirst().drifting) driftingChecks--
        }
        val full = tNs - driftChecks.first().tNs >= DRIFT_RELEASE_NS
        if (full && driftingChecks * 2 > driftChecks.size) {
            locked = false
            clearDriftChecks()
        }
    }

    private fun clearDriftChecks() {
        driftChecks.clear()
        driftingChecks = 0
    }

    /** Every difference within [MAX_DEVIATION_RAD] of the window's circular mean. */
    private fun spreadOk(): Boolean {
        val mean = meanRad(0, window.size)
        for (d in window) {
            if (abs(wrapRad(d.rad - mean)) > MAX_DEVIATION_RAD) return false
        }
        return true
    }

    /**
     * The means of the older and the newer half within [MAX_TREND_RAD]; true for a window under
     * [MIN_TREND_SAMPLES], whose halves are too noisy to judge.
     */
    private fun trendOk(): Boolean {
        val n = window.size
        if (n < MIN_TREND_SAMPLES) return true
        val half = n / 2
        return abs(wrapRad(meanRad(n - half, n) - meanRad(0, half))) <= MAX_TREND_RAD
    }

    /** Circular mean of the window entries [from, to), radians. */
    private fun meanRad(from: Int, to: Int): Double {
        var c = 0.0
        var s = 0.0
        for (i in from until to) {
            c += window[i].c
            s += window[i].s
        }
        return atan2(s, c)
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
        if (calibrating || interference) release()
    }

    /** Drops the lock and the window; settling starts over from the next sample. */
    private fun release() {
        locked = false
        window.clear()
        skippedOutlier = false
        clearDriftChecks()
    }

    companion object {
        /**
         * Longest gap to the game sample a fused sample is paired with. The live streams arrive at
         * about 50 Hz each, so the nearest game sample is normally 20 ms away; one much farther off may
         * show another orientation while the phone turns, and its yaw difference would be wrong.
         */
        const val PAIR_WINDOW_NS: Long = 100_000_000L

        /** Game samples kept for pairing, well beyond [PAIR_WINDOW_NS] so late fused samples still pair. */
        private const val GAME_KEEP_NS: Long = 500_000_000L

        /**
         * The fused vector counts as silent (status WAITING) after this long without a sample: the same
         * threshold the stall detector uses, far above its 50 Hz spacing.
         */
        const val STALE_NS: Long = 1_000_000_000L

        /**
         * How long the yaw difference has to hold steady for a lock. The fused vector settles over a
         * few seconds after registration; two steady seconds show it has.
         */
        const val LOCK_WINDOW_NS: Long = 2_000_000_000L

        /**
         * Fewest yaw differences a full window must hold for a lock. The live stream is thinned to about
         * 50 Hz, so a full window holds about 100; under 40 means most pairs were lost, and the spread
         * and trend of a few samples say little.
         */
        const val MIN_WINDOW_SAMPLES: Int = 40

        /**
         * Largest deviation of any yaw difference in the window from the window's circular mean: a
         * spread of about 4 degrees. A settled fused vector in a steady hand jitters by about half a
         * degree (standard deviation), and this is four of those, so such a hand locks in two seconds;
         * at 1.5 degrees the same jitter keeps trimming the window and the lock often takes three
         * times as long. A jitter of one degree puts one sample in twenty past this, and skipping lone
         * outliers lets it lock in about three seconds. Slow drift inside this spread is for
         * [MAX_TREND_RAD] to catch.
         */
        val MAX_DEVIATION_RAD: Double = Math.toRadians(2.0)

        /**
         * Largest difference between the circular means of the older and the newer half of the window.
         * The halves' centres lie about a second apart, so this bounds the drift to about a degree per
         * second: a fused vector still converging (a time constant of a few seconds and several degrees
         * to go) drifts faster, while a half's mean averages away the jitter [MAX_DEVIATION_RAD] allows.
         */
        val MAX_TREND_RAD: Double = Math.toRadians(1.0)

        /**
         * Fewest samples a window needs before its trend is judged, and before a lone sample far from its
         * mean is skipped as a glitch. The mean of a half holding only a few samples is as noisy as a
         * single sample, so on a short window the test would keep trimming a jittery but steady stream
         * and the window could not grow; ten samples a half average that out. The mean of a shorter
         * window is too uncertain to call one sample an outlier.
         */
        const val MIN_TREND_SAMPLES: Int = 20

        /**
         * Longest silence of the fused samples (or of the pairs) before the window is cleared and the
         * lock released. Samples arrive every 20 ms; a quarter of a second missing means the app was
         * paused or the sensors were registered again, and a restarted fusion has to settle anew.
         */
        const val MAX_GAP_NS: Long = 250_000_000L

        /**
         * Once locked, two yaw differences in a row this far from the locked mean release the lock (one
         * alone is skipped as a glitch). Well above the 3 to 5 degrees a hand-held phone wobbles by at
         * worst, so the lock does not flicker; a jump this large means the fused vector re-anchored, and
         * north has moved. A smaller move is for the drift test ([DRIFT_RELEASE_NS]) to catch.
         */
        val RELEASE_DEVIATION_RAD: Double = Math.toRadians(6.0)

        /**
         * How long the drift test looks back while locked: the lock lets go when the window drifted on
         * most samples of the last half second. A fused vector re-converging by a few degrees fails the
         * test on most samples for a second or more; hand sway of under a degree fails it on a sample now
         * and then, and on most of half a second too rarely to flicker the lock.
         */
        const val DRIFT_RELEASE_NS: Long = 500_000_000L

        /**
         * The Earth's field is 25 to 65 microtesla everywhere; outside a margin around that range the
         * magnetometer is measuring something else.
         */
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
