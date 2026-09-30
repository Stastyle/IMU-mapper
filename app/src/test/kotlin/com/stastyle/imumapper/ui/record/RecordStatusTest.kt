package com.stastyle.imumapper.ui.record

import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.capture.SensorHealth
import com.stastyle.imumapper.capture.SensorKind
import com.stastyle.imumapper.capture.SensorStats
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.ui.common.NO_VALUE
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The recording screen's status precedence, sensor tiles and live counters. */
class RecordStatusTest {

    private val s = 1_000_000_000L

    private fun ok(kind: SensorKind) = SensorHealth(kind, available = true, sampleCount = 500L, rateHz = 100.0)

    private fun absent(kind: SensorKind) = SensorHealth(kind, available = false)

    private fun stalled(kind: SensorKind) = ok(kind).copy(stalled = true, rateHz = 0.0)

    private fun silent(kind: SensorKind) = SensorHealth(kind, available = true)

    /** Every sensor delivering, then [overrides] by kind. */
    private fun stats(vararg overrides: SensorHealth, writeError: String? = null): SensorStats {
        val map = SensorKind.entries.associateWith { ok(it) }.toMutableMap()
        for (h in overrides) map[h.kind] = h
        return SensorStats(sensors = map, stepCount = 40, running = true, writeError = writeError)
    }

    private fun recording(
        elapsedNs: Long = 60 * s,
        paused: Boolean = false,
        steps: Int = 0,
        marks: Int = 0,
        mode: TripMode = TripMode.POCKET,
    ) = RecordingState.Recording(
        tripId = 1L,
        mode = mode,
        carryPosition = CarryPosition.HAND,
        startedNs = 0L,
        elapsedNs = elapsedNs,
        paused = paused,
        stepCount = steps,
        annotationCount = marks,
        photosDir = File("photos"),
    )

    // --- Status precedence ---

    @Test
    fun allDeliveringIsSensorsOk() {
        assertEquals(RecordStatusLine("Sensors OK", RecordTone.Good), RecordStatus.status(stats(), recording()))
    }

    @Test
    fun writeErrorWinsOverEverything() {
        val line = RecordStatus.status(
            stats(stalled(SensorKind.ACCEL), stalled(SensorKind.BARO), writeError = "No space left on device"),
            recording(paused = true),
        )
        assertEquals("Log write failed: No space left on device", line.text)
        assertEquals(RecordTone.Bad, line.tone)
        // The pause is still explained under whichever line wins.
        assertEquals(RecordStatus.PAUSED_NOTE, line.detail)
    }

    @Test
    fun coreSensorFaultIsRedAndBeatsOtherSensors() {
        val line = RecordStatus.status(stats(stalled(SensorKind.GYRO), stalled(SensorKind.BARO)), recording())
        assertEquals(RecordStatusLine("Gyroscope not delivering", RecordTone.Bad), line)
        assertEquals(
            "Accelerometer and gyroscope not delivering",
            RecordStatus.status(stats(stalled(SensorKind.ACCEL), absent(SensorKind.GYRO)), recording()).text,
        )
    }

    @Test
    fun missingCoreSensorIsRed() {
        val line = RecordStatus.status(stats(absent(SensorKind.ACCEL)), recording())
        assertEquals(RecordStatusLine("Accelerometer not delivering", RecordTone.Bad), line)
    }

    @Test
    fun otherSensorFaultIsAmberAndBeatsPause() {
        val line = RecordStatus.status(stats(stalled(SensorKind.BARO)), recording(paused = true))
        assertEquals("Barometer not delivering", line.text)
        assertEquals(RecordTone.Warning, line.tone)
        assertEquals(RecordStatus.PAUSED_NOTE, line.detail)
    }

    @Test
    fun pausedIsAmberWithTheExplanation() {
        assertEquals(
            RecordStatusLine("Paused", RecordTone.Warning, RecordStatus.PAUSED_NOTE),
            RecordStatus.status(stats(), recording(paused = true)),
        )
    }

    @Test
    fun sensorsThePhoneLacksAreNotFaults() {
        // No barometer, no uncalibrated streams and no step detector: nothing is missing that should deliver.
        val line = RecordStatus.status(
            stats(absent(SensorKind.BARO), absent(SensorKind.MAG_UNCAL), absent(SensorKind.STEP)),
            recording(),
        )
        assertEquals("Sensors OK", line.text)
    }

