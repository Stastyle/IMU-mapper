package com.stastyle.imumapper.pipeline.tuning

import com.stastyle.imumapper.pipeline.core.HeadingAxisMode
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Vec3
import java.util.Locale

enum class FieldKind { NUMBER, INTEGER, BOOLEAN, ENUM, VECTOR }

/**
 * One [PipelineConfig] field as the assisted-tuning prompt describes it and as a proposal may set
 * it. [key] is the JSON name the config serialises with, so a model that edits the JSON it was
 * shown produces something the parser accepts without translation.
 */
class ConfigFieldSpec(
    val key: String,
    val kind: FieldKind,
    val unit: String,
    val min: Double,
    val max: Double,
    /** What the field does and what evidence should move it, in one or two sentences for the prompt. */
    val description: String,
    /** Named values for [FieldKind.ENUM]. */
    val values: List<String> = emptyList(),
    /**
     * False for values the guided calibration flows measure directly (stride, heading offset, gyro
     * bias): the prompt asks the model to leave them alone unless the walk shows they are wrong. Also
     * false for user settings no walk can show to be wrong (northFromCompass), whose description
     * tells the model never to change them.
     */
    val tunable: Boolean = true,
)

/**
 * The catalogue behind the prompt's parameter table and the proposal validation. Ranges match
 * the debug screen's editor; keep the two in step when a field is added.
 */
object ConfigSchema {

