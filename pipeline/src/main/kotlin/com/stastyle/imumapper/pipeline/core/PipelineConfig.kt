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
    /**
     * Use the magnetometer-corrected rotation vector to bound gyro yaw drift during the trip. Where
     * north is comes from [northFromCompass], not from this.
     */
    val useMagnetometer: Boolean = true,
    /**
     * Turn the path so +Y is magnetic north, taken from the fused rotation vector at the start of the
     * trip. Independent of [useMagnetometer]: with that off, the start still sets north and the
     * magnetometer is ignored afterwards. Off, the trip keeps the game rotation vector's yaw, which is
     * arbitrary and differs from one recording to the next.
     */
    val northFromCompass: Boolean = true,
    /**
     * Relative tolerance on magnetic field magnitude (and dip) versus the value seen at the start
     * before magnetometer readings are ignored as disturbed.
     */
    val magGateTolerance: Double = 0.15,
    /** Gyro bias to subtract, rad/s, sensor frame (from the still-bias calibration). */
    val gyroBias: Vec3 = Vec3.ZERO,

    // --- carry changes ---
    /**
     * Detect moves of the phone to another carry position (hand to pocket, pocket to vest) from the
     * tilt, hold the walking heading while the phone is moving, and re-estimate the heading offset
     * from the steps after the move, exactly as after a REORIENT annotation.
     */
    val autoReorient: Boolean = true,
    /** Change of the gravity direction in the device frame that counts as a carry change, radians. */
    val carryChangeTiltRad: Double = 0.5236,
    /** Seconds the tilt must stay steady after a move before the new carry position counts as settled. */
    val carryChangeSettleS: Double = 1.5,

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
    /**
     * Low-pass time constant for barometric altitude, seconds. Used only while [baroConfirmSteps]
     * is 0; the climb filter takes its height from per-step means of the unsmoothed pressure.
     */
    val baroSmoothingS: Double = 1.0,
    /**
     * Hold altitude constant while no steps occur (removes pressure noise when standing). Used only
     * while [baroConfirmSteps] is 0; the climb filter judges a stand like any other step interval.
     */
    val baroHoldWhenStill: Boolean = true,
    /**
     * A gap between two steps longer than this, seconds, counts as standing still for
     * [baroHoldWhenStill]. Must exceed the slowest supported step period ([stepBandLowHz] = 0.5 Hz
     * is a 2 s period), or slow walkers lose their climb. The barometer filter's settling tail
     * after the last step and the run-up to the next step are still counted; only the middle of
     * the gap is frozen. Used only while [baroConfirmSteps] is 0.
     */
    val baroStillGapS: Double = 4.0,
    /**
     * The climb filter: a barometric height change reaches the path only when the height keeps
     * moving the same way step after step, as on stairs or a slope, with at least this many of those
     * steps moving it by [baroConfirmStepM] or more and not shaped like a jump (see pdr/Climbs).
     * Sudden changes, such as the pressure differences between rooms and door pulses, are held out
     * at any count. At 4, over 30 m at a 0.67 m stride, about 80 % of a 5 % slope and 30 % of a 3 %
     * one come through (less at shorter strides; the path never strays more than [baroMaxHeldM]
     * from the barometer), and flights of three stairs or more are kept. A higher count holds out
     * more of pressure changes spread over a second or more and of wobble slower than about 4 s,
     * which pass in part, but loses short flights (three stairs about half the time at 5) and more
     * of a slope. 0 turns the filter off and takes every change through [baroSmoothingS] and
     * [baroHoldWhenStill].
     */
    val baroConfirmSteps: Int = 4,
    /**
     * Height change of a single step, metres, from which it counts toward [baroConfirmSteps]. A
     * smaller step in the same direction neither counts nor breaks the run.
     */
    val baroConfirmStepM: Double = 0.02,
    /**
     * With [baroConfirmSteps] on, the path's height stays within this many metres of the
     * barometer's: held-out change beyond it comes through. It bounds the drift that slow pressure
     * wobble would otherwise build up and the height a long gentle slope can lose, while pressure
     * zones smaller than it (1.5 m is 18 Pa) stay out. The bound is on the path against the
     * barometer, not per slope: on the way back up a gentle ramp walked down, the path first moves
     * from one side of the band to the other, so that leg can lose up to twice this. Once held-out
     * height has used the band up, pressure changes the same way pass until the path is back near
     * the barometer.
     */
    val baroMaxHeldM: Double = 1.5,

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
