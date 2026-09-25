package com.stastyle.imumapper.pipeline.vio

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.EventKind
import com.stastyle.imumapper.pipeline.core.KeyframeSample
import com.stastyle.imumapper.pipeline.core.PathKeyframe
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PauseIntervals
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Processor
import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.log.RawLog
import com.stastyle.imumapper.pipeline.pdr.Angles
import com.stastyle.imumapper.pipeline.pdr.Diag
import com.stastyle.imumapper.pipeline.pdr.OrientationEstimator
import com.stastyle.imumapper.pipeline.pdr.PdrContext
import com.stastyle.imumapper.pipeline.pdr.PdrProcessor
import com.stastyle.imumapper.pipeline.pdr.PdrSolver
import com.stastyle.imumapper.pipeline.post.LoopClosure
import com.stastyle.imumapper.pipeline.post.PathBuilder
import com.stastyle.imumapper.pipeline.post.Smoothing
import kotlin.math.abs

/**
 * Visual-inertial path: ARCore poses converted to ENU, resampled, with tracking gaps filled by
 * PDR and re-anchored when tracking resumes.
 *
 * Frame handling: ARCore's world frame is turned into ENU by [ArCoreFrame.AR_TO_ENU], then yawed
 * so the camera heading at the first TRACKING frame matches the IMU's camera heading at that
 * moment (the same rotation-vector frame PDR uses, so both paths share one "north"), and
 * translated so the first tracking pose is the origin. Every stretch of continuous tracking gets
 * its own [FrameTransform]: after a loss the next stretch is translated so its first pose lands on
 * the PDR estimate, and re-yawed as well when its heading disagrees with the IMU by more than
 * [yawRealignThresholdRad] (ARCore normally keeps its frame across a loss; a fresh session does not).
 *
 * A tracking loss is a STOPPED frame between tracking frames, a hole longer than [maxHoleS]
 * between tracking frames (PAUSED frames count as a hole, see [TrackingRuns]), or the log
 * continuing for longer than that after the last tracking frame. With
 * [PipelineConfig.pdrFallbackWhenTrackingLost] the loss is bridged by [PdrSolver.solveSegment]
 * from the last good pose. The walking direction handed to PDR is its own heading at that moment
 * plus the difference between the VIO and the IMU camera headings there, which is exactly the
 * IMU's accumulated yaw drift: measured on the device axis it holds during turns and while
 * standing, unlike a velocity direction. Without the fallback the path simply has a hole in time,
 * the ARCore frame is trusted as it is, and smoothing does not bridge the hole.
 *
 * The recorder keeps logging while the user has paused the trip, so poses inside a [PAUSE, RESUME)
 * interval are dropped here: whatever was walked during the pause is not part of the trip. The
 * position is held at the last pose before the pause and the run after it is re-anchored there
 * (re-yawed as after a loss when its heading disagrees with the IMU), without counting a loss or
 * filling with PDR.
 *
 * Frames before the first TRACKING frame are not part of the path: the origin is the first
 * tracking pose, as the viewer and calibration screens expect.
 */