    val fields: List<ConfigFieldSpec> = listOf(
        ConfigFieldSpec(
            "strideLengthM", FieldKind.NUMBER, "m", 0.1, 3.0,
            "Fixed stride length used when weinbergK is 0. Measured by the stride walk; change it only when " +
                "the step count is right but the distance is consistently off by the same factor.",
            tunable = false,
        ),
        ConfigFieldSpec(
            "weinbergK", FieldKind.NUMBER, "", 0.0, 5.0,
            "Gain of the Weinberg stride model stride = k * swing^(1/4); 0 disables it and the fixed stride is " +
                "used. Typical values 0.3 to 0.6 when enabled.",
        ),
        ConfigFieldSpec(
            "headingOffsetRad", FieldKind.NUMBER, "rad", -6.2832, 6.2832,
            "Angle added to the phone's heading to get the walking direction, for how the phone is held when " +
                "the recording starts. Measured by the heading walk. A constant rotation of the whole path (every " +
                "leg off by the same angle) may mean a wrong offset, but can also come from the compass at the start " +
                "(metal nearby) or from magnetic versus true north, so change it only when the same rotation repeats " +
                "across walks started in clean surroundings with northReference \"magnetic\". When northReference " +
                "is not \"magnetic\", the direction of the path is the gyro's arbitrary start and says nothing " +
                "about this offset. headingOffsetDeg is accepted as an alternative key in degrees.",
            tunable = false,
        ),
        ConfigFieldSpec(
            "headingAxis", FieldKind.ENUM, "", 0.0, 0.0,
            "Device axis the heading offset was measured on. Leave as is unless the phone is carried differently.",
            values = HeadingAxisMode.entries.map { it.name },
            tunable = false,
        ),
        ConfigFieldSpec(
            "useMagnetometer", FieldKind.BOOLEAN, "", 0.0, 1.0,
            "Correct slow gyro yaw drift with the magnetometer during the walk. It only bounds drift; where north " +
                "is comes from northFromCompass and stays when this is off. Turn off when the magnetic gate passes " +
                "only a small fraction of the samples or the path bends where the walk was straight.",
        ),
        ConfigFieldSpec(
            "northFromCompass", FieldKind.BOOLEAN, "", 0.0, 1.0,
            "Turn the path so +Y is magnetic north, read from the compass at the start of the walk " +
                "(northReference and northOffsetDeg in the diagnostics). The user's choice of map frame, not a " +
                "tuning knob: never propose changing it, because no walk can show it is wrong.",
            tunable = false,
        ),
        ConfigFieldSpec(
            "magGateTolerance", FieldKind.NUMBER, "fraction", 0.0, 1.0,
            "Relative tolerance on magnetic field magnitude and dip before a reading counts as disturbed. " +
                "Raise a little when the gate rejects almost everything in a clean environment; lower it when " +
                "metal or rock bends the path.",
        ),
        ConfigFieldSpec(
            "gyroBias", FieldKind.VECTOR, "rad/s", -1.0, 1.0,
            "Gyroscope bias subtracted before integration, as {\"x\",\"y\",\"z\"}. Measured by the still-bias flow; " +
                "only used when no rotation vector sensor is available.",
            tunable = false,
        ),
        ConfigFieldSpec(
            "stepMinIntervalS", FieldKind.NUMBER, "s", 0.05, 2.0,
            "Shortest time between two steps. Raise it (towards 0.4) when many step intervals are below the " +
                "walker's cadence (double counting); lower it for fast walkers or running.",
        ),
        ConfigFieldSpec(
            "stepMinSwing", FieldKind.NUMBER, "m/s^2", 0.0, 20.0,
            "Smallest peak-to-valley swing of band-passed vertical acceleration that counts as a step. Raise " +
                "it when steps are counted while standing or the swing distribution has a cluster of tiny " +
                "values; lower it when soft steps are missed (too few steps for the distance).",
        ),
        ConfigFieldSpec(
            "stepBandLowHz", FieldKind.NUMBER, "Hz", 0.05, 10.0,
            "Lower edge of the step band-pass. Keep below half the cadence.",
        ),
        ConfigFieldSpec(
            "stepBandHighHz", FieldKind.NUMBER, "Hz", 0.1, 20.0,
            "Upper edge of the step band-pass; must stay above stepBandLowHz. Lower it (towards 2.5) when " +
                "hand or phone shake is counted as steps; raise it for running.",
        ),
        ConfigFieldSpec(
            "preferHardwareSteps", FieldKind.BOOLEAN, "", 0.0, 1.0,
            "Use Android's hardware step detector instead of the software one when both exist. Try it when " +
                "the hardware count is much closer to the truth than the software count.",
        ),
        ConfigFieldSpec(
            "baroSmoothingS", FieldKind.NUMBER, "s", 0.0, 60.0,
            "Low-pass time constant of the barometric altitude, used only while baroConfirmSteps is 0. Raise it " +
                "when the height wobbles on flat ground; lower it when stairs come out smeared or too low.",
        ),
        ConfigFieldSpec(
            "baroHoldWhenStill", FieldKind.BOOLEAN, "", 0.0, 1.0,
            "Freeze the altitude while no steps happen, which removes pressure noise when standing. Used only " +
                "while baroConfirmSteps is 0.",
        ),
        ConfigFieldSpec(
            "baroStillGapS", FieldKind.NUMBER, "s", 0.5, 60.0,
            "A gap between steps longer than this counts as standing still for baroHoldWhenStill. Must stay " +
                "above the slowest step period (2 s at stepBandLowHz = 0.5). Used only while baroConfirmSteps is 0.",
        ),
        ConfigFieldSpec(
            "baroConfirmSteps", FieldKind.INTEGER, "steps", 0.0, 50.0,
            "A height change counts only when the height keeps moving the same way step after step, with at " +
                "least this many steps of baroConfirmStepM or more, as on stairs or a slope; shorter changes " +
                "(pressure zones indoors, doors) are held out, while wobble slower than about 4 s passes. " +
                "0 turns the filter off. Raise it when the height jumps on flat ground; lower it when short " +
                "flights of stairs are missing, but not below 4: at 3 about half of sudden pressure jumps pass, " +
                "at 2 or 1 nearly all.",
        ),
        ConfigFieldSpec(
            "baroConfirmStepM", FieldKind.NUMBER, "m", 0.0, 0.5,
            "Height change of one step from which it counts toward baroConfirmSteps; a smaller step the same " +
                "way neither counts nor breaks the run. Lower it when gentle slopes come out flat; raise it when " +
                "noise on flat ground passes as a climb.",
        ),
        ConfigFieldSpec(
            "baroMaxHeldM", FieldKind.NUMBER, "m", 0.0, 50.0,
            "With baroConfirmSteps on, the height stays within this of the barometer's: held-out change beyond " +
                "it comes through. It acts only once that much is held out, so lower it only when a long gentle " +
                "slope has lost about this much (baroLimitM above 0); raise it when pressure zones larger than " +
                "it show as steps on one floor with no gentle slope before them.",
        ),
        ConfigFieldSpec(
            "loopClosure", FieldKind.BOOLEAN, "", 0.0, 1.0,
            "Spread the end-to-start error over the path when the walker declared a closed loop.",
        ),
        ConfigFieldSpec(
            "smoothingWindow", FieldKind.INTEGER, "points", 1.0, 99.0,
            "Moving-average window over path points; 1 disables smoothing. Larger values round off corners.",
        ),
        ConfigFieldSpec(
            "pdrFallbackWhenTrackingLost", FieldKind.BOOLEAN, "", 0.0, 1.0,
            "Fill ARCore tracking gaps with step-based dead reckoning instead of leaving holes.",
        ),
        ConfigFieldSpec(
            "vioResamplePeriodS", FieldKind.NUMBER, "s", 0.01, 5.0,
            "Output sample period of ARCore paths.",
        ),
    )

