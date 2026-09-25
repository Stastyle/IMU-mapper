package com.stastyle.imumapper.pipeline.vio

import com.stastyle.imumapper.pipeline.core.KeyframeSample
import com.stastyle.imumapper.pipeline.core.LogRecord
import com.stastyle.imumapper.pipeline.core.PointCloudSample
import com.stastyle.imumapper.pipeline.core.PoseSample
import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.TrackingState
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.log.RawLog
import com.stastyle.imumapper.pipeline.pdr.Angles
import com.stastyle.imumapper.pipeline.pdr.SyntheticWalk
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic synthetic ARCore pose stream. The walker follows the same leg script and timing
 * as [SyntheticWalk] (constant speed legs, turns blended over 0.6 s), so the two generators can be
 * combined into one log: the walk supplies the IMU records and this class the poses, point clouds
 * and keyframes. The camera is held level, looking along the walking direction and pitched down
 * by [cameraPitchRad].
 *
 * ARCore's world frame is defined by a session: [sessionHeadingRad] is the ENU compass heading of
 * ARCore's -Z axis and [sessionOriginAr] the ARCore coordinates of the ENU origin. A [reset]
 * switches to a new session frame from a given time, which is what a fresh ARCore session after a
 * long loss looks like.
 */
class SyntheticVio(
    val frameHz: Int = 30,
    val speedMps: Double = 1.2,
    val sessionHeadingRad: Double = 0.0,
    val sessionOriginAr: Vec3 = Vec3.ZERO,
    val cameraPitchRad: Double = 0.2,
    val turnS: Double = 0.6,
) {
    private class Session(val headingRad: Double, val originAr: Vec3) {
        private val enuToAr: Quat = (Quat.yaw(-headingRad) * ArCoreFrame.AR_TO_ENU).normalized().conjugate()

        fun positionAr(pEnu: Vec3): Vec3 = enuToAr.rotate(pEnu) + originAr
        fun orientationAr(qEnu: Quat): Quat = (enuToAr * qEnu).normalized()
    }

    private sealed interface Leg
    private class Still(val seconds: Double) : Leg
    private class Walk(val to: Vec3) : Leg

    private class Phase(val startS: Double, val endS: Double, val from: Vec3, val to: Vec3, val headingRad: Double)

    private class Gap(val fromS: Double, val toS: Double, val paused: Boolean)
    private class Reset(val atS: Double, val session: Session)

    private val legs = ArrayList<Leg>()
    private val gaps = ArrayList<Gap>()
    private val resets = ArrayList<Reset>()
    private var pointsPerFrame = 0
    private var pointSpreadM = 0.0

    fun still(seconds: Double): SyntheticVio = apply { legs.add(Still(seconds)) }
    fun walkTo(x: Double, y: Double, z: Double = 0.0): SyntheticVio = apply { legs.add(Walk(Vec3(x, y, z))) }

    /** Tracking is lost in [fromS, toS): frames are reported PAUSED with a frozen pose, or not at all. */
    fun lose(fromS: Double, toS: Double, paused: Boolean = true): SyntheticVio =
        apply { gaps.add(Gap(fromS, toS, paused)) }

    /** From [atS] on, poses come from a new ARCore session with its own world frame. */
    fun reset(atS: Double, headingRad: Double, originAr: Vec3): SyntheticVio =
        apply { resets.add(Reset(atS, Session(headingRad, originAr))) }

    /** Emits a point cloud with every frame: [count] points within [spreadM] of the camera. */
    fun pointClouds(count: Int, spreadM: Double): SyntheticVio = apply {
        pointsPerFrame = count
        pointSpreadM = spreadM
    }

    val totalS: Double get() = phases().let { if (it.isEmpty()) 0.0 else it[it.size - 1].endS }

    private fun phases(): List<Phase> {
        val out = ArrayList<Phase>()
        var t = 0.0
        var pos = Vec3.ZERO
        var heading = 0.0
        for (leg in legs) {
            when (leg) {
                is Still -> {
                    out.add(Phase(t, t + leg.seconds, pos, pos, heading))
                    t += leg.seconds
                }
                is Walk -> {
                    val d = leg.to - pos
                    val horizontal = sqrt(d.x * d.x + d.y * d.y)
                    if (horizontal > 1e-9) heading = atan2(d.x, d.y)
                    val seconds = horizontal / speedMps
                    out.add(Phase(t, t + seconds, pos, leg.to, heading))
                    t += seconds
                    pos = leg.to
                }
            }
        }
        return out
    }

    private fun phaseIndex(phases: List<Phase>, s: Double): Int {
        var idx = 0
        for (i in phases.indices) if (phases[i].startS <= s) idx = i else break
        return idx
    }

    /** True ENU position at [s] seconds. */
    fun truthAt(s: Double): Vec3 {
        val phases = phases()
        val ph = phases[phaseIndex(phases, s)]
        if (ph.endS <= ph.startS) return ph.from
        val f = ((s - ph.startS) / (ph.endS - ph.startS)).coerceIn(0.0, 1.0)
        return ph.from.lerp(ph.to, f)
    }

    /** Camera (= walking) heading at [s] seconds, blended through turns like [SyntheticWalk]. */
    fun headingAt(s: Double): Double {
        val phases = phases()
        val idx = phaseIndex(phases, s)
        val ph = phases[idx]
        if (idx == 0 || s - ph.startS >= turnS) return ph.headingRad
        val prev = phases[idx - 1].headingRad
        val f = (s - ph.startS) / turnS
        return Angles.wrap(prev + Angles.diff(ph.headingRad, prev) * f)
    }

    /** Camera-to-ENU orientation of a level camera looking along [headingRad], pitched down. */
    fun cameraQuatEnu(headingRad: Double): Quat =
        (Quat.yaw(-headingRad) * Quat.fromAxisAngle(Vec3.UNIT_X, PI / 2.0 - cameraPitchRad)).normalized()

    private fun sessionAt(s: Double): Session {
        var session = Session(sessionHeadingRad, sessionOriginAr)
        for (r in resets) if (r.atS <= s) session = r.session
        return session
    }

    private fun gapAt(s: Double): Gap? {
        for (g in gaps) if (g.fromS <= s && s < g.toS) return g
        return null
    }

    /** ARCore coordinates of an ENU position under the session frame in force at [s]. */
    fun toAr(pEnu: Vec3, s: Double): Vec3 = sessionAt(s).positionAr(pEnu)

    fun toArOrientation(qEnu: Quat, s: Double): Quat = sessionAt(s).orientationAr(qEnu)

    /** A keyframe record at [s] seconds carrying the true camera pose in ARCore coordinates. */
    fun keyframe(s: Double, fileName: String): KeyframeSample {
        val p = toAr(truthAt(s), s)
        val q = toArOrientation(cameraQuatEnu(headingAt(s)), s)
        return KeyframeSample(
            ns(s), fileName, p.x.toFloat(), p.y.toFloat(), p.z.toFloat(),
            q.x.toFloat(), q.y.toFloat(), q.z.toFloat(), q.w.toFloat(),
        )
    }

    fun poses(): List<PoseSample> = records().filterIsInstance<PoseSample>()

    fun records(): List<LogRecord> {
        val out = ArrayList<LogRecord>()
        val rng = Random(11)
        val total = totalS
        val n = (total * frameHz).toInt() + 1
        var frozen: PoseSample? = null
        for (k in 0 until n) {
            val s = k.toDouble() / frameHz
            val t = ns(s)
            val gap = gapAt(s)
            if (gap != null) {
                if (gap.paused) {
                    // ARCore keeps reporting the last pose while paused; the recorder logs it anyway.
                    val f = frozen
                    val pose = if (f == null) {
                        pose(t, s, TrackingState.PAUSED)
                    } else {
                        f.copy(tNs = t, frameTimestampNs = t, tracking = TrackingState.PAUSED, failureReason = 2)
                    }
                    out.add(pose)
                }
                continue
            }
            val pose = pose(t, s, TrackingState.TRACKING)
            frozen = pose
            out.add(pose)
            if (pointsPerFrame > 0) {
                val truth = truthAt(s)
                val xyzc = FloatArray(pointsPerFrame * 4)
                for (i in 0 until pointsPerFrame) {
                    val p = Vec3(
                        truth.x + (rng.nextDouble() * 2.0 - 1.0) * pointSpreadM,
                        truth.y + (rng.nextDouble() * 2.0 - 1.0) * pointSpreadM,
                        truth.z + (rng.nextDouble() * 2.0 - 1.0) * pointSpreadM,
                    )
                    val ar = toAr(p, s)
                    xyzc[i * 4] = ar.x.toFloat()
                    xyzc[i * 4 + 1] = ar.y.toFloat()
                    xyzc[i * 4 + 2] = ar.z.toFloat()
                    xyzc[i * 4 + 3] = rng.nextDouble().toFloat()
                }
                out.add(PointCloudSample(t, xyzc))
            }
        }
        return out
    }

    private fun pose(t: Long, s: Double, state: TrackingState): PoseSample {
        val p = toAr(truthAt(s), s)
        val q = toArOrientation(cameraQuatEnu(headingAt(s)), s)
        return PoseSample(
            t, t, p.x.toFloat(), p.y.toFloat(), p.z.toFloat(),
            q.x.toFloat(), q.y.toFloat(), q.z.toFloat(), q.w.toFloat(),
            state, if (state == TrackingState.TRACKING) 0 else 2,
        )
    }

    /** A log holding only this generator's records (no IMU). */
    fun build(): RawLog = buildWith(emptyList())

    /** A log holding [imuRecords] (typically [SyntheticWalk.buildRecords]) plus this generator's records. */
    fun buildWith(imuRecords: List<LogRecord>, extra: List<LogRecord> = emptyList()): RawLog {
        val b = RawLog.Builder()
        for (r in imuRecords) b.add(r)
        for (r in records()) b.add(r)
        for (r in extra) b.add(r)
        return b.build()
    }

    companion object {
        fun ns(s: Double): Long = SyntheticWalk.BASE_NS + Math.round(s * 1e9)
    }
}
