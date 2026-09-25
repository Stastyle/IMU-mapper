package com.stastyle.imumapper.pipeline.core

import kotlinx.serialization.Serializable

/**
 * Which device axis the heading offset refers to. AUTO picks the forward axis (+Y) or the camera
 * axis (-Z) per trip from the mean tilt, which is fine until a calibrated offset is applied to a
 * trip that picked the other axis: the two headings differ by tens of degrees once the phone has
 * some roll. The calibration screen therefore stores the axis its run chose (diagnostic
 * `headingAxis`) together with the offset, and the first heading segment of every trip uses it;
 * segments after a REORIENT re-estimate the offset and pick their axis automatically.
 */
enum class HeadingAxisMode { AUTO, FORWARD, CAMERA }

/**
 * Every tunable of the processing pipeline. Stored with each result so a path can always be
 * reproduced, and edited from the calibration and debug screens.
 */
@Serializable
data class PipelineConfig(
    // --- stride ---
    /** Fixed stride length in metres, used when [weinbergK] is 0. */
    val strideLengthM: Double = 0.70,
    /** Weinberg stride model gain: stride = k * (aMax - aMin)^(1/4). 0 disables the model. */
    val weinbergK: Double = 0.0,

    // --- heading ---
    /** Offset added to the device forward heading to get the walking direction, radians. */
    val headingOffsetRad: Double = 0.0,
    /** Device axis [headingOffsetRad] was calibrated on; see [HeadingAxisMode]. */
    val headingAxis: HeadingAxisMode = HeadingAxisMode.AUTO,
    /** Use the magnetometer-corrected rotation vector to bound gyro yaw drift. */
    val useMagnetometer: Boolean = true,
    /**
     * Relative tolerance on magnetic field magnitude (and dip) versus the value seen at the start
     * before magnetometer readings are ignored as disturbed.
     */
    val magGateTolerance: Double = 0.15,
    /** Gyro bias to subtract, rad/s, sensor frame (from the still-bias calibration). */
    val gyroBias: Vec3 = Vec3.ZERO,

    // --- steps ---
    /** Minimum time between two steps in seconds. */
    val stepMinIntervalS: Double = 0.30,
    /** Minimum peak-to-valley swing of vertical acceleration for a step, m/s^2. */
    val stepMinSwing: Double = 1.0,
    /** Band-pass limits for step detection, Hz. */
    val stepBandLowHz: Double = 0.5,
    val stepBandHighHz: Double = 3.0,
    /** Prefer the hardware step detector over the software one when both exist. */
    val preferHardwareSteps: Boolean = false,

    // --- altitude ---
    /** Low-pass time constant for barometric altitude, seconds. */
    val baroSmoothingS: Double = 1.0,
    /** Hold altitude constant while no steps occur (removes pressure noise when standing). */
    val baroHoldWhenStill: Boolean = true,
    /**
     * A gap between two steps longer than this, seconds, counts as standing still for
     * [baroHoldWhenStill]. Must exceed the slowest supported step period ([stepBandLowHz] = 0.5 Hz
     * is a 2 s period), or slow walkers lose their climb. The barometer filter's settling tail
     * after the last step and the run-up to the next step are still counted; only the middle of
     * the gap is frozen.
     */
    val baroStillGapS: Double = 4.0,

    // --- post ---
    /** Apply loop closure when a LOOP_CLOSED annotation exists. */
    val loopClosure: Boolean = true,
    /** Moving-average window over path points; 1 disables smoothing. */
    val smoothingWindow: Int = 3,

    // --- VIO ---
    /** Fill ARCore tracking gaps with PDR instead of leaving holes. */
    val pdrFallbackWhenTrackingLost: Boolean = true,
    /** Output sample period for VIO paths, seconds. */
    val vioResamplePeriodS: Double = 0.1,
)
