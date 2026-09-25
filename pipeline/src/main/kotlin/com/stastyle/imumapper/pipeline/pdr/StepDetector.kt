package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.StepSample
import kotlin.math.PI

/** Detected steps: time of each step and the peak-to-valley swing of vertical acceleration in its cycle. */
class DetectedSteps(val tNs: LongArray, val swing: DoubleArray) {
    val size: Int get() = tNs.size

    /** Index of the first step with time >= [t]; equals [size] when none. */
    fun lowerBound(t: Long): Int {
        var lo = 0
        var hi = tNs.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tNs[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        val EMPTY = DetectedSteps(LongArray(0), DoubleArray(0))
    }
}

/**
 * Step detection on gravity-free vertical acceleration.
 *
 * The signal is band-passed (two cascaded first-order low-passes at the upper limit and two
 * first-order high-passes at the lower limit, each stepped with the real sample interval so the
 * sensor rate does not matter). One step is one full cycle of the band-passed signal: from a
 * rising zero crossing, through the peak, the falling crossing and the valley, to the next rising
 * crossing. The cycle counts when peak minus valley reaches [PipelineConfig.stepMinSwing] and the
 * peak is at least [PipelineConfig.stepMinIntervalS] after the previous accepted step. The step
 * time is the peak time. The swing reported for the stride model is the peak-to-valley range of the
 * low-passed (not high-passed) vertical acceleration over the same cycle, which is what the
 * Weinberg model is usually calibrated on.
 */
object StepDetector {

    fun detect(accel: WorldAccel, config: PipelineConfig): DetectedSteps {
        val n = accel.size
        if (n == 0) return DetectedSteps.EMPTY
        val band = DoubleArray(n)
        val low = DoubleArray(n)
        filter(accel.tNs, accel.vertical, config.stepBandLowHz, config.stepBandHighHz, band, low)

        val minIntervalNs = (config.stepMinIntervalS * 1e9).toLong()
        val times = ArrayList<Long>()
        val swings = ArrayList<Double>()
        var inCycle = false
        var positive = band[0] > 0.0
        var peak = 0.0
        var peakNs = 0L
        var valley = 0.0
        var lowMax = 0.0
        var lowMin = 0.0
        var lastStepNs = Long.MIN_VALUE
        for (i in 1 until n) {
            val b = band[i]
            val nowPositive = b > 0.0
            if (inCycle) {
                if (low[i] > lowMax) lowMax = low[i]
                if (low[i] < lowMin) lowMin = low[i]
                if (nowPositive && !positive) {
                    // Rising crossing closes the cycle.
                    val accepted = peak - valley >= config.stepMinSwing &&
                        (lastStepNs == Long.MIN_VALUE || peakNs - lastStepNs >= minIntervalNs)
                    if (accepted) {
                        times.add(peakNs)
                        swings.add(lowMax - lowMin)
                        lastStepNs = peakNs
                    }
                    peak = b
                    peakNs = accel.tNs[i]
                    valley = 0.0
                    lowMax = low[i]
                    lowMin = low[i]
                } else if (nowPositive) {
                    if (b > peak) {
                        peak = b
                        peakNs = accel.tNs[i]
                    }
                } else {
                    if (b < valley) valley = b
                }
            } else if (nowPositive && !positive) {
                inCycle = true
                peak = b
                peakNs = accel.tNs[i]
                valley = 0.0
                lowMax = low[i]
                lowMin = low[i]
            }
            positive = nowPositive
        }
        return DetectedSteps(times.toLongArray(), swings.toDoubleArray())
    }

    /**
     * Steps from the hardware step detector; the swing of each step is measured on the low-passed
     * vertical acceleration between the previous hardware step and this one (or the preceding
     * 0.6 s for the first step).
     */
    fun fromHardware(steps: List<StepSample>, accel: WorldAccel, config: PipelineConfig): DetectedSteps {
        val times = LongArray(steps.size)
        val swings = DoubleArray(steps.size)
        val low = if (accel.isEmpty) DoubleArray(0) else DoubleArray(accel.size).also {
            filter(accel.tNs, accel.vertical, config.stepBandLowHz, config.stepBandHighHz, DoubleArray(accel.size), it)
        }
        var prevNs = Long.MIN_VALUE
        var k = 0
        for (s in steps) {
            if (s.tNs < prevNs) continue
            val from = if (prevNs == Long.MIN_VALUE) s.tNs - 600_000_000L else prevNs
            times[k] = s.tNs
            swings[k] = swing(low, accel, from, s.tNs)
            prevNs = s.tNs
            k++
        }
        return DetectedSteps(times.copyOf(k), swings.copyOf(k))
    }

    /** Band-passed vertical acceleration, exposed for debug plots. */
    fun bandPass(tNs: LongArray, x: DoubleArray, lowHz: Double, highHz: Double): DoubleArray {
        val out = DoubleArray(x.size)
        filter(tNs, x, lowHz, highHz, out, DoubleArray(x.size))
        return out
    }

    private fun swing(low: DoubleArray, accel: WorldAccel, fromNs: Long, toNs: Long): Double {
        val from = accel.lowerBound(fromNs)
        val to = accel.lowerBound(toNs + 1)
        if (from >= to) return 0.0
        var mx = Double.NEGATIVE_INFINITY
        var mn = Double.POSITIVE_INFINITY
        for (i in from until to) {
            if (low[i] > mx) mx = low[i]
            if (low[i] < mn) mn = low[i]
        }
        return mx - mn
    }

    /**
     * Writes the band-passed signal into [band] and the low-passed-only signal into [low].
     * Time constants come from the cut-off frequencies; an unreasonable band (low >= high or
     * non-positive) degrades gracefully to a plain low-pass or a pass-through.
     */
    private fun filter(
        tNs: LongArray,
        x: DoubleArray,
        lowHz: Double,
        highHz: Double,
        band: DoubleArray,
        low: DoubleArray,
    ) {
        val n = x.size
        if (n == 0) return
        val rcLow = if (highHz > 0.0) 1.0 / (2.0 * PI * highHz) else 0.0
        val rcHigh = if (lowHz > 0.0) 1.0 / (2.0 * PI * lowHz) else 0.0
        var lp1 = x[0]
        var lp2 = x[0]
        var hp1 = 0.0
        var hp2 = 0.0
        var hpIn1 = lp2
        var hpIn2 = 0.0
        low[0] = lp2
        band[0] = 0.0
        for (i in 1 until n) {
            val dt = (tNs[i] - tNs[i - 1]) / 1e9
            val xi = x[i]
            if (dt <= 0.0) {
                low[i] = low[i - 1]
                band[i] = band[i - 1]
                continue
            }
            val aLow = if (rcLow > 0.0) dt / (rcLow + dt) else 1.0
            lp1 += (xi - lp1) * aLow
            lp2 += (lp1 - lp2) * aLow
            low[i] = lp2
            val aHigh = if (rcHigh > 0.0) rcHigh / (rcHigh + dt) else 0.0
            val h1 = aHigh * (hp1 + lp2 - hpIn1)
            hpIn1 = lp2
            hp1 = h1
            val h2 = aHigh * (hp2 + h1 - hpIn2)
            hpIn2 = h1
            hp2 = h2
            band[i] = if (rcHigh > 0.0) h2 else lp2
        }
    }
}
