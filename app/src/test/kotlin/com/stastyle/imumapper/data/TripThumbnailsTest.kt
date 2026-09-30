package com.stastyle.imumapper.data

import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.PathPoint
import com.stastyle.imumapper.pipeline.core.PositionSource
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.render.PathThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The trip cards' thumbnail cache, on a temp directory: sidecar reuse, regeneration, and never recreating folders. */
class TripThumbnailsTest {

    private lateinit var tmp: File
    private lateinit var files: TripFiles
    private lateinit var trips: FakeTripRepository

    // The app's settings (AppContainer.json).
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    /** An oval walk with a slight drift, long enough that the thumbnail keeps only some of its points. */
    private val points: List<PathPoint> = (0 until 300).map { i ->
        val a = 2 * PI * i / 299
        PathPoint(i * 100_000_000L, Vec3(10 * cos(a), 5 * sin(a) + i * 0.01, 0.0), PositionSource.PDR, 0.0, i)
    }
    private val expected = PathThumbnail.build(points)

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("imu-thumbs").toFile()
        files = TripFiles(File(tmp, "files"), File(tmp, "cache"))
        trips = FakeTripRepository(files)
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun thumbnails(beforeWrite: suspend (Long) -> Unit = {}) =
        TripThumbnails(files, trips, json, Dispatchers.Unconfined, beforeWrite)

    /** A trip row and its folder, with nothing in it. */
    private suspend fun newTrip(): Long = trips.createTrip(
        TripEntity(name = "walk", mode = TripMode.POCKET, carryPosition = CarryPosition.HAND, startedAtEpochMs = 1L),
    )

    /** A trip row and folder with run [runId] written as the processor writes it. */
    private suspend fun tripWithRun(runId: Int = 1): Long {
        val id = newTrip()
        files.resultFile(id, runId).writeText(sampleResult().copy(points = points).toJson())
        return id
    }

    private fun tripFolder(id: Long) = File(files.root, id.toString())

    @Test
    fun makesTheThumbnailAndWritesTheSidecar() = runBlocking {
        val id = tripWithRun()
        val cache = thumbnails()
        assertNull(cache.cached(id, 1), "nothing in memory before the first request")
        assertEquals(expected, cache.get(id, 1))
        assertEquals(expected, cache.cached(id, 1))
        val sidecar = cache.sidecarFile(id, 1)
        assertEquals(tripFolder(id), sidecar.parentFile, "beside the results folder, not in it")
        assertTrue(sidecar.isFile)
        assertFalse(File(sidecar.parentFile, sidecar.name + ".tmp").exists())
    }

    @Test
    fun aSecondInstanceReadsTheSidecarWithoutTheRun() = runBlocking {
        val id = tripWithRun()
        thumbnails().get(id, 1)
        files.resultFile(id, 1).delete()
        assertEquals(expected, thumbnails().get(id, 1))
    }

    @Test
    fun decodesOnlyThePoints() = runBlocking {
        val id = newTrip()
        // Nothing but the points has to be a valid PathResult.
        val pointsJson = json.encodeToString(ListSerializer(PathPoint.serializer()), points)
        files.resultFile(id, 1).writeText(
            "{\"pipelineVersion\":\"new\",\"points\":$pointsJson,\"stats\":\"elsewhere\",\"pointCloud\":[[1,2]]}",
        )
        assertEquals(expected, thumbnails().get(id, 1))
    }

    @Test
    fun aMissingFolderGivesNullAndCreatesNoFolder() = runBlocking {
        assertNull(thumbnails().get(42, 1))
        assertFalse(File(tmp, "files").exists(), "not even the trips root")
    }

    @Test
    fun aMissingRunGivesNullAndCreatesNoFolder() = runBlocking {
        val id = tripWithRun(runId = 1)
        val cache = thumbnails()
        assertNull(cache.get(id, 2))
        assertFalse(cache.sidecarFile(id, 2).exists())

        val emptyTrip = newTrip()
        assertNull(cache.get(emptyTrip, 1))
        assertFalse(File(tripFolder(emptyTrip), TripFiles.RESULTS_DIR_NAME).exists())
        assertEquals(emptyList(), tripFolder(emptyTrip).list()!!.toList())
    }

    @Test
    fun aCorruptSidecarIsMadeAgain() = runBlocking {
        val id = tripWithRun()
        val sidecar = thumbnails().sidecarFile(id, 1)
        sidecar.writeText("{\"formatVersion\":1,\"sourceLe")
        assertEquals(expected, thumbnails().get(id, 1))
        // The new sidecar is whole: it serves on its own once the run is gone.
        files.resultFile(id, 1).delete()
        assertEquals(expected, thumbnails().get(id, 1))
    }

