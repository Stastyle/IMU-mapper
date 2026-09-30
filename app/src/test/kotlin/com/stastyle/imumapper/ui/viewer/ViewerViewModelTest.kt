package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.SurveyFixtures
import com.stastyle.imumapper.data.FakeTripRepository
import com.stastyle.imumapper.data.TripFiles
import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.process.TripProcessor
import com.stastyle.imumapper.render.ColorMode
import com.stastyle.imumapper.render.Projector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Which trips the viewer processes on its own when it opens, and which it leaves to the Retry button; the
 * default colouring; how the camera follows the canvas as it changes height with the tab; and Share.
 */
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

    /** For trips that already have a run, which the viewer must never process. */
    private class NoProcessing : TripProcessor {
        override suspend fun process(tripId: Long, config: PipelineConfig?, label: String): PathResultEntity =
            throw AssertionError("the viewer processed a trip that has runs")
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

    /** A processed trip whose run 1 is [result]. */
    private suspend fun processedTrip(result: PathResult = SurveyFixtures.lWalk()): Long {
        val id = trip(TripStatus.PROCESSED)
        files.resultFile(id, 1).writeText(result.toJson())
        trips.addResult(
            PathResultEntity(
                tripId = id,
                runId = 1,
                pipelineVersion = result.pipelineVersion,
                createdAtEpochMs = 0L,
                fileName = files.resultFileName(1),
                statsJson = "{}",
                configJson = "{}",
            ),
        )
        return id
    }

    /** The viewer with its run loaded (file work runs inside the call); no canvas has been measured yet. */
    private fun viewer(tripId: Long, shareTrip: (suspend (Long) -> ShareRequest)? = null): ViewerViewModel =
        ViewerViewModel(
            tripId,
            trips,
            files,
            NoProcessing(),
            ioDispatcher = Dispatchers.Unconfined,
            shareTrip = shareTrip,
        )

    /** Every point of the shown path projects inside a [width] × [height] canvas. */
    private fun assertPathOnScreen(vm: ViewerViewModel, width: Float, height: Float) {
        val projector = Projector(vm.camera.value, width, height)
        for (p in assertNotNull(vm.ui.value.shownResult).points) {
            val s = assertNotNull(projector.project(p.p))
            assertTrue(s.x in 0f..width && s.y in 0f..height, "(${s.x}, ${s.y}) is off a $width x $height canvas")
        }
    }

    // --- processing on open ---

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

    // --- colouring and the camera ---

    @Test
    fun thePathIsColouredByDistanceWalkedByDefault() = runBlocking<Unit> {
        val vm = viewer(processedTrip())
        assertEquals(ColorMode.PROGRESS, vm.ui.value.options.colorMode)
    }

    @Test
    fun aViewNobodyMovedIsFittedAgainWhenTheCanvasChangesHeight() = runBlocking<Unit> {
        val id = processedTrip()
        val vm = viewer(id)
        // The 3D tab's tall canvas, then the Path tab's short one.
        vm.setViewport(1080f, 1920f)
        val tall = vm.camera.value
        vm.setViewport(1080f, 800f)
        val short = vm.camera.value
        assertNotEquals(tall, short)
        assertPathOnScreen(vm, 1080f, 800f)
        // The same view a canvas of that size gets when it is the first one measured.
        val direct = viewer(id).also { it.setViewport(1080f, 800f) }
        assertEquals(direct.camera.value, short)
        // Back to the tall canvas: still untouched, so fitted again.
        vm.setViewport(1080f, 1920f)
        assertEquals(tall, vm.camera.value)
    }

    @Test
    fun aViewTheUserMovedStaysWhenTheCanvasChangesHeight() = runBlocking<Unit> {
        val vm = viewer(processedTrip())
        vm.setViewport(1080f, 1920f)
        vm.orbit(40f, 10f)
        val moved = vm.camera.value
        vm.setViewport(1080f, 800f)
        assertEquals(moved, vm.camera.value)
        // A fit makes it a fitted view again, which then follows the canvas.
        vm.fitToPath()
        val fittedShort = vm.camera.value
        vm.setViewport(1080f, 1920f)
        assertNotEquals(fittedShort, vm.camera.value)
        assertPathOnScreen(vm, 1080f, 1920f)
    }

    // --- share ---

    @Test
    fun shareIsBusyUntilTheZipIsWrittenIgnoresASecondTapAndHandsOverTheShareSheet() = runBlocking<Unit> {
        val id = processedTrip()
        val gate = CompletableDeferred<ShareRequest>()
        val requested = ArrayList<Long>()
        val vm = viewer(id) { tripId ->
            requested += tripId
            gate.await()
        }
        assertTrue(vm.ui.value.canShare)
        assertTrue(vm.ui.value.shareEnabled)

        vm.share()
        assertTrue(vm.ui.value.busy)
        assertFalse(vm.ui.value.shareEnabled)
        vm.share()
        assertEquals(listOf(id), requested)

        val request = ShareRequest { }
        gate.complete(request)
        assertFalse(vm.ui.value.busy)
        assertSame(request, vm.ui.value.pendingShare)
        assertNull(vm.ui.value.message)

        vm.consumeShare()
        assertNull(vm.ui.value.pendingShare)
        assertTrue(vm.ui.value.shareEnabled)
    }

    @Test
    fun aFailedExportSaysWhyOnce() = runBlocking<Unit> {
        val vm = viewer(processedTrip()) { throw IOException("No space left on device") }
        vm.share()
        assertEquals("Export failed: No space left on device", vm.ui.value.message)
        assertFalse(vm.ui.value.busy)
        assertNull(vm.ui.value.pendingShare)

        vm.dismissMessage()
        assertNull(vm.ui.value.message)
    }

    @Test
    fun shareIsHiddenWithoutAnExporterAndOffWhileRecording() = runBlocking<Unit> {
        val plain = viewer(processedTrip())
        assertFalse(plain.ui.value.canShare)
        assertFalse(plain.ui.value.shareEnabled)
        plain.share()
        assertFalse(plain.ui.value.busy)

        val requested = ArrayList<Long>()
        val recording = viewer(trip(TripStatus.RECORDING)) { tripId ->
            requested += tripId
            ShareRequest { }
        }
        assertTrue(recording.ui.value.canShare)
        assertFalse(recording.ui.value.shareEnabled)
        recording.share()
        assertTrue(requested.isEmpty())
        assertNull(recording.ui.value.pendingShare)
    }
}
