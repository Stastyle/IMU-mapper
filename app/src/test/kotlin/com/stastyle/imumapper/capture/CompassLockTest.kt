package com.stastyle.imumapper.capture

import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.math.abs
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
        // +-1 degree of wobble stays inside the 3 degree limit.
        lock.feed(0, 1500, gameYawDeg = { t -> if ((t / 20) % 2 == 0L) 39.0 else 41.0 })
        assertEquals(0.75f, lock.reading(ns(1500)).progress, 0.02f)
        // A 6 degree jump starts the window over at the jump.
        lock.feed(1520, 1520, gameYawDeg = { 46.0 })
        assertEquals(0f, lock.reading(ns(1520)).progress, 0.001f)
        lock.feed(1540, 3500, gameYawDeg = { 46.0 })
        assertEquals(CompassStatus.SETTLING, lock.reading(ns(3500)).status)
        lock.feed(3520, 3520, gameYawDeg = { 46.0 })
        val r = lock.reading(ns(3520))
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
    fun lockLatchesUntilCalibrationReleasesIt() {
        val lock = CompassLock()
        lock.feed(0, 2000)
        assertEquals(CompassStatus.LOCKED, lock.reading(ns(2000)).status)
        // A jump after the lock restarts the window but does not unlock.
        lock.feed(2020, 2100, gameYawDeg = { 60.0 })
        val latched = lock.reading(ns(2100))
        assertEquals(CompassStatus.LOCKED, latched.status)
        assertEquals(-60.0, assertNotNull(latched.offsetDeg), 1e-6)
        // A figure-8 request releases it, and afterwards it has to settle again.
        lock.onMagAccuracy(1)
        assertEquals(CompassStatus.CALIBRATE, lock.reading(ns(2100)).status)
        lock.onMagAccuracy(3)
        assertEquals(CompassStatus.SETTLING, lock.reading(ns(2100)).status)
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
