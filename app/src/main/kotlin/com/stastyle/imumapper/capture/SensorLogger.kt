package com.stastyle.imumapper.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.stastyle.imumapper.pipeline.core.AccelSample
import com.stastyle.imumapper.pipeline.core.AccelUncalSample
import com.stastyle.imumapper.pipeline.core.BaroSample
import com.stastyle.imumapper.pipeline.core.EventKind
import com.stastyle.imumapper.pipeline.core.EventRecord
import com.stastyle.imumapper.pipeline.core.GyroSample
import com.stastyle.imumapper.pipeline.core.GyroUncalSample
import com.stastyle.imumapper.pipeline.core.LogRecord
import com.stastyle.imumapper.pipeline.core.MagSample
import com.stastyle.imumapper.pipeline.core.MagUncalSample
import com.stastyle.imumapper.pipeline.core.RotationSample
import com.stastyle.imumapper.pipeline.core.RotationSource
import com.stastyle.imumapper.pipeline.core.StepSample
import com.stastyle.imumapper.pipeline.log.LogWriter
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Registers every IMU-related sensor at the fastest rate on its own [HandlerThread] and turns each
 * [SensorEvent] into the matching [LogRecord].
 *
 * Ownership: the caller owns the [LogWriter]. [start] attaches it, [stop] flushes it and drops the
 * reference, and the caller closes it afterwards. A null writer is allowed: the logger then only
 * feeds [stats] and [live], which the debug and calibration screens use.
 *
 * All sensor callbacks, the periodic flush, stall detection and stats publishing run on the logger
 * thread, so the per-sensor bookkeeping needs no locks.
 */
class SensorLogger(context: Context) : SensorEventListener {

    private val sensorManager: SensorManager =
        context.applicationContext.getSystemService(SensorManager::class.java)

    private val thread = HandlerThread("imu-logger", Process.THREAD_PRIORITY_MORE_FAVORABLE).apply { start() }
    private val handler = Handler(thread.looper)

    private val sensors: Map<SensorKind, Sensor?> =
        SensorKind.entries.associateWith { sensorManager.getDefaultSensor(androidType(it)) }
    private val typeToKind: Map<Int, SensorKind> =
        sensors.entries.filter { it.value != null }.associate { it.value!!.type to it.key }

    // Logger-thread state (also read by stop() through a posted runnable).
    private var writer: LogWriter? = null
    private var running = false
    private var lastFlushNs = 0L
    private var recordsWritten = 0L
    private var stepCount = 0
    private var writeError: String? = null
    private val counts = LongArray(SensorKind.entries.size)
    private val lastTs = LongArray(SensorKind.entries.size)
    private val lastValues = Array(SensorKind.entries.size) { FloatArray(MAX_VALUES) }
    private val lastValueCount = IntArray(SensorKind.entries.size)
    private val rates = Array(SensorKind.entries.size) { RateEstimator() }
    private val decimators = Array(SensorKind.entries.size) { Decimator(LIVE_HZ) }
    private val stall = StallDetector()

    private val _stats = MutableStateFlow(snapshot(SystemClock.elapsedRealtimeNanos()))

    /** Per-sensor health, refreshed every [TICK_MS] while running. */
    val stats: StateFlow<SensorStats> = _stats.asStateFlow()

    private val _live = MutableSharedFlow<LogRecord>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Records thinned to about [LIVE_HZ] per sensor, for live plots. Steps are never dropped. */
    val live: SharedFlow<LogRecord> = _live.asSharedFlow()

    /** Sensors present on this device. */
    val available: Map<SensorKind, Boolean> = sensors.mapValues { it.value != null }

