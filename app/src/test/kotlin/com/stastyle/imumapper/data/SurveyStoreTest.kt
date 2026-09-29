package com.stastyle.imumapper.data

import com.stastyle.imumapper.pipeline.survey.CompassReference
import com.stastyle.imumapper.pipeline.survey.Detail
import com.stastyle.imumapper.pipeline.survey.ReferenceLine
import com.stastyle.imumapper.pipeline.survey.Station
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
import kotlinx.serialization.SerializationException
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** survey.json in a temp directory: what load reports, and that save never leaves half a file behind. */
class SurveyStoreTest {

    private lateinit var tmp: File
    private lateinit var files: TripFiles
    private lateinit var store: SurveyStore

    private val tripId = 7L

    private val doc = SurveyDoc(
        stations = listOf(
            Station(id = 1, kind = StationKind.START, name = "Start", tNs = 1_000_000_000L),
            Station(id = 2, kind = StationKind.MARK, name = "Junction 1", tNs = 6_000_000_000L),
            Station(id = 3, kind = StationKind.END, name = "End", tNs = 16_000_000_000L),
        ),
        references = listOf(
            CompassReference(
                id = 1,
                fromNs = 1_000_000_000L,
                toNs = 11_000_000_000L,
                bearingDeg = 47.5,
                backBearing = true,
                line = ReferenceLine.FITTED,
            ),
        ),
        manualRotationDeg = -2.5,
        manualRotationRunId = 3,
        detail = Detail.FINE,
    )

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("imu-survey").toFile()
        files = TripFiles(File(tmp, "files"), File(tmp, "cache"))
        store = SurveyStore(files)
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun surveyFileSitsNextToTheRawLog() {
        assertEquals(File(files.tripDir(tripId), "survey.json"), files.surveyFile(tripId))
        assertEquals(files.rawLog(tripId).parentFile, files.surveyFile(tripId).parentFile)
    }

    @Test
    fun missingFileIsMissing() {
        assertEquals(SurveyLoad.Missing, store.load(tripId))
        assertFalse(files.surveyFile(tripId).exists(), "loading never creates the file")
    }

    @Test
    fun saveThenLoadRoundTrips() {
        store.save(tripId, doc)
        assertEquals(SurveyLoad.Loaded(doc), store.load(tripId))
        assertEquals(doc.toJson(), files.surveyFile(tripId).readText())
    }

    @Test
    fun saveLeavesNoTempFileBehind() {
        store.save(tripId, doc)
        assertEquals(listOf(TripFiles.SURVEY_NAME), files.tripDir(tripId).list()!!.toList())
    }

    @Test
    fun secondSaveReplacesTheFirst() {
        store.save(tripId, doc)
        val edited = doc.copy(stations = doc.stations.dropLast(1), detail = Detail.COARSE)
        store.save(tripId, edited)
        assertEquals(SurveyLoad.Loaded(edited), store.load(tripId))
        assertEquals(listOf(TripFiles.SURVEY_NAME), files.tripDir(tripId).list()!!.toList())
    }

    @Test
    fun docThatCannotBeEncodedLeavesTheSavedFileAlone() {
        store.save(tripId, doc)
        assertFailsWith<SerializationException> { store.save(tripId, doc.copy(manualRotationDeg = Double.NaN)) }
        assertEquals(SurveyLoad.Loaded(doc), store.load(tripId))
        assertEquals(listOf(TripFiles.SURVEY_NAME), files.tripDir(tripId).list()!!.toList())
    }

    @Test
    fun invalidJsonIsMalformedAndLeftAsItIs() {
        val bad = "{ \"stations\": [ oops"
        files.surveyFile(tripId).writeText(bad)
        val load = assertIs<SurveyLoad.Malformed>(store.load(tripId))
        assertTrue(load.message.isNotBlank())
        assertFalse('\n' in load.message, "one line for the error text: ${load.message}")
        assertEquals(bad, files.surveyFile(tripId).readText())
    }

    @Test
    fun jsonOfTheWrongShapeIsMalformed() {
        files.surveyFile(tripId).writeText("[1, 2]")
        assertIs<SurveyLoad.Malformed>(store.load(tripId))
        assertEquals("[1, 2]", files.surveyFile(tripId).readText())
    }

    @Test
    fun directoryInTheFilesPlaceIsMalformed() {
        val dir = files.surveyFile(tripId).also { it.mkdirs() }
        val load = assertIs<SurveyLoad.Malformed>(store.load(tripId))
        assertTrue(load.message.isNotBlank())
        assertTrue(dir.isDirectory, "the directory is left as it is")
    }
}