    @Test
    fun silentStepDetectorIsNeverAFault() {
        // The step detector goes quiet whenever the user stands still.
        assertEquals("Sensors OK", RecordStatus.status(stats(silent(SensorKind.STEP)), recording()).text)
    }

    // --- The 2 s silence rule ---

    @Test
    fun silenceCountsOnlyAfterTwoSecondsOfActiveTime() {
        val quietBaro = stats(silent(SensorKind.BARO))
        assertEquals("Sensors OK", RecordStatus.status(quietBaro, recording(elapsedNs = 1_999_999_999L)).text)
        assertEquals("Barometer not delivering", RecordStatus.status(quietBaro, recording(elapsedNs = 2 * s)).text)
        // A silent accelerometer right after the start is not an alarm either; after the grace it is red.
        val quietAccel = stats(silent(SensorKind.ACCEL))
        assertEquals("Sensors OK", RecordStatus.status(quietAccel, recording(elapsedNs = s / 4)).text)
        val late = RecordStatus.status(quietAccel, recording(elapsedNs = 3 * s))
        assertEquals(RecordStatusLine("Accelerometer not delivering", RecordTone.Bad), late)
    }

    @Test
    fun stalledCountsAtOnce() {
        val line = RecordStatus.status(stats(stalled(SensorKind.MAG)), recording(elapsedNs = s / 2))
        assertEquals("Magnetometer not delivering", line.text)
    }

    // --- Sensor naming ---

    @Test
    fun namesOneOrTwoSensorsAndCountsMore() {
        assertEquals(
            "Magnetometer and barometer not delivering",
            RecordStatus.status(stats(stalled(SensorKind.BARO), stalled(SensorKind.MAG)), recording()).text,
        )
        assertEquals(
            "3 sensors not delivering",
            RecordStatus.status(
                stats(stalled(SensorKind.BARO), stalled(SensorKind.MAG), silent(SensorKind.ROT_VEC)),
                recording(),
            ).text,
        )
        assertEquals("Raw gyroscope not delivering", RecordStatus.notDeliveringText(listOf(SensorKind.GYRO_UNCAL)))
    }

    // --- Labels ---

    @Test
    fun processingLabelAndSubtitles() {
        assertEquals("PDR", RecordStatus.processingLabel(TripMode.POCKET))
        assertEquals("Camera + steps", RecordStatus.processingLabel(TripMode.FLASHLIGHT))
        assertEquals("Camera + steps", RecordStatus.processingLabel(TripMode.ILLUMINATED))
        assertEquals("Pocket · Hand", RecordStatus.headerSubtitle(TripMode.POCKET, CarryPosition.HAND))
        assertEquals("Pocket · Hand · PDR", RecordStatus.statusSubtitle(TripMode.POCKET, CarryPosition.HAND))
        assertEquals(
            "Flashlight · Helmet · Camera + steps",
            RecordStatus.statusSubtitle(TripMode.FLASHLIGHT, CarryPosition.HELMET),
        )
    }

    // --- Tiles ---

    @Test
    fun imuTileStates() {
        val active = 10 * s
        val good = RecordStatus.imuTile(stats(), active)
        assertEquals("OK", good.value)
        assertEquals(RecordTone.Good, good.tone)
        assertEquals("Accel 100 Hz", good.detail)
        assertEquals("IMU: OK, accelerometer at 100 Hz", good.spoken)

        val stalledTile = RecordStatus.imuTile(stats(stalled(SensorKind.GYRO)), active)
        assertEquals("Stalled", stalledTile.value)
        assertEquals(RecordTone.Bad, stalledTile.tone)

        val none = RecordStatus.imuTile(stats(absent(SensorKind.GYRO)), active)
        assertEquals("None", none.value)
        assertEquals("no gyroscope", none.detail)

        // Before the first samples and within the grace: neither OK nor stalled.
        val waiting = RecordStatus.imuTile(stats(silent(SensorKind.ACCEL)), s / 4)
        assertEquals("Waiting", waiting.value)
        assertEquals("Accel $NO_VALUE", waiting.detail)
    }

    @Test
    fun stepsTileStates() {
        assertEquals("ON", RecordStatus.stepsTile(stats(silent(SensorKind.STEP))).value)
        val none = RecordStatus.stepsTile(stats(absent(SensorKind.STEP)))
        assertEquals("None", none.value)
        assertEquals(RecordStatus.COUNTED_AFTER_STOP, none.detail)
    }

