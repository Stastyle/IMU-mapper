package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.log.RawLog
import kotlin.math.cos
import kotlin.math.sin

/** One stretch of the trip with a fixed heading axis and offset: from the start or a REORIENT. */
class HeadingSegment(val fromNs: Long, val axis: HeadingAxis, val offsetRad: Double, val estimated: Boolean)

/**
 * Everything [PdrSolver] derives from a log once: orientation, gravity-free acceleration, the steps
 * that will be used, and per-step heading and stride. Prepare it once and solve as many segments
 * as needed (the VIO fuser fills every tracking gap from the same context).
 */
class PdrContext(
    val log: RawLog,
    val config: PipelineConfig,
    val orientation: OrientationTrack,
    val orientationSource: OrientationEstimator.Source,
    val worldAccel: WorldAccel,
    /** The steps positions are built from: software or hardware depending on the config. */
    val steps: DetectedSteps,
    val softwareStepCount: Int,
    val hardwareStepCount: Int,
    val headingSegments: List<HeadingSegment>,
    /** Walking heading of each used step (device heading + offset), radians clockwise from north. */
    val stepHeadingRad: DoubleArray,
    val stepStrideM: DoubleArray,
    val altitude: AltitudeTrack?,
    val diagnostics: Map<String, String>,
) {
    fun segmentAt(tNs: Long): HeadingSegment {
        var seg = headingSegments[0]
        for (s in headingSegments) if (s.fromNs <= tNs) seg = s else break
        return seg
    }

    /** Walking heading the solver would assign at [tNs]: device heading at that time plus the segment offset. */
    fun walkingHeadingAt(tNs: Long): Double {
        val seg = segmentAt(tNs)
        return Angles.wrap(DeviceHeading.headingRad(orientation.at(tNs), seg.axis) + seg.offsetRad)
    }
}

/** Output of [PdrSolver.solve]: the start point followed by one point per step. */
class PdrSolution(val points: List<PathPoint>, val context: PdrContext)

/**
 * Pedestrian dead reckoning. [prepare] runs orientation, step detection, stride and heading once;
 * [solveSegment] integrates any time range from a known start, which is what [PdrProcessor] uses
 * for the whole trip and what the VIO fuser uses to bridge tracking losses.
 */
