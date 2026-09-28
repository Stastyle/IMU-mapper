package com.stastyle.imumapper.capture

import com.stastyle.imumapper.pipeline.core.LogRecord
import com.stastyle.imumapper.pipeline.core.MagSample
import com.stastyle.imumapper.pipeline.core.RotationSample
import com.stastyle.imumapper.pipeline.core.RotationSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class CompassFeedTest {

    @Test
    fun feedsTheLiveStreamAndTheMagnetometerAccuracyIntoOneLock() = runTest {
        val live = MutableSharedFlow<LogRecord>(extraBufferCapacity = 64)
        val accuracy = MutableStateFlow<Int?>(3)
        var nowNs = 0L
        val readings = ArrayList<CompassReading>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            CompassFeed.readings(live, accuracy, { nowNs }, feedContext = EmptyCoroutineContext)
                .collect { readings += it }
        }
        runCurrent()
        assertEquals(CompassStatus.WAITING, readings.last().status)

        for (ms in 0L..100L step 20L) {
            val t = ms * 1_000_000L
            nowNs = t
            live.emit(RotationSample(t, 0f, 0f, 0f, 1f, 0.05f, RotationSource.GAME))
            live.emit(RotationSample(t, 0f, 0f, 0f, 1f, 0.05f, RotationSource.FUSED))
            live.emit(MagSample(t, 0f, 30f, -30f))
        }
        advanceTimeBy(CompassFeed.PUBLISH_MS + 1)
        val settling = readings.last()
        assertEquals(CompassStatus.SETTLING, settling.status)
        assertEquals(0.0, assertNotNull(settling.offsetDeg), 1e-6)

        accuracy.value = 1
        advanceTimeBy(CompassFeed.PUBLISH_MS + 1)
        assertEquals(CompassStatus.CALIBRATE, readings.last().status)
        job.cancel()
    }
}
