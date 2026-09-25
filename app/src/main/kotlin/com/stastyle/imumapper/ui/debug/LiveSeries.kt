package com.stastyle.imumapper.ui.debug

import com.stastyle.imumapper.pipeline.core.AccelSample
import com.stastyle.imumapper.pipeline.core.BaroSample
import com.stastyle.imumapper.pipeline.core.LogRecord
import com.stastyle.imumapper.pipeline.core.Quat
import com.stastyle.imumapper.pipeline.core.RotationSample
import com.stastyle.imumapper.pipeline.core.RotationSource
import com.stastyle.imumapper.pipeline.core.StepSample
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.ui.calibration.CalibrationMath

// Pure Kotlin (no Android) so the live-plot bookkeeping is unit-testable on the JVM.

/** Fixed-capacity ring buffer of floats for one live plot. Not thread-safe; the owner synchronises. */
class LiveSeries(val capacity: Int) {
    private val buf = FloatArray(capacity)
    private var start = 0

    var size: Int = 0
        private set

    /** Number of values ever pushed, so a mark taken at push time can be located later. */
    var total: Long = 0
        private set

    fun push(v: Float) {
        if (size < capacity) {
            buf[(start + size) % capacity] = v
            size++
        } else {
            buf[start] = v
            start = (start + 1) % capacity
        }
        total++
    }

    fun latest(): Float? = if (size == 0) null else buf[(start + size - 1) % capacity]

    fun toArray(): FloatArray = FloatArray(size) { buf[(start + it) % capacity] }

    /** Position inside [toArray] of the value pushed as number [pushTotal]; null once it scrolled out. */
    fun indexOf(pushTotal: Long): Int? {
        val offset = pushTotal - (total - size)
        return if (offset < 0 || offset >= size) null else offset.toInt()
    }

    fun clear() {
        start = 0
        size = 0
        total = 0
    }
}

/** Immutable data for one chart; a plain class so every snapshot triggers a redraw. */
class ChartData(val values: FloatArray, val marks: IntArray = IntArray(0)) {
    val latest: Float? get() = if (values.isEmpty()) null else values[values.size - 1]

    fun min(): Float {
        var m = Float.POSITIVE_INFINITY
        for (v in values) if (v < m) m = v
        return m
    }

    fun max(): Float {
        var m = Float.NEGATIVE_INFINITY
        for (v in values) if (v > m) m = v
        return m
    }

    companion object {
        val EMPTY = ChartData(FloatArray(0))
    }
}

class LiveCharts(
    val accelMag: ChartData,
    val vertical: ChartData,
    val headingGame: ChartData,
    val headingFused: ChartData,
    val pressure: ChartData,
    /** Hardware step events seen since the live view started. */
    val stepsSeen: Int,
) {
    companion object {
        val EMPTY = LiveCharts(ChartData.EMPTY, ChartData.EMPTY, ChartData.EMPTY, ChartData.EMPTY, ChartData.EMPTY, 0)
    }
}

/**
 * Turns the decimated live record stream into the four plotted quantities. Vertical acceleration
 * needs the latest game-rotation quaternion to rotate the sensor-frame sample into ENU, so it is
 * empty until the first rotation sample arrives. Hardware steps are kept as marks on the vertical
 * acceleration chart, which is what the software step detector looks at.
 */
class LiveAccumulator(windowSamples: Int = 500, baroSamples: Int = 200) {
    val accelMag = LiveSeries(windowSamples)
    val vertical = LiveSeries(windowSamples)
    val headingGame = LiveSeries(windowSamples)
    val headingFused = LiveSeries(windowSamples)
    val pressure = LiveSeries(baroSamples)

    private var gameQuat: Quat? = null
    private val stepMarks = ArrayDeque<Long>()

    var stepsSeen: Int = 0
        private set

    fun accept(record: LogRecord) {
        when (record) {
            is AccelSample -> {
                val a = Vec3.of(record.x, record.y, record.z)
                accelMag.push(a.length.toFloat())
                val q = gameQuat
                if (q != null) vertical.push(CalibrationMath.verticalAccel(q, a).toFloat())
            }
            is RotationSample -> {
                val q = record.toQuat()
                val deg = CalibrationMath.headingDeg(q).toFloat()
                if (record.source == RotationSource.GAME) {
                    gameQuat = q
                    headingGame.push(deg)
                } else {
                    headingFused.push(deg)
                }
            }
            is BaroSample -> pressure.push(record.hPa)
            is StepSample -> {
                stepsSeen++
                stepMarks.addLast(vertical.total)
                while (stepMarks.size > MAX_MARKS) stepMarks.removeFirst()
            }
            else -> Unit
        }
    }

    fun snapshot(): LiveCharts {
        val marks = ArrayList<Int>()
        for (m in stepMarks) {
            val i = vertical.indexOf(m)
            if (i != null) marks.add(i)
        }
        return LiveCharts(
            accelMag = ChartData(accelMag.toArray()),
            vertical = ChartData(vertical.toArray(), marks.toIntArray()),
            headingGame = ChartData(headingGame.toArray()),
            headingFused = ChartData(headingFused.toArray()),
            pressure = ChartData(pressure.toArray()),
            stepsSeen = stepsSeen,
        )
    }

    fun clear() {
        accelMag.clear()
        vertical.clear()
        headingGame.clear()
        headingFused.clear()
        pressure.clear()
        gameQuat = null
        stepMarks.clear()
        stepsSeen = 0
    }

    private companion object {
        const val MAX_MARKS = 64
    }
}
