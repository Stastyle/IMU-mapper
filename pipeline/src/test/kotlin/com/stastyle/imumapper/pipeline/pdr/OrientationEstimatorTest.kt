package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.MagSample
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.RotationSample
import com.stastyle.imumapper.pipeline.core.RotationSource
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.log.RawLog
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where north comes from. A real game rotation vector starts at an arbitrary yaw, so these walks give
 * the synthetic one a 40 degree offset (counter-clockwise) that the fused vector and the magnetometer
 * do not have. Without the offset game and fused agree at the start, and no test could tell a path
 * in the game frame from one turned onto magnetic north.
 */
class OrientationEstimatorTest {

    private val offset = Math.toRadians(40.0)

    /** Stands still for 2 s, then walks 20 m north. */
    private fun north(w: SyntheticWalk): SyntheticWalk = w.still(2.0).walkTo(0.0, 20.0).still(1.0)

    private fun offsetWalk(driftRadPerS: Double = 0.0): SyntheticWalk =
        north(SyntheticWalk(gameYawOffsetRad = offset, gameYawDriftRadPerS = driftRadPerS))

    private fun config(w: SyntheticWalk, tweak: (PipelineConfig) -> PipelineConfig = { it }): PipelineConfig =
        tweak(PipelineConfig(strideLengthM = w.strideM))

    private fun run(w: SyntheticWalk, cfg: PipelineConfig = config(w)): PathResult =
        PdrProcessor().process(w.build(cfg), cfg)

    private val trueNorthEnd = Vec3(0.0, 20.0, 0.0)

    /** Where the 20 m walk north ends when the path keeps a game frame turned [rad] counter-clockwise. */
    private fun relativeEnd(rad: Double): Vec3 = Vec3(20.0 * sin(-rad), 20.0 * cos(-rad), 0.0)

    private fun assertEndsNear(expected: Vec3, r: PathResult, tol: Double, what: String) {
        val end = r.points.last().p
        val d = hypot(end.x - expected.x, end.y - expected.y)
        assertTrue(d <= tol, "$what: expected the end within $tol m of $expected but it was $end")
    }

    private fun northOffsetDeg(r: PathResult): Double = assertNotNull(r.diagnostics["northOffsetDeg"]).toDouble()

    @Test
    fun gameYawOffsetIsTurnedOntoMagneticNorth() {
        val w = offsetWalk()
        val r = run(w)
        // A wrong sign would turn the path 80 degrees off instead of removing the 40.
        assertEndsNear(trueNorthEnd, r, 1.5, "north walk")
        assertEquals("magnetic", r.diagnostics["northReference"])
        assertEquals("applied", r.diagnostics["yawCorrection"])
        assertTrue(abs(northOffsetDeg(r) + 40.0) < 0.5, "northOffsetDeg ${r.diagnostics["northOffsetDeg"]}")

        // The track itself points the device at true north (it faces the walk with no offset).
        val track = OrientationEstimator().estimate(w.build(), PipelineConfig()).track
        for (s in listOf(0.5, 3.0, 8.0, 15.0)) {
            val heading = Math.toDegrees(track.at(SyntheticWalk.BASE_NS + (s * 1e9).toLong()).forwardHeadingRad())
            assertTrue(abs(heading) < 2.0, "device heading at $s s: $heading")
        }
    }

    @Test
    fun northFromCompassOffKeepsTheGameFrame() {
        val w = offsetWalk()
        val r = run(w, config(w) { it.copy(northFromCompass = false) })
        assertEndsNear(relativeEnd(offset), r, 1.5, "relative walk")
        assertEquals("relative: north from compass is off", r.diagnostics["northReference"])
        assertEquals("applied", r.diagnostics["yawCorrection"], "the drift correction does not depend on north")
        assertTrue(abs(northOffsetDeg(r) + 40.0) < 0.5, "the offset is still measured")

        val bare = run(w, config(w) { it.copy(northFromCompass = false, useMagnetometer = false) })
        assertEndsNear(relativeEnd(offset), bare, 1.5, "no compass at all")
        assertEquals("disabled", bare.diagnostics["yawCorrection"])
        assertEquals("relative: north from compass is off", bare.diagnostics["northReference"])
        assertNull(bare.diagnostics["northOffsetDeg"])
    }

