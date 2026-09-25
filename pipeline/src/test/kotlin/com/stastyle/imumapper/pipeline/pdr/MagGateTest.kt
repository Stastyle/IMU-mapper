package com.stastyle.imumapper.pipeline.pdr

import com.stastyle.imumapper.pipeline.core.MagSample
import com.stastyle.imumapper.pipeline.core.Vec3
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MagGateTest {

    @Test
    fun referenceFromFirstSecondAndGating() {
        val samples = ArrayList<MagSample>()
        for (i in 0 until 100) samples.add(MagSample(i * 20_000_000L, 0f, 22f, -40f))
        // Disturbed readings after the first second must not move the reference.
        for (i in 100 until 200) samples.add(MagSample(i * 20_000_000L, 60f, 0f, -10f))
        val gate = assertNotNull(MagGate.fromStart(samples, OrientationTrack.EMPTY, 0.15))
        assertTrue(gate.passes(Vec3(0.0, 22.0, -40.0)))
        assertTrue(gate.passes(Vec3(2.0, 23.0, -41.0)))
        assertFalse(gate.passes(Vec3(60.0, 0.0, -10.0)), "magnitude and dip off")
        assertFalse(gate.passes(Vec3(0.0, 40.0, -22.0)), "same magnitude, wrong dip")
        assertFalse(gate.passes(Vec3(0.0, 30.0, -55.0)), "same dip, wrong magnitude")
        assertNull(MagGate.fromStart(emptyList(), OrientationTrack.EMPTY, 0.15))
    }
}