    @Test
    fun aSidecarWithMismatchedArraysIsMadeAgain() = runBlocking {
        val id = tripWithRun()
        val length = files.resultFile(id, 1).length()
        thumbnails().sidecarFile(id, 1).writeText(sidecarJson(TripThumbnails.FORMAT_VERSION, length, "[0.5,0.5]"))
        assertEquals(expected, thumbnails().get(id, 1))
    }

    @Test
    fun anOldFormatSidecarIsMadeAgain() = runBlocking {
        val id = tripWithRun()
        val length = files.resultFile(id, 1).length()
        val cache = thumbnails()
        cache.sidecarFile(id, 1).writeText(sidecarJson(TripThumbnails.FORMAT_VERSION - 1, length))
        assertEquals(expected, cache.get(id, 1))
        files.resultFile(id, 1).delete()
        assertEquals(expected, thumbnails().get(id, 1), "the old sidecar was replaced")
    }

    @Test
    fun aSidecarFromAnotherRunFileIsMadeAgain() = runBlocking {
        val id = tripWithRun()
        val length = files.resultFile(id, 1).length()
        thumbnails().sidecarFile(id, 1).writeText(sidecarJson(TripThumbnails.FORMAT_VERSION, length + 1))
        assertEquals(expected, thumbnails().get(id, 1))
    }

    @Test
    fun aCurrentSidecarIsTrustedOverTheRun() = runBlocking {
        val id = tripWithRun()
        val length = files.resultFile(id, 1).length()
        thumbnails().sidecarFile(id, 1).writeText(sidecarJson(TripThumbnails.FORMAT_VERSION, length))
        val stored = PathThumbnail(floatArrayOf(0.25f), floatArrayOf(0.75f), floatArrayOf(0f))
        assertEquals(stored, thumbnails().get(id, 1))
    }

    @Test
    fun failuresAreRememberedForTheSession() = runBlocking {
        val id = newTrip()
        files.resultFile(id, 1).writeText("{\"points\":[{\"tNs\":")
        val cache = thumbnails()
        assertNull(cache.get(id, 1))
        files.resultFile(id, 1).writeText(sampleResult().copy(points = points).toJson())
        assertNull(cache.get(id, 1), "the failure is not retried")
        assertEquals(expected, thumbnails().get(id, 1), "a new session tries again")
    }

    @Test
    fun aGenerationFinishingAfterTheTripIsDeletedWritesNothing() = runBlocking {
        val id = tripWithRun()
        val cache = thumbnails(beforeWrite = { trips.deleteTrip(it) })
        cache.get(id, 1)
        assertFalse(tripFolder(id).exists(), "the folder is not recreated")
        assertEquals(emptyList(), files.root.list()!!.toList())
    }

    @Test
    fun aSidecarWrittenAfterTheRowWentIsRemoved() = runBlocking {
        // The folder is still there but the row is not: the trip is being deleted and its files go next.
        val id = 9L
        files.resultFile(id, 1).writeText(sampleResult().copy(points = points).toJson())
        val cache = thumbnails()
        assertNotNull(cache.get(id, 1))
        assertFalse(cache.sidecarFile(id, 1).exists())
        assertFalse(File(tripFolder(id), cache.sidecarFile(id, 1).name + ".tmp").exists())
    }

    @Test
    fun aCancelledCallerStillClearsUpAfterADeletedTrip() = runBlocking {
        val id = tripWithRun()
        var rowGone = false
        // Like Room's suspending queries, this getTrip throws once its caller is cancelled.
        val room = object : TripRepository by trips {
            override suspend fun getTrip(tripId: Long): TripEntity? {
                currentCoroutineContext().ensureActive()
                return if (rowGone) null else trips.getTrip(tripId)
            }
        }
        lateinit var card: Job
        val cache = TripThumbnails(files, room, json, Dispatchers.Unconfined) {
            // The delete removed the row, which took the card off the screen, and is emptying the folder.
            rowGone = true
            File(tripFolder(id), TripFiles.RESULTS_DIR_NAME).deleteRecursively()
            card.cancel()
        }
        card = launch { cache.get(id, 1) }
        card.join()
        assertTrue(card.isCancelled)
        assertFalse(cache.sidecarFile(id, 1).exists())
        assertFalse(tripFolder(id).exists(), "the folder the delete emptied is not left behind")
    }

    @Test
    fun aPathWithoutPointsGivesAnEmptyThumbnail() = runBlocking {
        val id = newTrip()
        files.resultFile(id, 1).writeText(sampleResult().copy(points = emptyList()).toJson())
        assertEquals(0, thumbnails().get(id, 1)?.size)
    }

    private fun sidecarJson(version: Int, sourceLength: Long, x: String = "[0.25]"): String =
        "{\"formatVersion\":$version,\"sourceLength\":$sourceLength,\"x\":$x,\"y\":[0.75],\"progress\":[0.0]}"
}