    @Test
    fun headingTileStates() {
        val active = 10 * s
        assertEquals("OK", RecordStatus.headingTile(stats(), active).value)
        // The game rotation vector delivered and then stopped while the fused one keeps delivering: the log holds
        // game samples, so the pipeline keeps the game vector and freezes the heading. That is no fallback.
        val stalledGame = RecordStatus.headingTile(stats(stalled(SensorKind.GAME_ROT)), active)
        assertEquals("Stalled", stalledGame.value)
        assertEquals(RecordTone.Warning, stalledGame.tone)
        assertEquals("Heading: stalled, the game rotation vector stopped", stalledGame.spoken)
        assertEquals(
            "Stalled",
            RecordStatus.headingTile(stats(stalled(SensorKind.GAME_ROT), absent(SensorKind.ROT_VEC)), active).value,
        )
        assertEquals(
            "None",
            RecordStatus.headingTile(stats(absent(SensorKind.GAME_ROT), absent(SensorKind.ROT_VEC)), active).value,
        )
        assertEquals(
            "Waiting",
            RecordStatus.headingTile(stats(silent(SensorKind.GAME_ROT), silent(SensorKind.ROT_VEC)), s).value,
        )
        // The fused sensor delivering first right after the start is not yet a fallback; past the grace it is.
        val gameLate = stats(silent(SensorKind.GAME_ROT))
        assertEquals("Waiting", RecordStatus.headingTile(gameLate, s / 4).value)
        assertEquals("Fallback", RecordStatus.headingTile(gameLate, 2 * s).value)
        // A game rotation vector that never delivered is flagged stalled by the stall detector after a second, but
        // the log holds none of its samples, so the pipeline does use the fused one: that stall is a fallback.
        val neverDelivered = stats(silent(SensorKind.GAME_ROT).copy(stalled = true))
        assertEquals("Fallback", RecordStatus.headingTile(neverDelivered, active).value)
        // Without a game rotation vector the fused one is the fallback at once.
        assertEquals("Fallback", RecordStatus.headingTile(stats(absent(SensorKind.GAME_ROT)), s / 4).value)
    }

    @Test
    fun magTileShowsTheAccuracyWithBars() {
        val active = 10 * s
        fun mag(accuracy: Int?) = RecordStatus.magTile(stats(ok(SensorKind.MAG).copy(accuracy = accuracy)), active)
        assertEquals(SensorTileState("High", RecordTone.Good, null, "Magnetometer: high accuracy", 3), mag(3))
        assertEquals("Medium", mag(2).value)
        assertEquals(2, mag(2).bars)
        assertEquals("Low", mag(1).value)
        assertEquals(RecordTone.Warning, mag(1).tone)
        val unreliable = mag(0)
        assertEquals("Unreliable", unreliable.value)
        assertEquals(0, unreliable.bars)
        assertEquals(RecordStatus.FIGURE_8, unreliable.detail)
        assertEquals("Magnetometer: unreliable, wave the phone in a figure 8", unreliable.spoken)
        assertEquals("No contact", mag(-1).value)
    }

    @Test
    fun magTileWaitsForAnAccuracy() {
        val waiting = RecordStatus.magTile(stats(ok(SensorKind.MAG).copy(accuracy = null)), 10 * s)
        assertEquals("Waiting", waiting.value)
        assertNull(waiting.bars)
    }

    @Test
    fun stalledMagnetometerNeverShowsItsStaleAccuracy() {
        val tile = RecordStatus.magTile(stats(stalled(SensorKind.MAG).copy(accuracy = 3)), 10 * s)
        assertEquals("Stalled", tile.value)
        assertNull(tile.bars)
        // Silent past the grace with an accuracy left over from the compass preview: stalled too.
        val silentTile = RecordStatus.magTile(stats(silent(SensorKind.MAG).copy(accuracy = 3)), 10 * s)
        assertEquals("Stalled", silentTile.value)
    }

    @Test
    fun magTileWithoutMagnetometer() {
        val tile = RecordStatus.magTile(stats(absent(SensorKind.MAG)), 10 * s)
        assertEquals("None", tile.value)
        assertEquals(RecordTone.Neutral, tile.tone)
    }

    // --- Counters ---

