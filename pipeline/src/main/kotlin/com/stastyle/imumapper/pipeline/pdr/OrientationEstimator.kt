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
 * raw gyro (bias removed) and accel.
 *
 * The game vector's yaw starts wherever the sensor happened to start, a different angle in every
 * recording, so on its own the path would come out turned by a random angle. When the game vector
 * is primary, the yaw difference between the fused vector (referenced to magnetic north) and the
 * game vector is measured per fused sample, but only at moments where the magnetic field passed the
 * [MagGate]. Its circular mean over [referenceWindowS], starting at the first fused sample that
 * passes (normally the first one, so the first second of the trip), is the reference: with
 * [PipelineConfig.northFromCompass] on, the whole trip is turned by it, so +Y is magnetic north
 * (diagnostics `northReference` = "magnetic", `northOffsetDeg` = the reference). When that window
 * starts more than [referenceWindowS] after the first fused sample, `northReferenceAtS` says how
 * many seconds later. With [PipelineConfig.useMagnetometer] on, the difference is also low-passed
 * over [yawCorrectionTimeConstantS] and its change since the reference is applied as a drift
 * correction; with it off, the reference alone is applied and the magnetometer is ignored after
 * the start. With the fused vector primary the track is magnetic already. Otherwise the yaw stays
 * arbitrary and `northReference` starts with "relative: " followed by the reason.
 *
 * The gate is referenced to the field at the start of the log ([MagGate.fromStart]), so a start
 * that is steadily disturbed, for example by metal next to the phone for the whole first second,
 * passes the gate and is not detected: the trip is turned by the disturbed compass heading and
 * `northReference` still says "magnetic". Only a start whose readings stray from their own mean
 * fails the gate, and then the reference waits for the first reading that passes.
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
            val track = if ((config.useMagnetometer || config.northFromCompass) && fused.isNotEmpty()) {
                correctYaw(base, fused, log, config, diag)
            } else {
                diag["yawCorrection"] = if (!config.useMagnetometer) "disabled" else "no fused rotation samples"
                diag[NORTH_REFERENCE] = relativeNorth(config, "no fused rotation samples")
                base
            }
            return Result(track, Source.GAME, diag)
        }
        if (fused.isNotEmpty()) {
            diag["orientationSource"] = Source.FUSED.name
            diag["rotationSamples"] = fused.size.toString()
            diag["yawCorrection"] = "not needed: fused rotation vector is the primary source"
            diag[NORTH_REFERENCE] = MAGNETIC
            return Result(OrientationTrack.fromRotationSamples(fused), Source.FUSED, diag)
        }
        if (log.gyro.isNotEmpty() && log.accel.isNotEmpty()) {
            diag["orientationSource"] = Source.MADGWICK.name
            diag["yawCorrection"] = "not available without rotation vector samples"
            diag["madgwickBeta"] = madgwickBeta.toString()
            diag[NORTH_REFERENCE] = RELATIVE + "no rotation vector samples"
            return Result(madgwick(log, config), Source.MADGWICK, diag)
        }
        diag["orientationSource"] = Source.NONE.name
        diag["orientationWarning"] = "no rotation vector, gyro or accel samples; identity orientation used"
        diag[NORTH_REFERENCE] = NO_ORIENTATION
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

    /**
     * Turns the game track onto magnetic north and removes its yaw drift, as the config asks; see the
     * class comment. Only moments where the magnetic field passed the gate are trusted. The gate is
     * referenced to the start itself, so a start that is steadily disturbed passes it and is not
     * detected: the whole trip is turned by the disturbed heading. A start whose readings fail the
     * gate moves the reference to the first fused sample that passes (`northReferenceAtS`), and only
     * a trip where nothing passes keeps the game vector's yaw and says why in `northReference`.
     */
    private fun correctYaw(
        base: OrientationTrack,
        fused: List<RotationSample>,
        log: RawLog,
        config: PipelineConfig,
        diag: MutableMap<String, String>,
    ): OrientationTrack {
        val gate = MagGate.fromStart(log.mag, base, config.magGateTolerance, referenceWindowS)
        if (gate == null) {
            skip(config, diag, "no magnetometer samples to gate the fused heading", "no magnetometer samples")
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
            skip(config, diag, "magnetic field never passed the gate", "magnetic field never passed the gate")
            return base
        }

        // Reference difference: the gated circular mean over one window that starts at the first fused
        // sample passing the gate (the first sample on a normal trip, so the first second). It is the
        // yaw that turns the game frame onto magnetic north, and the zero of the drift correction below.
        val window = (referenceWindowS * 1e9).toLong()
        var refStart = 0
        while (!pass[refStart]) refStart++
        val refEnd = times[refStart] + window
        var rc = 0.0
        var rs = 0.0
        for (i in refStart until n) {
            if (times[i] > refEnd) break
            if (!pass[i]) continue
            rc += cos(delta[i])
            rs += sin(delta[i])
        }
        val ref = atan2(rs, rc)
        diag["northOffsetDeg"] = Diag.num(Math.toDegrees(ref), 1)
        val lateNs = times[refStart] - times[0]
        if (lateNs > window) diag["northReferenceAtS"] = Diag.num(lateNs / 1e9, 1)

        if (!config.useMagnetometer) {
            // North from the start only: one constant turn, and the magnetometer is ignored afterwards.
            diag["yawCorrection"] = "disabled"
            diag[NORTH_REFERENCE] = MAGNETIC
            val turn = Quat.yaw(ref)
            val out = OrientationTrack.Builder(base.size)
            for (k in 0 until base.size) out.add(base.timeAt(k), (turn * base.quatAt(k)).normalized())
            return out.build()
        }

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
        diag[YAW_CORRECTION_FINAL] = Diag.num(Math.toDegrees(corr[n - 1]), 2)
        diag["yawCorrection"] = "applied"
        diag[NORTH_REFERENCE] = if (config.northFromCompass) MAGNETIC else NORTH_OFF

        // corr is the drift since the reference; adding the reference itself puts north on +Y.
        val anchor = if (config.northFromCompass) ref else 0.0
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
            out.add(t, (Quat.yaw(c + anchor) * base.quatAt(k)).normalized())
        }
        return out.build()
    }

    /**
     * A trip whose game track cannot be turned onto north. With the magnetometer off there was no
     * drift correction to skip, so `yawCorrection` stays "disabled" and only the north reason is new.
     */
    private fun skip(config: PipelineConfig, diag: MutableMap<String, String>, correction: String, north: String) {
        diag["yawCorrection"] = if (config.useMagnetometer) "skipped: $correction" else "disabled"
        diag[NORTH_REFERENCE] = relativeNorth(config, north)
    }

    /** A relative `northReference`; switching north off is the reason whatever else went wrong. */
    private fun relativeNorth(config: PipelineConfig, reason: String): String =
        if (config.northFromCompass) RELATIVE + reason else NORTH_OFF

    companion object {
        /** Diagnostics key: [MAGNETIC] when +Y of the result is magnetic north, else [RELATIVE] and the reason. */
        const val NORTH_REFERENCE: String = "northReference"
        const val MAGNETIC: String = "magnetic"

        /** Prefix of every [NORTH_REFERENCE] value that is not [MAGNETIC]: the yaw is the gyro's own. */
        const val RELATIVE: String = "relative: "
        const val NO_ORIENTATION: String = RELATIVE + "no orientation samples"
        const val NORTH_OFF: String = RELATIVE + "north from compass is off"

        /**
         * Diagnostics key: the drift correction applied by the end of the trip, degrees. The heading
         * calibration reads it to reject a walk on which the compass moved.
         */
        const val YAW_CORRECTION_FINAL: String = "yawCorrectionFinalDeg"
    }
}
