package com.stastyle.imumapper.ui.debug

import com.stastyle.imumapper.pipeline.core.HeadingAxisMode
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.ui.calibration.CalibrationMath
import java.util.Locale
import kotlin.math.abs

// Pure Kotlin: the config editor's text model and validation, unit-tested on the JVM.

enum class FieldType { DOUBLE, INT, BOOL }

/** Every [PipelineConfig] field as the editor shows it. Angles are edited in degrees, stored in radians. */
enum class ConfigField(val label: String, val type: FieldType, val hint: String) {
    STRIDE_LENGTH_M("Stride length (m)", FieldType.DOUBLE, "0.1 to 3"),
    WEINBERG_K("Weinberg k (0 = fixed stride)", FieldType.DOUBLE, "0 to 5"),
    HEADING_OFFSET_DEG("Heading offset (deg)", FieldType.DOUBLE, "-360 to 360"),
    USE_MAGNETOMETER("Use magnetometer", FieldType.BOOL, ""),
    MAG_GATE_TOLERANCE("Magnetic gate tolerance (fraction)", FieldType.DOUBLE, "0 to 1"),
    GYRO_BIAS_X("Gyro bias x (rad/s)", FieldType.DOUBLE, "-1 to 1"),
    GYRO_BIAS_Y("Gyro bias y (rad/s)", FieldType.DOUBLE, "-1 to 1"),
    GYRO_BIAS_Z("Gyro bias z (rad/s)", FieldType.DOUBLE, "-1 to 1"),
    AUTO_REORIENT("Detect carry changes (auto re-orient)", FieldType.BOOL, ""),
    CARRY_CHANGE_TILT_DEG("Carry change tilt (deg)", FieldType.DOUBLE, "5 to 90"),
    CARRY_CHANGE_SETTLE_S("Carry change settle time (s)", FieldType.DOUBLE, "0.2 to 10"),
    STEP_MIN_INTERVAL_S("Min step interval (s)", FieldType.DOUBLE, "0.05 to 2"),
    STEP_MIN_SWING("Min step swing (m/s²)", FieldType.DOUBLE, "0 to 20"),
    STEP_BAND_LOW_HZ("Step band low (Hz)", FieldType.DOUBLE, "0.05 to 10"),
    STEP_BAND_HIGH_HZ("Step band high (Hz)", FieldType.DOUBLE, "above low, up to 20"),
    PREFER_HARDWARE_STEPS("Prefer hardware steps", FieldType.BOOL, ""),
    BARO_SMOOTHING_S("Baro smoothing (s)", FieldType.DOUBLE, "0 to 60"),
    BARO_HOLD_WHEN_STILL("Hold altitude when still", FieldType.BOOL, ""),
    LOOP_CLOSURE("Loop closure", FieldType.BOOL, ""),
    SMOOTHING_WINDOW("Smoothing window (points)", FieldType.INT, "1 to 99"),
    PDR_FALLBACK("PDR fallback when tracking lost", FieldType.BOOL, ""),
    VIO_RESAMPLE_PERIOD_S("VIO resample period (s)", FieldType.DOUBLE, "0.01 to 5"),
}

/**
 * Text of every field as typed by the user. [headingAxis] is not editable: it belongs to the
 * calibrated offset and is carried through so that saving from the editor keeps it.
 */