    fun find(key: String): ConfigFieldSpec? = fields.firstOrNull { it.key == key }

    /** Value of [key] in [c] as the prompt prints it; null for an unknown key. */
    fun valueText(c: PipelineConfig, key: String): String? = when (key) {
        "strideLengthM" -> num(c.strideLengthM)
        "weinbergK" -> num(c.weinbergK)
        "headingOffsetRad" -> num(c.headingOffsetRad, 4)
        "headingAxis" -> c.headingAxis.name
        "useMagnetometer" -> c.useMagnetometer.toString()
        "northFromCompass" -> c.northFromCompass.toString()
        "magGateTolerance" -> num(c.magGateTolerance)
        "gyroBias" -> vec(c.gyroBias)
        "stepMinIntervalS" -> num(c.stepMinIntervalS)
        "stepMinSwing" -> num(c.stepMinSwing)
        "stepBandLowHz" -> num(c.stepBandLowHz)
        "stepBandHighHz" -> num(c.stepBandHighHz)
        "preferHardwareSteps" -> c.preferHardwareSteps.toString()
        "baroSmoothingS" -> num(c.baroSmoothingS)
        "baroHoldWhenStill" -> c.baroHoldWhenStill.toString()
        "baroStillGapS" -> num(c.baroStillGapS)
        "baroConfirmSteps" -> c.baroConfirmSteps.toString()
        "baroConfirmStepM" -> num(c.baroConfirmStepM)
        "baroMaxHeldM" -> num(c.baroMaxHeldM)
        "loopClosure" -> c.loopClosure.toString()
        "smoothingWindow" -> c.smoothingWindow.toString()
        "pdrFallbackWhenTrackingLost" -> c.pdrFallbackWhenTrackingLost.toString()
        "vioResamplePeriodS" -> num(c.vioResamplePeriodS)
        else -> null
    }

    /** Keys whose value differs between [a] and [b], in schema order. */
    fun changedKeys(a: PipelineConfig, b: PipelineConfig): List<String> =
        fields.map { it.key }.filter { valueText(a, it) != valueText(b, it) }

    /** Shortest locale-independent decimal text without exponent or trailing zeros. */
    fun num(x: Double, decimals: Int = 6): String {
        val s = String.format(Locale.US, "%." + decimals + "f", x).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }

    private fun vec(v: Vec3): String = "{x: " + num(v.x) + ", y: " + num(v.y) + ", z: " + num(v.z) + "}"
}
