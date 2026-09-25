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

    fun at(tNs: Long): Double {
        if (times.isEmpty()) return 0.0
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
        if (i < 0) return heights[0]
        if (i >= times.size - 1) return heights[times.size - 1]
        val t0 = times[i]
        val t1 = times[i + 1]
        if (t1 <= t0) return heights[i]
        val f = (tNs - t0).toDouble() / (t1 - t0).toDouble()
        return heights[i] + (heights[i + 1] - heights[i]) * f
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
