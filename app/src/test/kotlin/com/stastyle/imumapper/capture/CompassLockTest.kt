package com.stastyle.imumapper.capture

import com.stastyle.imumapper.pipeline.core.AccelSample
import com.stastyle.imumapper.pipeline.core.MagSample
import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.RotationSample
import com.stastyle.imumapper.pipeline.core.RotationSource
import com.stastyle.imumapper.pipeline.core.Vec3
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompassLockTest {

    /** 42 microtesla pointing north and down, as a flat phone sees the Earth's field at mid latitudes. */
    private val earth = Vec3(0.0, 30.0, -30.0)

    private fun ns(ms: Long): Long = ms * 1_000_000L

    /**
     * Feeds game, fused and magnetometer samples every [stepMs] over [fromMs, toMs]. The game vector is
     * the fused one turned by [gameYawDeg] (counter-clockwise), so the fused-minus-game yaw the lock
     * watches is minus that angle.
     */
    private fun CompassLock.feed(
        fromMs: Long,
        toMs: Long,
        gameYawDeg: (Long) -> Double = { 40.0 },
        fused: Quat = Quat.IDENTITY,
        field: (Long) -> Vec3 = { earth },
        stepMs: Long = 20,
    ) {
        var t = fromMs
        while (t <= toMs) {
            onGame(ns(t), Quat.yaw(Math.toRadians(gameYawDeg(t))) * fused)
            onFused(ns(t), fused, 0.05)
            val f = field(t)
            onMag(ns(t), f.x, f.y, f.z)
            t += stepMs
        }
    }

    /** Feeds one sample at [t] and returns the status right after it. */
    private fun CompassLock.statusAfter(t: Long, gameYawDeg: Double): CompassStatus {
        feed(t, t, gameYawDeg = { gameYawDeg })
        return reading(ns(t)).status
    }

    /** When a lock fed 40 degrees plus Gaussian jitter of [sigmaDeg] first locks, ms; null if not in [limitMs]. */
    private fun lockTimeMs(sigmaDeg: Double, seed: Long, limitMs: Long = 60_000): Long? {
        val random = Random(seed)
        val lock = CompassLock()
        var t = 0L
        while (t <= limitMs) {
            if (lock.statusAfter(t, 40.0 + random.nextGaussian() * sigmaDeg) == CompassStatus.LOCKED) return t
            t += 20
        }
        return null
    }

    /** Outcome of [reconverge]: times in ms after the drift began, and the reading at the end. */
    private class Reconvergence(
        val releasedMs: Long?,
        val relockedMs: Long?,
        val releases: Int,
        val end: CompassReading,
    )

    /**
     * Locks on 40 degrees for two seconds, then moves the yaw difference by [amplitudeDeg] with a [tauS]
     * time constant for 12 s; both with Gaussian jitter of [noiseDeg].
     */
    private fun reconverge(amplitudeDeg: Double, tauS: Double, noiseDeg: Double, seed: Long): Reconvergence {
        val random = Random(seed)
        val lock = CompassLock()
        lock.feed(0, 2000, gameYawDeg = { 40.0 + random.nextGaussian() * noiseDeg })
        assertEquals(CompassStatus.LOCKED, lock.reading(ns(2000)).status, "seed $seed did not lock first")
        var released: Long? = null
        var relocked: Long? = null
        var releases = 0
        var previous = CompassStatus.LOCKED
        var t = 2020L
        while (t <= 14_000) {
            val drift = amplitudeDeg * (1.0 - exp(-(t - 2000) / 1000.0 / tauS))
            val status = lock.statusAfter(t, 40.0 + drift + random.nextGaussian() * noiseDeg)
            if (previous == CompassStatus.LOCKED && status != CompassStatus.LOCKED) {
                releases++
                if (released == null) released = t - 2000
            }
            if (released != null && relocked == null && status == CompassStatus.LOCKED) relocked = t - 2000
            previous = status
            t += 20
        }
        return Reconvergence(released, relocked, releases, lock.reading(ns(14_000)))
    }

    @Test
    fun waitsForTheFirstFusedSample() {
        val lock = CompassLock()
        val r = lock.reading(ns(500))
        assertEquals(CompassStatus.WAITING, r.status)
        assertNull(r.headingDeg)
        assertEquals(0f, r.progress)
    }

    @Test
    fun waitsAgainWhenTheFusedVectorGoesQuiet() {
        val lock = CompassLock()
        lock.feed(0, 500)
        assertEquals(CompassStatus.SETTLING, lock.reading(ns(520)).status)
        assertEquals(CompassStatus.WAITING, lock.reading(ns(500) + CompassLock.STALE_NS + 1).status)
    }

    @Test
    fun settlesThenLocksAfterTwoSteadySeconds() {
        val lock = CompassLock()
        lock.feed(0, 1000)
        val half = lock.reading(ns(1000))
        assertEquals(CompassStatus.SETTLING, half.status)
        assertEquals(0.5f, half.progress, 0.02f)
        lock.feed(1020, 1980)
        assertEquals(CompassStatus.SETTLING, lock.reading(ns(1980)).status)
        lock.feed(2000, 2000)
        val locked = lock.reading(ns(2000))
        assertEquals(CompassStatus.LOCKED, locked.status)
        assertEquals(1f, locked.progress)
        // Game turned +40 degrees from the fused vector: the offset onto north is -40.
        assertEquals(-40.0, assertNotNull(locked.offsetDeg), 1e-6)
        assertEquals(42.43, assertNotNull(locked.fieldUt), 0.01)
    }

    @Test
    fun smallWobbleKeepsTheWindowButALargerJumpRestartsIt() {
        val lock = CompassLock()
        // +-1 degree of wobble stays inside the 2 degree deviation limit.
        lock.feed(0, 1500, gameYawDeg = { t -> if ((t / 20) % 2 == 0L) 39.0 else 41.0 })
        assertEquals(0.75f, lock.reading(ns(1500)).progress, 0.02f)
        // One sample 6 degrees off is skipped as a glitch: the window keeps its progress.
        lock.feed(1520, 1520, gameYawDeg = { 46.0 })
        assertEquals(0.75f, lock.reading(ns(1520)).progress, 0.02f)
        // A second one in a row is a real jump, and the window starts over at it.
        lock.feed(1540, 1540, gameYawDeg = { 46.0 })
        assertEquals(0f, lock.reading(ns(1540)).progress, 0.001f)
        lock.feed(1560, 3520, gameYawDeg = { 46.0 })
        assertEquals(CompassStatus.SETTLING, lock.reading(ns(3520)).status)
        lock.feed(3540, 3540, gameYawDeg = { 46.0 })
        val r = lock.reading(ns(3540))
        assertEquals(CompassStatus.LOCKED, r.status)
        assertEquals(-46.0, assertNotNull(r.offsetDeg), 1e-6)
    }

    @Test
    fun lowMagnetometerAccuracyAsksForAFigureEight() {
        val lock = CompassLock()
        lock.feed(0, 1000)
        lock.onMagAccuracy(1)
        assertEquals(CompassStatus.CALIBRATE, lock.reading(ns(1000)).status)
        lock.onMagAccuracy(0)
        assertEquals(CompassStatus.CALIBRATE, lock.reading(ns(1000)).status)
        lock.onMagAccuracy(2)
        val after = lock.reading(ns(1000))
        assertEquals(CompassStatus.SETTLING, after.status)
        // Calibration threw the window away; settling starts over.
        assertEquals(0f, after.progress)
        lock.onMagAccuracy(null)
        assertEquals(CompassStatus.SETTLING, lock.reading(ns(1000)).status)
    }

    @Test
    fun deltasWhileCalibratingDoNotCountTowardsTheLock() {
        val lock = CompassLock()
        lock.onMagAccuracy(0)
        lock.feed(0, 3000)
        assertEquals(CompassStatus.CALIBRATE, lock.reading(ns(3000)).status)
        lock.onMagAccuracy(3)
        assertEquals(CompassStatus.SETTLING, lock.reading(ns(3000)).status)
        lock.feed(3020, 5020)
        assertEquals(CompassStatus.LOCKED, lock.reading(ns(5020)).status)
    }

    @Test
    fun fieldOutsideTheEarthsRangeIsInterference() {
        val strong = CompassLock()
        strong.feed(0, 500, field = { Vec3(0.0, 90.0, 0.0) })
        assertEquals(CompassStatus.INTERFERENCE, strong.reading(ns(500)).status)

        val weak = CompassLock()
        weak.feed(0, 500, field = { Vec3(0.0, 10.0, 0.0) })
        assertEquals(CompassStatus.INTERFERENCE, weak.reading(ns(500)).status)
    }

    @Test
    fun changingFieldStrengthIsInterference() {
        val lock = CompassLock()
        lock.feed(0, 2500, field = { t -> if ((t / 20) % 2 == 0L) Vec3(0.0, 40.0, 0.0) else Vec3(0.0, 48.0, 0.0) })
        assertEquals(CompassStatus.INTERFERENCE, lock.reading(ns(2500)).status)
    }

    @Test
    fun aLargeJumpAfterTheLockReleasesIt() {
        val lock = CompassLock()
        lock.feed(0, 2000)
        assertEquals(CompassStatus.LOCKED, lock.reading(ns(2000)).status)
        // North moved by 20 degrees: the lock no longer describes it. The first sample there is skipped
        // as a glitch, the second releases the lock, and settling starts at it.
        lock.feed(2020, 2100, gameYawDeg = { 60.0 })
        val released = lock.reading(ns(2100))
        assertEquals(CompassStatus.SETTLING, released.status)
        assertEquals(0.03f, released.progress, 0.001f)
        assertEquals(-60.0, assertNotNull(released.offsetDeg), 1e-6)
        lock.feed(2120, 4020, gameYawDeg = { 60.0 })
        assertEquals(CompassStatus.SETTLING, lock.reading(ns(4020)).status)
        lock.feed(4040, 4040, gameYawDeg = { 60.0 })
        assertEquals(CompassStatus.LOCKED, lock.reading(ns(4040)).status)
    }

    @Test
    fun aJumpJustOverSixDegreesReleasesTheLockAtOnceAndOneJustUnderDoesNot() {
        // Under the jump limit the lock holds through the jump; only the drift test can release it,
        // and not within half a second (see aStepUnderTheJumpLimitThatHoldsIsADrift).
        val under = CompassLock()
        under.feed(0, 2000)
        under.feed(2020, 2500, gameYawDeg = { 45.5 })
        assertEquals(CompassStatus.LOCKED, under.reading(ns(2500)).status)

        val over = CompassLock()
        over.feed(0, 2000)
        // One sample over the limit is a glitch and keeps the lock; the second in a row releases it.
        over.feed(2020, 2020, gameYawDeg = { 46.5 })
        assertEquals(CompassStatus.LOCKED, over.reading(ns(2020)).status)
        over.feed(2040, 2040, gameYawDeg = { 46.5 })
        assertEquals(CompassStatus.SETTLING, over.reading(ns(2040)).status)
    }

    @Test
    fun wobbleWithinSixDegreesKeepsTheLock() {
        val lock = CompassLock()
        lock.feed(0, 2000)
        assertEquals(CompassStatus.LOCKED, lock.reading(ns(2000)).status)
        // A hand swinging the phone: the difference wobbles 3 to 5 degrees either side of the locked 40.
        val wobble = listOf(35.0, 44.0, 37.0, 45.0, 36.0, 43.0)
        lock.feed(2020, 6000, gameYawDeg = { t -> wobble[((t / 20) % wobble.size).toInt()] })
        assertEquals(CompassStatus.LOCKED, lock.reading(ns(6000)).status)
        assertEquals(1f, lock.reading(ns(6000)).progress)
    }

    @Test
    fun calibrationReleasesTheLock() {
        val lock = CompassLock()
        lock.feed(0, 2000)
        assertEquals(CompassStatus.LOCKED, lock.reading(ns(2000)).status)
        // A figure-8 request releases it, and afterwards it has to settle again.
        lock.onMagAccuracy(1)
        assertEquals(CompassStatus.CALIBRATE, lock.reading(ns(2000)).status)
        lock.onMagAccuracy(3)
        val after = lock.reading(ns(2000))
        assertEquals(CompassStatus.SETTLING, after.status)
        assertEquals(0f, after.progress)
    }

    @Test
    fun aDriftOfTwoDegreesPerSecondNeverLocks() {
        val lock = CompassLock()
        // The spread of two seconds of this ramp is 4 degrees, just inside the deviation limit; the
        // trend between the window's halves (2 degrees) is what refuses it.
        lock.feed(0, 10_000, gameYawDeg = { t -> 20.0 + 2.0 * t / 1000.0 })
        val r = lock.reading(ns(10_000))
        assertEquals(CompassStatus.SETTLING, r.status)
        // Dropping the drifting start keeps about one second: progress shrinks instead of filling.
        assertTrue(r.progress < 0.6f, "progress ${r.progress}")
    }

    @Test
    fun aDriftOfOneDegreePerSecondLocksOnceItSettles() {
        val lock = CompassLock()
        lock.feed(0, 3000, gameYawDeg = { t -> 20.0 + t / 1000.0 })
        // Held at 23 degrees from 3 s on: within two seconds the window is steady again.
        lock.feed(3020, 5000, gameYawDeg = { 23.0 })
        val r = lock.reading(ns(5000))
        assertEquals(CompassStatus.LOCKED, r.status)
        assertEquals(-23.0, assertNotNull(r.offsetDeg), 0.5)
    }

    @Test
    fun aFusedVectorStillConvergingLocksOnlyNearItsFinalNorth() {
        // The yaw difference approaches its final value with a 3 s time constant from 20 degrees off, as
        // the fused vector does after registration. Locking after two seconds regardless would anchor
        // north about 10 degrees off.
        val lock = CompassLock()
        var lockedAtMs: Long? = null
        var t = 0L
        while (t <= 20_000 && lockedAtMs == null) {
            lock.feed(t, t, gameYawDeg = { ms -> 40.0 + 20.0 * exp(-ms / 3000.0) })
            if (lock.reading(ns(t)).status == CompassStatus.LOCKED) lockedAtMs = t
            t += 20
        }
        val at = assertNotNull(lockedAtMs)
        val remainingDeg = 20.0 * exp(-at / 3000.0)
        assertTrue(remainingDeg < 2.5, "locked at $at ms with $remainingDeg degrees to go")
    }

    @Test
    fun handHeldJitterOfHalfADegreeStillLocks() {
        val random = Random(42)
        val jitter = HashMap<Long, Double>()
        val lock = CompassLock()
        lock.feed(0, 3000, gameYawDeg = { t -> 40.0 + jitter.getOrPut(t) { random.nextGaussian() * 0.5 } })
        val r = lock.reading(ns(3000))
        assertEquals(CompassStatus.LOCKED, r.status)
        assertEquals(-40.0, assertNotNull(r.offsetDeg), 0.3)
    }

    @Test
    fun aLoneGlitchIsSkippedAndTheLockComesOnTime() {
        val lock = CompassLock()
        lock.feed(0, 1000)
        // A 5 degree spike: added, it would fail the spread and trim the window down to itself.
        lock.feed(1020, 1020, gameYawDeg = { 45.0 })
        val r = lock.reading(ns(1020))
        assertEquals(0.5f, r.progress, 0.001f)
        assertEquals(-40.0, assertNotNull(r.offsetDeg), 1e-6)
        lock.feed(1040, 2000)
        assertEquals(CompassStatus.LOCKED, lock.reading(ns(2000)).status)
        // Once locked, a lone spike past the jump limit is skipped too.
        lock.feed(2020, 2020, gameYawDeg = { 55.0 })
        assertEquals(CompassStatus.LOCKED, lock.reading(ns(2020)).status)
        lock.feed(2040, 2100)
        assertEquals(CompassStatus.LOCKED, lock.reading(ns(2100)).status)
    }

    @Test
    fun jitterOfOneDegreeLocksInEverySeededRun() {
        // One sample in twenty lands past the 2 degree spread. Before lone outliers were skipped each of
        // them trimmed the window, and a third of these runs did not lock within a minute.
        val times = (0L until 30L).map { seed -> assertNotNull(lockTimeMs(1.0, seed), "seed $seed never locked") }
            .sorted()
        assertTrue(times[times.size / 2] <= 3500, "median ${times[times.size / 2]} ms")
        assertTrue(times.last() <= 10_000, "slowest ${times.last()} ms")
    }

    @Test
    fun jitterOfOnePointTwoDegreesLocksToo() {
        // Before lone outliers were skipped, none of these runs locked within a minute.
        for (seed in 0L until 30L) assertNotNull(lockTimeMs(1.2, seed), "seed $seed never locked within a minute")
    }

    @Test
    fun aReconvergenceUnderTheJumpLimitReleasesTheLockUntilItSettles() {
        // After the lock the fused vector re-anchors and moves 5.5 degrees with a 2 s time constant: never
        // 6 degrees from the locked north, but north is moving, and a recording started now would take it
        // part-way.
        val r = reconverge(amplitudeDeg = 5.5, tauS = 2.0, noiseDeg = 0.0, seed = 0)
        val released = assertNotNull(r.releasedMs, "the lock held through the drift")
        // Released with more than 2 degrees still to go.
        assertTrue(5.5 * exp(-released / 2000.0) > 2.0, "released at $released ms")
        val relocked = assertNotNull(r.relockedMs, "never locked again")
        assertTrue(relocked <= 5000, "locked again at $relocked ms")
        assertEquals(1, r.releases)
        assertEquals(CompassStatus.LOCKED, r.end.status)
        assertEquals(-45.5, assertNotNull(r.end.offsetDeg), 0.5)
    }

    @Test
    fun aJitteryReconvergenceReleasesTheLockToo() {
        // With half a degree of jitter on top, the trend alone rarely fails on most of half a second: the
        // jitter keeps trimming the window short. The window's mean leaving the locked north catches it.
        for (seed in 100L until 108L) {
            val r = reconverge(amplitudeDeg = 5.5, tauS = 2.0, noiseDeg = 0.5, seed = seed)
            val released = assertNotNull(r.releasedMs, "seed $seed: the lock held through the drift")
            assertTrue(released <= 3000, "seed $seed: released at $released ms")
            assertNotNull(r.relockedMs, "seed $seed: never locked again")
            assertEquals(1, r.releases, "seed $seed")
            assertEquals(CompassStatus.LOCKED, r.end.status, "seed $seed")
            assertEquals(-45.5, assertNotNull(r.end.offsetDeg), 0.5, "seed $seed")
        }
    }

    @Test
    fun handSwayDoesNotFlickerTheLock() {
        // Sway of 0.7 degrees (standard deviation): 60 % of the variance a random wander with a 0.15 s time
        // constant, the rest white jitter. It fails the drift test on a sample now and then, not on most of
        // half a second.
        val sigma = 0.7
        val a = exp(-0.02 / 0.15)
        val wanderStep = sqrt(0.6) * sigma * sqrt(1 - a * a)
        val white = sqrt(0.4) * sigma
        for (seed in 200L until 205L) {
            val random = Random(seed)
            val lock = CompassLock()
            lock.feed(0, 2000)
            assertEquals(CompassStatus.LOCKED, lock.reading(ns(2000)).status)
            var wander = 0.0
            var t = 2020L
            while (t <= 62_000) {
                wander = a * wander + random.nextGaussian() * wanderStep
                val status = lock.statusAfter(t, 40.0 + wander + random.nextGaussian() * white)
                assertEquals(CompassStatus.LOCKED, status, "seed $seed at $t ms")
                t += 20
            }
        }
    }

    @Test
    fun aStepUnderTheJumpLimitThatHoldsIsADrift() {
        // North moves 4.5 degrees at once and stays there. Under the jump limit, so the lock holds at first;
        // once the window holds the new north, its mean is off the locked one on most of half a second, and
        // the lock waits for two steady seconds as after a jump.
        val lock = CompassLock()
        lock.feed(0, 2000)
        lock.feed(2020, 2500, gameYawDeg = { 44.5 })
        assertEquals(CompassStatus.LOCKED, lock.reading(ns(2500)).status)
        lock.feed(2520, 3000, gameYawDeg = { 44.5 })
        assertEquals(CompassStatus.SETTLING, lock.reading(ns(3000)).status)
        // The window kept the new north since its second sample (the first was skipped as a glitch).
        lock.feed(3020, 4020, gameYawDeg = { 44.5 })
        assertEquals(CompassStatus.SETTLING, lock.reading(ns(4020)).status)
        lock.feed(4040, 4040, gameYawDeg = { 44.5 })
        val r = lock.reading(ns(4040))
        assertEquals(CompassStatus.LOCKED, r.status)
        assertEquals(-44.5, assertNotNull(r.offsetDeg), 1e-6)
    }

    @Test
    fun aSparseStreamDoesNotLock() {
        val lock = CompassLock()
        // One pair every 100 ms: the window spans two seconds but holds only 21 samples.
        lock.feed(0, 4000, stepMs = 100)
        assertEquals(CompassStatus.SETTLING, lock.reading(ns(4000)).status)
    }

    @Test
    fun aLoneSampleAfterALongGapDoesNotLock() {
        val lock = CompassLock()
        lock.feed(0, 1980)
        assertEquals(CompassStatus.SETTLING, lock.reading(ns(1980)).status)
        // 30 s later (the app was in the background) one sample arrives: it starts a new window.
        lock.feed(31_980, 31_980)
        val r = lock.reading(ns(31_980))
        assertEquals(CompassStatus.SETTLING, r.status)
        assertEquals(0f, r.progress)
    }

    @Test
    fun aGapAfterTheLockReleasesIt() {
        val lock = CompassLock()
        lock.feed(0, 2000)
        assertEquals(CompassStatus.LOCKED, lock.reading(ns(2000)).status)
        // Two seconds without samples: the fusion may have restarted, so north has to settle again.
        lock.feed(4000, 4100)
        val r = lock.reading(ns(4100))
        assertEquals(CompassStatus.SETTLING, r.status)
        assertEquals(0.05f, r.progress, 0.001f)
        lock.feed(4120, 6000)
        assertEquals(CompassStatus.LOCKED, lock.reading(ns(6000)).status)
    }

    @Test
    fun aShortGapKeepsTheWindow() {
        val lock = CompassLock()
        lock.feed(0, 1000)
        // 200 ms missing is under the gap limit: the window carries on and locks on time.
        lock.feed(1200, 2000)
        assertEquals(CompassStatus.LOCKED, lock.reading(ns(2000)).status)
    }

    @Test
    fun liveRecordsAreRoutedByType() {
        val lock = CompassLock()
        val g = Quat.yaw(Math.toRadians(40.0))
        var t = 0L
        while (t <= 2000) {
            val gx = g.x.toFloat()
            val gy = g.y.toFloat()
            val gz = g.z.toFloat()
            lock.feed(RotationSample(ns(t), gx, gy, gz, g.w.toFloat(), -1f, RotationSource.GAME))
            lock.feed(RotationSample(ns(t), 0f, 0f, 0f, 1f, 0.05f, RotationSource.FUSED))
            lock.feed(MagSample(ns(t), earth.x.toFloat(), earth.y.toFloat(), earth.z.toFloat()))
            lock.feed(AccelSample(ns(t), 0f, 0f, 9.81f))
            t += 20
        }
        val r = lock.reading(ns(2000))
        assertEquals(CompassStatus.LOCKED, r.status)
        assertEquals(-40.0, assertNotNull(r.offsetDeg), 1e-3)
        assertEquals(Math.toDegrees(0.05), assertNotNull(r.accuracyDeg), 1e-3)
    }

    @Test
    fun interferenceReleasesTheLock() {
        val lock = CompassLock()
        lock.feed(0, 2000)
        assertEquals(CompassStatus.LOCKED, lock.reading(ns(2000)).status)
        lock.feed(2020, 2020, field = { Vec3(0.0, 100.0, 0.0) })
        assertEquals(CompassStatus.INTERFERENCE, lock.reading(ns(2020)).status)
        // The strong sample stays in the variation window for two seconds, then settling restarts.
        lock.feed(2040, 4020)
        assertEquals(CompassStatus.INTERFERENCE, lock.reading(ns(4020)).status)
        lock.feed(4040, 4100)
        assertEquals(CompassStatus.SETTLING, lock.reading(ns(4100)).status)
        lock.feed(4120, 6100)
        assertEquals(CompassStatus.LOCKED, lock.reading(ns(6100)).status)
    }

    @Test
    fun fusedSamplesWithoutAGameSampleWithin100MsAreIgnored() {
        val lock = CompassLock()
        lock.onGame(ns(0), Quat.yaw(Math.toRadians(20.0)))
        var t = 150L
        while (t <= 3000) {
            lock.onFused(ns(t), Quat.IDENTITY, 0.05)
            lock.onMag(ns(t), earth.x, earth.y, earth.z)
            t += 20
        }
        val r = lock.reading(ns(3000))
        assertEquals(CompassStatus.SETTLING, r.status)
        assertNull(r.offsetDeg)
        assertEquals(0f, r.progress)
    }

    @Test
    fun aFusedSampleWaitsForTheNearestGameSample() {
        val lock = CompassLock()
        lock.onGame(ns(0), Quat.yaw(Math.toRadians(10.0)))
        lock.onFused(ns(60), Quat.IDENTITY, 0.05)
        // Not paired yet: a closer game sample may still arrive.
        assertNull(lock.reading(ns(60)).offsetDeg)
        lock.onGame(ns(90), Quat.yaw(Math.toRadians(30.0)))
        // 30 ms beats 60 ms.
        assertEquals(-30.0, assertNotNull(lock.reading(ns(90)).offsetDeg), 1e-6)
    }

    @Test
    fun aFusedSamplePairsWithAnEarlierGameSampleOnceTheStreamsMoveOn() {
        val paired = CompassLock()
        paired.onGame(ns(0), Quat.yaw(Math.toRadians(20.0)))
        paired.onFused(ns(80), Quat.IDENTITY, 0.05)
        paired.onMag(ns(200), earth.x, earth.y, earth.z)
        assertEquals(-20.0, assertNotNull(paired.reading(ns(200)).offsetDeg), 1e-6)

        val tooFar = CompassLock()
        tooFar.onGame(ns(0), Quat.yaw(Math.toRadians(20.0)))
        tooFar.onFused(ns(120), Quat.IDENTITY, 0.05)
        tooFar.onMag(ns(300), earth.x, earth.y, earth.z)
        assertNull(tooFar.reading(ns(300)).offsetDeg)
    }

    @Test
    fun headingUsesTheTopEdgeWhenFlatAndTheCameraWhenUpright() {
        fun heading(q: Quat): Double {
            val lock = CompassLock()
            lock.onFused(0L, q, -1.0)
            return assertNotNull(lock.reading(0L).headingDeg)
        }
        val east = Quat.yaw(Math.toRadians(-90.0))
        val upright = Quat.fromAxisAngle(Vec3.UNIT_X, Math.toRadians(90.0))
        assertEquals(0.0, heading(Quat.IDENTITY), 1e-9)
        assertEquals(90.0, heading(east), 1e-9)
        // Upright: the top edge points at the sky, the camera looks where the user faces.
        assertEquals(0.0, heading(upright), 1e-9)
        assertEquals(90.0, heading(east * upright), 1e-9)
        assertEquals(270.0, heading(Quat.yaw(Math.toRadians(90.0)) * upright), 1e-9)
        // Tilted 30 degrees up from flat is still "flat": the top edge is used.
        val tilted = east * Quat.fromAxisAngle(Vec3.UNIT_X, Math.toRadians(30.0))
        assertEquals(90.0, heading(tilted), 1e-9)
    }

    @Test
    fun accuracyIsReportedOnlyWhenTheSensorGivesOne() {
        val lock = CompassLock()
        lock.onFused(0L, Quat.IDENTITY, -1.0)
        assertNull(lock.reading(0L).accuracyDeg)
        lock.onFused(10L, Quat.IDENTITY, 0.1)
        assertEquals(Math.toDegrees(0.1), assertNotNull(lock.reading(10L).accuracyDeg), 1e-9)
    }

    @Test
    fun yawDifferenceNearHalfATurnWrapsInsteadOfJumping() {
        val lock = CompassLock()
        // The difference alternates between +179 and -179 degrees: two degrees apart, not 358.
        lock.feed(0, 2000, gameYawDeg = { t -> if ((t / 20) % 2 == 0L) -179.0 else 179.0 })
        val r = lock.reading(ns(2000))
        assertEquals(CompassStatus.LOCKED, r.status)
        assertEquals(180.0, abs(assertNotNull(r.offsetDeg)), 0.01)
    }

    @Test
    fun headingNearSouthStaysInRange() {
        val lock = CompassLock()
        lock.onFused(0L, Quat.yaw(Math.toRadians(-179.5)), -1.0)
        assertEquals(179.5, assertNotNull(lock.reading(0L).headingDeg), 1e-9)
        lock.onFused(10L, Quat.yaw(Math.toRadians(179.5)), -1.0)
        assertEquals(180.5, assertNotNull(lock.reading(10L).headingDeg), 1e-9)
    }

    @Test
    fun unwrapTakesTheShortWayRound() {
        assertEquals(361.0, CompassLock.unwrapDegrees(359.0, 1.0), 1e-9)
        assertEquals(-1.0, CompassLock.unwrapDegrees(1.0, 359.0), 1e-9)
        assertEquals(730.0, CompassLock.unwrapDegrees(720.0, 10.0), 1e-9)
        assertEquals(-350.0, CompassLock.unwrapDegrees(-355.0, 10.0), 1e-9)
        assertTrue(abs(CompassLock.unwrapDegrees(0.0, 180.0)) == 180.0)
        assertEquals(0.0, CompassLock.normalizeDegrees(360.0), 1e-9)
        assertEquals(350.0, CompassLock.normalizeDegrees(-10.0), 1e-9)
    }
}
