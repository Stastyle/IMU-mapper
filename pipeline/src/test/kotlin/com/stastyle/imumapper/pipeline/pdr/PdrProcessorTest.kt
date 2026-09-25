package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.AnnotationKind
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
    fun emptyLogGivesOnlyTheStartPoint() {
        val r = PdrProcessor().process(RawLog.Builder().build(), PipelineConfig())
        assertEquals(1, r.points.size)
        assertEquals(0, r.stats.stepCount)
        assertEquals("NONE", r.diagnostics["orientationSource"])
    }
}
