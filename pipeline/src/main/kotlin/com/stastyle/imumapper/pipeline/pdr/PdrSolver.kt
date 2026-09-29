package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.HeadingAxisMode
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PauseIntervals
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.log.RawLog
import kotlin.math.cos
import kotlin.math.sin

/**
 * One stretch of the trip with a fixed heading axis and offset: from the start, or from the end of a
 * move of the phone (a detected carry change or a REORIENT). While the phone was being moved, from
 * [moveFromNs] to [fromNs], the walking heading is held at [moveHeadingRad], the one from just before
 * the move, and [offsetRad] continues it after the move. The first segment has no move:
 * [moveFromNs] equals [fromNs].
 */
class HeadingSegment(
    val fromNs: Long,
    val axis: HeadingAxis,
    val offsetRad: Double,
    val moveFromNs: Long = fromNs,
    val moveHeadingRad: Double = 0.0,
) {
    companion object {
        /**
         * Walking heading at [tNs] with the phone at orientation [q]: the held heading while the
         * phone was being moved, otherwise the device heading plus the offset of the segment.
         */
        fun walkingHeading(segments: List<HeadingSegment>, tNs: Long, q: Quat): Double {
            var k = 0
            while (k + 1 < segments.size && segments[k + 1].fromNs <= tNs) k++
            if (k + 1 < segments.size && tNs >= segments[k + 1].moveFromNs) return segments[k + 1].moveHeadingRad
            val seg = segments[k]
            return Angles.wrap(DeviceHeading.headingRad(q, seg.axis) + seg.offsetRad)
        }
    }
}

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
    /** Walking heading of each used step ([HeadingSegment.walkingHeading]), radians clockwise from north. */
    val stepHeadingRad: DoubleArray,
    val stepStrideM: DoubleArray,
    val altitude: AltitudeTrack?,
    /**
     * The barometric height change of each step when [PipelineConfig.baroConfirmSteps] is on; null
     * when the path follows [altitude].
     */
    val climbs: Climbs?,
    val diagnostics: Map<String, String>,
) {
    fun segmentAt(tNs: Long): HeadingSegment {
        var seg = headingSegments[0]
        for (s in headingSegments) if (s.fromNs <= tNs) seg = s else break
        return seg
    }

    /** Walking heading the solver would assign at [tNs]; see [HeadingSegment.walkingHeading]. */
    fun walkingHeadingAt(tNs: Long): Double = HeadingSegment.walkingHeading(headingSegments, tNs, orientation.at(tNs))
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
    /** Seconds just before and just after a move over which the walking heading is averaged. */
    private val moveHeadingWindowS: Double = 1.5,
    /** A REORIENT tap stands for a move of the phone up to this many seconds before or after it. */
    private val reorientMoveS: Double = 2.0,
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
        buildHeadings(log, config, orientation.track, steps, segments, headings, diag)

        val altitude = AltitudeTrack.fromBaro(log.baro, config.baroSmoothingS)
        diag["baro"] = if (altitude == null) "absent" else "present"
        if (altitude != null) diag["baroP0hPa"] = Diag.num(altitude.p0hPa, 2)
        val climbs = if (altitude != null && config.baroConfirmSteps > 0) {
            // Climbs are judged and measured on the unsmoothed height; see Climbs.
            val raw = AltitudeTrack.fromBaro(log.baro, 0.0)!!
            Climbs.detect(
                raw, steps, pauses, log.firstTimestampNs, config.baroConfirmSteps, config.baroConfirmStepM,
                maxOf(0.0, config.baroMaxHeldM),
            )
                .also { addClimbDiagnostics(it, steps, log.firstTimestampNs, diag) }
        } else {
            null
        }

        return PdrContext(
            log, config, orientation.track, orientation.source, world, steps, software.size, hardware.size,
            pauses, segments, headings, strides, altitude, climbs, diag,
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
        val climbs = ctx.climbs
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
            if (climbs != null) {
                z += climbs.changeAfter(i, fromNs)
            } else if (altitude != null) {
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

    /** A stretch [startNs, endNs) during which the phone was being moved and its heading means nothing. */
    private class Move(val startNs: Long, val endNs: Long)

    /**
     * Splits the trip into heading segments at every move of the phone and gives each step its
     * walking heading. A move is a carry change found by [CarryChangeDetector] or the stretch around
     * a REORIENT tap. The walking heading is carried through a move instead of being measured again
     * after it: the walker is taken to keep going straight while moving the phone, so the heading
     * averaged over [moveHeadingWindowS] just before the move is held for the steps during it, and
     * the new offset is the one that gives the same heading over the same time just after it. Moving
     * the phone therefore never turns the path. A turn made during the move is lost, but turns before
     * or after it are kept, standing or walking, because the orientation follows them on both sides.
     */
    private fun buildHeadings(
        log: RawLog,
        config: PipelineConfig,
        track: OrientationTrack,
        steps: DetectedSteps,
        segments: MutableList<HeadingSegment>,
        headings: DoubleArray,
        diag: MutableMap<String, String>,
    ) {
        val startNs = log.firstTimestampNs
        val changes = if (config.autoReorient) {
            CarryChangeDetector.detect(
                track, startNs, log.lastTimestampNs, config.carryChangeTiltRad, config.carryChangeSettleS,
            )
        } else {
            emptyList()
        }
        val found = ArrayList<Move>()
        for (c in changes) found.add(Move(c.startNs, c.endNs))
        var superseded = 0
        val tapNs = (reorientMoveS * 1e9).toLong()
        for (a in log.annotations) {
            if (a.kind != AnnotationKind.REORIENT) continue
            val from = maxOf(startNs, a.tNs - tapNs)
            val to = a.tNs + tapNs
            if (to <= from) continue
            // A tap next to a detected move (the natural moment to tap, just before or after moving
            // the phone) is served by that move.
            if (changes.any { from < it.endNs && to > it.startNs }) {
                superseded++
                continue
            }
            found.add(Move(from, to))
        }
        found.sortBy { it.startNs }
        // Overlapping moves (taps close together) are one move.
        val moves = ArrayList<Move>(found.size)
        for (m in found) {
            val last = moves.lastOrNull()
            if (last != null && m.startNs < last.endNs) {
                moves[moves.size - 1] = Move(last.startNs, maxOf(last.endNs, m.endNs))
            } else {
                moves.add(m)
            }
        }
        diag["reorientCount"] = moves.size.toString()
        diag["carryChanges"] = changes.size.toString()
        if (changes.isNotEmpty()) {
            diag["carryChangeTimesS"] = changes.joinToString(";") {
                Diag.num((it.startNs - startNs) / 1e9, 1) + "-" + Diag.num((it.endNs - startNs) / 1e9, 1) +
                    (if (it.returned) " returned" else "")
            }
        }
        if (superseded > 0) diag["reorientSuperseded"] = superseded.toString()

        // The calibrated offset belongs to one axis, so the first segment takes the configured one;
        // after a move the offset is derived again and the axis may be chosen freely.
        var axis = when (config.headingAxis) {
            HeadingAxisMode.FORWARD -> HeadingAxis.FORWARD
            HeadingAxisMode.CAMERA -> HeadingAxis.CAMERA
            HeadingAxisMode.AUTO -> axisFor(track, steps, startNs, moves.firstOrNull()?.startNs ?: Long.MAX_VALUE)
        }
        var offset = config.headingOffsetRad
        segments.add(HeadingSegment(startNs, axis, offset))
        val windowNs = (moveHeadingWindowS * 1e9).toLong()
        val offsets = StringBuilder()
        for (k in moves.indices) {
            val m = moves[k]
            val beforeFromNs = maxOf(segments[segments.size - 1].fromNs, m.startNs - windowNs)
            val held = Angles.wrap(DeviceHeading.meanHeadingRad(track, axis, beforeFromNs, m.startNs) + offset)
            val nextNs = if (k + 1 < moves.size) moves[k + 1].startNs else Long.MAX_VALUE
            axis = axisFor(track, steps, m.endNs, nextNs)
            val after = DeviceHeading.meanHeadingRad(track, axis, m.endNs, minOf(m.endNs + windowNs, nextNs))
            offset = Angles.diff(held, after)
            segments.add(HeadingSegment(m.endNs, axis, offset, m.startNs, held))
            if (offsets.isNotEmpty()) offsets.append(';')
            offsets.append(Diag.num(Math.toDegrees(offset), 1))
        }
        val cursor = track.cursor()
        for (i in 0 until steps.size) {
            val t = steps.tNs[i]
            headings[i] = HeadingSegment.walkingHeading(segments, t, cursor.at(t))
        }
        diag["headingAxisMode"] = config.headingAxis.name
        diag["headingAxis"] = segments.joinToString(";") { it.axis.name }
        diag["headingOffsetDeg"] = Diag.num(Math.toDegrees(config.headingOffsetRad), 1)
        if (offsets.isNotEmpty()) diag["reorientOffsetsDeg"] = offsets.toString()
    }

    /** Axis for the steps in [fromNs, toNs), or for the orientation at [fromNs] when there are none. */
    private fun axisFor(track: OrientationTrack, steps: DetectedSteps, fromNs: Long, toNs: Long): HeadingAxis {
        val first = steps.lowerBound(fromNs)
        val last = steps.lowerBound(toNs)
        return if (first < last) {
            DeviceHeading.chooseAxis(track, steps.tNs, first, last)
        } else {
            DeviceHeading.chooseAxis(track, longArrayOf(fromNs), 0, 1)
        }
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

    /**
     * baroClimbs and baroClimbsHeld count the climbs (ways back included) and the held-out runs,
     * baroHeldM adds up the sizes of those runs, baroLimitM is what the baroMaxHeldM limit let
     * through, and baroClimbTimesS gives each climb as start-end seconds, from the step before it to
     * its last step.
     */
    private fun addClimbDiagnostics(
        climbs: Climbs,
        steps: DetectedSteps,
        startNs: Long,
        diag: MutableMap<String, String>,
    ) {
        diag["baroClimbs"] = climbs.size.toString()
        diag["baroClimbsHeld"] = climbs.heldRuns.toString()
        diag["baroHeldM"] = Diag.num(climbs.heldM, 2)
        diag["baroLimitM"] = Diag.num(climbs.limitM, 2)
        if (climbs.size > 0) {
            diag["baroClimbTimesS"] = (0 until climbs.size).joinToString(";") { k ->
                Diag.num((steps.tNs[climbs.firstStep(k) - 1] - startNs) / 1e9, 1) + "-" +
                    Diag.num((steps.tNs[climbs.lastStep(k)] - startNs) / 1e9, 1)
            }
        }
    }

    companion object {
        /** Time constants after which a first-order low-pass is taken as settled (95 %). */
        const val SETTLING_TIME_CONSTANTS: Double = 3.0
    }
}
