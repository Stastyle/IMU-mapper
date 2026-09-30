package com.stastyle.imumapper.ui.record

import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.capture.SensorHealth
import com.stastyle.imumapper.capture.SensorKind
import com.stastyle.imumapper.capture.SensorStats
import com.stastyle.imumapper.capture.formatRate
import com.stastyle.imumapper.capture.modeLabel
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.ui.common.NO_VALUE
import com.stastyle.imumapper.ui.common.formatClock
import com.stastyle.imumapper.ui.common.formatDistance
import com.stastyle.imumapper.ui.triplist.TripFormat
import java.util.Locale
import kotlin.math.roundToInt

/*
 * What the recording screen says about the sensors and the live counters, as plain data, so the rules are
 * unit-tested on the JVM and the composables only pick colours and icons. No Android or Compose types here.
 */

/** How good a status or tile state is; the screen maps it to a colour. */
enum class RecordTone { Good, Neutral, Warning, Bad }

/** The status card's headline, its colour, and a line under it (why a pause leaves time out), if any. */
data class RecordStatusLine(val text: String, val tone: RecordTone, val detail: String? = null)

/**
 * One sensor tile: its short [value] ("OK", "Stalled"), an optional [detail] line, and [spoken], which
 * TalkBack reads instead of the tile's texts. [bars] is the magnetometer's accuracy as 0 to 3 signal bars,
 * null for the other tiles and whenever no accuracy may be shown.
 */
data class SensorTileState(
    val value: String,
    val tone: RecordTone,
    val detail: String?,
    val spoken: String,
    val bars: Int? = null,
)

/** The stats card's texts. Every value is either measured or [NO_VALUE]; never a made-up zero. */
data class RecordNumbers(
    val clock: String,
    val clockDetail: String?,
    val steps: String,
    val stepsDetail: String?,
    val marks: String,
    val distance: String,
    val distanceDetail: String,
    val distanceSpoken: String,
    val cadence: String,
    val cadenceDetail: String,
    val cadenceSpoken: String,
)

object RecordStatus {

    /**
     * A sensor that has delivered nothing after this much active recording time counts as not delivering.
     * The logger's stall detector only watches sensors whose registration worked, and the counters start
     * from zero when a recording begins, so a grace period keeps the first snapshot from raising an alarm.
     */
    const val SILENCE_GRACE_NS = 2_000_000_000L

    /**
     * Wall time since the start exceeding the active time by more than this means the trip has been paused.
     * Publishing lag is a few milliseconds; a false positive only adds "excl. pauses", which is always true.
     */
    const val PAUSE_EVIDENCE_NS = 2_000_000_000L

    const val PAUSED_NOTE = "Sensors keep logging; this stretch is left out of the path and the timer."

    /** Without a hardware step detector the pipeline finds the steps in the accelerometer after Stop. */
    const val COUNTED_AFTER_STOP = "counted after Stop"

    const val FIGURE_8 = "wave in a figure 8"

    /** Cadence needs some time to divide by; before this it is a dash. */
    private const val MIN_CADENCE_NS = 1_000_000_000L

    private val CORE = listOf(SensorKind.ACCEL, SensorKind.GYRO)

    /**
     * The status card's headline, first match wins: a log write failure, then the accelerometer or gyroscope
     * (without which PDR has nothing), then any other watched sensor that stopped, then a pause, then OK.
     * While paused the explanation of what a pause does is shown under whichever line wins.
     */
    fun status(stats: SensorStats, recording: RecordingState.Recording): RecordStatusLine {
        val activeNs = recording.elapsedNs
        val note = if (recording.paused) PAUSED_NOTE else null
        stats.writeError?.let { return RecordStatusLine("Log write failed: $it", RecordTone.Bad, note) }
        val core = CORE.filter { kind ->
            val h = stats.of(kind)
            !h.available || notDelivering(h, activeNs)
        }
        if (core.isNotEmpty()) return RecordStatusLine(notDeliveringText(core), RecordTone.Bad, note)
        val others = SensorKind.entries.filter { kind ->
            kind.stallMonitored && kind !in CORE && notDelivering(stats.of(kind), activeNs)
        }
        if (others.isNotEmpty()) return RecordStatusLine(notDeliveringText(others), RecordTone.Warning, note)
        if (recording.paused) return RecordStatusLine("Paused", RecordTone.Warning, PAUSED_NOTE)
        return RecordStatusLine("Sensors OK", RecordTone.Good)
    }

