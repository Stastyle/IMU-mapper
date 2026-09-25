package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Processor
import com.stastyle.imumapper.pipeline.log.RawLog

/**
 * Pedestrian dead reckoning: steps + stride + heading + barometric altitude -> 3D path.
 * See docs/PLAN.md section 5 for the algorithm. Implemented by the pipeline-pdr work item.
 */
class PdrProcessor : Processor {
    override fun process(log: RawLog, config: PipelineConfig): PathResult {
        TODO("PDR pipeline not implemented yet")
    }
}
