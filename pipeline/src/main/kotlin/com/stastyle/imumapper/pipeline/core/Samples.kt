package com.stastyle.imumapper.pipeline.core

import kotlinx.serialization.Serializable

/**
 * Raw records exactly as the recorder writes them. Timestamps are nanoseconds from
 * `SystemClock.elapsedRealtimeNanos()` (the clock Android sensor events use), never wall time.
 *
 * Every record type has a binary encoding in [com.stastyle.imumapper.pipeline.log.LogCodec].
 */
sealed interface LogRecord {
    val tNs: Long
}

/** Calibrated accelerometer, m/s^2, sensor frame, includes gravity. */
data class AccelSample(override val tNs: Long, val x: Float, val y: Float, val z: Float) : LogRecord

/** Calibrated gyroscope, rad/s, sensor frame. */
data class GyroSample(override val tNs: Long, val x: Float, val y: Float, val z: Float) : LogRecord

/** Calibrated magnetometer, microtesla, sensor frame. */
data class MagSample(override val tNs: Long, val x: Float, val y: Float, val z: Float) : LogRecord

/** Uncalibrated accelerometer with the bias Android estimated at that moment. */
data class AccelUncalSample(
    override val tNs: Long,
    val x: Float, val y: Float, val z: Float,
    val bx: Float, val by: Float, val bz: Float,
) : LogRecord

/** Uncalibrated gyroscope with drift estimate. */
data class GyroUncalSample(
    override val tNs: Long,
    val x: Float, val y: Float, val z: Float,
    val bx: Float, val by: Float, val bz: Float,
) : LogRecord

/** Uncalibrated magnetometer with hard-iron estimate. */
data class MagUncalSample(
    override val tNs: Long,
    val x: Float, val y: Float, val z: Float,
    val bx: Float, val by: Float, val bz: Float,
) : LogRecord

/** Barometric pressure in hPa. */
data class BaroSample(override val tNs: Long, val hPa: Float) : LogRecord

enum class RotationSource {
    /** TYPE_GAME_ROTATION_VECTOR: gyro + accel only, no magnetometer. Drifts slowly in yaw. */
    GAME,
    /** TYPE_ROTATION_VECTOR: gyro + accel + magnetometer. Yaw is absolute but can be disturbed. */
    FUSED,
}

/**
 * Orientation from one of Android's rotation-vector sensors in the (x, y, z, w) layout the sensor
 * reports. [headingAccuracyRad] is only meaningful for [RotationSource.FUSED]; -1 when unknown.
 */
data class RotationSample(
    override val tNs: Long,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float,
    val headingAccuracyRad: Float,
    val source: RotationSource,
) : LogRecord {
    fun toQuat(): Quat = Quat.fromAndroidRotationVector(qx.toDouble(), qy.toDouble(), qz.toDouble(), qw.toDouble())
}

/** One hardware step-detector event. */
data class StepSample(override val tNs: Long) : LogRecord

enum class TrackingState { STOPPED, PAUSED, TRACKING }

/**
 * ARCore camera pose. Position and orientation are in ARCore's own world frame (right-handed,
 * +Y up, gravity aligned, origin and yaw fixed when the session started). The VIO fuser converts
 * this to the pipeline's ENU frame.
 *
 * [tNs] is the elapsed-realtime timestamp taken when the frame was received, so it is comparable
 * with IMU samples; [frameTimestampNs] is ARCore's own frame timestamp for reference.
 */
data class PoseSample(
    override val tNs: Long,
    val frameTimestampNs: Long,
    val tx: Float, val ty: Float, val tz: Float,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float,
    val tracking: TrackingState,
    /** ARCore TrackingFailureReason ordinal, 0 when tracking. */
    val failureReason: Int,
) : LogRecord {
    fun position() = Vec3.of(tx, ty, tz)
    fun orientation(): Quat = Quat.fromXyzw(qx.toDouble(), qy.toDouble(), qz.toDouble(), qw.toDouble())
}

/**
 * Sparse ARCore feature points at one frame, in ARCore world coordinates.
 * [xyzc] holds 4 floats per point: x, y, z, confidence.
 */
data class PointCloudSample(override val tNs: Long, val xyzc: FloatArray) : LogRecord {
    val count: Int get() = xyzc.size / 4

    override fun equals(other: Any?): Boolean =
        other is PointCloudSample && other.tNs == tNs && other.xyzc.contentEquals(xyzc)

    override fun hashCode(): Int = 31 * tNs.hashCode() + xyzc.contentHashCode()
}

/**
 * A photo taken during recording. [fileName] is relative to the trip's photo directory.
 * Pose fields are the ARCore camera pose at capture (ARCore frame), all zero when unknown.
 */
data class KeyframeSample(
    override val tNs: Long,
    val fileName: String,
    val tx: Float, val ty: Float, val tz: Float,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float,
) : LogRecord

enum class AnnotationKind {
    WAYPOINT, JUNCTION, CHAMBER, NOTE,
    /** User declared they are back at the start; enables loop closure. */
    LOOP_CLOSED,
    /** User changed how the phone is carried; heading offset is re-estimated after this. */
    REORIENT,
}

/** A user annotation placed on the timeline. */
data class AnnotationRecord(override val tNs: Long, val kind: AnnotationKind, val note: String) : LogRecord

enum class EventKind {
    START, STOP, PAUSE, RESUME,
    SCREEN_OFF, SCREEN_ON,
    TRACKING_LOST, TRACKING_REGAINED,
    TORCH_ON, TORCH_OFF,
    /** Emitted when a sensor delivers nothing for over a second. */
    SENSOR_STALL,
}

/** A recorder lifecycle event. */
data class EventRecord(override val tNs: Long, val kind: EventKind) : LogRecord

/** The first record of every log: JSON-encoded [LogMeta]. */
data class MetaRecord(val json: String) : LogRecord {
    override val tNs: Long get() = 0L
}

enum class TripMode {
    /** IMU only, phone anywhere, no light needed. */
    POCKET,
    /** ARCore with the torch on, phone held in hand. */
    FLASHLIGHT,
    /** ARCore in a lit space with automatic photo keyframes. */
    ILLUMINATED,
}

enum class CarryPosition { HAND, POCKET, CHEST, HELMET }

/** Metadata written at the start of a log. */
@Serializable
data class LogMeta(
    val formatVersion: Int = 1,
    val appVersion: String,
    val deviceModel: String,
    val androidSdk: Int,
    val mode: TripMode,
    val carryPosition: CarryPosition,
    val startedAtEpochMs: Long,
    /** Requested sampling period per sensor in microseconds, keyed by Android sensor type name. */
    val sensorPeriodsUs: Map<String, Int> = emptyMap(),
    /** Calibration in effect when the recording started. */
    val config: PipelineConfig = PipelineConfig(),
    val notes: String = "",
)
