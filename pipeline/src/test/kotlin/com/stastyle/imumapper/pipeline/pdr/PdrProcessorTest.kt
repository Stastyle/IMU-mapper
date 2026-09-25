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

        val open = PdrProcessor().process(w.build(cfg), cfg.copy(loopClosure = false))
        assertNull(open.stats.closureErrorM)
        assertTrue(open.points.last().p.length > 0.2, "without closure the error remains")
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
    fun reorientReestimatesHeadingOffset() {
        val w = walk().still(2.0).walkTo(0.0, 10.0)
            .annotate(AnnotationKind.REORIENT).deviceOffset(PI / 2).walkTo(0.0, 20.0).still(1.0)
        val r = run(w)
        val end = r.points.last().p
        assertTrue(end.y > 16.0 && abs(end.x) < 3.0, "offset should be re-estimated, path ended at $end")
        assertEquals("1", r.diagnostics["reorientCount"])
        assertNotNull(r.diagnostics["reorientOffsetsDeg"])

        // Control: the same carry change without the annotation turns the path by 90 degrees.
        val c = walk().still(2.0).walkTo(0.0, 10.0).deviceOffset(PI / 2).walkTo(0.0, 20.0).still(1.0)
        val control = run(c)
        assertTrue(abs(control.points.last().p.x) > 6.0, "control should veer off: ${control.points.last().p}")
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
        // stop must not be thrown away with the middle of the gap.
        val w = walk().still(2.0)
            .walkTo(0.0, 4.0, 1.0).still(6.0).walkTo(0.0, 8.0, 2.0).still(6.0)
            .walkTo(0.0, 12.0, 3.0).still(6.0).walkTo(0.0, 16.0, 4.0).still(6.0)
        val held = run(w)
        assertWithin(4.0, held.stats.maxZ, 0.20, "climb with stops and hold")
        val free = run(w, config(w) { it.copy(baroHoldWhenStill = false) })
        assertWithin(4.0, free.stats.maxZ, 0.20, "climb with stops without hold")

        // A slow walker (0.45 Hz cadence, 2.2 s between steps) is not standing still.
        val slow = SyntheticWalk(speedMps = 0.5, cadenceHz = 0.45).still(2.0).walkTo(0.0, 10.0, 2.0).still(2.0)
        val r = run(slow)
        assertTrue(r.stats.stepCount >= 5, "steps at a slow cadence: ${r.stats.stepCount}")
        assertWithin(2.0, r.stats.maxZ, 0.30, "climb at a slow cadence")
    }

    @Test
    fun longStillHoldsAltitudeAgainstPressureDrift() {
        // Pressure drifting 0.005 hPa/s reads as a 4 cm/s descent. Over a 30 s stop the hold freezes
        // the middle of the gap, so far less of the drift reaches the path than without it.
        val w = SyntheticWalk(baroDriftHpaPerS = 0.005)
            .still(2.0).walkTo(0.0, 8.0).still(30.0).walkTo(0.0, 16.0).still(1.0)
        val held = run(w).points.last().p.z
        val free = run(w, config(w) { it.copy(baroHoldWhenStill = false) }).points.last().p.z
        assertTrue(free < -1.2, "without the hold the drift shows: $free")
        assertTrue(held > free + 0.7, "the hold should remove most of the drift: held $held vs free $free")

        // A shorter gap limit freezes more of it.
        val tight = run(w, config(w) { it.copy(baroStillGapS = 1.0) }).points.last().p.z
        assertTrue(tight >= held - 1e-9, "tight $tight vs held $held")
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