    /**
     * An existing sensor that is stalled, or has delivered nothing although [activeNs] of recording passed
     * the [SILENCE_GRACE_NS]. A sensor the phone lacks is not "not delivering": it was never expected to.
     */
    fun notDelivering(health: SensorHealth, activeNs: Long): Boolean =
        health.available && (health.stalled || (health.sampleCount == 0L && activeNs >= SILENCE_GRACE_NS))

    /** "Barometer not delivering", "Magnetometer and barometer not delivering", "3 sensors not delivering". */
    fun notDeliveringText(kinds: List<SensorKind>): String = when (kinds.size) {
        1 -> capitalized(sensorName(kinds[0])) + " not delivering"
        2 -> capitalized(sensorName(kinds[0])) + " and " + sensorName(kinds[1]) + " not delivering"
        else -> "${kinds.size} sensors not delivering"
    }

    /** The sensor's full name in running text; the chips keep their short [SensorKind.label]. */
    fun sensorName(kind: SensorKind): String = when (kind) {
        SensorKind.ACCEL -> "accelerometer"
        SensorKind.GYRO -> "gyroscope"
        SensorKind.MAG -> "magnetometer"
        SensorKind.ACCEL_UNCAL -> "raw accelerometer"
        SensorKind.GYRO_UNCAL -> "raw gyroscope"
        SensorKind.MAG_UNCAL -> "raw magnetometer"
        SensorKind.BARO -> "barometer"
        SensorKind.GAME_ROT -> "game rotation vector"
        SensorKind.ROT_VEC -> "rotation vector"
        SensorKind.STEP -> "step detector"
    }

    /** How the trip will be processed: PDR for Pocket; camera tracking with steps as the fallback otherwise. */
    fun processingLabel(mode: TripMode): String = if (mode == TripMode.POCKET) "PDR" else "Camera + steps"

    /** The top bar's subtitle, "Pocket · Hand". */
    fun headerSubtitle(mode: TripMode, carry: CarryPosition): String =
        modeLabel(mode) + " · " + TripFormat.carryLabel(carry)

    /** The status card's subtitle, "Pocket · Hand · PDR". */
    fun statusSubtitle(mode: TripMode, carry: CarryPosition): String =
        headerSubtitle(mode, carry) + " · " + processingLabel(mode)

    /** Accelerometer and gyroscope together, with the accelerometer's rate. */
    fun imuTile(stats: SensorStats, activeNs: Long): SensorTileState {
        val accel = stats.of(SensorKind.ACCEL)
        val gyro = stats.of(SensorKind.GYRO)
        if (!accel.available || !gyro.available) {
            val missing = CORE.filter { !stats.of(it).available }.joinToString(" or ") { sensorName(it) }
            return SensorTileState("None", RecordTone.Bad, "no $missing", "IMU: none, this phone has no $missing")
        }
        val rate = if (accel.rateHz > 0.0) formatRate(accel.rateHz) else null
        val detail = "Accel " + (rate ?: NO_VALUE)
        val spokenRate = if (rate != null) ", accelerometer at $rate" else ""
        return when {
            notDelivering(accel, activeNs) || notDelivering(gyro, activeNs) ->
                SensorTileState("Stalled", RecordTone.Bad, detail, "IMU: stalled$spokenRate")
            accel.sampleCount == 0L || gyro.sampleCount == 0L ->
                SensorTileState("Waiting", RecordTone.Neutral, detail, "IMU: waiting for the first samples")
            else -> SensorTileState("OK", RecordTone.Good, detail, "IMU: OK$spokenRate")
        }
    }

