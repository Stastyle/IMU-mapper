package com.stastyle.imumapper.capture

import com.stastyle.imumapper.pipeline.core.TripMode
import java.util.Locale

// Pure helpers for the recorder. No Android classes here so app/src/test can exercise them on the JVM.

/**
 * The sensors the recorder listens to. [periodKey] is the key used in `LogMeta.sensorPeriodsUs`;
 * [stallMonitored] is false for event sensors that legitimately go quiet (the step detector).
 */
enum class SensorKind(val label: String, val periodKey: String, val stallMonitored: Boolean) {
    ACCEL("Accel", "TYPE_ACCELEROMETER", true),
    GYRO("Gyro", "TYPE_GYROSCOPE", true),
    MAG("Mag", "TYPE_MAGNETIC_FIELD", true),
    ACCEL_UNCAL("Accel raw", "TYPE_ACCELEROMETER_UNCALIBRATED", true),
    GYRO_UNCAL("Gyro raw", "TYPE_GYROSCOPE_UNCALIBRATED", true),
    MAG_UNCAL("Mag raw", "TYPE_MAGNETIC_FIELD_UNCALIBRATED", true),
    BARO("Baro", "TYPE_PRESSURE", true),
    GAME_ROT("Game rot", "TYPE_GAME_ROTATION_VECTOR", true),
    ROT_VEC("Rot vec", "TYPE_ROTATION_VECTOR", true),
    STEP("Steps", "TYPE_STEP_DETECTOR", false),
}

/** Health of one sensor as seen by the logger. [lastValues] holds the raw event values (up to six). */
data class SensorHealth(
    val kind: SensorKind,
    /** False when the device has no such sensor. */
    val available: Boolean,
    val sampleCount: Long = 0L,
    /** Delivery rate over the last second, 0 when nothing arrived recently. */
    val rateHz: Double = 0.0,
    val lastValues: List<Float> = emptyList(),
    val lastTimestampNs: Long = 0L,
    /** True while the sensor has delivered nothing for over [StallDetector] threshold. */
    val stalled: Boolean = false,
)

/** Snapshot of every sensor plus the counters the recording screen shows. Published a few times a second. */
data class SensorStats(
    val sensors: Map<SensorKind, SensorHealth> = emptyMap(),
    /** Records the logger handed to the writer so far, 0 when running without a writer. */
    val recordsWritten: Long = 0L,
    /** Hardware step-detector events so far. */
    val stepCount: Int = 0,
    val running: Boolean = false,
    /** Message of the last write failure, null when writes succeed. */
    val writeError: String? = null,
) {
    fun of(kind: SensorKind): SensorHealth = sensors[kind] ?: SensorHealth(kind, available = false)

    /** True when the accelerometer and gyroscope, the two sensors PDR cannot do without, deliver. */
    val coreSensorsHealthy: Boolean
        get() = listOf(SensorKind.ACCEL, SensorKind.GYRO).all { k ->
            val h = of(k)
            h.available && !h.stalled && h.sampleCount > 0
        }
}

/**
 * Sliding-window rate estimate from sample timestamps. The rate is recomputed once per window, which
 * keeps it steady on screen at 500 Hz instead of flickering with every jittery interval.
 */
class RateEstimator(private val windowNs: Long = 1_000_000_000L) {
    private var windowStartNs = Long.MIN_VALUE
    private var windowCount = 0
    private var lastNs = Long.MIN_VALUE

    /** Rate measured over the last complete window, 0 before the first window closes. */
    var rateHz: Double = 0.0
        private set

    fun onSample(tNs: Long) {
        if (windowStartNs == Long.MIN_VALUE || tNs < windowStartNs) {
            windowStartNs = tNs
            windowCount = 0
        }
        windowCount++
        lastNs = tNs
        val span = tNs - windowStartNs
        if (span >= windowNs) {
            // windowCount samples span (windowCount - 1) intervals.
            rateHz = (windowCount - 1) * 1e9 / span
            windowStartNs = tNs
            windowCount = 1
        }
    }

    /** The rate as of [nowNs]: drops to 0 once the sensor has been silent for two windows. */
    fun rateAt(nowNs: Long): Double =
        if (lastNs == Long.MIN_VALUE || nowNs - lastNs > 2 * windowNs) 0.0 else rateHz

    fun reset() {
        windowStartNs = Long.MIN_VALUE
        windowCount = 0
        lastNs = Long.MIN_VALUE
        rateHz = 0.0
    }
}

/**
 * Flags sensors that stop delivering. A sensor is reported once when it crosses [thresholdNs] of silence
 * and again only after it has delivered something in between, so a dead sensor does not flood the log.
 */
class StallDetector(private val thresholdNs: Long = 1_000_000_000L) {
    private val lastSeenNs = HashMap<SensorKind, Long>()
    private val stalled = HashSet<SensorKind>()

    /** Call for every sample, and once at registration time so a sensor that never delivers is caught too. */
    fun onSample(kind: SensorKind, tNs: Long) {
        lastSeenNs[kind] = tNs
        stalled.remove(kind)
    }

    /** Sensors that newly stalled as of [nowNs], in enum order. */
    fun check(nowNs: Long): List<SensorKind> {
        val newly = ArrayList<SensorKind>()
        for ((kind, t) in lastSeenNs) {
            if (kind.stallMonitored && kind !in stalled && nowNs - t > thresholdNs) {
                stalled.add(kind)
                newly.add(kind)
            }
        }
        newly.sortBy { it.ordinal }
        return newly
    }

    fun isStalled(kind: SensorKind): Boolean = kind in stalled

    fun reset() {
        lastSeenNs.clear()
        stalled.clear()
    }
}

/** Passes at most one sample per period, used to thin 500 Hz streams to what a live plot can draw. */
class Decimator(targetHz: Double) {
    private val periodNs: Long = (1e9 / targetHz).toLong()
    private var lastPassedNs = Long.MIN_VALUE

    fun accept(tNs: Long): Boolean {
        if (lastPassedNs != Long.MIN_VALUE && tNs >= lastPassedNs && tNs - lastPassedNs < periodNs) return false
        lastPassedNs = tNs
        return true
    }

    fun reset() {
        lastPassedNs = Long.MIN_VALUE
    }
}

/** "m:ss" under an hour, "h:mm:ss" above. Negative input clamps to zero. */
fun formatElapsed(elapsedNs: Long): String {
    val totalS = (elapsedNs / 1_000_000_000L).coerceAtLeast(0L)
    val h = totalS / 3600
    val m = (totalS % 3600) / 60
    val s = totalS % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

/** Short user-facing name of a mode, also used in the default trip name. */
fun modeLabel(mode: TripMode): String = when (mode) {
    TripMode.POCKET -> "Pocket"
    TripMode.FLASHLIGHT -> "Flashlight"
    TripMode.ILLUMINATED -> "Illuminated"
}

/** Rate for display: whole Hz above 10, one decimal below (barometers run at a few Hz). */
fun formatRate(rateHz: Double): String =
    if (rateHz >= 10.0) String.format(Locale.US, "%.0f Hz", rateHz) else String.format(Locale.US, "%.1f Hz", rateHz)
