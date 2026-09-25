package com.stastyle.imumapper.pipeline

import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Processor
import com.stastyle.imumapper.pipeline.log.RawLog
import com.stastyle.imumapper.pipeline.pdr.PdrProcessor
import com.stastyle.imumapper.pipeline.vio.VioProcessor

/**
 * Entry point used by the app: picks the VIO path when the log holds ARCore tracking, otherwise
 * plain PDR. Both processors can also be run explicitly for side-by-side comparison.
 */
class DefaultProcessor(
    val pdr: PdrProcessor = PdrProcessor(),
    val vio: VioProcessor = VioProcessor(pdr),
) : Processor {
    override fun process(log: RawLog, config: PipelineConfig): PathResult =
        if (log.hasVio) vio.process(log, config) else pdr.process(log, config)
}
