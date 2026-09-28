package com.stastyle.imumapper.capture

import android.os.SystemClock
import com.stastyle.imumapper.pipeline.core.LogRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Runs a [CompassLock] on live sensors: the compass step before a recording and before the heading
 * calibration both come here, so they decide "north is settled" the same way.
 */
object CompassFeed {

    /** About 15 readings a second: smooth enough for the dial, far below the sensor rate. */
    const val PUBLISH_MS: Long = 66L

    /**
     * Readings of a fresh [CompassLock] fed from [logger]'s live records and magnetometer accuracy, one
     * every [PUBLISH_MS] from collection until the collector stops. The logger must be running; the
     * flow neither starts nor stops it.
     */
    fun readings(logger: SensorLogger): Flow<CompassReading> = readings(
        live = logger.live,
        magAccuracy = logger.stats.map { it.of(SensorKind.MAG).accuracy },
        nowNs = SystemClock::elapsedRealtimeNanos,
    )

    /**
     * The same from plain flows, for tests. [nowNs] is the elapsed-realtime clock the records are
     * stamped on. The live stream carries every sensor at about 50 Hz, so it is sorted on
     * [feedContext], off the main thread; the lock is shared with the sampler, hence the locks.
     */
    fun readings(
        live: Flow<LogRecord>,
        magAccuracy: Flow<Int?>,
        nowNs: () -> Long,
        feedContext: CoroutineContext = Dispatchers.Default,
    ): Flow<CompassReading> = channelFlow {
        val lock = CompassLock()
        launch(feedContext) { live.collect { r -> synchronized(lock) { lock.feed(r) } } }
        launch { magAccuracy.collect { a -> synchronized(lock) { lock.onMagAccuracy(a) } } }
        while (true) {
            send(synchronized(lock) { lock.reading(nowNs()) })
            delay(PUBLISH_MS)
        }
    }
}
