package com.stastyle.imumapper.pipeline.vio

import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.AnnotationRecord
import com.stastyle.imumapper.pipeline.core.EventKind
import com.stastyle.imumapper.pipeline.core.EventRecord
import com.stastyle.imumapper.pipeline.core.KeyframeSample
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.PoseSample
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.RotationSample
import com.stastyle.imumapper.pipeline.core.RotationSource
import com.stastyle.imumapper.pipeline.core.StepSample
import com.stastyle.imumapper.pipeline.core.TrackingState
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.log.RawLog
import com.stastyle.imumapper.pipeline.pdr.Angles
import com.stastyle.imumapper.pipeline.pdr.PdrProcessor
import com.stastyle.imumapper.pipeline.pdr.SyntheticWalk
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VioProcessorTest {

    /** Walking parameters shared by the IMU and the pose generator: a 0.4 m stride keeps PDR steps under 0.5 m. */
    private val speed = 0.8
    private val cadence = 2.0

    private fun walk(): SyntheticWalk = SyntheticWalk(speedMps = speed, cadenceHz = cadence)

    private fun vio(headingDeg: Double = 0.0, originAr: Vec3 = Vec3.ZERO): SyntheticVio =
        SyntheticVio(speedMps = speed, sessionHeadingRad = Math.toRadians(headingDeg), sessionOriginAr = originAr)

    private fun config(): PipelineConfig = PipelineConfig(strideLengthM = speed / cadence)

    private fun square(w: SyntheticWalk): SyntheticWalk =
        w.still(2.0).walkTo(0.0, 5.0).walkTo(5.0, 5.0).walkTo(5.0, 0.0).walkTo(0.0, 0.0).still(1.0)

    private fun square(v: SyntheticVio): SyntheticVio =
        v.still(2.0).walkTo(0.0, 5.0).walkTo(5.0, 5.0).walkTo(5.0, 0.0).walkTo(0.0, 0.0).still(1.0)

    private fun run(log: RawLog, config: PipelineConfig = config()): PathResult = VioProcessor().process(log, config)

    private fun maxJump(r: PathResult): Double {
        var max = 0.0
        for (i in 1 until r.points.size) max = maxOf(max, r.points[i].p.distanceTo(r.points[i - 1].p))
        return max
    }

    private fun assertNear(expected: Vec3, actual: Vec3, tol: Double, what: String) {
        assertTrue(expected.distanceTo(actual) <= tol, "$what: expected $expected within $tol m but was $actual")
    }

    private fun secondsOf(r: PathResult, point: Int): Double = (r.points[point].tNs - SyntheticWalk.BASE_NS) / 1e9

    @Test
    fun movingAlongArcoreMinusZAppearsAsNorth() {
        // Hand-written poses: identity orientation, position sliding along ARCore -Z at 1 m/s.
        val poses = ArrayList<PoseSample>()
        for (k in 0..100) {
            val t = SyntheticWalk.BASE_NS + k * 100_000_000L
            val z = -(k / 10.0)
            poses.add(PoseSample(t, t, 1f, 2f, z.toFloat(), 0f, 0f, 0f, 1f, TrackingState.TRACKING, 0))
        }
        val b = RawLog.Builder()
        for (p in poses) b.add(p)
        val r = run(b.build())
        assertEquals("NONE", r.diagnostics["yawAlignmentSource"])
        assertNear(Vec3.ZERO, r.points[0].p, 1e-6, "first tracking pose is the origin")
        assertNear(Vec3(0.0, 10.0, 0.0), r.points.last().p, 1e-3, "-Z motion is north")
        for (p in r.points) {
            assertTrue(abs(p.p.x) < 1e-6 && abs(p.p.z) < 1e-6, "path stays on the north axis: ${p.p}")
            assertTrue(abs(p.headingRad) < 1e-6, "identity camera looks north, heading ${p.headingRad}")
            assertEquals(PositionSource.VIO, p.source)
        }
        assertEquals(101, r.points.size)
        assertEquals(1.0, r.stats.vioFraction)
        assertEquals("0", r.diagnostics["trackingLossCount"])
    }

    @Test
    fun yawIsAlignedToImuHeadingAtFirstTrackingFrame() {
        // ARCore's -Z points at 50 degrees; the IMU game vector says the camera looks north, so the
        // northward walk must come out north. Without rotation samples it comes out at -50 degrees.
        val v = vio(headingDeg = 50.0).still(1.0).walkTo(0.0, 10.0).still(0.5)
        val imu = walk().still(1.0).walkTo(0.0, 10.0).still(0.5).buildRecords()
        val aligned = run(v.buildWith(imu.filterIsInstance<RotationSample>()))
        assertEquals("GAME", aligned.diagnostics["yawAlignmentSource"])
        assertNear(Vec3(0.0, 10.0, 0.0), aligned.points.last().p, 0.3, "aligned end")
        assertTrue(abs(Angles.diff(aligned.points.last().headingRad, 0.0)) < Math.toRadians(3.0))

        val raw = run(v.build())
        assertEquals("NONE", raw.diagnostics["yawAlignmentSource"])
        val h = Math.toRadians(-50.0)
        assertNear(Vec3(10.0 * sin(h), 10.0 * cos(h), 0.0), raw.points.last().p, 0.05, "unaligned end")

        // Fused samples are the fallback when there is no game vector.
        val fusedOnly = imu.filterIsInstance<RotationSample>().map { it.copy(source = RotationSource.FUSED) }
        val fused = run(v.buildWith(fusedOnly))
        assertEquals("FUSED", fused.diagnostics["yawAlignmentSource"])
        assertNear(Vec3(0.0, 10.0, 0.0), fused.points.last().p, 0.3, "fused-aligned end")
    }

    @Test
    fun circleWithoutGapFollowsTruthAndIsDeterministic() {
        val v = vio(headingDeg = 120.0, originAr = Vec3(4.0, -1.0, 2.0)).still(1.0)
        val radius = 4.0
        for (k in 1..36) {
            val a = 2.0 * PI * k / 36.0
            v.walkTo(radius * sin(a), radius - radius * cos(a))
        }
        v.still(0.5)
        // The IMU tells the processor the camera looks north at the start (the walk starts northward).
        val imu = walk().still(1.0).walkTo(0.0, 1.0).buildRecords().filterIsInstance<RotationSample>()
            .filter { it.tNs <= SyntheticVio.ns(1.5) }
        val log = v.buildWith(imu)
        val r = run(log)
        assertEquals(1.0, r.stats.vioFraction)
        assertEquals("0", r.diagnostics["trackingLossCount"])
        for (i in r.points.indices) {
            val truth = v.truthAt(secondsOf(r, i))
            assertNear(truth, r.points[i].p, 0.05, "point $i")
        }
        val perimeter = 36.0 * 2.0 * radius * sin(PI / 36.0)
        assertTrue(abs(r.stats.distanceM - perimeter) < 0.3, "distance ${r.stats.distanceM} vs $perimeter")
        assertEquals(r.toJson(), run(log).toJson(), "processing must be deterministic")

        // Smoothing is on by default, so the unsmoothed resampled poses are kept alongside.
        assertEquals(r.points.size, r.rawPoints.size)
        assertTrue(r.rawPoints.indices.all { r.rawPoints[it].tNs == r.points[it].tNs })
        assertTrue(run(log, config().copy(smoothingWindow = 1)).rawPoints.isEmpty())
    }

    @Test
    fun pausedGapAcrossCornerIsBridgedByPdrAndReanchored() {
        // Tracking is lost at 13 s (mid second leg) and returns at 16 s, after the corner at 14.5 s.
        val w = square(walk())
        val v = square(vio(headingDeg = 30.0)).lose(13.0, 16.0, paused = true)
        val log = v.buildWith(w.buildRecords())
        val r = run(log)
        assertEquals("arcore", r.diagnostics["vio"])
        assertEquals("1", r.diagnostics["trackingLossCount"])
        assertEquals("pdr", r.diagnostics["trackingLostFill"])
        assertEquals("0", r.diagnostics["reanchorYawRealigned"])
        val lostS = assertNotNull(r.diagnostics["trackingLostS"]).toDouble()
        assertTrue(abs(lostS - 3.0) < 0.15, "lost seconds $lostS")
        val pdrPoints = r.points.count { it.source == PositionSource.PDR }
        assertTrue(pdrPoints in 3..8, "about 6 steps at 2 Hz should fill the 3 s gap, got $pdrPoints")
        assertTrue(r.stats.vioFraction > 0.9 && r.stats.vioFraction < 1.0)
        assertTrue(maxJump(r) <= 0.5, "largest jump between consecutive points ${maxJump(r)}")
        for (i in r.points.indices) {
            val truth = v.truthAt(secondsOf(r, i))
            assertNear(truth, r.points[i].p, 0.8, "point $i (${r.points[i].source}) at ${secondsOf(r, i)} s")
        }
        assertEquals(r.toJson(), run(log).toJson(), "processing must be deterministic")
    }

    @Test
    fun holeInFramesIsAlsoATrackingLoss() {
        val w = square(walk())
        val v = square(vio()).lose(9.0, 11.5, paused = false)
        val r = run(v.buildWith(w.buildRecords()))
        assertEquals("1", r.diagnostics["trackingLossCount"])
        assertTrue(r.points.any { it.source == PositionSource.PDR })
        assertTrue(maxJump(r) <= 0.5, "largest jump ${maxJump(r)}")
        assertNear(v.truthAt(secondsOf(r, r.points.size - 1)), r.points.last().p, 0.8, "end")

        // A hole shorter than half a second is interpolated over, not a loss.
        val short = square(vio()).lose(9.0, 9.3, paused = false)
        val rs = run(short.buildWith(w.buildRecords()))
        assertEquals("0", rs.diagnostics["trackingLossCount"])
        assertTrue(rs.points.none { it.source == PositionSource.PDR })
    }

    @Test
    fun briefPausedBlipIsInterpolatedNotCountedAsALoss() {
        // One PAUSED frame at 9 s (a head turn): ARCore keeps its frame, so the run continues and the
        // displacement across the blip is kept instead of being re-anchored away.
        val w = square(walk())
        val v = square(vio(headingDeg = 30.0)).lose(9.0, 9.02, paused = true)
        val log = v.buildWith(w.buildRecords())
        assertTrue(log.poses.count { it.tracking == TrackingState.PAUSED } == 1)
        val r = run(log)
        assertEquals("1", r.diagnostics["trackingRuns"])
        assertEquals("0", r.diagnostics["trackingLossCount"])
        assertTrue(r.points.none { it.source == PositionSource.PDR })
        assertEquals(1.0, r.stats.vioFraction)
        for (i in r.points.indices) {
            assertNear(v.truthAt(secondsOf(r, i)), r.points[i].p, 0.1, "point $i at ${secondsOf(r, i)} s")
        }
        assertNear(Vec3.ZERO, r.points.last().p, 0.1, "back at the origin with no offset")
    }

    @Test
    fun pausedStretchHoldsThePosition() {
        // The user pauses at (0, 10), wanders 4 m east while the recorder keeps logging, and resumes.
        // The walk after the pause continues from (0, 10): the path ends at (0, 20), not (4, 20).
        val w = walk().still(2.0).walkTo(0.0, 10.0).pause().walkTo(4.0, 10.0).still(1.0).resume()
            .walkTo(4.0, 20.0).still(1.0)
        val v = vio(headingDeg = 30.0).still(2.0).walkTo(0.0, 10.0).walkTo(4.0, 10.0).still(1.0).walkTo(4.0, 20.0)
            .still(1.0)
        val log = v.buildWith(w.buildRecords())
        val pauseNs = log.events.first { it.kind == EventKind.PAUSE }.tNs
        val resumeNs = log.events.first { it.kind == EventKind.RESUME }.tNs
        val r = run(log)
        assertEquals("1", r.diagnostics["pauseCount"])
        assertEquals("0", r.diagnostics["trackingLossCount"])
        assertEquals("0", r.diagnostics["reanchorYawRealigned"])
        assertTrue(r.points.none { it.tNs >= pauseNs && it.tNs < resumeNs }, "no path point inside the pause")
        assertTrue(r.points.none { it.source == PositionSource.PDR })
        assertNear(Vec3(0.0, 20.0, 0.0), r.points.last().p, 0.3, "end without the detour")
        for (i in r.points.indices) {
            val s = secondsOf(r, i)
            val expected = if (r.points[i].tNs < pauseNs) v.truthAt(s) else v.truthAt(s) - Vec3(4.0, 0.0, 0.0)
            assertNear(expected, r.points[i].p, 0.3, "point $i at $s s")
        }
        assertTrue(abs(r.stats.distanceM - 20.0) < 1.0, "distance without the detour: ${r.stats.distanceM}")
        // Steps taken during the pause are not counted either.
        assertTrue(abs(r.stats.stepCount - 20.0 / (speed / cadence)) < 6, "steps ${r.stats.stepCount}")
        assertEquals(r.toJson(), run(log).toJson(), "processing must be deterministic")
    }

    @Test
    fun madgwickOrientationIsSharedWithTheGapFill() {
        // No rotation-vector samples: PDR runs on Madgwick, whose yaw is arbitrary. The ARCore frame
        // must be aligned to that same yaw so the 6 s PDR fill continues the VIO line.
        val w = SyntheticWalk(speedMps = speed, cadenceHz = cadence, includeRotation = false)
            .still(2.0).walkTo(0.0, 20.0).still(1.0)
        val v = vio(headingDeg = 120.0).still(2.0).walkTo(0.0, 20.0).still(1.0).lose(8.0, 14.0, paused = true)
        val r = run(v.buildWith(w.buildRecords()))
        assertEquals("MADGWICK", r.diagnostics["yawAlignmentSource"])
        assertEquals("MADGWICK", r.diagnostics["pdr.orientationSource"])
        assertEquals("1", r.diagnostics["trackingLossCount"])
        assertEquals("pdr", r.diagnostics["trackingLostFill"])
        assertTrue(r.points.count { it.source == PositionSource.PDR } in 8..16)
        val end = r.points.last().p
        assertTrue(abs(end.length - 20.0) < 2.0, "the walk is 20 m long whatever the yaw: $end")
        // Every point lies close to the straight line from the origin to the end.
        val ux = end.x / end.length
        val uy = end.y / end.length
        for (p in r.points) {
            val across = abs(p.p.x * uy - p.p.y * ux)
            assertTrue(across < 1.0, "point ${p.p} (${p.source}) is $across m off the line")
        }
        assertTrue(maxJump(r) <= 0.5, "largest jump ${maxJump(r)}")
    }

    @Test
    fun withoutAnyOrientationTheGapStaysAHole() {
        // Only hardware steps and ARCore poses: PDR has steps but no orientation, so dead-reckoning
        // the gap would point it in an arbitrary direction. The gap is left open instead.
        val w = square(walk())
        val v = square(vio()).lose(13.0, 16.0, paused = true)
        val imu = w.buildRecords().filter { it is StepSample || it is EventRecord }
        val r = run(v.buildWith(imu))
        assertEquals("NONE", r.diagnostics["yawAlignmentSource"])
        assertEquals("NONE", r.diagnostics["pdr.orientationSource"])
        assertEquals("none: no orientation", r.diagnostics["trackingLostFill"])
        assertEquals("1", r.diagnostics["trackingLossCount"])
        assertTrue(r.points.none { it.source == PositionSource.PDR })
        assertTrue(r.stats.stepCount > 30, "steps are still counted: ${r.stats.stepCount}")
    }

    @Test
    fun freshSessionAfterGapIsReanchoredAndReyawed() {
        // After the loss ARCore comes back in a different world frame (150 degrees, shifted origin).
        val w = square(walk())
        val v = square(vio(headingDeg = 30.0))
            .lose(10.0, 12.0, paused = true)
            .reset(12.0, Math.toRadians(150.0), Vec3(3.0, -2.0, 1.0))
        val r = run(v.buildWith(w.buildRecords()))
        assertEquals("1", r.diagnostics["trackingLossCount"])
        assertEquals("1", r.diagnostics["reanchorYawRealigned"])
        assertTrue(maxJump(r) <= 0.5, "largest jump ${maxJump(r)}")
        for (i in r.points.indices) {
            val truth = v.truthAt(secondsOf(r, i))
            assertNear(truth, r.points[i].p, 0.8, "point $i at ${secondsOf(r, i)} s")
        }
        assertNear(Vec3.ZERO, r.points.last().p, 0.8, "back at the origin")
    }

    @Test
    fun withoutFallbackTheGapStaysAHole() {
        val w = square(walk())
        val v = square(vio()).lose(13.0, 16.0, paused = true)
        val r = run(v.buildWith(w.buildRecords()), config().copy(pdrFallbackWhenTrackingLost = false))
        assertEquals("none: fallback disabled", r.diagnostics["trackingLostFill"])
        assertEquals("1", r.diagnostics["trackingLossCount"])
        assertTrue(r.points.none { it.source == PositionSource.PDR })
        assertTrue(maxJump(r) > 1.0, "the hole is visible as a jump: ${maxJump(r)}")
        // ARCore's frame is trusted as is, so the points after the gap still match the truth.
        assertNear(v.truthAt(secondsOf(r, r.points.size - 1)), r.points.last().p, 0.1, "end")
    }

    @Test
    fun lossUntilTheEndIsFilledToTheEnd() {
        val w = square(walk())
        val v = square(vio()).lose(15.0, 100.0, paused = true)
        val r = run(v.buildWith(w.buildRecords()))
        assertEquals("1", r.diagnostics["trackingLossCount"])
        assertTrue(assertNotNull(r.diagnostics["trackingGaps"]).contains("to end"))
        assertEquals(PositionSource.PDR, r.points.last().source)
        assertNear(Vec3.ZERO, r.points.last().p, 1.0, "PDR brings the walker back near the origin")
    }

    @Test
    fun logWithoutTrackingFramesIsDelegatedToPdr() {
        val w = square(walk())
        val v = square(vio()).lose(0.0, 100.0, paused = true)
        val log = v.buildWith(w.buildRecords())
        assertTrue(log.poses.isNotEmpty() && !log.hasVio)
        val r = run(log)
        assertEquals("delegated to PDR: no TRACKING poses", r.diagnostics["vio"])
        val reference = PdrProcessor().process(log, config())
        assertEquals(reference.points, r.points)
        assertEquals(reference.stats, r.stats)
    }

    @Test
    fun keyframesUseTheirRecordedPoseOrFallBackToThePath() {
        val v = square(vio(headingDeg = 30.0)).lose(13.0, 16.0, paused = true)
        val w = square(walk())
        val withPose = v.keyframe(5.0, "a.jpg")
        val inGap = v.keyframe(14.0, "gap.jpg")
        val noPose = KeyframeSample(SyntheticVio.ns(20.0), "b.jpg", 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val r = run(v.buildWith(w.buildRecords(), listOf(withPose, inGap, noPose)))
        assertEquals(3, r.keyframes.size)
        assertEquals("a.jpg", r.keyframes[0].fileName)
        assertNear(v.truthAt(5.0), r.keyframes[0].p, 0.1, "keyframe by pose")
        assertTrue(abs(Angles.diff(r.keyframes[0].headingRad, v.headingAt(5.0))) < Math.toRadians(3.0))
        // The keyframe inside the gap has a pose but no tracking run within 0.5 s of it, so it is placed
        // on the path like the pose-less one.
        assertEquals("1", r.diagnostics["keyframesByPose"])
        assertEquals("2", r.diagnostics["keyframesByPath"])
        val nearGap = r.points.minByOrNull { abs(it.tNs - inGap.tNs) }
        assertEquals(assertNotNull(nearGap).p, r.keyframes[1].p)
        // The pose-less keyframe sits on the nearest path point.
        val near = r.points.minByOrNull { abs(it.tNs - noPose.tNs) }
        assertEquals(assertNotNull(near).p, r.keyframes[2].p)
    }

    @Test
    fun annotationsAndLoopClosureFollowThePath() {
        val w = square(walk())
        val v = square(vio()).lose(13.0, 16.0, paused = true)
        val closure = AnnotationRecord(SyntheticVio.ns(27.0), AnnotationKind.LOOP_CLOSED, "back")
        val waypoint = AnnotationRecord(SyntheticVio.ns(5.0), AnnotationKind.WAYPOINT, "w")
        val r = run(v.buildWith(w.buildRecords(), listOf(waypoint, closure)))
        assertEquals(2, r.annotations.size)
        assertNear(v.truthAt(5.0), r.annotations[0].p, 0.3, "waypoint")
        assertNotNull(r.stats.closureErrorM)
        assertTrue(assertNotNull(r.diagnostics["loopClosure"]).startsWith("applied"))
        assertNear(Vec3.ZERO, r.annotations[1].p, 0.1, "closure annotation after correction")
        assertTrue(r.stats.stepCount > 30, "IMU steps are counted even in VIO mode: ${r.stats.stepCount}")
        assertNotNull(r.diagnostics["pdr.orientationSource"])
    }

    @Test
    fun pointCloudIsDecimatedAndCapped() {
        val v = square(vio(headingDeg = 45.0)).lose(13.0, 16.0, paused = true).pointClouds(120, 2.0)
        val w = square(walk())
        val r = run(v.buildWith(w.buildRecords()))
        val raw = assertNotNull(r.diagnostics["pointCloudRaw"]).toInt()
        val confident = assertNotNull(r.diagnostics["pointCloudConfident"]).toInt()
        val voxels = assertNotNull(r.diagnostics["pointCloudVoxels"]).toInt()
        assertTrue(raw > 50_000, "raw $raw")
        assertTrue(confident < raw && confident > raw / 2, "confident $confident of $raw")
        assertTrue(voxels > 20_000, "the scene should hold more voxels than the cap: $voxels")
        assertEquals(20_000, r.pointCloud.size)
        // Points stay around the path (the run after the gap carries the re-anchor offset of up to a
        // stride) and are spread over the whole trip, not just its start.
        for (p in r.pointCloud) assertTrue(p.z > -2.6 && p.z < 2.6 && p.x > -2.6 && p.x < 7.6, "point $p")
        assertTrue(r.pointCloud.any { it.y < 1.0 } && r.pointCloud.any { it.y > 4.0 })
    }

    @Test
    fun resamplePeriodIsHonoured() {
        val v = vio().still(1.0).walkTo(0.0, 4.0)
        val r = run(v.build(), config().copy(vioResamplePeriodS = 0.5))
        for (i in 1 until r.points.size - 1) {
            assertEquals(500_000_000L, r.points[i].tNs - r.points[i - 1].tNs)
        }
        val every = run(v.build(), config().copy(vioResamplePeriodS = 0.0))
        assertEquals(v.poses().size, every.points.size)
    }
}

class TrackingRunsTest {

    private fun pose(s: Double, state: TrackingState = TrackingState.TRACKING): PoseSample {
        val t = SyntheticVio.ns(s)
        return PoseSample(t, t, 0f, 0f, 0f, 0f, 0f, 0f, 1f, state, 0)
    }

    @Test
    fun splitsAtLongPausesStoppedFramesHolesAndBackwardsTime() {
        val poses = listOf(
            pose(0.0, TrackingState.PAUSED), pose(0.1), pose(0.2), pose(0.3, TrackingState.PAUSED),
            pose(0.4), pose(0.5), pose(0.6, TrackingState.PAUSED), pose(0.8, TrackingState.PAUSED),
            pose(1.0, TrackingState.PAUSED), pose(1.1), pose(1.2), pose(1.3, TrackingState.STOPPED),
            pose(1.4), pose(1.5), pose(2.5), pose(2.6), pose(2.0), pose(2.7), pose(2.7),
        )
        val runs = TrackingRuns.split(poses, 500_000_000L)
        assertEquals(4, runs.size)
        // A single PAUSED frame does not end a run; the 0.6 s of PAUSED frames after 0.5 s does.
        assertEquals(listOf(0.1, 0.2, 0.4, 0.5).map { SyntheticVio.ns(it) }, runs[0].poses.map { it.tNs })
        assertEquals(2, runs[1].poses.size)
        assertEquals(SyntheticVio.ns(1.1), runs[1].firstNs)
        assertEquals(2, runs[2].poses.size)
        assertEquals(SyntheticVio.ns(1.4), runs[2].firstNs)
        assertEquals(3, runs[3].poses.size)
        assertEquals(SyntheticVio.ns(2.5), runs[3].firstNs)
        assertEquals(SyntheticVio.ns(2.7), runs[3].lastNs)
        assertEquals(0L, runs[3].distanceNs(SyntheticVio.ns(2.6)))
        assertEquals(100_000_000L, runs[3].distanceNs(SyntheticVio.ns(2.8)))
        assertTrue(TrackingRuns.split(listOf(pose(0.0, TrackingState.STOPPED)), 500_000_000L).isEmpty())
        assertTrue(TrackingRuns.split(listOf(pose(0.0, TrackingState.PAUSED)), 500_000_000L).isEmpty())
    }
}

class FrameTransformTest {

    @Test
    fun anchoringAndRealigningLandWhereAsked() {
        val qAr = Quat.fromAxisAngle(Vec3.UNIT_Y, 0.7)
        val pAr = Vec3(1.0, 2.0, 3.0)
        val target = Vec3(-4.0, 5.0, 0.5)
        val anchored = FrameTransform(Quat.yaw(0.3), Vec3(9.0, 9.0, 9.0)).anchoredAt(pAr, target)
        assertTrue(anchored.position(pAr).distanceTo(target) < 1e-9)
        assertEquals(anchored.cameraHeadingRad(qAr), FrameTransform(Quat.yaw(0.3), Vec3.ZERO).cameraHeadingRad(qAr))

        val realigned = anchored.realigned(qAr, 2.0, pAr, target)
        assertTrue(realigned.position(pAr).distanceTo(target) < 1e-9)
        assertTrue(abs(Angles.diff(realigned.cameraHeadingRad(qAr), 2.0)) < 1e-9)
        // Distances are preserved by any transform: it is rigid.
        val other = Vec3(2.0, -1.0, 0.0)
        assertTrue(abs(realigned.position(other).distanceTo(realigned.position(pAr)) - other.distanceTo(pAr)) < 1e-9)
    }

    @Test
    fun arcoreAxesMapToEnu() {
        val t = FrameTransform.IDENTITY
        assertTrue(t.position(Vec3(1.0, 0.0, 0.0)).distanceTo(Vec3(1.0, 0.0, 0.0)) < 1e-12, "x is east")
        assertTrue(t.position(Vec3(0.0, 1.0, 0.0)).distanceTo(Vec3(0.0, 0.0, 1.0)) < 1e-12, "y is up")
        assertTrue(t.position(Vec3(0.0, 0.0, -1.0)).distanceTo(Vec3(0.0, 1.0, 0.0)) < 1e-12, "-z is north")
    }
}

class PointCloudDecimatorTest {

    @Test
    fun dropsLowConfidenceMergesVoxelsAndCaps() {
        val d = PointCloudDecimator(0.1, 3, 0.3f)
        val id = FrameTransform.IDENTITY
        d.add(floatArrayOf(0.01f, 0.01f, 0.01f, 0.9f, 0.02f, 0.02f, 0.02f, 0.9f, 0.5f, 0.5f, 0.5f, 0.2f), id)
        assertEquals(3, d.rawCount)
        assertEquals(2, d.confidentCount)
        assertEquals(1, d.voxelCount)
        d.add(floatArrayOf(1f, 0f, 0f, 1f, 2f, 0f, 0f, 1f, 3f, 0f, 0f, 1f, 4f, 0f, 0f, 1f, 5f, 0f, 0f, 1f), id)
        assertEquals(6, d.voxelCount)
        val kept = d.result()
        assertEquals(3, kept.size)
        // Thinned uniformly: first, third and fifth of the six voxels (the first keeps its own coordinates).
        assertTrue(abs(kept[0].x - 0.01) < 1e-6 && kept[1].x == 2.0 && kept[2].x == 4.0, "kept $kept")
        // Negative coordinates get their own voxels too.
        d.add(floatArrayOf(-0.05f, 0f, 0f, 1f), id)
        assertEquals(7, d.voxelCount)
    }
}