    /** The hardware step detector. It goes quiet whenever the user stands still, so it is never "stalled". */
    fun stepsTile(stats: SensorStats): SensorTileState = if (stats.of(SensorKind.STEP).available) {
        SensorTileState("ON", RecordTone.Good, null, "Step detector: on")
    } else {
        SensorTileState(
            "None",
            RecordTone.Neutral,
            COUNTED_AFTER_STOP,
            "Step detector: none, steps are counted after Stop",
        )
    }

    /**
     * The orientation source: the game rotation vector, which the path's heading comes from, or the fused
     * rotation vector as a fallback when only that one delivers.
     */
    fun headingTile(stats: SensorStats, activeNs: Long): SensorTileState {
        val game = stats.of(SensorKind.GAME_ROT)
        val fused = stats.of(SensorKind.ROT_VEC)
        return when {
            delivering(game) -> SensorTileState("OK", RecordTone.Good, null, "Heading: OK")
            // The counters restart with the recording, so the fused sensor's first sample may land a tick before
            // the game rotation vector's; that is no reason to warn about a fallback before the grace is over.
            game.available && !notDelivering(game, activeNs) -> waitingHeading()
            delivering(fused) -> SensorTileState(
                "Fallback",
                RecordTone.Warning,
                "rotation vector",
                "Heading: fallback, from the rotation vector only",
            )
            !game.available && !fused.available ->
                SensorTileState("None", RecordTone.Warning, null, "Heading: none, this phone has no rotation sensor")
            notDelivering(game, activeNs) || notDelivering(fused, activeNs) ->
                SensorTileState("Stalled", RecordTone.Warning, null, "Heading: stalled")
            else -> waitingHeading()
        }
    }

    private fun waitingHeading() =
        SensorTileState("Waiting", RecordTone.Neutral, null, "Heading: waiting for the first samples")

    /**
     * The magnetometer's calibration, from the accuracy it last reported. A stalled sensor shows Stalled, never
     * the accuracy it reported before it stopped, which would claim a calibration nobody can check.
     */
    fun magTile(stats: SensorStats, activeNs: Long): SensorTileState {
        val mag = stats.of(SensorKind.MAG)
        if (!mag.available) {
            return SensorTileState("None", RecordTone.Neutral, null, "Magnetometer: none on this phone")
        }
        if (notDelivering(mag, activeNs)) {
            return SensorTileState("Stalled", RecordTone.Warning, null, "Magnetometer: stalled")
        }
        // The values of SensorManager.SENSOR_STATUS_*.
        return when (mag.accuracy) {
            3 -> SensorTileState("High", RecordTone.Good, null, "Magnetometer: high accuracy", bars = 3)
            2 -> SensorTileState("Medium", RecordTone.Good, null, "Magnetometer: medium accuracy", bars = 2)
            1 -> SensorTileState(
                "Low",
                RecordTone.Warning,
                FIGURE_8,
                "Magnetometer: low, wave the phone in a figure 8",
                bars = 1,
            )
            0 -> SensorTileState(
                "Unreliable",
                RecordTone.Warning,
                FIGURE_8,
                "Magnetometer: unreliable, wave the phone in a figure 8",
                bars = 0,
            )
            -1 -> SensorTileState("No contact", RecordTone.Warning, null, "Magnetometer: no contact", bars = 0)
            else -> SensorTileState("Waiting", RecordTone.Neutral, null, "Magnetometer: waiting for its accuracy")
        }
    }

