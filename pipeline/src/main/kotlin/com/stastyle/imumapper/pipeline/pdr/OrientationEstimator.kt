package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.RotationSample
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.log.RawLog
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds the device-to-ENU [OrientationTrack] of a log.
 *
 * Source priority: the game rotation vector (gyro + accel, drift-free tilt, slowly drifting yaw);
 * then the fused rotation vector alone if that is all the log has; then a [MadgwickFilter] over
 * raw gyro (bias removed) and accel. When the game vector is primary and the config allows the
 * magnetometer, the yaw difference between the fused and the game vector is low-passed over
 * [yawCorrectionTimeConstantS] and applied as a correction, but only from moments where the
 * magnetic field passed the [MagGate]. The correction is relative to the difference seen in the
 * first second, so the trip still starts in the game vector's frame and only the drift is removed.
 */
class OrientationEstimator(
    private val madgwickBeta: Double = 0.1,
    private val yawCorrectionTimeConstantS: Double = 5.0,
    private val referenceWindowS: Double = 1.0,
) {
    enum class Source { GAME, FUSED, MADGWICK, NONE }

    class Result(val track: OrientationTrack, val source: Source, val diagnostics: Map<String, String>)

    fun estimate(log: RawLog, config: PipelineConfig): Result {
        val diag = LinkedHashMap<String, String>()
        val game = log.gameRotation
        val fused = log.fusedRotation
        if (game.isNotEmpty()) {
            val base = OrientationTrack.fromRotationSamples(game)
            diag["orientationSource"] = Source.GAME.name
            diag["rotationSamples"] = game.size.toString()
            val track = if (config.useMagnetometer && fused.isNotEmpty()) {
                correctYaw(base, fused, log, config, diag)
            } else {
                diag["yawCorrection"] = if (!config.useMagnetometer) "disabled" else "no fused rotation samples"
                base
            }
            return Result(track, Source.GAME, diag)
        }
        if (fused.isNotEmpty()) {
            diag["orientationSource"] = Source.FUSED.name
            diag["rotationSamples"] = fused.size.toString()
            diag["yawCorrection"] = "not needed: fused rotation vector is the primary source"
            return Result(OrientationTrack.fromRotationSamples(fused), Source.FUSED, diag)
        }
        if (log.gyro.isNotEmpty() && log.accel.isNotEmpty()) {
            diag["orientationSource"] = Source.MADGWICK.name
            diag["yawCorrection"] = "not available without rotation vector samples"
            diag["madgwickBeta"] = madgwickBeta.toString()
            return Result(madgwick(log, config), Source.MADGWICK, diag)
        }
        diag["orientationSource"] = Source.NONE.name
        diag["orientationWarning"] = "no rotation vector, gyro or accel samples; identity orientation used"
        return Result(OrientationTrack.EMPTY, Source.NONE, diag)
    }

    private fun madgwick(log: RawLog, config: PipelineConfig): OrientationTrack {
        val filter = MadgwickFilter(madgwickBeta)
        val accel = log.accel
        val builder = OrientationTrack.Builder(log.gyro.size)
        val bias = config.gyroBias
        var ai = 0
        var lastNs = Long.MIN_VALUE
        for (g in log.gyro) {
            if (g.tNs < lastNs) continue
            // Nearest accel sample at or before the gyro sample; accel arrives at a similar rate.
            while (ai + 1 < accel.size && accel[ai + 1].tNs <= g.tNs) ai++
            val a = accel[ai]
            val dt = if (lastNs == Long.MIN_VALUE) 0.0 else (g.tNs - lastNs) / 1e9
            if (!filter.initialized) filter.initFromAccel(a.x.toDouble(), a.y.toDouble(), a.z.toDouble())
            filter.update(
                g.x - bias.x, g.y - bias.y, g.z - bias.z,
                a.x.toDouble(), a.y.toDouble(), a.z.toDouble(),
                dt,
            )
            builder.add(g.tNs, filter.q)
            lastNs = g.tNs
        }
        return builder.build()
    }

    private fun correctYaw(
        base: OrientationTrack,
        fused: List<RotationSample>,
        log: RawLog,
        config: PipelineConfig,
        diag: MutableMap<String, String>,
    ): OrientationTrack {
        val gate = MagGate.fromStart(log.mag, base, config.magGateTolerance, referenceWindowS)
        if (gate == null) {
            diag["yawCorrection"] = "skipped: no magnetometer samples to gate the fused heading"
            return base
        }
        diag["magRefMagnitudeUt"] = Diag.num(gate.refMagnitudeUt, 2)
        diag["magRefDipDeg"] = Diag.num(Math.toDegrees(gate.refDipRad), 1)

        // Yaw difference fused - game per fused sample, gated by the nearest magnetometer reading.
        val n = fused.size
        val times = LongArray(n)
        val delta = DoubleArray(n)
        val pass = BooleanArray(n)
        val gameCursor = base.cursor()
        val mag = log.mag
        var mi = 0
        var passed = 0
        for (i in 0 until n) {
            val f = fused[i]
            val qg = gameCursor.at(f.tNs)
            while (mi + 1 < mag.size && mag[mi + 1].tNs <= f.tNs) mi++
            val m = mag[mi]
            val world = qg.rotate(Vec3.of(m.x, m.y, m.z))
            times[i] = f.tNs
            delta[i] = (f.toQuat() * qg.inverse()).eulerZXY().first
            pass[i] = gate.passes(world)
            if (pass[i]) passed++
        }
        diag["magGatePassFraction"] = Diag.num(passed.toDouble() / n, 3)
        if (passed == 0) {
            diag["yawCorrection"] = "skipped: magnetic field never passed the gate"
            return base
        }

        // Reference difference over the first second (gated), so only drift after that is removed.
        val refEnd = times[0] + (referenceWindowS * 1e9).toLong()
        var rc = 0.0
        var rs = 0.0
        var refCount = 0
        for (i in 0 until n) {
            if (times[i] > refEnd && refCount > 0) break
            if (!pass[i]) continue
            rc += cos(delta[i])
            rs += sin(delta[i])
            refCount++
        }
        if (refCount == 0) {
            diag["yawCorrection"] = "skipped: magnetic field disturbed during the reference second"
            return base
        }
        val ref = atan2(rs, rc)

        // Exponential average of the gated difference, tracked as a unit vector to avoid wrap-around,
        // then unwrapped into a continuous angle so linear interpolation between samples is safe.
        val corr = DoubleArray(n)
        var ec = cos(ref)
        var es = sin(ref)
        var lastNs = times[0]
        var unwrapped = 0.0
        var prevAngle = 0.0
        for (i in 0 until n) {
            val dt = (times[i] - lastNs) / 1e9
            lastNs = times[i]
            if (pass[i]) {
                val alpha = if (yawCorrectionTimeConstantS <= 0.0) 1.0 else dt / (yawCorrectionTimeConstantS + dt)
                ec += (cos(delta[i]) - ec) * alpha
                es += (sin(delta[i]) - es) * alpha
            }
            val angle = Angles.wrap(atan2(es, ec) - ref)
            unwrapped += Angles.diff(angle, prevAngle)
            prevAngle = angle
            corr[i] = unwrapped
        }
        diag["yawCorrectionFinalDeg"] = Diag.num(Math.toDegrees(corr[n - 1]), 2)
        diag["yawCorrection"] = "applied"

        val out = OrientationTrack.Builder(base.size)
        var ci = 0
        for (k in 0 until base.size) {
            val t = base.timeAt(k)
            while (ci + 1 < n && times[ci + 1] <= t) ci++
            val c = if (ci + 1 < n && times[ci + 1] > times[ci] && t > times[ci]) {
                val f = (t - times[ci]).toDouble() / (times[ci + 1] - times[ci]).toDouble()
                corr[ci] + (corr[ci + 1] - corr[ci]) * f.coerceIn(0.0, 1.0)
            } else {
                corr[ci]
            }
            out.add(t, (Quat.yaw(c) * base.quatAt(k)).normalized())
        }
        return out.build()
    }
}
