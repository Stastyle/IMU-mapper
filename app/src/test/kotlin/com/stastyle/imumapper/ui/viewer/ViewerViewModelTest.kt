package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.data.FakeTripRepository
import com.stastyle.imumapper.data.TripFiles
import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.process.TripProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Which trips the viewer processes on its own when it opens, and which it leaves to the Retry button. */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModelTest {

    private lateinit var tmp: File
    private lateinit var files: TripFiles
    private lateinit var trips: FakeTripRepository

    /** Counts calls and fails them, so a call is visible without a real pipeline. */
    private class CountingProcessor : TripProcessor {
        val tripIds = ArrayList<Long>()
        override suspend fun process(tripId: Long, config: PipelineConfig?, label: String): PathResultEntity {
            tripIds += tripId
            throw IllegalStateException("fake processor")
        }
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tmp = Files.createTempDirectory("imu-viewer").toFile()
        files = TripFiles(File(tmp, "files"), File(tmp, "cache"))
        trips = FakeTripRepository(files)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        tmp.deleteRecursively()
    }

    private suspend fun trip(status: TripStatus, lastError: String? = null): Long = trips.createTrip(
        TripEntity(
            name = "walk", mode = TripMode.POCKET, carryPosition = CarryPosition.HAND, startedAtEpochMs = 1L,
            status = status, lastError = lastError,
        ),
    )

    @Test
    fun recordedTripWithoutRunsIsProcessedOnOpen() = runBlocking {
        val id = trip(TripStatus.RECORDED)
        val processor = CountingProcessor()
        val vm = ViewerViewModel(id, trips, files, processor)
        assertEquals(listOf(id), processor.tripIds)
        assertEquals("Processing failed: fake processor", vm.ui.value.error)
    }

    @Test
    fun failedTripIsNotReprocessedOnOpen() = runBlocking {
        val id = trip(TripStatus.FAILED, lastError = "Not enough memory to process this trip (raw log 140 MB)")
        val processor = CountingProcessor()
        val vm = ViewerViewModel(id, trips, files, processor)
        assertEquals(emptyList<Long>(), processor.tripIds)
        assertFalse(vm.ui.value.processing)
        assertNull(vm.ui.value.error)
        assertEquals(TripStatus.FAILED, vm.ui.value.trip?.status)

        // Retry is the user's explicit choice and still runs.
        vm.retryProcessing()
        assertEquals(listOf(id), processor.tripIds)
    }

    @Test
    fun tripStillRecordingIsNotProcessed() = runBlocking {
        val id = trip(TripStatus.RECORDING)
        val processor = CountingProcessor()
        ViewerViewModel(id, trips, files, processor)
        assertEquals(emptyList<Long>(), processor.tripIds)
    }
}
