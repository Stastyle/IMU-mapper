package com.stastyle.imumapper.data

import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The exporter's ZIP layout, built and inspected on a temp directory without Android. */
class TripArchiveTest {

    private lateinit var tmp: File
    private lateinit var files: TripFiles

    private val trip = TripEntity(
        id = 7,
        name = "Cave loop",
        mode = TripMode.FLASHLIGHT,
        carryPosition = CarryPosition.HAND,
        startedAtEpochMs = 1_700_000_000_000L,
        endedAtEpochMs = 1_700_000_300_000L,
        status = TripStatus.PROCESSED,
        rawLogSizeBytes = 1234,
        latestRunId = 2,
        distanceM = 88.0,
        durationS = 300.0,
        notes = "wet floor",
    )

    private val results = listOf(
        PathResultEntity(7, 1, 1, 10L, "run-1.json", "{\"distanceM\":80.0}", "{}", "PDR"),
        PathResultEntity(7, 2, 1, 20L, "run-2.json", "{\"distanceM\":88.0}", "{}", "VIO"),
    )

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("imu-archive").toFile()
        files = TripFiles(File(tmp, "files"), File(tmp, "cache"))
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun populateTrip(withResults: Boolean = true, withPhotos: Boolean = true) {
        writeSampleLog(files.rawLog(trip.id), withVio = true)
        if (withResults) {
            files.resultFile(trip.id, 1).writeText(sampleResult(80.0).toJson())
            files.resultFile(trip.id, 2).writeText(sampleResult(88.0).toJson())
            // Left behind by an interrupted write; must not be exported.
            File(files.resultsDir(trip.id), "run-3.json.tmp").writeText("partial")
        }
        if (withPhotos) {
            File(files.photosDir(trip.id), "kf-0001.jpg").writeBytes(byteArrayOf(1, 2, 3))
            File(files.photosDir(trip.id), "kf-0002.jpg").writeBytes(byteArrayOf(4, 5))
        }
        // A stray file in the trip root is not part of the layout.
        File(files.tripDir(trip.id), "scratch.txt").writeText("ignore me")
    }

    private fun buildZip(): File {
        val zip = File(files.exportDir(), "trip.zip")
        zip.outputStream().use { out ->
            TripArchive.write(trip, results, files.tripDir(trip.id), out, exportedAtEpochMs = 99L, appVersion = "1.2.3")
        }
        return zip
    }

    @Test
    fun zipHoldsManifestLogResultsAndPhotos() {
        populateTrip()
        val zip = buildZip()
        ZipFile(zip).use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            assertEquals(
                listOf(
                    "trip.json", "raw.imul", "results/run-1.json", "results/run-2.json",
                    "photos/kf-0001.jpg", "photos/kf-0002.jpg",
                ),
                names,
            )
            val raw = z.getInputStream(z.getEntry("raw.imul")).readBytes()
            assertTrue(raw.contentEquals(files.rawLog(trip.id).readBytes()))
            val photo = z.getInputStream(z.getEntry("photos/kf-0001.jpg")).readBytes()
            assertTrue(photo.contentEquals(byteArrayOf(1, 2, 3)))

            val manifestText = z.getInputStream(z.getEntry("trip.json")).readBytes().toString(Charsets.UTF_8)
            val manifest = TripArchive.decodeManifest(manifestText)
            assertEquals(TripArchive.MANIFEST_VERSION, manifest.formatVersion)
            assertEquals(99L, manifest.exportedAtEpochMs)
            assertEquals("1.2.3", manifest.appVersion)
            assertEquals("Cave loop", manifest.name)
            assertEquals(TripMode.FLASHLIGHT, manifest.mode)
            assertEquals(CarryPosition.HAND, manifest.carryPosition)
            assertEquals(TripStatus.PROCESSED, manifest.status)
            assertEquals(2, manifest.latestRunId)
            assertEquals(88.0, manifest.distanceM)
            assertEquals("wet floor", manifest.notes)
            assertEquals(listOf(1, 2), manifest.results.map { it.runId })
            assertEquals(listOf("PDR", "VIO"), manifest.results.map { it.label })
            assertEquals("{\"distanceM\":80.0}", manifest.results[0].statsJson)
        }
    }

    @Test
    fun zipWithoutResultsOrPhotosStillExports() {
        populateTrip(withResults = false, withPhotos = false)
        val zip = buildZip()
        ZipFile(zip).use { z ->
            assertEquals(listOf("trip.json", "raw.imul"), z.entries().asSequence().map { it.name }.toList())
        }
    }

    @Test
    fun zipWithoutRawLogStillCarriesManifest() {
        val out = ByteArrayOutputStream()
        TripArchive.write(trip, emptyList(), files.tripDir(trip.id), out)
        val extracted = TripArchive.extractZip(out.toByteArray().inputStream(), File(tmp, "staging"))
        assertNull(extracted.rawLog)
        assertEquals("Cave loop", extracted.manifest?.name)
    }

    @Test
    fun manifestRoundTripsThroughEntity() {
        val manifest = TripManifest.of(trip, results, exportedAtEpochMs = 5L)
        val decoded = TripArchive.decodeManifest(TripArchive.encodeManifest(manifest))
        assertEquals(manifest, decoded)
        // The entity comes back without the exporting phone's row id.
        assertEquals(trip.copy(id = 0), decoded.toTripEntity())
        assertEquals(results[1], decoded.results[1].toEntity(7))
    }

    @Test
    fun pruneExportsRemovesOnlyOldFiles() {
        val old = File(files.exportDir(), "old.zip").also { it.writeText("x") }
        val fresh = File(files.exportDir(), "fresh.zip").also { it.writeText("y") }
        old.setLastModified(1_000L)
        fresh.setLastModified(10_000L)
        files.pruneExports(maxAgeMs = 5_000L, nowMs = 12_000L)
        assertTrue(!old.exists())
        assertTrue(fresh.exists())
    }
}