class VioProcessor(
    private val pdr: PdrProcessor = PdrProcessor(),
    private val solver: PdrSolver = PdrSolver(),
    /** Longest hole between two TRACKING frames that is still interpolated instead of treated as a loss. */
    private val maxHoleS: Double = 0.5,
    /** Heading disagreement with the IMU at which a resumed tracking run is re-yawed, not only translated. */
    private val yawRealignThresholdRad: Double = Math.toRadians(30.0),
    private val voxelSizeM: Double = 0.1,
    private val maxPointCloudPoints: Int = 20_000,
    private val minPointConfidence: Float = 0.3f,
) : Processor {

    override fun process(log: RawLog, config: PipelineConfig): PathResult {
        if (log.poses.isEmpty()) return delegate(log, config, "no pose records")
        val maxHoleNs = (maxHoleS * 1e9).toLong()
        val pauses = PauseIntervals.of(log.events)
        val poses = if (pauses.isEmpty) log.poses else log.poses.filter { !pauses.contains(it.tNs) }
        val runs = TrackingRuns.split(poses, maxHoleNs)
        if (runs.isEmpty()) return delegate(log, config, "no TRACKING poses")

        val diag = LinkedHashMap<String, String>()
        diag["vio"] = "arcore"
        diag["poseFrames"] = log.poses.size.toString()
        diag["trackingFrames"] = runs.sumOf { it.poses.size }.toString()
        diag["trackingRuns"] = runs.size.toString()
        diag["trackingLostEvents"] = log.events.count { it.kind == EventKind.TRACKING_LOST }.toString()

        // PDR is prepared whenever the log carries step data: it fills gaps and gives the step count.
        val ctx: PdrContext? =
            if (log.accel.isNotEmpty() || log.steps.isNotEmpty()) solver.prepare(log, config) else null
        val imu = ImuHeading.of(log, ctx)
        diag["yawAlignmentSource"] = imu.source.name

        val first = runs[0].first
        val rawHeading = FrameTransform.IDENTITY.cameraHeadingRad(first.orientation())
        val imuHeading = imu.cameraHeadingRad(first.tNs)
        val yaw = if (imuHeading == null) Quat.IDENTITY else ArCoreFrame.yawBetween(rawHeading, imuHeading)
        diag["yawAlignmentDeg"] =
            Diag.num(Math.toDegrees(if (imuHeading == null) 0.0 else Angles.diff(imuHeading, rawHeading)), 1)
        var transform = FrameTransform(yaw, Vec3.ZERO).anchoredAt(first.position(), Vec3.ZERO)

        // Without an orientation the PDR headings are all the bare offset: dead-reckoning with them
        // would send every gap off in one arbitrary direction, so the gap is left as a hole instead.
        val oriented = ctx != null && ctx.orientationSource != OrientationEstimator.Source.NONE
        val fill = config.pdrFallbackWhenTrackingLost && oriented
        diag["trackingLostFill"] = when {
            fill -> "pdr"
            !config.pdrFallbackWhenTrackingLost -> "none: fallback disabled"
            ctx == null -> "none: no IMU data"
            else -> "none: no orientation"
        }
        val periodNs = Math.round(config.vioResamplePeriodS * 1e9)
        val transforms = ArrayList<FrameTransform>(runs.size)
        val points = ArrayList<PathPoint>()
        val gaps = StringBuilder()
        val pausedGaps = StringBuilder()
        var lossCount = 0
        var lostNs = 0L
        var pauseCount = 0
        var pausedNs = 0L
        var realigned = 0
        var vioPoints = 0
        var pdrPoints = 0
        val t0 = runs[0].firstNs

        for (i in runs.indices) {
            val run = runs[i]
            transforms.add(transform)
            val runPoints = PoseResampler.resample(run, transform, periodNs)
            points.addAll(runPoints)
            vioPoints += runPoints.size

            val trailing = i + 1 == runs.size
            val lostAt = run.lastNs
            val regainAt = if (trailing) log.lastTimestampNs else runs[i + 1].firstNs
            if (trailing && regainAt - lostAt <= maxHoleNs) break

            val lastGood = runPoints[runPoints.size - 1]
            var estimate = lastGood.p
            val span = Diag.num((lostAt - t0) / 1e9, 1) + "s-" + Diag.num((regainAt - t0) / 1e9, 1) + "s"
            // A gap the user spent paused (up to a frame at either end) is not a loss: nothing in it
            // was walked, so the position is simply held.
            val paused = !pauses.isEmpty && regainAt - lostAt - pauses.coveredNs(lostAt, regainAt) <= maxHoleNs
            if (paused) {
                pauseCount++
                pausedNs += regainAt - lostAt
                if (pausedGaps.isNotEmpty()) pausedGaps.append(';')
                pausedGaps.append(span)
                if (trailing) {
                    pausedGaps.append(" (to end)")
                    break
                }
            } else {
                lossCount++
                lostNs += regainAt - lostAt
                var gapSteps = 0
                if (fill && ctx != null) {
                    val toNs = if (trailing) Long.MAX_VALUE else regainAt
                    val gap = solver.solveSegment(ctx, lostAt, toNs, lastGood.p, handoverHeading(ctx, imu, lastGood))
                    if (gap.isNotEmpty()) estimate = gap[gap.size - 1].p
                    points.addAll(gap)
                    gapSteps = gap.size
                    pdrPoints += gap.size
                }
                if (gaps.isNotEmpty()) gaps.append(';')
                gaps.append(span).append(':').append(gapSteps).append(" steps")
                if (trailing) {
                    gaps.append(" (to end)")
                    break
                }
                if (!fill) continue
            }

            // Re-anchor the resumed run on the estimate (the PDR end, or the held position after a
            // pause); keep ARCore's yaw unless it clearly disagrees with the IMU.
            val next = runs[i + 1].first
            var candidate = transform.anchoredAt(next.position(), estimate)
            val imuAtRegain = imu.cameraHeadingRad(next.tNs)
            if (imuAtRegain != null) {
                val resumed = candidate.cameraHeadingRad(next.orientation())
                if (abs(Angles.diff(resumed, imuAtRegain)) > yawRealignThresholdRad) {
                    candidate = candidate.realigned(next.orientation(), imuAtRegain, next.position(), estimate)
                    realigned++
                    (if (paused) pausedGaps else gaps).append(" yaw re-aligned")
                }
            }
            transform = candidate
        }
        diag["trackingLossCount"] = lossCount.toString()
        diag["trackingLostS"] = Diag.num(lostNs / 1e9, 1)
        if (gaps.isNotEmpty()) diag["trackingGaps"] = gaps.toString()
        if (!pauses.isEmpty) {
            diag["pauseCount"] = pauseCount.toString()
            diag["pausedS"] = Diag.num(pausedNs / 1e9, 1)
            if (pausedGaps.isNotEmpty()) diag["pausedGaps"] = pausedGaps.toString()
        }
        diag["reanchorYawRealigned"] = realigned.toString()
        diag["vioPoints"] = vioPoints.toString()
        diag["pdrPoints"] = pdrPoints.toString()

        val decimator = PointCloudDecimator(voxelSizeM, maxPointCloudPoints, minPointConfidence)
        for (cloud in log.pointClouds) {
            val t = transformAt(runs, transforms, cloud.tNs, maxHoleNs) ?: continue
            decimator.add(cloud.xyzc, t)
        }
        val pointCloud = decimator.result()
        diag["pointCloudRaw"] = decimator.rawCount.toString()
        diag["pointCloudConfident"] = decimator.confidentCount.toString()
        diag["pointCloudVoxels"] = decimator.voxelCount.toString()
        diag["pointCloudKept"] = pointCloud.size.toString()

        val rawPoints: List<PathPoint> = points
        var finalPoints: List<PathPoint> = points
        var closureErrorM: Double? = null
        val closures = log.annotations.filter { it.kind == AnnotationKind.LOOP_CLOSED }.map { it.tNs }
        if (config.loopClosure && closures.isNotEmpty()) {
            val closed = LoopClosure.apply(finalPoints, closures)
            if (closed != null) {
                finalPoints = closed.points
                closureErrorM = closed.closureErrorM
                diag["loopClosure"] = "applied at " + closures.size + " annotation(s)"
            } else {
                diag["loopClosure"] = "skipped: no path before the annotation"
            }
        } else if (closures.isNotEmpty()) {
            diag["loopClosure"] = "disabled"
        }
        finalPoints = smoothPiecewise(finalPoints, config.smoothingWindow, maxHoleNs)

        if (ctx != null) for ((k, v) in ctx.diagnostics) diag["pdr.$k"] = v
        val keyframes = placeKeyframes(log.keyframes, runs, transforms, maxHoleNs, rawPoints, finalPoints, diag)
        return PathBuilder.build(
            config = config,
            points = finalPoints,
            log = log,
            stepCount = ctx?.steps?.size ?: 0,
            closureErrorM = closureErrorM,
            diagnostics = diag,
            pointCloud = pointCloud,
        ).copy(keyframes = keyframes)
    }

    /**
     * Walking direction handed to PDR at a loss: PDR's own walking heading at that moment, shifted
     * by how far the IMU's camera heading has drifted from the VIO one. [lastGood] is the last VIO
     * point, whose heading is the camera heading in the aligned ARCore frame.
     */
    private fun handoverHeading(ctx: PdrContext, imu: ImuHeading, lastGood: PathPoint): Double? {
        val imuHeading = imu.cameraHeadingRad(lastGood.tNs) ?: return null
        val drift = Angles.diff(lastGood.headingRad, imuHeading)
        return Angles.wrap(ctx.walkingHeadingAt(lastGood.tNs) + drift)
    }

    /** Smooths each stretch of points that is contiguous in time; a hole longer than [maxHoleNs] is left sharp. */
    private fun smoothPiecewise(points: List<PathPoint>, window: Int, maxHoleNs: Long): List<PathPoint> {
        if (window <= 1 || points.size < 3) return points
        val out = ArrayList<PathPoint>(points.size)
        var start = 0
        for (i in 1..points.size) {
            if (i == points.size || points[i].tNs - points[i - 1].tNs > maxHoleNs) {
                out.addAll(Smoothing.movingAverage(points.subList(start, i), window))
                start = i
            }
        }
        return out
    }

    /** Transform of the tracking run nearest to [tNs], null when [tNs] is farther than [maxHoleNs] from every run. */
    private fun transformAt(
        runs: List<TrackingRun>,
        transforms: List<FrameTransform>,
        tNs: Long,
        maxHoleNs: Long,
    ): FrameTransform? {
        var best = -1
        var bestDistance = Long.MAX_VALUE
        for (i in runs.indices) {
            val d = runs[i].distanceNs(tNs)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
            if (d == 0L) break
        }
        return if (best >= 0 && bestDistance <= maxHoleNs) transforms[best] else null
    }

    /**
     * Keyframes are placed by their recorded ARCore pose when they have one and a tracking run
     * covers their time, shifted by whatever loop closure and smoothing moved the nearest path
     * point so the marker stays on the drawn line; otherwise they sit on the nearest path point.
     */
    private fun placeKeyframes(
        keyframes: List<KeyframeSample>,
        runs: List<TrackingRun>,
        transforms: List<FrameTransform>,
        maxHoleNs: Long,
        rawPoints: List<PathPoint>,
        finalPoints: List<PathPoint>,
        diag: MutableMap<String, String>,
    ): List<PathKeyframe> {
        if (keyframes.isEmpty()) return emptyList()
        var byPose = 0
        val out = ArrayList<PathKeyframe>(keyframes.size)
        for (k in keyframes) {
            val hasPose = !(k.qx == 0f && k.qy == 0f && k.qz == 0f && k.qw == 0f)
            val t = if (hasPose) transformAt(runs, transforms, k.tNs, maxHoleNs) else null
            val near = if (rawPoints.isEmpty()) -1 else PathBuilder.nearestIndex(rawPoints, k.tNs)
            if (t != null) {
                byPose++
                val shift = if (near < 0) Vec3.ZERO else finalPoints[near].p - rawPoints[near].p
                val p = t.position(Vec3.of(k.tx, k.ty, k.tz)) + shift
                val q = Quat.fromXyzw(k.qx.toDouble(), k.qy.toDouble(), k.qz.toDouble(), k.qw.toDouble())
                out.add(PathKeyframe(k.tNs, k.fileName, p, t.cameraHeadingRad(q)))
            } else if (near >= 0) {
                out.add(PathKeyframe(k.tNs, k.fileName, finalPoints[near].p, finalPoints[near].headingRad))
            } else {
                out.add(PathKeyframe(k.tNs, k.fileName, Vec3.ZERO, 0.0))
            }
        }
        diag["keyframesByPose"] = byPose.toString()
        diag["keyframesByPath"] = (keyframes.size - byPose).toString()
        return out
    }

    private fun delegate(log: RawLog, config: PipelineConfig, reason: String): PathResult {
        val result = pdr.process(log, config)
        val diag = LinkedHashMap<String, String>(result.diagnostics)
        diag["vio"] = "delegated to PDR: $reason"
        return result.copy(diagnostics = diag)
    }
}
