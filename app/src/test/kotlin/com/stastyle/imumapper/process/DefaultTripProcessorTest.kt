package com.stastyle.imumapper.process

import com.stastyle.imumapper.data.FakeCalibrationRepository
import com.stastyle.imumapper.data.FakeProcessor
import com.stastyle.imumapper.data.FakeTripRepository
import com.stastyle.imumapper.data.TripFiles
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.data.sampleResult
import com.stastyle.imumapper.data.writeSampleLog
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PathStats
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.TripMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultTripProcessorTest {

    private lateinit var tmp: File
    private lateinit var files: TripFiles
    private lateinit var trips: FakeTripRepository
    private lateinit var calibration: FakeCalibrationRepository
    private lateinit var processor: FakeProcessor
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var now = 1_700_000_000_000L

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("imu-proc").toFile()
        files = TripFiles(File(tmp, "files"), File(tmp, "cache"))
        trips = FakeTripRepository(files)
        calibration = FakeCalibrationRepository(PipelineConfig(strideLengthM = 0.66))
        processor = FakeProcessor()
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun subject(processor: FakeProcessor = this.processor) = DefaultTripProcessor(
        trips = trips,
        calibration = calibration,
        files = files,
        processor = processor,
        json = json,
        ioDispatcher = Dispatchers.IO,
        computeDispatcher = Dispatchers.Default,
        clock = { now },
    )

    private suspend fun recordedTrip(withVio: Boolean = false, status: TripStatus = TripStatus.RECORDED): Long {
        val id = trips.createTrip(
            TripEntity(
                name = "walk",
                mode = if (withVio) TripMode.FLASHLIGHT else TripMode.POCKET,
                carryPosition = CarryPosition.POCKET,
                startedAtEpochMs = 1L,
                status = status,
            ),
        )
        writeSampleLog(files.rawLog(id), withVio = withVio)
        return id
    }

    @Test
    fun processesAndStoresFirstRun() = runBlocking<Unit> {
        val id = recordedTrip()
        val entity = subject().process(id)

        assertEquals(1, entity.runId)
        assertEquals(id, entity.tripId)
        assertEquals("run-1.json", entity.fileName)
        assertEquals("PDR", entity.label)
        assertEquals(now, entity.createdAtEpochMs)
        assertEquals(sampleResult().stats, json.decodeFromString(PathStats.serializer(), entity.statsJson))
        assertEquals(calibration.config, json.decodeFromString(PipelineConfig.serializer(), entity.configJson))

        val stored = PathResult.fromJson(files.resultFile(id, 1).readText())
        assertEquals(2, stored.points.size)
        assertEquals(0.66, stored.config.strideLengthM)
        assertTrue(files.resultsDir(id).listFiles()!!.none { it.name.endsWith(".tmp") })

        val trip = assertNotNull(trips.getTrip(id))
        assertEquals(TripStatus.PROCESSED, trip.status)
        assertEquals(1, trip.latestRunId)
        assertEquals(12.5, trip.distanceM)
        assertEquals(30.0, trip.durationS)
        assertNull(trip.lastError)
        assertEquals(entity, trips.getResult(id, 1))
    }

    @Test
    fun usesSavedCalibrationUnlessConfigGiven() = runBlocking<Unit> {
        val id = recordedTrip()
        subject().process(id)
        assertEquals(0.66, processor.calls.single().strideLengthM)

        val explicit = PipelineConfig(strideLengthM = 0.9)
        val second = subject().process(id, explicit, "long stride")
        assertEquals(0.9, processor.calls.last().strideLengthM)
        assertEquals("long stride", second.label)
    }

    @Test
    fun secondRunGetsNextIdAndKeepsFirst() = runBlocking<Unit> {
        val id = recordedTrip()
        val first = subject().process(id)
        processor.result = sampleResult(distanceM = 20.0)
        val second = subject().process(id)

        assertEquals(1, first.runId)
        assertEquals(2, second.runId)
        assertTrue(files.resultFile(id, 1).isFile)
        assertTrue(files.resultFile(id, 2).isFile)
        assertEquals(listOf(1, 2), trips.listResults(id).map { it.runId })
        assertEquals(2, trips.getTrip(id)!!.latestRunId)
        assertEquals(20.0, trips.getTrip(id)!!.distanceM)
    }

    @Test
    fun labelIsVioWhenLogHasTracking() = runBlocking<Unit> {
        val id = recordedTrip(withVio = true)
        assertEquals("VIO", subject().process(id).label)
    }

    @Test
    fun explicitProcessorAndLabel() = runBlocking<Unit> {
        val id = recordedTrip(withVio = true)
        val other = FakeProcessor(result = sampleResult(distanceM = 3.0))
        val entity = subject().processWith(id, other, label = "PDR only")
        assertEquals("PDR only", entity.label)
        assertEquals(1, other.calls.size)
        assertEquals(0, processor.calls.size)
        assertEquals(3.0, trips.getTrip(id)!!.distanceM)
    }

    @Test
    fun failureMarksTripFailedAndRethrows() = runBlocking<Unit> {
        val id = recordedTrip()
        processor.failure = IllegalStateException("no steps found")

        val e = assertFailsWith<IllegalStateException> { subject().process(id) }
        assertEquals("no steps found", e.message)

        val trip = trips.getTrip(id)!!
        assertEquals(TripStatus.FAILED, trip.status)
        assertEquals("no steps found", trip.lastError)
        assertNull(trip.latestRunId)
        assertTrue(trips.listResults(id).isEmpty())
        assertTrue(files.resultsDir(id).listFiles()!!.isEmpty())

        // A later successful run clears the error.
        processor.failure = null
        subject().process(id)
        assertEquals(TripStatus.PROCESSED, trips.getTrip(id)!!.status)
        assertNull(trips.getTrip(id)!!.lastError)
    }

    @Test
    fun renameDuringRunIsKeptBySuccessAndFailure() = runBlocking<Unit> {
        val id = recordedTrip()
        val renaming = FakeProcessor(onProcess = { _, _ -> runBlocking { trips.renameTrip(id, "renamed") } })
        subject(renaming).process(id)
        var trip = trips.getTrip(id)!!
        assertEquals("renamed", trip.name)
        assertEquals(TripStatus.PROCESSED, trip.status)
        assertEquals(1, trip.latestRunId)

        val failing = FakeProcessor(
            failure = IllegalStateException("boom"),
            onProcess = { _, _ -> runBlocking { trips.renameTrip(id, "renamed again") } },
        )
        assertFailsWith<IllegalStateException> { subject(failing).process(id) }
        trip = trips.getTrip(id)!!
        assertEquals("renamed again", trip.name)
        assertEquals(TripStatus.FAILED, trip.status)
        assertEquals("boom", trip.lastError)
        // A failed re-run keeps the earlier result reachable from the row.
        assertEquals(1, trip.latestRunId)
        assertEquals(12.5, trip.distanceM)
    }

    @Test
    fun missingLogFails() = runBlocking<Unit> {
        val id = trips.createTrip(
            TripEntity(
                name = "empty", mode = TripMode.POCKET, carryPosition = CarryPosition.HAND, startedAtEpochMs = 1L,
                status = TripStatus.RECORDED,
            ),
        )
        assertFailsWith<FileNotFoundException> { subject().process(id) }
        assertEquals(TripStatus.FAILED, trips.getTrip(id)!!.status)
        assertTrue(trips.getTrip(id)!!.lastError!!.contains("raw.imul"))
    }

    @Test
    fun refusesTripStillRecording() = runBlocking<Unit> {
        val id = recordedTrip(status = TripStatus.RECORDING)
        assertFailsWith<IllegalStateException> { subject().process(id) }
        // The recorder owns the row: its status must not be clobbered.
        assertEquals(TripStatus.RECORDING, trips.getTrip(id)!!.status)
        assertEquals(0, processor.calls.size)
    }

    @Test
    fun unknownTripThrows() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { subject().process(42L) }
    }

    @Test
    fun runsOnTheSameTripAreSerialised() = runBlocking<Unit> {
        val id = recordedTrip()
        val entered = CountDownLatch(1)
        val slow = FakeProcessor(onProcess = { _, _ ->
            entered.countDown()
            // Hold the first run long enough that a parallel second run would overlap if unguarded.
            Thread.sleep(150)
        })
        val subject = subject(slow)
        val results = listOf(
            async(Dispatchers.Default) { subject.process(id) },
            async(Dispatchers.Default) {
                entered.await(5, TimeUnit.SECONDS)
                subject.process(id)
            },
        ).awaitAll()

        assertEquals(1, slow.maxConcurrent.get())
        assertEquals(setOf(1, 2), results.map { it.runId }.toSet())
        assertEquals(2, trips.listResults(id).size)
    }

    @Test
    fun runsOnDifferentTripsMayOverlap() = runBlocking<Unit> {
        val a = recordedTrip()
        val b = recordedTrip()
        val both = CountDownLatch(2)
        val slow = FakeProcessor(onProcess = { _, _ ->
            both.countDown()
            both.await(2, TimeUnit.SECONDS)
        })
        val subject = subject(slow)
        listOf(
            async(Dispatchers.Default) { subject.process(a) },
            async(Dispatchers.Default) { subject.process(b) },
        ).awaitAll()
        assertEquals(2, slow.maxConcurrent.get())
    }

    @Test
    fun readResultLoadsStoredFile() = runBlocking<Unit> {
        val id = recordedTrip()
        val entity = subject().process(id)
        val loaded = files.readResult(entity)
        assertEquals(sampleResult().points, loaded.points)
    }
}
