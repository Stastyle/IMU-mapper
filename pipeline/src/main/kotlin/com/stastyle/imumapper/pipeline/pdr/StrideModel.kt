package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.PipelineConfig
import kotlin.math.pow

/**
 * Stride length per step: the calibrated constant, or Weinberg's k * (aMax - aMin)^(1/4) with the
 * vertical acceleration swing of the step when [PipelineConfig.weinbergK] is positive. A zero
 * swing (hardware step with no accel data) falls back to the constant so a step never has length 0.
 */
object StrideModel {
    fun strideM(config: PipelineConfig, swing: Double): Double {
        if (config.weinbergK > 0.0 && swing > 0.0) return config.weinbergK * swing.pow(0.25)
        return config.strideLengthM
    }

    fun usesWeinberg(config: PipelineConfig): Boolean = config.weinbergK > 0.0
}