data class ConfigDraft(
    val values: Map<ConfigField, String>,
    val headingAxis: HeadingAxisMode = HeadingAxisMode.AUTO,
) {

    fun text(field: ConfigField): String = values[field] ?: ""

    fun bool(field: ConfigField): Boolean = values[field] == "true"

    fun with(field: ConfigField, text: String): ConfigDraft = copy(values = values + (field to text))

    companion object {
        fun from(c: PipelineConfig): ConfigDraft = ConfigDraft(
            headingAxis = c.headingAxis,
            values = mapOf(
                ConfigField.STRIDE_LENGTH_M to num(c.strideLengthM),
                ConfigField.WEINBERG_K to num(c.weinbergK),
                ConfigField.HEADING_OFFSET_DEG to num(Math.toDegrees(c.headingOffsetRad)),
                ConfigField.USE_MAGNETOMETER to c.useMagnetometer.toString(),
                ConfigField.MAG_GATE_TOLERANCE to num(c.magGateTolerance),
                ConfigField.GYRO_BIAS_X to num(c.gyroBias.x),
                ConfigField.GYRO_BIAS_Y to num(c.gyroBias.y),
                ConfigField.GYRO_BIAS_Z to num(c.gyroBias.z),
                ConfigField.AUTO_REORIENT to c.autoReorient.toString(),
                ConfigField.CARRY_CHANGE_TILT_DEG to num(Math.toDegrees(c.carryChangeTiltRad)),
                ConfigField.CARRY_CHANGE_SETTLE_S to num(c.carryChangeSettleS),
                ConfigField.STEP_MIN_INTERVAL_S to num(c.stepMinIntervalS),
                ConfigField.STEP_MIN_SWING to num(c.stepMinSwing),
                ConfigField.STEP_BAND_LOW_HZ to num(c.stepBandLowHz),
                ConfigField.STEP_BAND_HIGH_HZ to num(c.stepBandHighHz),
                ConfigField.PREFER_HARDWARE_STEPS to c.preferHardwareSteps.toString(),
                ConfigField.BARO_SMOOTHING_S to num(c.baroSmoothingS),
                ConfigField.BARO_HOLD_WHEN_STILL to c.baroHoldWhenStill.toString(),
                ConfigField.LOOP_CLOSURE to c.loopClosure.toString(),
                ConfigField.SMOOTHING_WINDOW to c.smoothingWindow.toString(),
                ConfigField.PDR_FALLBACK to c.pdrFallbackWhenTrackingLost.toString(),
                ConfigField.VIO_RESAMPLE_PERIOD_S to num(c.vioResamplePeriodS),
            ),
        )

        /** Shortest round-trippable decimal text: no exponent, no trailing zeros, locale independent. */
        fun num(x: Double): String {
            val s = String.format(Locale.US, "%.6f", x).trimEnd('0').trimEnd('.')
            return if (s == "-0") "0" else s
        }
    }
}

/** [config] is null when [errors] is not empty. */
data class ParsedConfig(val config: PipelineConfig?, val errors: Map<ConfigField, String>)

object ConfigFields {

