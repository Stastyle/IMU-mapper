package com.stastyle.imumapper.pipeline.core

import com.stastyle.imumapper.pipeline.log.RawLog

/**
 * Bump when the output of the pipeline changes so stored results can be told apart.
 *
 * 2: results carry [PathResult.rawPoints], the path before loop closure and smoothing.
 * 3: carry changes are detected from the tilt and re-estimate the heading offset (autoReorient).
 * 4: the walking heading is carried through every move of the phone (carry change or REORIENT)
 *    instead of being re-estimated from the gait, so moving the phone no longer turns the path.
 * 5: +Y is magnetic north, set from the fused rotation vector at the start of the trip
 *    (northFromCompass); before, every trip kept the game rotation vector's arbitrary yaw.
 */
const val PIPELINE_VERSION: Int = 5

/**
 * Turns a raw log into a path. Implementations must be deterministic: the same log and config
 * always give the same result, with no dependence on wall-clock time or randomness.
 */
interface Processor {
    val version: Int get() = PIPELINE_VERSION

    fun process(log: RawLog, config: PipelineConfig): PathResult
}
