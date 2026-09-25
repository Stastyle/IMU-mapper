package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.AccelSample
import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.AnnotationRecord
import com.stastyle.imumapper.pipeline.core.BaroSample
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.EventKind
import com.stastyle.imumapper.pipeline.core.EventRecord
import com.stastyle.imumapper.pipeline.core.GyroSample
import com.stastyle.imumapper.pipeline.core.LogMeta
import com.stastyle.imumapper.pipeline.core.LogRecord
import com.stastyle.imumapper.pipeline.core.MagSample
import com.stastyle.imumapper.pipeline.core.MetaRecord
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.RotationSample
import com.stastyle.imumapper.pipeline.core.RotationSource
import com.stastyle.imumapper.pipeline.core.StepSample
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.log.LogWriter
import com.stastyle.imumapper.pipeline.log.RawLog
import kotlinx.serialization.json.Json
import java.io.OutputStream
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic synthetic pedestrian log: a walker follows legs at constant speed with a steady
 * cadence; the phone rides along with a fixed tilt and a walking-direction offset. Generates
 * accelerometer (gravity + vertical bounce + forward/lateral gait oscillation + noise), gyroscope
 * (differentiated from the true orientation, plus bias), game and fused rotation vectors (the game
 * one with optional yaw drift), magnetometer, barometer and hardware step events.
 *
 * Steps happen at the cadence while walking, so the true stride is speed / cadence.
 */