    /**
     * Effective sampling period per sensor in microseconds for `LogMeta.sensorPeriodsUs`. We ask for
     * SENSOR_DELAY_FASTEST, so the period we actually get is the sensor's minimum delay.
     */
    fun sensorPeriodsUs(): Map<String, Int> =
        sensors.entries.filter { it.value != null }.associate { it.key.periodKey to it.value!!.minDelay }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            onTick(SystemClock.elapsedRealtimeNanos())
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Starts listening. Safe to call again while running: the previous writer is flushed and replaced. */
    fun start(writer: LogWriter?) {
        runOnLoggerThread { startOnThread(writer) }
    }

    /** Unregisters the listeners and flushes the writer. Blocks (at most two seconds) until the thread is quiet. */
    fun stop() {
        sensorManager.unregisterListener(this)
        runOnLoggerThreadAndWait { stopOnThread() }
    }

    /** Stops and quits the thread. The logger cannot be used afterwards. */
    fun release() {
        stop()
        thread.quitSafely()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val kind = typeToKind[event.sensor.type] ?: return
        val record = toRecord(kind, event) ?: return
        val i = kind.ordinal
        val t = event.timestamp
        counts[i]++
        lastTs[i] = t
        val n = minOf(event.values.size, MAX_VALUES)
        System.arraycopy(event.values, 0, lastValues[i], 0, n)
        lastValueCount[i] = n
        rates[i].onSample(t)
        stall.onSample(kind, t)
        if (kind == SensorKind.STEP) stepCount++

        val w = writer
        if (w != null) {
            try {
                w.write(record)
                recordsWritten++
            } catch (e: Exception) {
                // Keep going: the stats show the failure and the rest of the log stays usable.
                writeError = e.message ?: e.javaClass.simpleName
            }
        }
        if (kind == SensorKind.STEP || decimators[i].accept(t)) _live.tryEmit(record)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun startOnThread(newWriter: LogWriter?) {
        if (running) {
            sensorManager.unregisterListener(this)
            try {
                writer?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "flush on restart failed", e)
            }
        }
        resetCounters()
        writer = newWriter
        running = true
        val now = SystemClock.elapsedRealtimeNanos()
        lastFlushNs = now
        for ((kind, sensor) in sensors) {
            if (sensor == null) continue
            val ok = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, 0, handler)
            if (ok) stall.onSample(kind, now) else Log.w(TAG, "registerListener failed for ${kind.label}")
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
        _stats.value = snapshot(now)
    }

