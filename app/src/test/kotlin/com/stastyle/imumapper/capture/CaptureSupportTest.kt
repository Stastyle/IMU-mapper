package com.stastyle.imumapper.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateEstimatorTest {
    private val ms = 1_000_000L

    @Test
    fun measuresSteadyRate() {
        val r = RateEstimator(windowNs = 1_000 * ms)
        // 200 Hz: a sample every 5 ms for 2.5 s.
        for (i in 0..500) r.onSample(i * 5 * ms)
        assertEquals(200.0, r.rateHz, 0.5)
        assertEquals(200.0, r.rateAt(2_500 * ms), 0.5)
    }

    @Test
    fun isZeroBeforeFirstWindowAndAfterSilence() {
        val r = RateEstimator(windowNs = 1_000 * ms)
        for (i in 0..50) r.onSample(i * 5 * ms)
        assertEquals(0.0, r.rateHz)
        for (i in 51..250) r.onSample(i * 5 * ms)
        assertTrue(r.rateHz > 190.0)
        // Two silent windows: the display should not keep showing a stale rate.
        assertEquals(0.0, r.rateAt(250 * 5 * ms + 3_000 * ms))
        assertTrue(r.rateAt(250 * 5 * ms + 500 * ms) > 190.0)
    }

    @Test
    fun handlesJitterAndSlowSensors() {
        val r = RateEstimator(windowNs = 1_000 * ms)
        // ~10 Hz barometer with 30 % jitter.
        var t = 0L
        var i = 0
        while (t < 5_000 * ms) {
            r.onSample(t)
            t += (100 + (if (i % 2 == 0) 30 else -30)) * ms
            i++
        }
        assertEquals(10.0, r.rateHz, 1.0)
    }

    @Test
    fun resetForgetsHistory() {
        val r = RateEstimator()
        for (i in 0..400) r.onSample(i * 5 * ms)
        r.reset()
        assertEquals(0.0, r.rateHz)
        assertEquals(0.0, r.rateAt(10_000 * ms))
    }
}

class StallDetectorTest {
    private val s = 1_000_000_000L

    @Test
    fun reportsSilentSensorOnce() {
        val d = StallDetector(thresholdNs = 1 * s)
        d.onSample(SensorKind.ACCEL, 0)
        d.onSample(SensorKind.GYRO, 0)
        assertEquals(emptyList(), d.check(s / 2))
        d.onSample(SensorKind.GYRO, s)
        assertEquals(listOf(SensorKind.ACCEL), d.check(s + s / 2))
        assertTrue(d.isStalled(SensorKind.ACCEL))
        assertFalse(d.isStalled(SensorKind.GYRO))
        // Accel still silent: not reported again (gyro keeps delivering).
        d.onSample(SensorKind.GYRO, 5 * s - s / 2)
        assertEquals(emptyList(), d.check(5 * s))
    }

    @Test
    fun recoveryRearmsTheReport() {
        val d = StallDetector(thresholdNs = 1 * s)
        d.onSample(SensorKind.BARO, 0)
        assertEquals(listOf(SensorKind.BARO), d.check(2 * s))
        d.onSample(SensorKind.BARO, 2 * s)
        assertFalse(d.isStalled(SensorKind.BARO))
        assertEquals(listOf(SensorKind.BARO), d.check(4 * s))
    }

    @Test
    fun stepDetectorIsNeverAStall() {
        val d = StallDetector(thresholdNs = 1 * s)
        d.onSample(SensorKind.STEP, 0)
        d.onSample(SensorKind.MAG, 0)
        assertEquals(listOf(SensorKind.MAG), d.check(60 * s))
        assertFalse(d.isStalled(SensorKind.STEP))
    }

    @Test
    fun exactlyThresholdIsNotAStall() {
        val d = StallDetector(thresholdNs = 1 * s)
        d.onSample(SensorKind.ACCEL, 0)
        assertEquals(emptyList(), d.check(s))
        assertEquals(listOf(SensorKind.ACCEL), d.check(s + 1))
    }
}

class DecimatorTest {
    private val ms = 1_000_000L

    @Test
    fun thins500HzToAbout50Hz() {
        val d = Decimator(targetHz = 50.0)
        var passed = 0
        for (i in 0 until 500) if (d.accept(i * 2 * ms)) passed++
        // One second of 500 Hz input: 50 samples, plus or minus one for the boundary.
        assertTrue(passed in 49..51, "passed $passed")
    }