class SyntheticWalk(
    val rateHz: Int = 200,
    val speedMps: Double = 1.2,
    val cadenceHz: Double = 1.8,
    val tiltRad: Double = 0.4,
    val includeRotation: Boolean = true,
    val includeFused: Boolean = true,
    val includeMag: Boolean = true,
    val includeBaro: Boolean = true,
    val includeHardwareSteps: Boolean = true,
    /** Slow yaw drift of the game rotation vector, rad/s (positive = counter-clockwise). */
    val gameYawDriftRadPerS: Double = 0.0,
    val gyroBias: Vec3 = Vec3.ZERO,
    val noiseSeed: Int = 7,
    val accelNoise: Double = 0.08,
    val verticalBounce: Double = 2.5,
    val forwardBounce: Double = 1.2,
    val lateralBounce: Double = 0.5,
) {
    val strideM: Double get() = speedMps / cadenceHz

    private sealed interface Leg
    private class Still(val seconds: Double) : Leg
    private class Walk(val to: Vec3) : Leg
    private class Annotate(val kind: AnnotationKind, val note: String) : Leg
    private class Offset(val rad: Double) : Leg

    private val legs = ArrayList<Leg>()
    private var initialOffsetRad = 0.0

    fun still(seconds: Double): SyntheticWalk = apply { legs.add(Still(seconds)) }
    fun walkTo(x: Double, y: Double, z: Double = 0.0): SyntheticWalk = apply { legs.add(Walk(Vec3(x, y, z))) }
    fun annotate(kind: AnnotationKind, note: String = ""): SyntheticWalk = apply { legs.add(Annotate(kind, note)) }

    /** Changes how the phone is carried from now on: walking heading minus device heading. */
    fun deviceOffset(rad: Double): SyntheticWalk = apply {
        if (legs.isEmpty()) initialOffsetRad = rad else legs.add(Offset(rad))
    }

    private class Phase(
        val startS: Double,
        val endS: Double,
        val from: Vec3,
        val to: Vec3,
        val walking: Boolean,
        val headingRad: Double,
    )

    private class Timeline(
        val phases: List<Phase>,
        val annotations: List<Pair<Double, AnnotationRecord>>,
        val offsetChanges: List<Pair<Double, Double>>,
        val totalS: Double,
    )

    private fun timeline(): Timeline {
        val phases = ArrayList<Phase>()
        val annotations = ArrayList<Pair<Double, AnnotationRecord>>()
        val offsets = ArrayList<Pair<Double, Double>>()
        var t = 0.0
        var pos = Vec3.ZERO
        var heading = 0.0
        for (leg in legs) {
            when (leg) {
                is Still -> {
                    phases.add(Phase(t, t + leg.seconds, pos, pos, false, heading))
                    t += leg.seconds
                }
                is Walk -> {
                    val d = leg.to - pos
                    val horizontal = sqrt(d.x * d.x + d.y * d.y)
                    if (horizontal > 1e-9) heading = atan2(d.x, d.y)
                    val seconds = horizontal / speedMps
                    phases.add(Phase(t, t + seconds, pos, leg.to, true, heading))
                    t += seconds
                    pos = leg.to
                }
                is Annotate -> annotations.add(t to AnnotationRecord(0L, leg.kind, leg.note))
                is Offset -> offsets.add(t to leg.rad)
            }
        }
        return Timeline(phases, annotations, offsets, t)
    }

    private fun phaseAt(phases: List<Phase>, s: Double): Phase {
        var p = phases[0]
        for (ph in phases) if (ph.startS <= s) p = ph else break
        return p
    }

    private fun positionAt(ph: Phase, s: Double): Vec3 {
        if (!ph.walking || ph.endS <= ph.startS) return ph.from
        val f = ((s - ph.startS) / (ph.endS - ph.startS)).coerceIn(0.0, 1.0)
        return ph.from.lerp(ph.to, f)
    }

    /** Walking heading with turns blended over [turnS] seconds so the gyro sees finite rates. */
    private fun headingAt(phases: List<Phase>, s: Double, turnS: Double = 0.6): Double {
        val idx = phases.indexOf(phaseAt(phases, s))
        val ph = phases[idx]
        if (idx == 0 || s - ph.startS >= turnS) return ph.headingRad
        val prev = phases[idx - 1].headingRad
        val f = (s - ph.startS) / turnS
        val d = Angles.diff(ph.headingRad, prev)
        return Angles.wrap(prev + d * f)
    }

    private fun offsetAt(changes: List<Pair<Double, Double>>, s: Double, blendS: Double = 1.0): Double {
        var off = initialOffsetRad
        var prev = initialOffsetRad
        for ((at, rad) in changes) {
            if (s < at) break
            prev = off
            off = rad
            if (s - at < blendS) {
                val f = (s - at) / blendS
                return Angles.wrap(prev + Angles.diff(rad, prev) * f)
            }
        }
        return off
    }

    /** Orientation of the device (sensor -> ENU) given the device heading and gait phase. */
    private fun deviceQuat(deviceHeadingRad: Double, gaitPhase: Double): Quat {
        val sway = Quat.fromAxisAngle(Vec3.UNIT_Y, 0.04 * sin(gaitPhase / 2.0))
        return (Quat.yaw(-deviceHeadingRad) * Quat.fromAxisAngle(Vec3.UNIT_X, tiltRad) * sway).normalized()
    }

    fun buildRecords(): List<LogRecord> {
        val tl = timeline()
        val rng = Random(noiseSeed)
        val out = ArrayList<LogRecord>()
        val dt = 1.0 / rateHz
        val n = (tl.totalS * rateHz).toInt() + 1
        val g = WorldAccel.GRAVITY
        val magWorld = Vec3(0.0, 22.0, -40.0)
        var gait = 0.0
        var lastStepCycle = -1
        var prevQ: Quat? = null
        val rotEvery = maxOf(1, rateHz / 100)
        val magEvery = maxOf(1, rateHz / 50)
        val baroEvery = maxOf(1, rateHz / 25)
        out.add(EventRecord(ns(0.0), EventKind.START))
        for (k in 0 until n) {
            val s = k * dt
            val ph = phaseAt(tl.phases, s)
            val walkHeading = headingAt(tl.phases, s)
            val offset = offsetAt(tl.offsetChanges, s)
            val deviceHeading = Angles.wrap(walkHeading - offset)
            val walking = ph.walking
            if (walking) gait += 2.0 * PI * cadenceHz * dt
            val q = deviceQuat(deviceHeading, gait)
            val t = ns(s)

            // World-frame acceleration: gravity plus gait oscillation while walking.
            var ax = 0.0
            var ay = 0.0
            var az = g
            if (walking) {
                val fwdX = sin(walkHeading)
                val fwdY = cos(walkHeading)
                val fwd = forwardBounce * cos(gait)
                val lat = lateralBounce * sin(gait / 2.0)
                ax += fwd * fwdX + lat * fwdY
                ay += fwd * fwdY - lat * fwdX
                az += verticalBounce * sin(gait)
            }
            ax += gaussian(rng) * accelNoise
            ay += gaussian(rng) * accelNoise
            az += gaussian(rng) * accelNoise
            val aSensor = q.conjugate().rotate(Vec3(ax, ay, az))
            out.add(AccelSample(t, aSensor.x.toFloat(), aSensor.y.toFloat(), aSensor.z.toFloat()))

            // Gyro from the orientation change over one sample, in the sensor frame.
            val pq = prevQ
            var wx = 0.0
            var wy = 0.0
            var wz = 0.0
            if (pq != null) {
                var dq = pq.conjugate() * q
                if (dq.w < 0.0) dq = Quat(-dq.w, -dq.x, -dq.y, -dq.z)
                val vn = sqrt(dq.x * dq.x + dq.y * dq.y + dq.z * dq.z)
                if (vn > 1e-12) {
                    val angle = 2.0 * atan2(vn, dq.w)
                    wx = dq.x / vn * angle / dt
                    wy = dq.y / vn * angle / dt
                    wz = dq.z / vn * angle / dt
                }
            }
            prevQ = q
            out.add(
                GyroSample(
                    t,
                    (wx + gyroBias.x + gaussian(rng) * 0.002).toFloat(),
                    (wy + gyroBias.y + gaussian(rng) * 0.002).toFloat(),
                    (wz + gyroBias.z + gaussian(rng) * 0.002).toFloat(),
                ),
            )

            if (includeRotation && k % rotEvery == 0) {
                val drifted = (Quat.yaw(gameYawDriftRadPerS * s) * q).normalized()
                out.add(rotation(t, drifted, RotationSource.GAME))
                if (includeFused) out.add(rotation(t, q, RotationSource.FUSED))
            }
            if (includeMag && k % magEvery == 0) {
                val m = q.conjugate().rotate(magWorld)
                val mx = (m.x + gaussian(rng) * 0.3).toFloat()
                val my = (m.y + gaussian(rng) * 0.3).toFloat()
                val mz = (m.z + gaussian(rng) * 0.3).toFloat()
                out.add(MagSample(t, mx, my, mz))
            }
            if (includeBaro && k % baroEvery == 0) {
                val z = positionAt(ph, s).z
                val p = P0 * (1.0 - z / 44330.0).pow(1.0 / 0.1903) + gaussian(rng) * 0.01
                out.add(BaroSample(t, p.toFloat()))
            }
            // A hardware step event at every vertical-acceleration peak (gait phase pi/2 + 2 pi k).
            if (includeHardwareSteps && walking) {
                val cycle = floor((gait - PI / 2.0) / (2.0 * PI)).toInt()
                if (cycle > lastStepCycle && gait >= PI / 2.0) {
                    lastStepCycle = cycle
                    out.add(StepSample(t))
                }
            }
        }
        for ((s, a) in tl.annotations) out.add(AnnotationRecord(ns(s), a.kind, a.note))
        out.add(EventRecord(ns(tl.totalS), EventKind.STOP))
        return out
    }

    fun meta(config: PipelineConfig = PipelineConfig()): LogMeta = LogMeta(
        appVersion = "synthetic",
        deviceModel = "SyntheticWalk",
        androidSdk = 35,
        mode = TripMode.POCKET,
        carryPosition = CarryPosition.HAND,
        startedAtEpochMs = 1_700_000_000_000L,
        sensorPeriodsUs = mapOf("accelerometer" to 1_000_000 / rateHz, "gyroscope" to 1_000_000 / rateHz),
        config = config,
        notes = "generated by SyntheticWalk",
    )

    fun build(config: PipelineConfig = PipelineConfig()): RawLog {
        val b = RawLog.Builder()
        b.add(MetaRecord(Json.encodeToString(LogMeta.serializer(), meta(config))))
        for (r in buildRecords()) b.add(r)
        return b.build()
    }

    /** Writes the log in file (time) order through [LogWriter]. */
    fun write(out: OutputStream, config: PipelineConfig = PipelineConfig()) {
        LogWriter(out).use { w ->
            w.writeMeta(meta(config))
            for (r in buildRecords().sortedBy { it.tNs }) w.write(r)
        }
    }

    /** Total length of the walking legs in metres (horizontal). */
    fun trueDistanceM(): Double {
        var d = 0.0
        for (ph in timeline().phases) if (ph.walking) {
            val dx = ph.to.x - ph.from.x
            val dy = ph.to.y - ph.from.y
            d += sqrt(dx * dx + dy * dy)
        }
        return d
    }

    private fun ns(s: Double): Long = BASE_NS + Math.round(s * 1e9)

    private fun rotation(t: Long, q: Quat, source: RotationSource): RotationSample {
        val accuracy = if (source == RotationSource.GAME) -1f else 0.05f
        return RotationSample(t, q.x.toFloat(), q.y.toFloat(), q.z.toFloat(), q.w.toFloat(), accuracy, source)
    }

    private fun gaussian(rng: Random): Double {
        // Box-Muller; deterministic through the seeded generator.
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * Math.log(u1)) * cos(2.0 * PI * u2)
    }

    companion object {
        const val BASE_NS: Long = 1_000_000_000_000L
        const val P0: Double = 1013.25
    }
}
