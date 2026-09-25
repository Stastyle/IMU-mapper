package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.AccelSample
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.StepSample
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StepDetectorTest {

    private fun bounce(seconds: Double, hz: Double, amplitude: Double, rate: Int = 200): WorldAccel {
        val samples = ArrayList<AccelSample>()
        val n = (seconds * rate).toInt()
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val bounce = if (t > 2.0 && t < seconds - 2.0) amplitude * sin(2 * PI * hz * t) else 0.0
            samples.add(AccelSample((t * 1e9).toLong(), 0f, 0f, (9.81 + bounce).toFloat()))
        }
        return WorldAccel.compute(samples, OrientationTrack.EMPTY)
    }

    @Test
    fun countsOneStepPerCycle() {
        val steps = StepDetector.detect(bounce(14.0, 2.0, 2.0), PipelineConfig())
        // 10 s of bouncing at 2 Hz: 20 cycles, the first or last may be lost at the edges.
        assertTrue(steps.size in 18..20, "steps: ${steps.size}")
        for (i in 1 until steps.size) {
            val dt = (steps.tNs[i] - steps.tNs[i - 1]) / 1e9
            assertTrue(dt > 0.45 && dt < 0.55, "interval $dt")
        }
        // Two first-order low-passes at 3 Hz pass about 70 % of a 2 Hz sinusoid: swing about 2.8 of 4.
        assertTrue(steps.swing.all { it > 2.2 && it < 4.2 }, "swing of low-passed signal: ${steps.swing.toList()}")
    }

    @Test
    fun smallSwingIsNotAStep() {
        assertEquals(0, StepDetector.detect(bounce(10.0, 2.0, 0.3), PipelineConfig()).size)
        assertEquals(0, StepDetector.detect(bounce(10.0, 2.0, 0.0), PipelineConfig()).size)
    }

    @Test
    fun tooFastCyclesAreRateLimited() {
        val steps = StepDetector.detect(bounce(14.0, 6.0, 2.0), PipelineConfig(stepBandHighHz = 8.0))
        for (i in 1 until steps.size) {
            assertTrue(steps.tNs[i] - steps.tNs[i - 1] >= 300_000_000L)
        }
    }

    @Test
    fun hardwareStepsCarrySwing() {
        val accel = bounce(8.0, 2.0, 2.0)
        val hw = listOf(StepSample(3_000_000_000L), StepSample(3_500_000_000L), StepSample(4_000_000_000L))
        val steps = StepDetector.fromHardware(hw, accel, PipelineConfig())
        assertEquals(3, steps.size)
        assertTrue(steps.swing.all { it > 2.5 }, "swings ${steps.swing.toList()}")
        val noAccel = WorldAccel.compute(emptyList(), OrientationTrack.EMPTY)
        assertEquals(0, StepDetector.fromHardware(hw, noAccel, PipelineConfig()).swing.count { it > 0.0 })
    }
}