    fun parse(draft: ConfigDraft): ParsedConfig {
        val errors = LinkedHashMap<ConfigField, String>()

        fun double(field: ConfigField, min: Double, max: Double): Double? {
            val v = draft.text(field).trim().replace(',', '.').toDoubleOrNull()
            if (v == null || v.isNaN() || v.isInfinite()) {
                errors[field] = "Enter a number"
                return null
            }
            if (v < min || v > max) {
                errors[field] = "Must be between " + ConfigDraft.num(min) + " and " + ConfigDraft.num(max)
                return null
            }
            return v
        }

        fun int(field: ConfigField, min: Int, max: Int): Int? {
            val v = draft.text(field).trim().toIntOrNull()
            if (v == null) {
                errors[field] = "Enter a whole number"
                return null
            }
            if (v < min || v > max) {
                errors[field] = "Must be between $min and $max"
                return null
            }
            return v
        }

        val stride = double(ConfigField.STRIDE_LENGTH_M, 0.1, 3.0)
        val weinberg = double(ConfigField.WEINBERG_K, 0.0, 5.0)
        val headingDeg = double(ConfigField.HEADING_OFFSET_DEG, -360.0, 360.0)
        val magTol = double(ConfigField.MAG_GATE_TOLERANCE, 0.0, 1.0)
        val bx = double(ConfigField.GYRO_BIAS_X, -1.0, 1.0)
        val by = double(ConfigField.GYRO_BIAS_Y, -1.0, 1.0)
        val bz = double(ConfigField.GYRO_BIAS_Z, -1.0, 1.0)
        val carryTiltDeg = double(ConfigField.CARRY_CHANGE_TILT_DEG, 5.0, 90.0)
        val carrySettle = double(ConfigField.CARRY_CHANGE_SETTLE_S, 0.2, 10.0)
        val minInterval = double(ConfigField.STEP_MIN_INTERVAL_S, 0.05, 2.0)
        val minSwing = double(ConfigField.STEP_MIN_SWING, 0.0, 20.0)
        val bandLow = double(ConfigField.STEP_BAND_LOW_HZ, 0.05, 10.0)
        val bandHigh = double(ConfigField.STEP_BAND_HIGH_HZ, 0.1, 20.0)
        if (bandLow != null && bandHigh != null && bandHigh <= bandLow) {
            errors[ConfigField.STEP_BAND_HIGH_HZ] = "Must be above the low limit"
        }
        val baroSmoothing = double(ConfigField.BARO_SMOOTHING_S, 0.0, 60.0)
        val window = int(ConfigField.SMOOTHING_WINDOW, 1, 99)
        val resample = double(ConfigField.VIO_RESAMPLE_PERIOD_S, 0.01, 5.0)

        if (errors.isNotEmpty()) return ParsedConfig(null, errors)
        val config = PipelineConfig(
            strideLengthM = stride!!,
            weinbergK = weinberg!!,
            headingOffsetRad = CalibrationMath.wrapRad(Math.toRadians(headingDeg!!)),
            headingAxis = draft.headingAxis,
            useMagnetometer = draft.bool(ConfigField.USE_MAGNETOMETER),
            magGateTolerance = magTol!!,
            gyroBias = Vec3(bx!!, by!!, bz!!),
            autoReorient = draft.bool(ConfigField.AUTO_REORIENT),
            carryChangeTiltRad = Math.toRadians(carryTiltDeg!!),
            carryChangeSettleS = carrySettle!!,
            stepMinIntervalS = minInterval!!,
            stepMinSwing = minSwing!!,
            stepBandLowHz = bandLow!!,
            stepBandHighHz = bandHigh!!,
            preferHardwareSteps = draft.bool(ConfigField.PREFER_HARDWARE_STEPS),
            baroSmoothingS = baroSmoothing!!,
            baroHoldWhenStill = draft.bool(ConfigField.BARO_HOLD_WHEN_STILL),
            loopClosure = draft.bool(ConfigField.LOOP_CLOSURE),
            smoothingWindow = window!!,
            pdrFallbackWhenTrackingLost = draft.bool(ConfigField.PDR_FALLBACK),
            vioResamplePeriodS = resample!!,
        )
        return ParsedConfig(config, emptyMap())
    }

    /** True when [a] and [b] differ in any field beyond float-formatting noise. */
    fun differs(a: PipelineConfig, b: PipelineConfig): Boolean {
        fun d(x: Double, y: Double) = abs(x - y) > 1e-6
        return d(a.strideLengthM, b.strideLengthM) || d(a.weinbergK, b.weinbergK) ||
            d(a.headingOffsetRad, b.headingOffsetRad) || a.headingAxis != b.headingAxis ||
            a.useMagnetometer != b.useMagnetometer ||
            d(a.magGateTolerance, b.magGateTolerance) || d(a.gyroBias.x, b.gyroBias.x) ||
            d(a.gyroBias.y, b.gyroBias.y) || d(a.gyroBias.z, b.gyroBias.z) ||
            a.autoReorient != b.autoReorient || d(a.carryChangeTiltRad, b.carryChangeTiltRad) ||
            d(a.carryChangeSettleS, b.carryChangeSettleS) ||
            d(a.stepMinIntervalS, b.stepMinIntervalS) || d(a.stepMinSwing, b.stepMinSwing) ||
            d(a.stepBandLowHz, b.stepBandLowHz) || d(a.stepBandHighHz, b.stepBandHighHz) ||
            a.preferHardwareSteps != b.preferHardwareSteps || d(a.baroSmoothingS, b.baroSmoothingS) ||
            a.baroHoldWhenStill != b.baroHoldWhenStill || a.loopClosure != b.loopClosure ||
            a.smoothingWindow != b.smoothingWindow ||
            a.pdrFallbackWhenTrackingLost != b.pdrFallbackWhenTrackingLost ||
            d(a.vioResamplePeriodS, b.vioResamplePeriodS)
    }

    /** Short label for a run made from the editor, e.g. "debug stride=0.75 k=0.4". */
    fun runLabel(config: PipelineConfig): String =
        "debug stride=" + ConfigDraft.num(config.strideLengthM) + " k=" + ConfigDraft.num(config.weinbergK) +
            " off=" + ConfigDraft.num(Math.toDegrees(config.headingOffsetRad)) + "°"
}
