package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Processor
import com.stastyle.imumapper.pipeline.log.RawLog
import com.stastyle.imumapper.pipeline.post.LoopClosure
import com.stastyle.imumapper.pipeline.post.PathBuilder
import com.stastyle.imumapper.pipeline.post.Smoothing

/**
 * Pedestrian dead reckoning: steps + stride + heading + barometric altitude -> 3D path, then loop
 * closure and smoothing. See docs/PLAN.md section 5. The heavy lifting is in [PdrSolver] so the
 * VIO fuser can dead-reckon single segments with exactly the same code.
 */
class PdrProcessor(private val solver: PdrSolver = PdrSolver()) : Processor {

    override fun process(log: RawLog, config: PipelineConfig): PathResult {
        val solution = solver.solve(log, config)
        val diagnostics = LinkedHashMap<String, String>(solution.context.diagnostics)
        var points = solution.points
        var closureErrorM: Double? = null
        val closures = log.annotations.filter { it.kind == AnnotationKind.LOOP_CLOSED }.map { it.tNs }
        if (config.loopClosure && closures.isNotEmpty()) {
            val closed = LoopClosure.apply(points, closures)
            if (closed != null) {
                points = closed.points
                closureErrorM = closed.closureErrorM
                diagnostics["loopClosure"] = "applied at " + closures.size + " annotation(s)"
            } else {
                diagnostics["loopClosure"] = "skipped: no path before the annotation"
            }
        } else if (closures.isNotEmpty()) {
            diagnostics["loopClosure"] = "disabled"
        }
        points = Smoothing.movingAverage(points, config.smoothingWindow)
        return PathBuilder.build(
            config = config,
            points = points,
            log = log,
            stepCount = solution.context.steps.size,
            closureErrorM = closureErrorM,
            diagnostics = diagnostics,
        )
    }
}
