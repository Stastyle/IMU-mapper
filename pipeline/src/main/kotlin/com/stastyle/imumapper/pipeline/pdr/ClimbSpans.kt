package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.PauseIntervals
import kotlin.math.abs

/**
 * The stretches of a trip whose barometric height change reaches the path when
 * [com.stastyle.imumapper.pipeline.core.PipelineConfig.baroConfirmSteps] is on.
 *
 * Indoors the pressure also changes on flat ground: rooms, corridors and stairwells are held at
 * different pressures, doors send pulses through a building and wind shakes it, and each pascal
 * reads as 8 cm of height. Such changes come and go within a few steps, while stairs and slopes
 * keep the height moving the same way step after step. So the height is taken only from runs:
 * consecutive steps that all move it the same way, of which at least `minSteps` move it by
 * `minStepM` or more. The smaller steps of a run neither count nor break it, because on a gentle
 * slope the barometer noise pushes some steps under `minStepM`.
 *
 * The decision uses the unsmoothed height averaged over each step (from the step before to the
 * step), whose response to a sudden change is over within two steps; the low-passed height would
 * spread a pressure jump over as many steps as its settling time holds and let it pass as a
 * climb. The accepted span of a run starts with the step interval before its first step, in which
 * the climb began, and ends [settleNs] after its last step, so the low-passed height the path is
 * built from has caught up with the climb. A step interval that touches a pause ends every run.
 */
class ClimbSpans private constructor(
    private val starts: LongArray,
    private val ends: LongArray,
    /** Runs with enough large steps to count. */
    val acceptedRuns: Int,
    /** Runs with some large steps but too few: changes held out of the path. */
    val rejectedRuns: Int,
) {
    val size: Int get() = starts.size

    fun startNs(i: Int): Long = starts[i]
    fun endNs(i: Int): Long = ends[i]

    /** Calls [block] with each maximal sub-range of [fromNs, toNs] that lies inside a span, in time order. */
    inline fun forEachInside(fromNs: Long, toNs: Long, block: (Long, Long) -> Unit) {
        if (toNs <= fromNs) return
        for (i in 0 until size) {
            val s = startNs(i)
            val e = endNs(i)
            if (e <= fromNs) continue
            if (s >= toNs) break
            val a = maxOf(fromNs, s)
            val b = minOf(toNs, e)
            if (b > a) block(a, b)
        }
    }

    companion object {
        /**
         * Finds the spans for [steps] from the unsmoothed height [raw]. [tripStartNs] opens the
         * interval of the first step. [minSteps] must be at least 1.
         */
        fun detect(
            raw: AltitudeTrack,
            steps: DetectedSteps,
            pauses: PauseIntervals,
            tripStartNs: Long,
            minSteps: Int,
            minStepM: Double,
            settleNs: Long,
        ): ClimbSpans {
            require(minSteps >= 1) { "minSteps must be at least 1: $minSteps" }
            val n = steps.size
            val t = steps.tNs
            fun intervalStart(i: Int): Long = if (i == 0) minOf(tripStartNs, t[0]) else t[i - 1]

            val mean = DoubleArray(n)
            val paused = BooleanArray(n)
            for (i in 0 until n) {
                val from = intervalStart(i)
                mean[i] = raw.meanOver(from, t[i])
                paused[i] = pauses.coveredNs(from, t[i]) > 0L
            }
            // Direction of the height change from the interval before each step to its own: +1 up,
            // -1 down, 0 across a pause. The first step has no interval before it.
            val dir = IntArray(n)
            val large = BooleanArray(n)
            for (i in 1 until n) {
                if (paused[i] || paused[i - 1]) continue
                val change = mean[i] - mean[i - 1]
                dir[i] = if (change > 0.0) 1 else if (change < 0.0) -1 else 0
                large[i] = dir[i] != 0 && abs(change) >= minStepM
            }

            val starts = ArrayList<Long>()
            val ends = ArrayList<Long>()
            var accepted = 0
            var rejected = 0
            var first = 1
            while (first < n) {
                if (dir[first] == 0) {
                    first++
                    continue
                }
                // A run: consecutive steps that all move the height the same way.
                var last = first
                while (last + 1 < n && dir[last + 1] == dir[first]) last++
                var largeSteps = 0
                for (k in first..last) if (large[k]) largeSteps++
                if (largeSteps >= minSteps) {
                    accepted++
                    val s = intervalStart(first - 1)
                    val e = t[last] + settleNs
                    if (ends.isNotEmpty() && s <= ends[ends.size - 1]) {
                        ends[ends.size - 1] = maxOf(ends[ends.size - 1], e)
                    } else {
                        starts.add(s)
                        ends.add(e)
                    }
                } else if (largeSteps > 0) {
                    rejected++
                }
                first = last + 1
            }
            return ClimbSpans(starts.toLongArray(), ends.toLongArray(), accepted, rejected)
        }
    }
}
