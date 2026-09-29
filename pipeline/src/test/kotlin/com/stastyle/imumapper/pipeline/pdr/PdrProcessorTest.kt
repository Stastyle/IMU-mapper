package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.AccelSample
import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.HeadingAxisMode
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.log.RawLog
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdrProcessorTest {

    private fun walk(): SyntheticWalk = SyntheticWalk()

    private fun config(walk: SyntheticWalk, tweak: (PipelineConfig) -> PipelineConfig = { it }): PipelineConfig =
        tweak(PipelineConfig(strideLengthM = walk.strideM))

    private fun run(walk: SyntheticWalk, config: PipelineConfig = config(walk)): PathResult =
        PdrProcessor().process(walk.build(config), config)

    private fun assertWithin(expected: Double, actual: Double, fraction: Double, what: String) {
        assertTrue(
            abs(actual - expected) <= fraction * abs(expected),
            "$what: expected $expected +- ${fraction * 100}% but was $actual",
        )
    }

    @Test
    fun straightWalkDistanceWithinTenPercent() {
        val w = walk().still(3.0).walkTo(0.0, 20.0).still(2.0)
        val r = run(w)
        assertWithin(20.0, r.stats.distanceM, 0.10, "distance")
        val end = r.points.last().p
        assertWithin(20.0, end.y, 0.10, "north displacement")
        assertTrue(abs(end.x) < 1.5, "path should stay on the north axis, x = ${end.x}")
        assertEquals(r.stats.stepCount + 1, r.points.size)
        assertEquals(-1, r.points[0].stepIndex)
        assertEquals(0, r.points[1].stepIndex)
        assertEquals("GAME", r.diagnostics["orientationSource"])
        assertNull(r.stats.closureErrorM)
    }

    @Test
    fun squareWithLoopClosureEndsAtOrigin() {
        // A stride that is 4 % too long and a slow game-vector yaw drift give a visible closure error.
        val w = SyntheticWalk(gameYawDriftRadPerS = 0.003)
            .still(2.0).walkTo(0.0, 5.0).walkTo(5.0, 5.0).walkTo(5.0, 0.0).walkTo(0.0, 0.0)
            .annotate(AnnotationKind.LOOP_CLOSED, "back").still(1.0)
        val cfg = PipelineConfig(strideLengthM = w.strideM * 1.04, useMagnetometer = false)
        val r = PdrProcessor().process(w.build(cfg), cfg)
        val err = assertNotNull(r.stats.closureErrorM)
        assertTrue(err > 0.2 && err < 3.0, "closure error before correction should be visible but sane: $err")
        val end = r.points.last().p
        assertTrue(end.length < 0.3, "path should end at the origin after closure, ended at $end")
        assertWithin(20.0, r.stats.distanceM, 0.15, "distance")
        assertEquals(1, r.annotations.size)
        assertTrue(r.annotations[0].p.length < 0.3)
        assertEquals("0", r.diagnostics["carryChanges"], "four corners are turns, not carry changes")

        val open = PdrProcessor().process(w.build(cfg), cfg.copy(loopClosure = false))
        assertNull(open.stats.closureErrorM)
        assertTrue(open.points.last().p.length > 0.2, "without closure the error remains")
    }

    @Test
    fun rawPointsKeepThePathBeforeClosureAndSmoothing() {
        val w = SyntheticWalk(gameYawDriftRadPerS = 0.003)
            .still(2.0).walkTo(0.0, 5.0).walkTo(5.0, 5.0).walkTo(5.0, 0.0).walkTo(0.0, 0.0)
            .annotate(AnnotationKind.LOOP_CLOSED, "back").still(1.0)
        val cfg = PipelineConfig(strideLengthM = w.strideM * 1.04, useMagnetometer = false)
        val log = w.build(cfg)
        val r = PdrProcessor().process(log, cfg)
        assertEquals(6, r.pipelineVersion)
        assertEquals(r.points.size, r.rawPoints.size, "post-processing moves points, never adds or drops them")
        for (i in r.points.indices) {
            assertEquals(r.points[i].tNs, r.rawPoints[i].tNs)
            assertEquals(r.points[i].stepIndex, r.rawPoints[i].stepIndex)
            assertEquals(r.points[i].headingRad, r.rawPoints[i].headingRad)
        }
        // The raw end is where dead reckoning left it: the closure error the stats report.
        val err = assertNotNull(r.stats.closureErrorM)
        assertTrue(abs(r.rawPoints.last().p.length - err) < 0.05, "raw end ${r.rawPoints.last().p} vs closure $err")
        assertTrue(r.points.last().p.length < 0.3, "the final path is closed")

        // The raw path is exactly what the pipeline produces with both corrections switched off.
        val plain = PdrProcessor().process(log, cfg.copy(loopClosure = false, smoothingWindow = 1))
        assertTrue(plain.rawPoints.isEmpty(), "nothing was moved, so nothing is stored twice")
        assertEquals(plain.points.map { it.p }, r.rawPoints.map { it.p })
    }

    @Test
    fun stairsShowInAltitude() {
        val w = walk().still(2.0).walkTo(0.0, 8.0).walkTo(0.0, 12.0, 2.5).walkTo(0.0, 20.0, 2.5).still(2.0)
        val r = run(w)
        assertWithin(2.5, r.stats.maxZ - r.stats.minZ, 0.20, "vertical range")
        assertTrue(r.points.last().p.z > 2.0, "the walker should end up on the upper floor")
        assertEquals("present", r.diagnostics["baro"])

        val flat = run(SyntheticWalk(includeBaro = false).still(2.0).walkTo(0.0, 8.0).walkTo(0.0, 12.0, 2.5))
        assertEquals(0.0, flat.stats.maxZ)
        assertEquals(0.0, flat.stats.minZ)
    }

    @Test
    fun stillPeriodProducesNoSteps() {
        val r = run(walk().still(10.0))
        assertEquals(0, r.stats.stepCount)
        assertEquals(1, r.points.size)
        assertEquals(Vec3.ZERO, r.points[0].p)

        val w = walk().still(3.0).walkTo(0.0, 10.0).still(6.0).walkTo(0.0, 20.0).still(2.0)
        val pause = run(w)
        assertWithin(20.0 / w.strideM, pause.stats.stepCount.toDouble(), 0.10, "step count with a pause")
        assertWithin(20.0, pause.stats.distanceM, 0.10, "distance with a pause")
    }

    @Test
    fun reorientCarriesTheHeadingAcrossTheGripChange() {
        // The phone is spun flat as the REORIENT is tapped, a move the tilt cannot see.
        val w = walk().still(2.0).walkTo(0.0, 10.0)
            .annotate(AnnotationKind.REORIENT).deviceOffset(PI / 2).walkTo(0.0, 20.0).still(1.0)
        val r = run(w)
        val end = r.points.last().p
        assertTrue(end.y > 19.0 && abs(end.x) < 0.5, "the heading should carry across the tap, path ended at $end")
        assertEquals("1", r.diagnostics["reorientCount"])
        assertNotNull(r.diagnostics["reorientOffsetsDeg"])

        // Control: the same carry change without the annotation turns the path by 90 degrees.
        val c = walk().still(2.0).walkTo(0.0, 10.0).deviceOffset(PI / 2).walkTo(0.0, 20.0).still(1.0)
        val control = run(c)
        assertTrue(abs(control.points.last().p.x) > 6.0, "control should veer off: ${control.points.last().p}")
    }

    @Test
    fun carryChangeIsDetectedAndReoriented() {
        // Hand to pocket at 8 m: the phone goes near vertical and turns 90 degrees relative to the walk,
        // and nobody taps anything.
        val w = walk().still(2.0).walkTo(0.0, 8.0).deviceTilt(1.3).deviceOffset(PI / 2).walkTo(0.0, 20.0).still(1.0)
        val r = run(w)
        val end = r.points.last().p
        assertTrue(end.y > 16.0 && abs(end.x) < 3.0, "path should stay north after the move, ended at $end")
        assertEquals("1", r.diagnostics["carryChanges"])
        assertEquals("1", r.diagnostics["reorientCount"])
        assertNotNull(r.diagnostics["carryChangeTimesS"])
        assertNotNull(r.diagnostics["reorientOffsetsDeg"])
        assertWithin(20.0, r.stats.distanceM, 0.10, "distance")

        // Control: with detection off the offset from the hand stays on and the path veers.
        val off = run(w, config(w) { it.copy(autoReorient = false) })
        assertTrue(abs(off.points.last().p.x) > 6.0, "without detection the path veers: ${off.points.last().p}")
        assertEquals("0", off.diagnostics["carryChanges"])

        // A REORIENT tapped as the move begins is served by the detected change, not by its own segment.
        val tapped = walk().still(2.0).walkTo(0.0, 8.0).annotate(AnnotationKind.REORIENT)
            .deviceTilt(1.3).deviceOffset(PI / 2).walkTo(0.0, 20.0).still(1.0)
        val t = run(tapped)
        assertEquals("1", t.diagnostics["reorientSuperseded"])
        assertEquals("1", t.diagnostics["reorientCount"])
        val tEnd = t.points.last().p
        assertTrue(tEnd.y > 16.0 && abs(tEnd.x) < 3.0, "tapped move ended at $tEnd")
    }

    @Test
    fun carryChangeAcrossACornerKeepsBothLegs() {
        // Pocket the phone on the first leg, then turn east: the re-estimated offset must survive the turn.
        val w = walk().still(2.0).walkTo(0.0, 6.0).deviceTilt(1.3).deviceOffset(PI / 2)
            .walkTo(0.0, 14.0).walkTo(10.0, 14.0).still(1.0)
        val r = run(w)
        val end = r.points.last().p
        assertTrue(end.distanceTo(Vec3(10.0, 14.0, 0.0)) < 3.0, "ended at $end")
        assertEquals("1", r.diagnostics["carryChanges"])
    }

    /** Walking heading the solver gives each step, degrees. */
    private fun stepHeadingsDeg(w: SyntheticWalk, cfg: PipelineConfig = config(w)): List<Double> =
        PdrSolver().prepare(w.build(cfg), cfg).stepHeadingRad.map { Math.toDegrees(it) }

    private val pocketSkew = Math.toRadians(20.0)

    @Test
    fun pocketingThePhoneLeavesNoTurn() {
        // In a trouser pocket the leg swing turns the main axis of the horizontal acceleration away
        // from the walk, 20 degrees here. The walk is straight, so the path must not turn where the
        // phone went into the pocket, whatever the acceleration says.
        val w = walk().still(2.0).walkTo(0.0, 8.0).deviceTilt(1.3).deviceOffset(PI / 2).gaitSkew(pocketSkew)
            .walkTo(0.0, 20.0).still(1.0)
        val headings = stepHeadingsDeg(w)
        assertTrue(headings.all { abs(it) < 4.0 }, "every step should head north: $headings")
        val end = run(w).points.last().p
        assertTrue(abs(end.x) < 0.5 && end.y > 19.0, "ended at $end")
    }

    @Test
    fun pocketingAsTheWalkStartsLeavesNoTurn() {
        // Start recording in the hand and slip the phone into the pocket with the first steps. Those
        // steps have no earlier step to take a heading from; they take the one the phone showed in
        // the hand before the move.
        val w = walk().still(1.0).deviceTilt(1.3).deviceOffset(PI / 2).gaitSkew(pocketSkew)
            .walkTo(0.0, 20.0).still(1.0)
        val headings = stepHeadingsDeg(w)
        assertEquals("1", run(w).diagnostics["carryChanges"])
        assertTrue(headings.all { abs(it) < 4.0 }, "the path should start straight: $headings")
    }

    @Test
    fun phoneTakenOutAndPutBackWhileWalkingKeepsTheWalkStraight() {
        // Out of the pocket for five metres in the hand, then back, walking all the time.
        val w = SyntheticWalk(tiltRad = 1.3).deviceOffset(PI / 2).gaitSkew(pocketSkew)
            .still(2.0).walkTo(0.0, 8.0)
            .deviceTilt(0.4).deviceOffset(0.0).gaitSkew(0.0).walkTo(0.0, 13.0)
            .deviceTilt(1.3).deviceOffset(PI / 2).gaitSkew(pocketSkew).walkTo(0.0, 24.0).still(1.0)
        val cfg = config(w) { it.copy(headingOffsetRad = PI / 2) }
        val r = run(w, cfg)
        assertEquals("2", r.diagnostics["carryChanges"])
        val headings = stepHeadingsDeg(w, cfg)
        assertTrue(headings.all { abs(it) < 4.0 }, "every step should head north: $headings")
        val end = r.points.last().p
        assertTrue(abs(end.x) < 0.5 && end.y > 23.0, "ended at $end")
    }

    @Test
    fun phonePutBackTheOtherWayRoundKeepsTheWalkStraight() {
        // A quick glance while walking, and the phone goes back screen out instead of screen in: the
        // same tilt, so the move counts as returned, but the phone has turned half a circle.
        val w = SyntheticWalk(tiltRad = 1.3).deviceOffset(PI / 2)
            .still(2.0).walkTo(0.0, 8.0)
            .deviceTilt(0.4).deviceOffset(0.0).walkTo(0.0, 10.0)
            .deviceTilt(1.3).deviceOffset(-PI / 2).walkTo(0.0, 20.0).still(1.0)
        val r = run(w, config(w) { it.copy(headingOffsetRad = PI / 2) })
        assertEquals("1", r.diagnostics["carryChanges"])
        assertTrue(r.diagnostics["carryChangeTimesS"]!!.endsWith(" returned"), r.diagnostics["carryChangeTimesS"])
        val end = r.points.last().p
        assertTrue(abs(end.x) < 0.5 && end.y > 19.0, "the walk should not turn back, ended at $end")
    }

    @Test
    fun turnMadeWithThePhoneInTheHandIsKept() {
        // Stop at a junction, take the phone out, turn right while holding it, put it back and walk
        // on. Both moves carry the heading across, and the turn between them happens in the hand,
        // where the orientation follows it.
        val w = SyntheticWalk(tiltRad = 1.3).deviceOffset(PI / 2).gaitSkew(pocketSkew)
            .still(2.0).walkTo(0.0, 10.0).still(1.0)
            .deviceTilt(0.4).deviceOffset(0.0).gaitSkew(0.0).still(3.0).turnTo(PI / 2).still(2.0)
            .deviceTilt(1.3).deviceOffset(PI / 2).gaitSkew(pocketSkew).still(3.0)
            .walkTo(10.0, 10.0).still(1.0)
        val r = run(w, config(w) { it.copy(headingOffsetRad = PI / 2) })
        assertEquals("2", r.diagnostics["carryChanges"])
        val end = r.points.last().p
        assertTrue(end.distanceTo(Vec3(10.0, 10.0, 0.0)) < 1.0, "ended at $end")
    }

    @Test
    fun walkingHeadingDuringAMoveIsTheHeldOne() {
        val w = walk().still(2.0).walkTo(0.0, 8.0).deviceTilt(1.3).deviceOffset(PI / 2).walkTo(0.0, 20.0).still(1.0)
        val cfg = config(w)
        val ctx = PdrSolver().prepare(w.build(cfg), cfg)
        assertEquals(2, ctx.headingSegments.size)
        val seg = ctx.headingSegments[1]
        assertTrue(seg.moveFromNs < seg.fromNs)
        // What the VIO hand-over reads mid-move is what the steps there were given.
        assertEquals(seg.moveHeadingRad, ctx.walkingHeadingAt((seg.moveFromNs + seg.fromNs) / 2))
        assertTrue(abs(seg.moveHeadingRad) < Math.toRadians(3.0), "held ${Math.toDegrees(seg.moveHeadingRad)}")
        val after = ctx.walkingHeadingAt(seg.fromNs + 1_000_000_000L)
        assertTrue(abs(Angles.diff(after, seg.moveHeadingRad)) < Math.toRadians(4.0), "after ${Math.toDegrees(after)}")
    }

    @Test
    fun madgwickFallbackWithoutRotationSamples() {
        val w = SyntheticWalk(includeRotation = false, gyroBias = Vec3(0.01, -0.02, 0.015))
            .still(3.0).walkTo(0.0, 20.0).still(2.0)
        val cfg = PipelineConfig(strideLengthM = w.strideM, gyroBias = Vec3(0.01, -0.02, 0.015))
        val r = PdrProcessor().process(w.build(cfg), cfg)
        assertEquals("MADGWICK", r.diagnostics["orientationSource"])
        assertWithin(20.0, r.stats.distanceM, 0.10, "distance")
        // Yaw is arbitrary without a rotation vector, but the walk must still be straight.
        assertTrue(r.points.last().p.length > 0.85 * r.stats.distanceM, "path should be straight")
    }

    @Test
    fun deterministic() {
        val w = SyntheticWalk(gameYawDriftRadPerS = 0.002).still(2.0).walkTo(0.0, 5.0).walkTo(5.0, 5.0)
            .annotate(AnnotationKind.LOOP_CLOSED).walkTo(5.0, 0.0).walkTo(0.0, 0.0).still(1.0)
        val cfg = config(w) { it.copy(weinbergK = 0.45) }
        val log: RawLog = w.build(cfg)
        val a = PdrProcessor().process(log, cfg)
        val b = PdrProcessor().process(log, cfg)
        assertEquals(a, b)
        assertEquals(a.toJson(), b.toJson())
    }

    @Test
    fun hardwareStepsUsedWhenPreferred() {
        val w = walk().still(2.0).walkTo(0.0, 12.0).still(1.0)
        val hw = run(w, config(w) { it.copy(preferHardwareSteps = true) })
        assertEquals("hardware", hw.diagnostics["stepsUsed"])
        assertEquals(hw.diagnostics["hardwareSteps"], hw.stats.stepCount.toString())
        assertWithin(12.0, hw.stats.distanceM, 0.10, "distance from hardware steps")
        val sw = run(w)
        assertEquals("software", sw.diagnostics["stepsUsed"])
        assertWithin(hw.stats.stepCount.toDouble(), sw.stats.stepCount.toDouble(), 0.10, "software vs hardware count")
    }

    @Test
    fun weinbergStrideScalesWithSwing() {
        val w = walk().still(2.0).walkTo(0.0, 20.0).still(1.0)
        // k chosen so that the synthetic swing (about 2 * bounce) reproduces the true stride.
        val k = w.strideM / Math.pow(2.0 * w.verticalBounce, 0.25)
        val r = run(w, config(w) { it.copy(weinbergK = k) })
        assertEquals("weinberg", r.diagnostics["strideModel"])
        assertWithin(20.0, r.stats.distanceM, 0.15, "weinberg distance")
    }

    @Test
    fun magnetometerCorrectsGameYawDrift() {
        val w = SyntheticWalk(gameYawDriftRadPerS = 0.02).still(2.0).walkTo(0.0, 20.0).still(1.0)
        val drifting = run(w, config(w) { it.copy(useMagnetometer = false) })
        val corrected = run(w, config(w) { it.copy(useMagnetometer = true) })
        assertEquals("applied", corrected.diagnostics["yawCorrection"])
        val xDrift = abs(drifting.points.last().p.x)
        val xCorrected = abs(corrected.points.last().p.x)
        assertTrue(xDrift > 2.0, "uncorrected drift should bend the path: $xDrift")
        assertTrue(xCorrected < xDrift / 2.0, "correction should at least halve the drift: $xCorrected vs $xDrift")
    }

    @Test
    fun segmentApiMatchesFullSolution() {
        val w = walk().still(2.0).walkTo(0.0, 10.0).walkTo(6.0, 10.0).still(1.0)
        val cfg = config(w)
        val log = w.build(cfg)
        val solver = PdrSolver()
        val full = solver.solve(log, cfg)
        val ctx = solver.prepare(log, cfg)
        val midIndex = full.points.size / 2
        val mid = full.points[midIndex]
        val tail = solver.solveSegment(ctx, mid.tNs + 1, Long.MAX_VALUE, mid.p, null)
        assertEquals(full.points.size - midIndex - 1, tail.size)
        assertTrue(full.points.last().p.distanceTo(tail.last().p) < 1e-6, "segment end should match the full path")
        assertEquals(full.points.last().stepIndex, tail.last().stepIndex)

        // A known start heading rotates the segment so its first step follows that heading.
        val turned = solver.solveSegment(ctx, mid.tNs + 1, Long.MAX_VALUE, Vec3.ZERO, PI)
        assertTrue(abs(Angles.diff(turned[0].headingRad, PI)) < 1e-9)
    }

    @Test
    fun stopAndGoStairsKeepTheClimb() {
        // Four 1 m flights with a 6 s stop after each: the stops are "still" gaps, yet the climb of
        // the last steps (still settling in the barometer filter) and of the first step after each
        // stop must not be thrown away with the middle of the gap. The hold works on the low-passed
        // height, so the climb filter is off for it; the filter must keep the climb too.
        val w = walk().still(2.0)
            .walkTo(0.0, 4.0, 1.0).still(6.0).walkTo(0.0, 8.0, 2.0).still(6.0)
            .walkTo(0.0, 12.0, 3.0).still(6.0).walkTo(0.0, 16.0, 4.0).still(6.0)
        val held = run(w, config(w) { it.copy(baroConfirmSteps = 0) })
        assertWithin(4.0, held.stats.maxZ, 0.20, "climb with stops and hold")
        val free = run(w, config(w) { it.copy(baroConfirmSteps = 0, baroHoldWhenStill = false) })
        assertWithin(4.0, free.stats.maxZ, 0.20, "climb with stops without hold")
        // The rise after the last step, before the recording ends, is not in the path.
        assertWithin(4.0, run(w).stats.maxZ, 0.10, "climb with stops through the climb filter")

        // A slow walker (0.45 Hz cadence, 2.2 s between steps) is not standing still.
        val slow = SyntheticWalk(speedMps = 0.5, cadenceHz = 0.45).still(2.0).walkTo(0.0, 10.0, 2.0).still(2.0)
        val r = run(slow)
        assertTrue(r.stats.stepCount >= 5, "steps at a slow cadence: ${r.stats.stepCount}")
        assertWithin(2.0, r.stats.maxZ, 0.30, "climb at a slow cadence")
    }

    @Test
    fun longStillHoldsAltitudeAgainstPressureDrift() {
        // Pressure drifting 0.005 hPa/s reads as a 4 cm/s descent. Over a 30 s stop the hold freezes
        // the middle of the gap, so far less of the drift reaches the path than without it. The climb
        // filter is off: it would hold most of the drift out too, and this is about the hold.
        val w = SyntheticWalk(baroDriftHpaPerS = 0.005)
            .still(2.0).walkTo(0.0, 8.0).still(30.0).walkTo(0.0, 16.0).still(1.0)
        val base = config(w) { it.copy(baroConfirmSteps = 0) }
        val held = run(w, base).points.last().p.z
        val free = run(w, base.copy(baroHoldWhenStill = false)).points.last().p.z
        assertTrue(free < -1.2, "without the hold the drift shows: $free")
        assertTrue(held > free + 0.7, "the hold should remove most of the drift: held $held vs free $free")

        // A shorter gap limit freezes more of it.
        val tight = run(w, base.copy(baroStillGapS = 1.0)).points.last().p.z
        assertTrue(tight >= held - 1e-9, "tight $tight vs held $held")
    }

    @Test
    fun pressureZonesOnFlatGroundAreHeldOut() {
        // One floor through rooms held at other pressures: 10 Pa lower (0.84 m "up") from 15 s to
        // 30 s, 6 Pa higher (0.5 m "down") from 35 s to 45 s, and a 2 s door pulse on top.
        val zones = { s: Double ->
            val zone = when (s) {
                in 15.0..30.0 -> -0.10
                in 35.0..45.0 -> 0.06
                else -> 0.0
            }
            zone + if (s in 20.0..22.0) -0.05 else 0.0
        }
        val w = SyntheticWalk(baroDisturbanceHpa = zones).still(2.0).walkTo(0.0, 60.0).still(2.0)
        val r = run(w)
        assertTrue(r.stats.maxZ < 0.1 && r.stats.minZ > -0.1, "flat floor: z from ${r.stats.minZ} to ${r.stats.maxZ}")
        assertEquals("0", r.diagnostics["baroClimbs"])
        assertNull(r.diagnostics["baroClimbTimesS"])
        // Each edge of a zone and of the pulse is a held run of about its size: 6 edges, 3.5 m.
        val held = r.diagnostics["baroClimbsHeld"]!!.toInt()
        assertTrue(held in 6..8, "held runs: $held")
        assertWithin(3.52, r.diagnostics["baroHeldM"]!!.toDouble(), 0.15, "height held out")

        // Barometer noise alone holds out nothing.
        val quiet = run(walk().still(2.0).walkTo(0.0, 60.0).still(2.0))
        assertEquals("0", quiet.diagnostics["baroClimbsHeld"])

        val every = run(w, config(w) { it.copy(baroConfirmSteps = 0) })
        assertTrue(
            every.stats.maxZ > 0.7 && every.stats.minZ < -0.4,
            "without the filter the zones show: z from ${every.stats.minZ} to ${every.stats.maxZ}",
        )
        assertNull(every.diagnostics["baroClimbs"])
    }

    @Test
    fun climbFilterKeepsStairsAndSteepRamps() {
        // A flight of 18 steps rising 3 m, with level walking on both sides.
        val stairs = walk().still(2.0).walkTo(0.0, 5.0).walkTo(0.0, 17.0, 3.0).walkTo(0.0, 22.0, 3.0).still(2.0)
        val r = run(stairs)
        assertWithin(3.0, r.points.last().p.z, 0.05, "flight of stairs")
        assertEquals("1", r.diagnostics["baroClimbs"])

        val ramp = walk().still(2.0).walkTo(0.0, 5.0).walkTo(0.0, 35.0, 3.6).walkTo(0.0, 40.0, 3.6).still(2.0)
        assertWithin(3.6, run(ramp).points.last().p.z, 0.05, "12 % ramp")

        // Four steps up: the phone rises through about six step intervals, so the default keeps them,
        // while a longer confirmation holds them out.
        val w = walk()
        val four = w.still(2.0).walkTo(0.0, 5.0).walkTo(0.0, 5.0 + 4 * w.strideM, 0.68)
            .walkTo(0.0, 10.0 + 4 * w.strideM, 0.68).still(2.0)
        assertWithin(0.68, run(four).points.last().p.z, 0.10, "four steps up")
        val strict = run(four, config(four) { it.copy(baroConfirmSteps = 10) }).points.last().p.z
        assertTrue(abs(strict) < 0.05, "four steps are fewer than 10: $strict")
    }

    @Test
    fun zoneRightAfterAFlightIsHeldOut() {
        // A stairwell door: 1 s after the last step of an 18-step flight up 3 m, the corridor is
        // 10 Pa higher (0.84 m "down"). The flight is kept, the zone is not.
        val stairs = walk().still(2.0).walkTo(0.0, 5.0).walkTo(0.0, 17.0, 3.0).walkTo(0.0, 30.0, 3.0).still(2.0)
        val flightEndS = 2.0 + 17.0 / 1.2
        val w = SyntheticWalk(baroDisturbanceHpa = { s -> if (s > flightEndS + 1.0) 0.10 else 0.0 })
            .still(2.0).walkTo(0.0, 5.0).walkTo(0.0, 17.0, 3.0).walkTo(0.0, 30.0, 3.0).still(2.0)
        assertWithin(3.0, run(w).points.last().p.z, 0.05, "flight with a zone after it")
        assertWithin(3.0, run(stairs).points.last().p.z, 0.05, "flight alone")
        val every = run(w, config(w) { it.copy(baroConfirmSteps = 0) }).points.last().p.z
        assertTrue(every < 2.4, "without the filter the zone shows: $every")
    }

    @Test
    fun wobbleOnFlatGroundStaysBounded() {
        // Wind on a building: 4 Pa (0.34 m) of wobble with a 5 s period, whose swings last about as
        // many steps as a climb needs. Some swings pass as climbs; the way back must pass with them,
        // or the path climbs further with every swing.
        val w = SyntheticWalk(baroDisturbanceHpa = { s -> 0.04 * sin(2 * PI * s / 5.0) })
            .still(2.0).walkTo(0.0, 120.0).still(2.0)
        val r = run(w)
        assertTrue(r.stats.maxZ < 0.8 && r.stats.minZ > -0.8, "z from ${r.stats.minZ} to ${r.stats.maxZ}")
        assertTrue(abs(r.points.last().p.z) < 0.7, "end ${r.points.last().p.z}")
    }

    @Test
    fun climbFollowedByAShortDescentKeepsBoth() {
        // Six steps up 1 m, then three steps down 0.6 m: too few steps for a climb of its own, but it
        // brings the height more than half way back right after the climb.
        val w = walk()
        val s = w.strideM
        w.still(2.0).walkTo(0.0, 5.0).walkTo(0.0, 5.0 + 6 * s, 1.0).walkTo(0.0, 5.0 + 9 * s, 0.4)
            .walkTo(0.0, 15.0 + 9 * s, 0.4).still(2.0)
        assertWithin(0.4, run(w).points.last().p.z, 0.25, "up 1 m and down 0.6 m")
    }

    @Test
    fun climbFilterIgnoresTheLowPass() {
        // The filter works on the unsmoothed height, so baroSmoothingS and the hold do not change it.
        val w = walk().still(2.0).walkTo(0.0, 5.0).walkTo(0.0, 17.0, 3.0).walkTo(0.0, 22.0, 3.0).still(8.0)
            .walkTo(0.0, 30.0, 3.0).still(1.0)
        val base = run(w).points.map { it.p.z }
        val raw = run(w, config(w) { it.copy(baroSmoothingS = 0.0, baroHoldWhenStill = false) }).points.map { it.p.z }
        assertEquals(base, raw)
    }

    @Test
    fun segmentApiMatchesFullSolutionOnStairs() {
        // The VIO fuser solves gaps from any step; the climb spans must not depend on where it starts.
        val w = walk().still(2.0).walkTo(0.0, 5.0).walkTo(0.0, 17.0, 3.0).walkTo(0.0, 22.0, 3.0).still(1.0)
        val cfg = config(w)
        val log = w.build(cfg)
        val solver = PdrSolver()
        val full = solver.solve(log, cfg)
        val ctx = solver.prepare(log, cfg)
        val mid = full.points.minBy { abs(it.p.z - 1.5) }
        val tail = solver.solveSegment(ctx, mid.tNs + 1, Long.MAX_VALUE, mid.p, null)
        val end = full.points.last().p
        assertTrue(abs(end.z - tail.last().p.z) < 1e-6, "segment end ${tail.last().p} vs full $end")
        assertWithin(3.0, tail.last().p.z, 0.05, "climb through the segment")
    }

    @Test
    fun pausedStretchIsLeftOut() {
        // The user pauses at (0, 10), wanders 4 m east and 3 m up, waits, resumes and walks north
        // again. The path must continue from (0, 10) at the original height as if nothing happened.
        val w = walk().still(2.0).walkTo(0.0, 10.0).pause().walkTo(4.0, 10.0, 3.0).still(3.0).resume()
            .walkTo(4.0, 20.0, 3.0).still(1.0)
        val r = run(w)
        val end = r.points.last().p
        assertWithin(20.0, end.y, 0.10, "north displacement without the detour")
        assertTrue(abs(end.x) < 1.5, "the detour must not show: x = ${end.x}")
        assertTrue(abs(end.z) < 0.5, "the climb during the pause must not show: z = ${end.z}")
        assertTrue(abs(r.stats.maxZ) < 0.5, "no point should carry the paused climb: ${r.stats.maxZ}")
        assertWithin(20.0 / w.strideM, r.stats.stepCount.toDouble(), 0.10, "steps outside the pause")
        assertWithin(20.0, r.stats.distanceM, 0.10, "distance outside the pause")
        assertEquals("1", r.diagnostics["pauses"])
        assertTrue(assertNotNull(r.diagnostics["pausedSteps"]).toInt() >= 5)

        // Control: without the events the detour is part of the path.
        val c = walk().still(2.0).walkTo(0.0, 10.0).walkTo(4.0, 10.0, 3.0).still(3.0).walkTo(4.0, 20.0, 3.0).still(1.0)
        val control = run(c)
        assertTrue(control.points.last().p.x > 2.5 && control.stats.maxZ > 2.0, "control ${control.points.last().p}")
        assertNull(control.diagnostics["pauses"])
    }

    @Test
    fun hardwareStepsAreTheFallbackWithoutAccelerometer() {
        val w = walk().still(2.0).walkTo(0.0, 20.0).still(1.0)
        val cfg = config(w)
        val b = RawLog.Builder()
        for (rec in w.buildRecords()) if (rec !is AccelSample) b.add(rec)
        val r = PdrProcessor().process(b.build(), cfg)
        assertEquals("0", r.diagnostics["softwareSteps"])
        assertEquals("hardware", r.diagnostics["stepsUsed"])
        assertEquals("no accelerometer samples; hardware steps used", r.diagnostics["stepsNote"])
        assertTrue(r.stats.stepCount > 20, "hardware steps should drive the path: ${r.stats.stepCount}")
        assertWithin(20.0, r.stats.distanceM, 0.15, "distance from hardware steps")
        assertWithin(20.0, r.points.last().p.y, 0.15, "north displacement from hardware steps")
    }

    @Test
    fun configuredHeadingAxisIsUsedForTheFirstSegment() {
        val w = walk().still(2.0).walkTo(0.0, 20.0).still(1.0)
        val auto = run(w)
        assertEquals("AUTO", auto.diagnostics["headingAxisMode"])
        assertEquals("FORWARD", auto.diagnostics["headingAxis"])

        // The phone is pitched, not rolled, so both axes give the same heading and the path stays
        // straight; what matters is that the axis is the configured one, not the tilt-chosen one.
        val camera = run(w, config(w) { it.copy(headingAxis = HeadingAxisMode.CAMERA) })
        assertEquals("CAMERA", camera.diagnostics["headingAxisMode"])
        assertEquals("CAMERA", camera.diagnostics["headingAxis"])
        assertWithin(20.0, camera.points.last().p.y, 0.10, "north displacement on the camera axis")
        assertTrue(abs(camera.points.last().p.x) < 1.5, "x = ${camera.points.last().p.x}")

        // An upright phone would pick CAMERA on its own; FORWARD forces the calibrated axis.
        val upright = SyntheticWalk(tiltRad = 1.4).still(2.0).walkTo(0.0, 20.0).still(1.0)
        assertEquals("CAMERA", run(upright, config(upright)).diagnostics["headingAxis"])
        val forced = run(upright, config(upright) { it.copy(headingAxis = HeadingAxisMode.FORWARD) })
        assertEquals("FORWARD", forced.diagnostics["headingAxis"])

        // After a REORIENT the axis is chosen freely again.
        val re = walk().still(2.0).walkTo(0.0, 10.0).annotate(AnnotationKind.REORIENT).walkTo(0.0, 20.0).still(1.0)
        val reoriented = run(re, config(re) { it.copy(headingAxis = HeadingAxisMode.CAMERA) })
        assertEquals("CAMERA;FORWARD", reoriented.diagnostics["headingAxis"])
    }

    @Test
    fun emptyLogGivesOnlyTheStartPoint() {
        val r = PdrProcessor().process(RawLog.Builder().build(), PipelineConfig())
        assertEquals(1, r.points.size)
        assertEquals(0, r.stats.stepCount)
        assertEquals("NONE", r.diagnostics["orientationSource"])
    }
}
