package com.stastyle.imumapper.debug

import com.stastyle.imumapper.pipeline.core.AccelSample
import com.stastyle.imumapper.pipeline.core.BaroSample
import com.stastyle.imumapper.pipeline.core.RotationSample
import com.stastyle.imumapper.pipeline.core.RotationSource
import com.stastyle.imumapper.pipeline.core.StepSample
import com.stastyle.imumapper.ui.debug.LiveAccumulator
import com.stastyle.imumapper.ui.debug.LiveSeries
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveSeriesTest {
    @Test
    fun keepsTheNewestValuesInOrder() {
        val s = LiveSeries(3)
        s.push(1f)
        s.push(2f)
        assertContentEquals(floatArrayOf(1f, 2f), s.toArray())
        s.push(3f)
        s.push(4f)
        assertContentEquals(floatArrayOf(2f, 3f, 4f), s.toArray())
        assertEquals(4f, s.latest())
        assertEquals(4L, s.total)
        s.push(5f)
        s.push(6f)
        assertContentEquals(floatArrayOf(4f, 5f, 6f), s.toArray())
    }

    @Test
    fun locatesMarksUntilTheyScrollOut() {
        val s = LiveSeries(4)
        for (i in 0 until 3) s.push(i.toFloat())
        val mark = s.total // the next push is number 3
        s.push(3f)
        assertEquals(3, s.indexOf(mark))
        s.push(4f)
        s.push(5f)
        assertEquals(1, s.indexOf(mark))
        s.push(6f)
        s.push(7f)
        assertNull(s.indexOf(mark))
        s.clear()
        assertEquals(0, s.size)
        assertNull(s.latest())
    }
}

class LiveAccumulatorTest {
    @Test
    fun verticalWaitsForOrientationAndStepsAreMarked() {
        val acc = LiveAccumulator(windowSamples = 8, baroSamples = 4)
        acc.accept(AccelSample(1L, 0f, 0f, 9.81f))
        var snap = acc.snapshot()
        assertEquals(1, snap.accelMag.values.size)
        assertEquals(0, snap.vertical.values.size)
        // Identity game rotation: the phone is flat, vertical accel equals z minus g.
        acc.accept(RotationSample(2L, 0f, 0f, 0f, 1f, -1f, RotationSource.GAME))
        acc.accept(AccelSample(3L, 0f, 0f, 10.81f))
        acc.accept(StepSample(4L))
        acc.accept(AccelSample(5L, 0f, 0f, 9.81f))
        acc.accept(BaroSample(6L, 1013.2f))
        acc.accept(RotationSample(7L, 0f, 0f, 0f, 1f, 0.1f, RotationSource.FUSED))
        snap = acc.snapshot()
        assertEquals(2, snap.vertical.values.size)
        assertEquals(1.0f, snap.vertical.values[0], 1e-4f)
        assertEquals(0.0f, snap.vertical.values[1], 1e-4f)
        // The step arrived after one vertical sample, so it marks index 1 (the next sample).
        assertContentEquals(intArrayOf(1), snap.vertical.marks)
        assertEquals(1, snap.stepsSeen)
        assertEquals(1, snap.headingGame.values.size)
        assertEquals(1, snap.headingFused.values.size)
        assertEquals(1013.2f, snap.pressure.latest)
        acc.clear()
        assertEquals(0, acc.snapshot().accelMag.values.size)
        assertEquals(0, acc.snapshot().stepsSeen)
    }
}
