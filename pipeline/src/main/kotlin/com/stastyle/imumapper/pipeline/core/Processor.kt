package com.stastyle.imumapper.pipeline.core

import com.stastyle.imumapper.pipeline.log.RawLog

/**
 * Bump when the output of the pipeline changes so stored results can be told apart.
 *
 * 2: results carry [PathResult.rawPoints], the path before loop closure and smoothing.
 * 3: carry changes are detected from the tilt and re-estimate the heading offset (autoReorient).
 */
const val PIPELINE_VERSION: Int = 3

/**
 * Turns a raw log into a path. Implementations must be deterministic: the same log and config
 * always give the same result, with no dependence on wall-clock time or randomness.
 */
interface Processor {
    val version: Int get() = PIPELINE_VERSION

    fun process(log: RawLog, config: PipelineConfig): PathResult
}
