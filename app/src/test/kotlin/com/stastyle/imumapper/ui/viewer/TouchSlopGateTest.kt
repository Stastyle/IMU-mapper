package com.stastyle.imumapper.ui.viewer

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The viewer's touch slop, which gates the Survey long press and the tap. */
class TouchSlopGateTest {

    /** 8 dp on a 3.5x screen. */
    private val slop = 28f

    @Test
    fun `a still finger's jitter never leaves the slop however long it is held`() {
        val gate = TouchSlopGate(slop, 500f, 800f)
        // 1.5 s at 120 Hz (Samsung's longest Touch and hold delay), wobbling a pixel each way.
        var pathPx = 0f
        var x = 500f
        var y = 800f
        for (frame in 0 until 180) {
            val nx = 500f + if (frame % 2 == 0) 1f else -1f
            val ny = 800f + if (frame % 3 == 0) 1f else 0f
            pathPx += sqrt((nx - x) * (nx - x) + (ny - y) * (ny - y))
            x = nx
            y = ny
            gate.moveTo(x, y)
        }
        // Summed move by move, the wobble is far past the slop: that sum used to cancel the long press.
        assertTrue(pathPx > slop * 5, "path $pathPx")
        assertFalse(gate.exceeded)
    }

    @Test
    fun `a slow drift that stays inside the slop is still a press`() {
        val gate = TouchSlopGate(slop, 0f, 0f)
        for (i in 1..27) gate.moveTo(i.toFloat(), 0f)
        assertFalse(gate.exceeded)
    }

    @Test
    fun `moving the slop away from the down point leaves it`() {
        val gate = TouchSlopGate(slop, 0f, 0f)
        gate.moveTo(20f, 20f) // 28.3 px on the diagonal
        assertTrue(gate.exceeded)
    }

    @Test
    fun `a drag that comes back to where it went down is not a tap`() {
        val gate = TouchSlopGate(slop, 100f, 100f)
        gate.moveTo(100f, 160f)
        gate.moveTo(100f, 100f)
        assertTrue(gate.exceeded)
    }
}
