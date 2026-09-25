package com.stastyle.imumapper.data

import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.pipeline.log.LogReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The importer's parsing half: sniffing, unpacking and manifest mapping, without a content resolver. */
class TripImporterTest {

    private lateinit var tmp: File
    private lateinit var files: TripFiles

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("imu-import").toFile()
        files = TripFiles(File(tmp, "files"), File(tmp, "cache"))
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun detectsKindFromHeader() {
        val imul = "IMUL\u0001\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII)
        assertEquals(ArchiveKind.RAW_LOG, TripArchive.detectKind(imul))
        assertEquals(ArchiveKind.ZIP, TripArchive.detectKind(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0)))
        assertEquals(ArchiveKind.UNKNOWN, TripArchive.detectKind("{\"a\":1}".toByteArray()))
        assertEquals(ArchiveKind.UNKNOWN, TripArchive.detectKind(byteArrayOf(0x50, 0x4B)))
        assertEquals(ArchiveKind.UNKNOWN, TripArchive.detectKind(ByteArray(0)))
    }

    @Test
    fun exportedZipRoundTrips() {
        val trip = TripEntity(
            id = 3, name = "Corridor", mode = TripMode.POCKET, carryPosition = CarryPosition.CHEST,
            startedAtEpochMs = 123L, status = TripStatus.PROCESSED, latestRunId = 1, distanceM = 20.0, durationS = 40.0,
        )
        writeSampleLog(files.rawLog(3))
        files.resultFile(3, 1).writeText(sampleResult().toJson())
        File(files.photosDir(3), "p.jpg").writeBytes(byteArrayOf(9))
        val results = listOf(PathResultEntity(3, 1, 1, 1L, "run-1.json", "{}", "{}", "PDR"))
        val out = ByteArrayOutputStream()
        TripArchive.write(trip, results, files.tripDir(3), out)
        val bytes = out.toByteArray()

        val staging = File(files.importDir(), "s")
        val extracted = TripArchive.extractZip(bytes.inputStream(), staging)

        val manifest = assertNotNull(extracted.manifest)
        assertEquals("Corridor", manifest.name)
        assertEquals(CarryPosition.CHEST, manifest.carryPosition)
        assertEquals(1, manifest.results.size)
        val raw = assertNotNull(extracted.rawLog)
        assertEquals(File(staging, "raw.imul"), raw)
        assertTrue(raw.readBytes().contentEquals(files.rawLog(3).readBytes()))
        assertEquals(listOf("run-1.json"), extracted.results.map { it.name })
        assertEquals(sampleResult().toJson(), extracted.results[0].readText())
        assertEquals(listOf("p.jpg"), extracted.photos.map { it.name })
        assertTrue(File(staging, "photos/p.jpg").readBytes().contentEquals(byteArrayOf(9)))

        // The unpacked log is a readable IMUL file with its meta intact.
        val log = LogReader.read(raw)
        assertEquals(TripMode.POCKET, log.meta?.mode)
        assertEquals(50, log.accel.size)
    }

    @Test
    fun bareLogIsCopiedAsRawLog() {
        val src = File(tmp, "walk.imul")
        writeSampleLog(src)
        val staging = File(files.importDir(), "s")
        val extracted = src.inputStream().use { TripArchive.extractRawLog(it, staging) }
        assertNull(extracted.manifest)
        assertTrue(extracted.results.isEmpty())
        assertTrue(assertNotNull(extracted.rawLog).readBytes().contentEquals(src.readBytes()))
    }

    @Test
    fun unknownEntriesAreIgnored() {
        val bytes = zipOf(
            "__MACOSX/._trip.json" to "junk",
            "results/notes.txt" to "not a run",
            "results/deep/run-1.json" to "wrong depth",
            "other/raw.imul" to "wrong place",
            "raw.imul" to "IMUL",
            "results/run-4.json" to "{}",
        )
        val extracted = TripArchive.extractZip(bytes.inputStream(), File(files.importDir(), "s"))
        assertNull(extracted.manifest)
        assertEquals("IMUL", extracted.rawLog?.readText())
        assertEquals(listOf("run-4.json"), extracted.results.map { it.name })
        assertTrue(extracted.photos.isEmpty())
    }

    @Test
    fun rejectsPathsThatEscapeTheStagingDirectory() {
        val bytes = zipOf("results/../../evil.json" to "{}")
        assertFailsWith<IOException> { TripArchive.extractZip(bytes.inputStream(), File(files.importDir(), "s")) }
        assertTrue(!File(files.importDir(), "evil.json").exists())
    }

    @Test
    fun manifestBuildsARecordedEntityForAnotherPhone() {
        val manifest = TripManifest(
            exportedAtEpochMs = 1L, name = "n", mode = TripMode.ILLUMINATED, carryPosition = CarryPosition.HELMET,
            startedAtEpochMs = 5L, status = TripStatus.FAILED, lastError = "boom", latestRunId = 3,
        )
        val entity = manifest.toTripEntity()
        assertEquals(0L, entity.id)
        assertEquals(TripMode.ILLUMINATED, entity.mode)
        assertEquals(CarryPosition.HELMET, entity.carryPosition)
        assertEquals("boom", entity.lastError)
    }

    @Test
    fun runIdParsing() {
        assertEquals(12, TripArchive.runIdOf("run-12.json"))
        assertNull(TripArchive.runIdOf("run-x.json"))
        assertNull(TripArchive.runIdOf("notes.json"))
        assertNull(TripArchive.runIdOf("run-1.json.tmp"))
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