    @Test
    fun distanceEstimateShowsTheStride() {
        val n = RecordStatus.numbers(recording(steps = 32, marks = 3), stats(), strideM = 0.70, excludesPauses = false)
        assertEquals("32", n.steps)
        assertEquals("3", n.marks)
        assertEquals("22.4 m", n.distance)
        assertEquals("estimate · 0.70 m/step", n.distanceDetail)
        assertEquals("Estimated distance: 22.4 m, at 0.70 m per step", n.distanceSpoken)
        assertEquals("estimate · 0.65 m/step", RecordStatus.numbers(recording(), stats(), 0.648, false).distanceDetail)
    }

    @Test
    fun distanceWithoutAStrideIsADash() {
        val n = RecordStatus.numbers(recording(steps = 32), stats(), strideM = null, excludesPauses = false)
        assertEquals(NO_VALUE, n.distance)
        assertEquals("estimate", n.distanceDetail)
        assertNull(RecordStatus.distanceEstimateM(10, 0.0))
        assertNull(RecordStatus.distanceEstimateM(10, Double.NaN))
    }

    @Test
    fun cadenceNeedsTimeAndHoldsWhilePaused() {
        assertEquals(NO_VALUE, RecordStatus.numbers(recording(elapsedNs = 0L), stats(), 0.7, false).cadence)
        assertNull(RecordStatus.cadencePerMinute(5, 0L))
        assertNull(RecordStatus.cadencePerMinute(5, -3 * s))
        val walking = RecordStatus.numbers(recording(elapsedNs = 60 * s, steps = 100), stats(), 0.7, false)
        assertEquals("100", walking.cadence)
        assertEquals("Cadence: 100 steps per minute", walking.cadenceSpoken)
        // A pause freezes both the steps and the active time, so the average stays where it was.
        val paused = RecordStatus.numbers(recording(elapsedNs = 60 * s, steps = 100, paused = true), stats(), 0.7, true)
        assertEquals("100", paused.cadence)
        assertEquals(90.0, RecordStatus.cadencePerMinute(3, 2 * s)!!, 1e-9)
    }

    @Test
    fun noStepDetectorGivesDashesNotZeros() {
        val n = RecordStatus.numbers(recording(steps = 0), stats(absent(SensorKind.STEP)), 0.7, false)
        assertEquals(NO_VALUE, n.steps)
        assertEquals(NO_VALUE, n.distance)
        assertEquals(NO_VALUE, n.cadence)
        assertEquals(RecordStatus.COUNTED_AFTER_STOP, n.stepsDetail)
        assertEquals(RecordStatus.COUNTED_AFTER_STOP, n.distanceDetail)
        assertEquals(RecordStatus.COUNTED_AFTER_STOP, n.cadenceDetail)
        // The clock and the marks do not depend on the detector.
        assertEquals("0:01:00", n.clock)
        assertEquals("0", n.marks)
    }

    @Test
    fun clockSaysItLeavesPausesOutOnceThereWasOne() {
        assertNull(RecordStatus.numbers(recording(), stats(), 0.7, excludesPauses = false).clockDetail)
        assertEquals("excl. pauses", RecordStatus.numbers(recording(), stats(), 0.7, excludesPauses = true).clockDetail)
    }

    @Test
    fun pausedTimeIsEvidentFromTheClocks() {
        // 60 s since the start, 60 s active, read a moment later: no pause.
        assertFalse(RecordStatus.pausedTimeEvident(startedNs = 0L, elapsedNs = 60 * s, nowNs = 60 * s + s / 10))
        // 90 s since the start but only 60 s active: 30 s were paused.
        assertTrue(RecordStatus.pausedTimeEvident(startedNs = 0L, elapsedNs = 60 * s, nowNs = 90 * s))
    }

    @Test
    fun chips() {
        assertEquals("Accel 100 Hz", RecordStatus.chipText(ok(SensorKind.ACCEL)))
        assertEquals("Baro stalled", RecordStatus.chipText(stalled(SensorKind.BARO)))
        assertEquals("Mag raw none", RecordStatus.chipText(absent(SensorKind.MAG_UNCAL)))
        assertEquals("Steps 12", RecordStatus.chipText(ok(SensorKind.STEP).copy(sampleCount = 12)))
        assertEquals(RecordTone.Bad, RecordStatus.chipTone(stalled(SensorKind.BARO)))
        assertEquals(RecordTone.Good, RecordStatus.chipTone(ok(SensorKind.BARO)))
        assertEquals(RecordTone.Neutral, RecordStatus.chipTone(silent(SensorKind.STEP)))
    }
}
