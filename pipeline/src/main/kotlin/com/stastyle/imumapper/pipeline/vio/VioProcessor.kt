package com.stastyle.imumapper.pipeline.vio

import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Processor
import com.stastyle.imumapper.pipeline.log.RawLog
import com.stastyle.imumapper.pipeline.pdr.PdrProcessor

/**
 * Visual-inertial path: ARCore poses converted to ENU, resampled, with tracking gaps filled by
 * PDR and re-anchored when tracking resumes. Implemented by the pipeline-vio work item.
 */
class VioProcessor(private val pdr: PdrProcessor = PdrProcessor()) : Processor {
    override fun process(log: RawLog, config: PipelineConfig): PathResult {
        TODO("VIO pipeline not implemented yet")
    }
}