    @Test
    fun magnetometerOffStillSetsNorthFromTheStart() {
        val w = offsetWalk()
        val r = run(w, config(w) { it.copy(useMagnetometer = false) })
        assertEndsNear(trueNorthEnd, r, 1.5, "anchor only")
        assertEquals("disabled", r.diagnostics["yawCorrection"])
        assertEquals("magnetic", r.diagnostics["northReference"])
        assertNull(r.diagnostics["yawCorrectionFinalDeg"])
        assertTrue(abs(northOffsetDeg(r) + 40.0) < 0.5, "northOffsetDeg ${r.diagnostics["northOffsetDeg"]}")

        // Only the start is taken from the compass: game-vector drift after it is left alone.
        val d = offsetWalk(driftRadPerS = 0.02)
        val cfg = config(d) { it.copy(useMagnetometer = false) }
        val first = Math.toDegrees(PdrSolver().prepare(d.build(cfg), cfg).stepHeadingRad[0])
        assertTrue(abs(first) < 4.0, "the first step heads north: $first")
        val end = run(d, cfg).points.last().p
        assertTrue(abs(end.x) > 2.0, "the drift after the start should bend the path: $end")
    }

    @Test
    fun offsetAndDriftTogetherStillEndNorth() {
        val w = offsetWalk(driftRadPerS = 0.02)
        val r = run(w)
        assertEquals("magnetic", r.diagnostics["northReference"])
        assertEndsNear(trueNorthEnd, r, 2.5, "offset and drift")
    }

    @Test
    fun anyGameOffsetGivesTheSamePath() {
        val a = run(offsetWalk())
        val b = run(north(SyntheticWalk(gameYawOffsetRad = Math.toRadians(-110.0))))
        val c = run(north(SyntheticWalk()))
        for (other in listOf(b, c)) {
            assertEquals(a.points.size, other.points.size)
            val worst = a.points.indices.maxOf { a.points[it].p.distanceTo(other.points[it].p) }
            assertTrue(worst < 0.05, "paths differ by up to $worst m")
        }
        assertTrue(abs(northOffsetDeg(b) - 110.0) < 0.5, "northOffsetDeg ${b.diagnostics["northOffsetDeg"]}")
    }

    @Test
    fun northStaysRelativeWhenTheCompassCannotBeRead() {
        val noMag = north(SyntheticWalk(gameYawOffsetRad = offset, includeMag = false))
        val m = run(noMag)
        assertEquals("relative: no magnetometer samples", m.diagnostics["northReference"])
        assertEquals("skipped: no magnetometer samples to gate the fused heading", m.diagnostics["yawCorrection"])
        assertEndsNear(relativeEnd(offset), m, 1.5, "no magnetometer")
        val mOff = run(noMag, config(noMag) { it.copy(useMagnetometer = false) })
        assertEquals("disabled", mOff.diagnostics["yawCorrection"])
        assertEquals("relative: no magnetometer samples", mOff.diagnostics["northReference"])
        val mNorthOff = run(noMag, config(noMag) { it.copy(northFromCompass = false) })
        assertEquals("relative: north from compass is off", mNorthOff.diagnostics["northReference"])

        val noFused = north(SyntheticWalk(gameYawOffsetRad = offset, includeFused = false))
        val f = run(noFused)
        assertEquals("relative: no fused rotation samples", f.diagnostics["northReference"])
        assertEquals("no fused rotation samples", f.diagnostics["yawCorrection"])
        assertEndsNear(relativeEnd(offset), f, 1.5, "no fused vector")
        val fOff = run(noFused, config(noFused) { it.copy(useMagnetometer = false) })
        assertEquals("disabled", fOff.diagnostics["yawCorrection"])
        assertEquals("relative: no fused rotation samples", fOff.diagnostics["northReference"])

        // A zero tolerance rejects every noisy reading, the reference ones included.
        val w = offsetWalk()
        val g = run(w, config(w) { it.copy(magGateTolerance = 0.0) })
        assertEquals("0.000", g.diagnostics["magGatePassFraction"])
        assertEquals("relative: magnetic field never passed the gate", g.diagnostics["northReference"])
        assertEquals("skipped: magnetic field never passed the gate", g.diagnostics["yawCorrection"])
        assertEndsNear(relativeEnd(offset), g, 1.5, "gate never passed")
        val gOff = run(w, config(w) { it.copy(magGateTolerance = 0.0, useMagnetometer = false) })
        assertEquals("disabled", gOff.diagnostics["yawCorrection"])
        assertEquals("relative: magnetic field never passed the gate", gOff.diagnostics["northReference"])
    }

    /**
     * [w] with the magnetometer scattered for its first [seconds]: readings alternately 1.6 and 0.4
     * times the real field. Their mean is close to the real field, so the gate built from the first
     * second passes the steady field that follows and fails every scattered reading.
     */
    private fun scatteredStart(w: SyntheticWalk, seconds: Double): RawLog {
        val end = SyntheticWalk.BASE_NS + Math.round(seconds * 1e9)
        val b = RawLog.Builder()
        var k = 0
        for (rec in w.buildRecords()) {
            if (rec is MagSample && rec.tNs <= end) {
                val f = if (k++ % 2 == 0) 1.6f else 0.4f
                b.add(MagSample(rec.tNs, rec.x * f, rec.y * f, rec.z * f))
            } else {
                b.add(rec)
            }
        }
        return b.build()
    }