    private fun stopOnThread() {
        if (!running) {
            writer = null
            return
        }
        running = false
        handler.removeCallbacks(tick)
        try {
            writer?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "final flush failed", e)
        }
        writer = null
        _stats.value = snapshot(SystemClock.elapsedRealtimeNanos())
    }

    private fun onTick(nowNs: Long) {
        val w = writer
        val newlyStalled = stall.check(nowNs)
        if (w != null) {
            try {
                // One event per stalled sensor; the log format carries no sensor id, so the reader
                // sees "something stalled" and the stats tell which one.
                for (kind in newlyStalled) {
                    Log.w(TAG, "sensor stalled: ${kind.label}")
                    w.write(EventRecord(nowNs, EventKind.SENSOR_STALL))
                    recordsWritten++
                }
                if (nowNs - lastFlushNs >= FLUSH_NS) {
                    w.flush()
                    lastFlushNs = nowNs
                }
            } catch (e: Exception) {
                writeError = e.message ?: e.javaClass.simpleName
            }
        }
        _stats.value = snapshot(nowNs)
    }

    private fun snapshot(nowNs: Long): SensorStats {
        val map = LinkedHashMap<SensorKind, SensorHealth>()
        for (kind in SensorKind.entries) {
            val i = kind.ordinal
            val values = ArrayList<Float>(lastValueCount[i])
            for (j in 0 until lastValueCount[i]) values.add(lastValues[i][j])
            map[kind] = SensorHealth(
                kind = kind,
                available = sensors[kind] != null,
                sampleCount = counts[i],
                rateHz = rates[i].rateAt(nowNs),
                lastValues = values,
                lastTimestampNs = lastTs[i],
                stalled = stall.isStalled(kind),
            )
        }
        return SensorStats(
            sensors = map,
            recordsWritten = recordsWritten,
            stepCount = stepCount,
            running = running,
            writeError = writeError,
        )
    }

    private fun resetCounters() {
        counts.fill(0L)
        lastTs.fill(0L)
        lastValueCount.fill(0)
        for (r in rates) r.reset()
        for (d in decimators) d.reset()
        stall.reset()
        recordsWritten = 0L
        stepCount = 0
        writeError = null
    }

    private fun runOnLoggerThread(block: () -> Unit) {
        if (Looper.myLooper() == thread.looper) block() else handler.post { block() }
    }

    private fun runOnLoggerThreadAndWait(block: () -> Unit) {
        if (Looper.myLooper() == thread.looper) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        handler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(2, TimeUnit.SECONDS)) Log.w(TAG, "logger thread did not stop in time")
    }

    private fun toRecord(kind: SensorKind, e: SensorEvent): LogRecord? {
        val v = e.values
        val t = e.timestamp
        return when (kind) {
            SensorKind.ACCEL -> if (v.size >= 3) AccelSample(t, v[0], v[1], v[2]) else null
            SensorKind.GYRO -> if (v.size >= 3) GyroSample(t, v[0], v[1], v[2]) else null
            SensorKind.MAG -> if (v.size >= 3) MagSample(t, v[0], v[1], v[2]) else null
            SensorKind.ACCEL_UNCAL ->
                if (v.size >= 6) AccelUncalSample(t, v[0], v[1], v[2], v[3], v[4], v[5]) else null
            SensorKind.GYRO_UNCAL ->
                if (v.size >= 6) GyroUncalSample(t, v[0], v[1], v[2], v[3], v[4], v[5]) else null
            SensorKind.MAG_UNCAL ->
                if (v.size >= 6) MagUncalSample(t, v[0], v[1], v[2], v[3], v[4], v[5]) else null
            SensorKind.BARO -> if (v.isNotEmpty()) BaroSample(t, v[0]) else null
            SensorKind.GAME_ROT -> rotation(t, v, RotationSource.GAME)
            SensorKind.ROT_VEC -> rotation(t, v, RotationSource.FUSED)
            SensorKind.STEP -> StepSample(t)
        }
    }

    private fun rotation(t: Long, v: FloatArray, source: RotationSource): RotationSample? {
        if (v.size < 3) return null
        // Older HALs report only the vector part; w follows from the unit-norm constraint.
        val w = if (v.size >= 4) v[3] else sqrt((1f - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]).coerceAtLeast(0f))
        val accuracy = if (source == RotationSource.FUSED && v.size >= 5) v[4] else -1f
        return RotationSample(t, v[0], v[1], v[2], w, accuracy, source)
    }

    private fun androidType(kind: SensorKind): Int = when (kind) {
        SensorKind.ACCEL -> Sensor.TYPE_ACCELEROMETER
        SensorKind.GYRO -> Sensor.TYPE_GYROSCOPE
        SensorKind.MAG -> Sensor.TYPE_MAGNETIC_FIELD
        SensorKind.ACCEL_UNCAL -> Sensor.TYPE_ACCELEROMETER_UNCALIBRATED
        SensorKind.GYRO_UNCAL -> Sensor.TYPE_GYROSCOPE_UNCALIBRATED
        SensorKind.MAG_UNCAL -> Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED
        SensorKind.BARO -> Sensor.TYPE_PRESSURE
        SensorKind.GAME_ROT -> Sensor.TYPE_GAME_ROTATION_VECTOR
        SensorKind.ROT_VEC -> Sensor.TYPE_ROTATION_VECTOR
        SensorKind.STEP -> Sensor.TYPE_STEP_DETECTOR
    }

    companion object {
        private const val TAG = "SensorLogger"
        private const val TICK_MS = 250L
        private const val FLUSH_NS = 1_000_000_000L
        private const val LIVE_HZ = 50.0
        private const val MAX_VALUES = 6
    }
}
