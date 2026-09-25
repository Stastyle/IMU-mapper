package com.stastyle.imumapper.pipeline.vio

import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PoseSample
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.Vec3

/**
 * Resamples a tracking run onto a regular time grid (linear position, slerp orientation) and
 * converts every sample with the run's [FrameTransform]. The last frame of the run is always
 * emitted so a PDR fill after a tracking loss starts from the true last good pose.
 */
object PoseResampler {

    /** The run's last frame is emitted as an extra sample unless a grid sample already lies within this of it. */
    private const val MIN_TAIL_NS: Long = 1_000_000L

    fun resample(run: TrackingRun, transform: FrameTransform, periodNs: Long): List<PathPoint> {
        val poses = run.poses
        val out = ArrayList<PathPoint>()
        if (periodNs <= 0L) {
            for (pose in poses) out.add(convert(pose.tNs, pose.position(), pose.orientation(), transform))
            return out
        }
        val startNs = run.firstNs
        val endNs = run.lastNs
        var index = 0
        var k = 0L
        var lastEmitted = Long.MIN_VALUE
        while (true) {
            val t = startNs + k * periodNs
            if (t > endNs) break
            while (index + 1 < poses.size && poses[index + 1].tNs <= t) index++
            out.add(interpolated(poses, index, t, transform))
            lastEmitted = t
            k++
        }
        if (endNs - lastEmitted >= MIN_TAIL_NS) {
            val last = run.last
            out.add(convert(last.tNs, last.position(), last.orientation(), transform))
        }
        return out
    }

    private fun interpolated(poses: List<PoseSample>, index: Int, t: Long, transform: FrameTransform): PathPoint {
        val a = poses[index]
        if (index + 1 >= poses.size || a.tNs >= t) return convert(t, a.position(), a.orientation(), transform)
        val b = poses[index + 1]
        if (b.tNs <= a.tNs) return convert(t, a.position(), a.orientation(), transform)
        val f = (t - a.tNs).toDouble() / (b.tNs - a.tNs).toDouble()
        val p = a.position().lerp(b.position(), f)
        val q = a.orientation().slerp(b.orientation(), f)
        return convert(t, p, q, transform)
    }

    private fun convert(t: Long, pAr: Vec3, qAr: Quat, transform: FrameTransform): PathPoint =
        PathPoint(t, transform.position(pAr), PositionSource.VIO, transform.cameraHeadingRad(qAr), -1)
}