    @Test
    fun aScatteredStartTakesNorthFromTheFirstSecondThatPasses() {
        val w = offsetWalk(driftRadPerS = 0.02)
        val cfg = config(w)
        val r = PdrProcessor().process(scatteredStart(w, 2.0), cfg)
        assertEquals("magnetic", r.diagnostics["northReference"])
        assertEquals("applied", r.diagnostics["yawCorrection"])
        // The first fused sample that passes is at 2.02 s, so the reference second runs to 3.02 s.
        assertEquals("2.0", r.diagnostics["northReferenceAtS"])
        // Its mean is the 40 degree offset plus the drift at 2.52 s. The single sample at 2.02 s that
        // the reference used to be would give 42.3.
        val windowMean = -(40.0 + Math.toDegrees(0.02 * 2.52))
        assertTrue(abs(northOffsetDeg(r) - windowMean) < 0.1, "northOffsetDeg ${r.diagnostics["northOffsetDeg"]}")
        assertEndsNear(trueNorthEnd, r, 2.5, "scattered start")

        val anchorOnly = PdrProcessor().process(scatteredStart(w, 2.0), cfg.copy(useMagnetometer = false))
        assertEquals("magnetic", anchorOnly.diagnostics["northReference"])
        assertEquals("2.0", anchorOnly.diagnostics["northReferenceAtS"])
        assertEquals(r.diagnostics["northOffsetDeg"], anchorOnly.diagnostics["northOffsetDeg"])

        // A start that passes at once uses the first second and does not mention it.
        val clean = run(w)
        assertNull(clean.diagnostics["northReferenceAtS"])
        assertTrue(abs(northOffsetDeg(clean) + 40.0 + Math.toDegrees(0.02 * 0.5)) < 0.1, "clean start")
        // A scatter shorter than the reference second moves the window without the key.
        assertNull(PdrProcessor().process(scatteredStart(w, 0.5), cfg).diagnostics["northReferenceAtS"])
    }

    private fun fusedOnly(w: SyntheticWalk): RawLog {
        val b = RawLog.Builder()
        for (rec in w.buildRecords()) if (!(rec is RotationSample && rec.source == RotationSource.GAME)) b.add(rec)
        return b.build()
    }

    @Test
    fun otherSourcesSayWhereNorthIs() {
        val w = offsetWalk()
        val cfg = config(w)
        val fused = PdrProcessor().process(fusedOnly(w), cfg)
        assertEquals("FUSED", fused.diagnostics["orientationSource"])
        assertEquals("magnetic", fused.diagnostics["northReference"])
        assertEndsNear(trueNorthEnd, fused, 1.5, "fused only")

        val mw = north(SyntheticWalk(includeRotation = false))
        val madgwick = run(mw)
        assertEquals("MADGWICK", madgwick.diagnostics["orientationSource"])
        assertEquals("relative: no rotation vector samples", madgwick.diagnostics["northReference"])

        val none = PdrProcessor().process(RawLog.Builder().build(), PipelineConfig())
        assertEquals("relative: no orientation samples", none.diagnostics["northReference"])
    }

    @Test
    fun northReferenceIsTheLastKeyOfEveryBranch() {
        val w = offsetWalk()
        val cfg = config(w)
        val log = w.build(cfg)
        val cases = listOf(
            log to cfg,
            log to cfg.copy(northFromCompass = false),
            log to cfg.copy(useMagnetometer = false),
            log to cfg.copy(useMagnetometer = false, northFromCompass = false),
            log to cfg.copy(magGateTolerance = 0.0),
            scatteredStart(w, 2.0) to cfg,
            scatteredStart(w, 2.0) to cfg.copy(useMagnetometer = false),
            north(SyntheticWalk(includeMag = false)).build() to cfg,
            north(SyntheticWalk(includeFused = false)).build() to cfg,
            fusedOnly(w) to cfg,
            north(SyntheticWalk(includeRotation = false)).build() to cfg,
            RawLog.Builder().build() to cfg,
        )
        for ((l, c) in cases) {
            val keys = OrientationEstimator().estimate(l, c).diagnostics.keys
            assertEquals("northReference", keys.last(), "keys $keys")
        }
    }

    @Test
    fun deterministicWithAnOffset() {
        val w = offsetWalk(driftRadPerS = 0.01)
        val cfg = config(w)
        val log = w.build(cfg)
        val a = PdrProcessor().process(log, cfg)
        val b = PdrProcessor().process(log, cfg)
        assertEquals(a.toJson(), b.toJson())
    }
}
