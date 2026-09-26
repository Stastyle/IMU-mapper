package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.HeadingAxisMode
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PauseIntervals
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
    /** The steps positions are built from: software or hardware depending on the config, none inside a pause. */
    val steps: DetectedSteps,
    val softwareStepCount: Int,
    val hardwareStepCount: Int,
    /** The recorder's [PAUSE, RESUME) intervals: no step and no barometric change is taken from them. */
    val pauses: PauseIntervals,
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
        // The hardware detector is the fallback when the software one has nothing to work with
        // (accelerometer absent or stalled): an empty path is never the better answer.
        val useHardware = (config.preferHardwareSteps || software.size == 0) && hardware.size > 0
        val detected = if (useHardware) hardware else software
        diag["softwareSteps"] = software.size.toString()
        diag["hardwareSteps"] = hardware.size.toString()
        diag["stepsUsed"] = if (useHardware) "hardware" else "software"
        when {
            config.preferHardwareSteps && hardware.size == 0 ->
                diag["stepsNote"] = "hardware steps preferred but none logged"
            useHardware && !config.preferHardwareSteps ->
                diag["stepsNote"] = if (log.accel.isEmpty()) {
                    "no accelerometer samples; hardware steps used"
                } else {
                    "software detector found no steps; hardware steps used"
                }
        }

        val pauses = PauseIntervals.of(log.events)
        val steps = excludePaused(detected, pauses)
        if (!pauses.isEmpty) {
            diag["pauses"] = pauses.size.toString()
            diag["pausedSteps"] = (detected.size - steps.size).toString()
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
            pauses, segments, headings, strides, altitude, diag,
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
        val pauses = ctx.pauses
        val hold = ctx.config.baroHoldWhenStill
        val stillGapNs = (ctx.config.baroStillGapS * 1e9).toLong()
        val settleNs = (SETTLING_TIME_CONSTANTS * ctx.config.baroSmoothingS * 1e9).toLong()
        for (i in first until last) {
            val t = steps.tNs[i]
            val h = Angles.wrap(ctx.stepHeadingRad[i] + correction)
            val d = ctx.stepStrideM[i]
            x += d * sin(h)
            y += d * cos(h)
            if (altitude != null) {
                if (!hold || t - prevNs <= stillGapNs) {
                    z += altitudeDelta(altitude, pauses, prevNs, t)
                } else {
                    // Standing still: freeze only the middle of the gap. The low-passed barometer is
                    // still catching up with the last steps right after them, and the climb of this
                    // step has begun before its peak, so both ends of the gap are kept.
                    val tailEnd = minOf(t, prevNs + settleNs)
                    val headStart = maxOf(t - settleNs, tailEnd)
                    z += altitudeDelta(altitude, pauses, prevNs, tailEnd)
                    z += altitudeDelta(altitude, pauses, headStart, t)
                }
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
        val changes = if (config.autoReorient) {
            CarryChangeDetector.detect(
                track, log.firstTimestampNs, log.lastTimestampNs, config.carryChangeTiltRad, config.carryChangeSettleS,
            )
        } else {
            emptyList()
        }
        val boundaries = ArrayList<Long>()
        boundaries.add(log.firstTimestampNs)
        var superseded = 0
        val marginNs = (config.carryChangeSettleS * 1e9).toLong()
        for (a in log.annotations) {
            if (a.kind != AnnotationKind.REORIENT) continue
            // A tap during the move itself (the natural moment to tap, and the tap tends to come a
            // little before the tilt has visibly left) is served by the detected change: its own
            // boundary would start a segment on steps taken while the phone moved.
            if (changes.any { a.tNs >= it.startNs - marginNs && a.tNs < it.endNs }) {
                superseded++
                continue
            }
            boundaries.add(a.tNs)
        }
        // A change that settled back where it started keeps the offset: it is bridged, not a new segment.
        for (c in changes) if (!c.returned) boundaries.add(c.endNs)
        boundaries.sort()
        var w = 1
        for (i in 1 until boundaries.size) {
            if (boundaries[i] > boundaries[w - 1]) boundaries[w++] = boundaries[i]
        }
        while (boundaries.size > w) boundaries.removeAt(boundaries.size - 1)
        diag["reorientCount"] = (boundaries.size - 1).toString()
        diag["carryChanges"] = changes.size.toString()
        if (changes.isNotEmpty()) {
            val t0 = log.firstTimestampNs
            diag["carryChangeTimesS"] = changes.joinToString(";") {
                Diag.num((it.startNs - t0) / 1e9, 1) + "-" + Diag.num((it.endNs - t0) / 1e9, 1) +
                    (if (it.returned) " returned" else "")
            }
        }
        if (superseded > 0) diag["reorientSuperseded"] = superseded.toString()

        var offset = config.headingOffsetRad
        val cursor = track.cursor()
        val estimated = StringBuilder()
        for (b in boundaries.indices) {
            val fromNs = boundaries[b]
            val toNs = if (b + 1 < boundaries.size) boundaries[b + 1] else Long.MAX_VALUE
            val first = steps.lowerBound(fromNs)
            val last = steps.lowerBound(toNs)
            // The calibrated offset belongs to one axis, so the first segment takes the configured
            // one; after a REORIENT the offset is re-estimated and the axis may be chosen freely.
            val axis = when {
                b == 0 && config.headingAxis == HeadingAxisMode.FORWARD -> HeadingAxis.FORWARD
                b == 0 && config.headingAxis == HeadingAxisMode.CAMERA -> HeadingAxis.CAMERA
                first < last -> DeviceHeading.chooseAxis(track, steps.tNs, first, last)
                else -> DeviceHeading.chooseAxis(track, longArrayOf(fromNs), 0, 1)
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
            // Steps taken while the phone was being moved get the heading from just before the move:
            // the device heading means nothing mid-move, and the walker was told to keep going straight.
            // Done before the next segment reads these headings as its reference for the front/back choice.
            for (c in changes) {
                if (c.endNs <= fromNs || c.endNs > toNs) continue
                holdHeadings(steps, headings, c.startNs, c.endNs)
            }
        }
        diag["headingAxisMode"] = config.headingAxis.name
        diag["headingAxis"] = segments.joinToString(";") { it.axis.name }
        diag["headingOffsetDeg"] = Diag.num(Math.toDegrees(config.headingOffsetRad), 1)
        if (estimated.isNotEmpty()) diag["reorientOffsetsDeg"] = estimated.toString()
    }

    /** Gives the steps inside [fromNs, toNs) the heading of the last step before it; a move before any step is left alone. */
    private fun holdHeadings(steps: DetectedSteps, headings: DoubleArray, fromNs: Long, toNs: Long) {
        val first = steps.lowerBound(fromNs)
        val last = steps.lowerBound(toNs)
        if (first == 0 || first >= last) return
        for (i in first until last) headings[i] = headings[first - 1]
    }

    private fun excludePaused(steps: DetectedSteps, pauses: PauseIntervals): DetectedSteps {
        if (pauses.isEmpty || steps.size == 0) return steps
        val times = LongArray(steps.size)
        val swings = DoubleArray(steps.size)
        var k = 0
        for (i in 0 until steps.size) {
            if (pauses.contains(steps.tNs[i])) continue
            times[k] = steps.tNs[i]
            swings[k] = steps.swing[i]
            k++
        }
        return if (k == steps.size) steps else DetectedSteps(times.copyOf(k), swings.copyOf(k))
    }

    /** Barometric altitude change over [fromNs, toNs] with the paused parts left out. */
    private fun altitudeDelta(altitude: AltitudeTrack, pauses: PauseIntervals, fromNs: Long, toNs: Long): Double {
        if (toNs <= fromNs) return 0.0
        if (pauses.isEmpty) return altitude.at(toNs) - altitude.at(fromNs)
        var delta = 0.0
        pauses.forEachUnpaused(fromNs, toNs) { a, b -> delta += altitude.at(b) - altitude.at(a) }
        return delta
    }

    companion object {
        /** Time constants after which a first-order low-pass is taken as settled (95 %). */
        const val SETTLING_TIME_CONSTANTS: Double = 3.0
    }
}
