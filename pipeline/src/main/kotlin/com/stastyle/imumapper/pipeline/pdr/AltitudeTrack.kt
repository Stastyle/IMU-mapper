package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.BaroSample
import kotlin.math.pow

/**
 * Barometric height in metres relative to the trip start, h = 44330 * (1 - (p / p0)^0.1903) with
 * p0 the mean pressure of the first second, low-passed with a first-order filter of time constant
 * [smoothingS]. Linear interpolation between samples; clamped outside the sampled range.
 */
class AltitudeTrack private constructor(
    private val times: LongArray,
    private val heights: DoubleArray,
    val p0hPa: Double,
) {
    val size: Int get() = times.size
    val isEmpty: Boolean get() = times.isEmpty()

    /**
     * Integral of the height from the first sample up to each sample, metre-seconds, for [meanOver].
     * Built on first use: most tracks are only ever read with [at].
     */
    private val integral: DoubleArray by lazy {
        val out = DoubleArray(times.size)
        for (k in 1 until times.size) {
            out[k] = out[k - 1] + (heights[k - 1] + heights[k]) * 0.5 * ((times[k] - times[k - 1]) / 1e9)
        }
        out
    }

    fun at(tNs: Long): Double {
        if (times.isEmpty()) return 0.0
        val i = lastAtOrBefore(tNs)
        if (i < 0) return heights[0]
        if (i >= times.size - 1) return heights[times.size - 1]
        val t0 = times[i]
        val t1 = times[i + 1]
        if (t1 <= t0) return heights[i]
        val f = (tNs - t0).toDouble() / (t1 - t0).toDouble()
        return heights[i] + (heights[i + 1] - heights[i]) * f
    }

    /**
     * Mean of [at] over [fromNs, toNs], with the same interpolation and clamping; the value at
     * [toNs] for an empty or backwards range.
     */
    fun meanOver(fromNs: Long, toNs: Long): Double {
        if (times.isEmpty()) return 0.0
        if (toNs <= fromNs) return at(toNs)
        return (integralTo(toNs) - integralTo(fromNs)) / ((toNs - fromNs) / 1e9)
    }

    /** Integral of [at] from the first sample to [tNs], negative before it. */
    private fun integralTo(tNs: Long): Double {
        val i = lastAtOrBefore(tNs)
        if (i < 0) return heights[0] * ((tNs - times[0]) / 1e9)
        return integral[i] + (heights[i] + at(tNs)) * 0.5 * ((tNs - times[i]) / 1e9)
    }

    /** Index of the last sample at or before [tNs], -1 when there is none. */
    private fun lastAtOrBefore(tNs: Long): Int {
        var lo = 0
        var hi = times.size - 1
        var i = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= tNs) {
                i = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return i
    }

    companion object {
        fun heightAboveReference(pHpa: Double, p0Hpa: Double): Double = 44330.0 * (1.0 - (pHpa / p0Hpa).pow(0.1903))

        /** Null when the log has no barometer samples. */
        fun fromBaro(baro: List<BaroSample>, smoothingS: Double, referenceWindowS: Double = 1.0): AltitudeTrack? {
            if (baro.isEmpty()) return null
            val refEnd = baro[0].tNs + (referenceWindowS * 1e9).toLong()
            var sum = 0.0
            var count = 0
            for (s in baro) {
                if (s.tNs > refEnd && count > 0) break
                sum += s.hPa
                count++
            }
            val p0 = sum / count
            val times = LongArray(baro.size)
            val heights = DoubleArray(baro.size)
            var k = 0
            var filtered = 0.0
            var lastNs = Long.MIN_VALUE
            for (s in baro) {
                if (s.tNs < lastNs || s.hPa <= 0f) continue
                val raw = heightAboveReference(s.hPa.toDouble(), p0)
                val dt = if (lastNs == Long.MIN_VALUE) 0.0 else (s.tNs - lastNs) / 1e9
                val alpha = when {
                    smoothingS <= 0.0 -> 1.0
                    dt > 0.0 -> dt / (smoothingS + dt)
                    else -> 0.0
                }
                filtered += (raw - filtered) * alpha
                times[k] = s.tNs
                heights[k] = filtered
                lastNs = s.tNs
                k++
            }
            if (k == 0) return null
            return AltitudeTrack(times.copyOf(k), heights.copyOf(k), p0)
        }
    }
}
