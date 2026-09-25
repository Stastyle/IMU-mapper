package com.stastyle.imumapper.pipeline.core

import kotlinx.serialization.Serializable

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