class PdrSolver(
    private val orientationEstimator: OrientationEstimator = OrientationEstimator(),
    /** A gap between steps longer than this is "standing still": barometric change during it is ignored. */
    private val stillGapS: Double = 2.0,
    /** Steps used to re-estimate the heading offset after a REORIENT annotation. */
    private val reorientSteps: Int = 10,
    /** Steps before a REORIENT whose mean heading resolves the front/back ambiguity of the estimate. */
    private val reorientReferenceSteps: Int = 5,
) {

    fun prepare(log: RawLog, config: PipelineConfig): PdrContext {
        val diag = LinkedHashMap<String, String>()
        val orientation = orientationEstimator.estimate(log, config)
        diag.putAll(orientation.diagnostics)
        val world = WorldAccel.compute(log.accel, orientation.track)
        if (world.dropped > 0) diag["accelSamplesDropped"] = world.dropped.toString()

        val software = StepDetector.detect(world, config)
        val hardware = StepDetector.fromHardware(log.steps, world, config)
        val useHardware = config.preferHardwareSteps && hardware.size > 0
        val steps = if (useHardware) hardware else software
        diag["softwareSteps"] = software.size.toString()
        diag["hardwareSteps"] = hardware.size.toString()
        diag["stepsUsed"] = if (useHardware) "hardware" else "software"
        if (config.preferHardwareSteps && hardware.size == 0) {
            diag["stepsNote"] = "hardware steps preferred but none logged"
        }

        val strides = DoubleArray(steps.size) { StrideModel.strideM(config, steps.swing[it]) }
        diag["strideModel"] = if (StrideModel.usesWeinberg(config)) "weinberg" else "fixed"
        if (steps.size > 0) diag["meanStrideM"] = Diag.num(strides.sum() / steps.size, 3)

        val segments = ArrayList<HeadingSegment>()
        val headings = DoubleArray(steps.size)
        buildHeadings(log, config, orientation.track, world, steps, segments, headings, diag)

        val altitude = AltitudeTrack.fromBaro(log.baro, config.baroSmoothingS)
        diag["baro"] = if (altitude == null) "absent" else "present"
        if (altitude != null) diag["baroP0hPa"] = Diag.num(altitude.p0hPa, 2)

        return PdrContext(
            log, config, orientation.track, orientation.source, world, steps, software.size, hardware.size,
            segments, headings, strides, altitude, diag,
        )
    }

    /**
     * Dead-reckons the steps with fromNs <= t < toNs starting at [start]. Returns one point per step
     * (the start point itself is not included) with the global step index. When [startHeadingRad]
     * is given, the walking direction at [fromNs] is taken as known (for example from the last VIO
     * velocity) and the constant difference to the PDR heading at that moment is applied to every
     * step of the segment, which removes accumulated yaw drift at the hand-over.
     */
    fun solveSegment(
        ctx: PdrContext,
        fromNs: Long,
        toNs: Long,
        start: Vec3,
        startHeadingRad: Double?,
    ): List<PathPoint> {
        val steps = ctx.steps
        val first = steps.lowerBound(fromNs)
        val last = steps.lowerBound(toNs)
        val correction =
            if (startHeadingRad == null) 0.0 else Angles.diff(startHeadingRad, ctx.walkingHeadingAt(fromNs))
        val out = ArrayList<PathPoint>(maxOf(0, last - first))
        var x = start.x
        var y = start.y
        var z = start.z
        var prevNs = fromNs
        val altitude = ctx.altitude
        val hold = ctx.config.baroHoldWhenStill
        for (i in first until last) {
            val t = steps.tNs[i]
            val h = Angles.wrap(ctx.stepHeadingRad[i] + correction)
            val d = ctx.stepStrideM[i]
            x += d * sin(h)
            y += d * cos(h)
            if (altitude != null) {
                val gapS = (t - prevNs) / 1e9
                if (!hold || gapS <= stillGapS) z += altitude.at(t) - altitude.at(prevNs)
            }
            out.add(PathPoint(t, Vec3(x, y, z), PositionSource.PDR, h, i))
            prevNs = t
        }
        return out
    }

    fun solveSegment(
        log: RawLog,
        config: PipelineConfig,
        fromNs: Long,
        toNs: Long,
        start: Vec3,
        startHeadingRad: Double?,
    ): List<PathPoint> = solveSegment(prepare(log, config), fromNs, toNs, start, startHeadingRad)

    /** The whole trip from the origin: a start point (stepIndex -1) followed by one point per step. */
    fun solve(log: RawLog, config: PipelineConfig): PdrSolution {
        val ctx = prepare(log, config)
        val startNs = log.firstTimestampNs
        val steps = solveSegment(ctx, startNs, Long.MAX_VALUE, Vec3.ZERO, null)
        val startHeading = if (steps.isNotEmpty()) steps[0].headingRad else ctx.walkingHeadingAt(startNs)
        val points = ArrayList<PathPoint>(steps.size + 1)
        points.add(PathPoint(startNs, Vec3.ZERO, PositionSource.PDR, startHeading, -1))
        points.addAll(steps)
        return PdrSolution(points, ctx)
    }

    private fun buildHeadings(
        log: RawLog,
        config: PipelineConfig,
        track: OrientationTrack,
        world: WorldAccel,
        steps: DetectedSteps,
        segments: MutableList<HeadingSegment>,
        headings: DoubleArray,
        diag: MutableMap<String, String>,
    ) {
        val boundaries = ArrayList<Long>()
        boundaries.add(log.firstTimestampNs)
        for (a in log.annotations) {
            if (a.kind == AnnotationKind.REORIENT && a.tNs > boundaries[boundaries.size - 1]) boundaries.add(a.tNs)
        }
        diag["reorientCount"] = (boundaries.size - 1).toString()

        var offset = config.headingOffsetRad
        val cursor = track.cursor()
        val estimated = StringBuilder()
        for (b in boundaries.indices) {
            val fromNs = boundaries[b]
            val toNs = if (b + 1 < boundaries.size) boundaries[b + 1] else Long.MAX_VALUE
            val first = steps.lowerBound(fromNs)
            val last = steps.lowerBound(toNs)
            val axis = if (first < last) {
                DeviceHeading.chooseAxis(track, steps.tNs, first, last)
            } else {
                DeviceHeading.chooseAxis(track, longArrayOf(fromNs), 0, 1)
            }
            var wasEstimated = false
            if (b > 0 && first < last) {
                val windowEnd = minOf(last - 1, first + reorientSteps - 1)
                val deviceHeadings = DoubleArray(windowEnd - first + 1) {
                    DeviceHeading.headingRad(cursor.at(steps.tNs[first + it]), axis)
                }
                val meanDevice = Angles.circularMean(deviceHeadings, 0, deviceHeadings.size)
                val previous = Angles.circularMean(headings, maxOf(0, first - reorientReferenceSteps), first)
                if (meanDevice != null) {
                    val est = HeadingOffsetEstimator.estimate(
                        world, steps.tNs[first], steps.tNs[windowEnd] + 1, meanDevice, previous, offset,
                    )
                    if (est != null) {
                        offset = est
                        wasEstimated = true
                    }
                }
                if (estimated.isNotEmpty()) estimated.append(';')
                estimated.append(if (wasEstimated) Diag.num(Math.toDegrees(offset), 1) else "kept")
            }
            segments.add(HeadingSegment(fromNs, axis, offset, wasEstimated))
            for (i in first until last) {
                headings[i] = Angles.wrap(DeviceHeading.headingRad(cursor.at(steps.tNs[i]), axis) + offset)
            }
        }
        diag["headingAxis"] = segments.joinToString(";") { it.axis.name }
        diag["headingOffsetDeg"] = Diag.num(Math.toDegrees(config.headingOffsetRad), 1)
        if (estimated.isNotEmpty()) diag["reorientOffsetsDeg"] = estimated.toString()
    }
}