    /**
     * The stats card. Steps, the distance estimate and the cadence come from the hardware step detector; a
     * phone without one gets dashes, because its steps are only found in the accelerometer after Stop, and a
     * zero would read as "you have not moved". [excludesPauses] adds the note that the clock leaves pauses out.
     */
    fun numbers(
        recording: RecordingState.Recording,
        stats: SensorStats,
        strideM: Double?,
        excludesPauses: Boolean,
    ): RecordNumbers {
        val clock = formatClock(recording.elapsedNs)
        val clockDetail = if (excludesPauses) "excl. pauses" else null
        val marks = recording.annotationCount.toString()
        if (!stats.of(SensorKind.STEP).available) {
            return RecordNumbers(
                clock = clock,
                clockDetail = clockDetail,
                steps = NO_VALUE,
                stepsDetail = COUNTED_AFTER_STOP,
                marks = marks,
                distance = NO_VALUE,
                distanceDetail = COUNTED_AFTER_STOP,
                distanceSpoken = "Estimated distance: not available, $COUNTED_AFTER_STOP",
                cadence = NO_VALUE,
                cadenceDetail = COUNTED_AFTER_STOP,
                cadenceSpoken = "Cadence: not available, $COUNTED_AFTER_STOP",
            )
        }
        val steps = recording.stepCount
        val distance = formatDistance(distanceEstimateM(steps, strideM))
        val stride = validStride(strideM)?.let { String.format(Locale.US, "%.2f m/step", it) }
        val cadence = cadencePerMinute(steps, recording.elapsedNs)?.roundToInt()?.toString() ?: NO_VALUE
        return RecordNumbers(
            clock = clock,
            clockDetail = clockDetail,
            steps = steps.toString(),
            stepsDetail = null,
            marks = marks,
            distance = distance,
            distanceDetail = if (stride != null) "estimate · $stride" else "estimate",
            distanceSpoken = "Estimated distance: " + spoken(distance) +
                (if (stride != null) ", at ${stride.replace("/", " per ")}" else ""),
            cadence = cadence,
            cadenceDetail = "steps/min",
            cadenceSpoken = if (cadence == NO_VALUE) "Cadence: not available" else "Cadence: $cadence steps per minute",
        )
    }

    /** Hardware steps times the saved stride: a live estimate, not the processed path's length. */
    fun distanceEstimateM(steps: Int, strideM: Double?): Double? {
        val stride = validStride(strideM) ?: return null
        return steps.coerceAtLeast(0) * stride
    }

    /** Steps per minute of active time; null until there is a second of it to divide by. */
    fun cadencePerMinute(steps: Int, activeNs: Long): Double? {
        if (activeNs < MIN_CADENCE_NS) return null
        return steps.coerceAtLeast(0) * 60e9 / activeNs
    }

    /**
     * True when the recording that started at [startedNs] has more wall time behind it at [nowNs] than
     * [elapsedNs] of active time, so it was paused at some point. The screen may be opened on a recording
     * that was paused and resumed before, and this is the only trace of that pause it gets.
     */
    fun pausedTimeEvident(startedNs: Long, elapsedNs: Long, nowNs: Long): Boolean =
        (nowNs - startedNs) - elapsedNs > PAUSE_EVIDENCE_NS

    /** The expanded sensor list's chip text, "Accel 100 Hz", "Baro stalled", "Steps 12", "Mag raw none". */
    fun chipText(health: SensorHealth): String {
        val detail = when {
            !health.available -> "none"
            health.stalled -> "stalled"
            health.kind == SensorKind.STEP -> health.sampleCount.toString()
            else -> formatRate(health.rateHz)
        }
        return "${health.kind.label} $detail"
    }

    /** The chip's colour: stalled is bad, delivering is good, and absent or not yet delivering is neutral. */
    fun chipTone(health: SensorHealth): RecordTone = when {
        !health.available -> RecordTone.Neutral
        health.stalled -> RecordTone.Bad
        health.sampleCount == 0L -> RecordTone.Neutral
        else -> RecordTone.Good
    }

    private fun delivering(health: SensorHealth): Boolean =
        health.available && !health.stalled && health.sampleCount > 0L

    private fun validStride(strideM: Double?): Double? = strideM?.takeIf { it.isFinite() && it > 0.0 }

    private fun spoken(value: String): String = if (value == NO_VALUE) "not available" else value

    private fun capitalized(text: String): String = text.replaceFirstChar { it.uppercase(Locale.US) }
}