    @Test
    fun passesEverythingBelowTargetRate() {
        val d = Decimator(targetHz = 50.0)
        var passed = 0
        for (i in 0 until 10) if (d.accept(i * 100 * ms)) passed++
        assertEquals(10, passed)
    }

    @Test
    fun acceptsAfterTimeGoesBackwards() {
        val d = Decimator(targetHz = 50.0)
        assertTrue(d.accept(1_000 * ms))
        assertFalse(d.accept(1_001 * ms))
        assertTrue(d.accept(10 * ms))
    }
}

class FormatTest {
    @Test
    fun formatsElapsed() {
        assertEquals("0:00", formatElapsed(0))
        assertEquals("0:00", formatElapsed(-5_000_000_000L))
        assertEquals("0:59", formatElapsed(59_999_999_999L))
        assertEquals("1:05", formatElapsed(65_000_000_000L))
        assertEquals("1:00:00", formatElapsed(3_600_000_000_000L))
        assertEquals("2:03:04", formatElapsed((2 * 3600 + 3 * 60 + 4) * 1_000_000_000L))
    }

    @Test
    fun formatsRates() {
        assertEquals("498 Hz", formatRate(498.4))
        assertEquals("8.5 Hz", formatRate(8.5))
        assertEquals("0.0 Hz", formatRate(0.0))
    }

    @Test
    fun coreHealthNeedsAccelAndGyro() {
        val ok = SensorStats(
            sensors = mapOf(
                SensorKind.ACCEL to SensorHealth(SensorKind.ACCEL, available = true, sampleCount = 10),
                SensorKind.GYRO to SensorHealth(SensorKind.GYRO, available = true, sampleCount = 10),
            ),
        )
        assertTrue(ok.coreSensorsHealthy)
        val stalledGyro = ok.copy(
            sensors = ok.sensors + (SensorKind.GYRO to SensorHealth(SensorKind.GYRO, true, 10, stalled = true)),
        )
        assertFalse(stalledGyro.coreSensorsHealthy)
        assertFalse(SensorStats().coreSensorsHealthy)
    }
}

class PauseLedgerTest {
    private val s = 1_000_000_000L

    @Test
    fun elapsedAndStepsExcludePauses() {
        val l = PauseLedger(startedNs = 10 * s)
        assertFalse(l.paused)
        assertEquals(5 * s, l.activeElapsedNs(15 * s))
        assertEquals(40, l.activeSteps(40))

        assertTrue(l.pause(nowNs = 15 * s, stepsSoFar = 40))
        assertTrue(l.paused)
        // A pause still open: the timer and the step count stay frozen.
        assertEquals(5 * s, l.activeElapsedNs(25 * s))
        assertEquals(40, l.activeSteps(70))

        assertTrue(l.resume(nowNs = 25 * s, stepsSoFar = 70))
        assertFalse(l.paused)
        assertEquals(5 * s, l.activeElapsedNs(25 * s))
        assertEquals(8 * s, l.activeElapsedNs(28 * s))
        assertEquals(45, l.activeSteps(75))

        // A second pause adds up with the first.
        assertTrue(l.pause(28 * s, 75))
        assertTrue(l.resume(30 * s, 80))
        assertEquals(9 * s, l.activeElapsedNs(31 * s))
        assertEquals(46, l.activeSteps(81))
    }

    @Test
    fun transitionsHappenOnce() {
        val l = PauseLedger(startedNs = 0)
        assertFalse(l.resume(1 * s, 0))
        assertTrue(l.pause(1 * s, 0))
        assertFalse(l.pause(2 * s, 0))
        assertTrue(l.resume(3 * s, 0))
        assertFalse(l.resume(4 * s, 0))
        assertEquals(2 * s, l.activeElapsedNs(4 * s))
    }

    @Test
    fun stepCountNeverGoesNegative() {
        // The logger's counter went backwards (restarted) during a pause: that pause counts no steps.
        val l = PauseLedger(startedNs = 0)
        l.pause(1 * s, 30)
        assertEquals(5, l.activeSteps(5))
        l.resume(2 * s, 5)
        assertEquals(5, l.activeSteps(5))
        // Backwards after a pause that did count steps: clamp instead of reporting a negative count.
        val m = PauseLedger(startedNs = 0)
        m.pause(1 * s, 0)
        m.resume(2 * s, 10)
        assertEquals(0, m.activeSteps(3))
        assertEquals(2, m.activeSteps(12))
    }
}
