package com.stastyle.imumapper.pipeline.vio

import com.stastyle.imumapper.pipeline.core.PoseSample
import com.stastyle.imumapper.pipeline.core.TrackingState

/** A maximal stretch of TRACKING frames without a hole longer than the configured limit. */
class TrackingRun(val poses: List<PoseSample>) {
    val firstNs: Long get() = poses[0].tNs
    val lastNs: Long get() = poses[poses.size - 1].tNs
    val first: PoseSample get() = poses[0]
    val last: PoseSample get() = poses[poses.size - 1]

    /** Distance in nanoseconds from [tNs] to the run's time span, 0 inside it. */
    fun distanceNs(tNs: Long): Long = when {
        tNs < firstNs -> firstNs - tNs
        tNs > lastNs -> tNs - lastNs
        else -> 0L
    }
}

/**
 * Splits the pose stream into tracking runs. A run ends at a STOPPED frame (the session may come
 * back in a new world frame), at a hole longer than [maxHoleNs] between two TRACKING frames, or
 * at a timestamp that goes backwards (a corrupt record; the frame is dropped). PAUSED frames are
 * skipped rather than splitting: ARCore keeps its world frame across a brief interruption (one or
 * two frames of EXCESSIVE_MOTION during a head turn are common), so a pause shorter than
 * [maxHoleNs] is a hole to interpolate over, while a longer one leaves a hole between the last and
 * the next TRACKING frame that ends the run anyway. Frames before the first TRACKING frame are
 * ignored.
 */
object TrackingRuns {

    fun split(poses: List<PoseSample>, maxHoleNs: Long): List<TrackingRun> {
        val runs = ArrayList<TrackingRun>()
        var current = ArrayList<PoseSample>()
        var lastNs = Long.MIN_VALUE
        for (pose in poses) {
            if (pose.tracking == TrackingState.PAUSED) continue
            if (pose.tracking != TrackingState.TRACKING) {
                if (current.isNotEmpty()) {
                    runs.add(TrackingRun(current))
                    current = ArrayList()
                }
                continue
            }
            if (pose.tNs < lastNs) continue
            if (current.isNotEmpty() && pose.tNs - lastNs > maxHoleNs) {
                runs.add(TrackingRun(current))
                current = ArrayList()
            }
            // Two frames on the same nanosecond carry no motion; keep the first for a clean time axis.
            if (current.isNotEmpty() && pose.tNs == lastNs) continue
            current.add(pose)
            lastNs = pose.tNs
        }
        if (current.isNotEmpty()) runs.add(TrackingRun(current))
        return runs
    }
}
