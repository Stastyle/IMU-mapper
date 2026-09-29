package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.SurveyFixtures
import com.stastyle.imumapper.data.FakeTripRepository
import com.stastyle.imumapper.data.SurveyStore
import com.stastyle.imumapper.data.TripFiles
import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.pipeline.core.Vec3
import com.stastyle.imumapper.pipeline.survey.Station
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
import com.stastyle.imumapper.process.TripProcessor
import com.stastyle.imumapper.render.LayerSelection
import com.stastyle.imumapper.render.OrbitCamera
import com.stastyle.imumapper.render.SurveyHit
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Survey mode in the viewer, on the L walk: Start (id 1, 0 m), Junction 1 (id 2, 5 m), C1 (id 4,
 * 10 m, the corner at (0, 10)) and End (id 3, 15 m). File work runs on Dispatchers.Unconfined, so
 * every call, saves included, has finished when it returns. Tests use runBlocking<Unit> because JUnit 4
 * rejects a test method that returns a value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerSurveyTest {

    private lateinit var tmp: File
    private lateinit var files: TripFiles
    private lateinit var trips: FakeTripRepository

    /** These trips already have runs, so the viewer must never process one. */
    private class NoProcessing : TripProcessor {
        override suspend fun process(tripId: Long, config: PipelineConfig?, label: String): PathResultEntity =
            throw AssertionError("the viewer processed a trip that has runs")
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tmp = Files.createTempDirectory("imu-survey-viewer").toFile()
        files = TripFiles(File(tmp, "files"), File(tmp, "cache"))
        trips = FakeTripRepository(files)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        tmp.deleteRecursively()
    }

    /** A processed trip named "Cave loop" whose runs 1, 2, ... are [runs]. */
    private suspend fun processedTrip(vararg runs: PathResult): Long {
        val id = trips.createTrip(
            TripEntity(
                name = "Cave loop",
                mode = TripMode.POCKET,
                carryPosition = CarryPosition.HAND,
                startedAtEpochMs = 1L,
                status = TripStatus.PROCESSED,
            ),
        )
        runs.forEachIndexed { index, result ->
            val runId = index + 1
            files.resultFile(id, runId).writeText(result.toJson())
            trips.addResult(
                PathResultEntity(
                    tripId = id,
                    runId = runId,
                    pipelineVersion = result.pipelineVersion,
                    createdAtEpochMs = 0L,
                    fileName = files.resultFileName(runId),
                    statsJson = "{}",
                    configJson = "{}",
                ),
            )
        }
        return id
    }

    /** The viewer with the latest run loaded and a portrait viewport, so presets can frame. */
    private fun viewer(tripId: Long): ViewerViewModel = ViewerViewModel(
        tripId,
        trips,
        files,
        NoProcessing(),
        surveys = SurveyStore(files),
        ioDispatcher = Dispatchers.Unconfined,
    ).also { it.setViewport(1080f, 1920f) }

    private fun survey(vm: ViewerViewModel): SurveyUi = assertNotNull(vm.ui.value.survey)

    private fun savedDoc(tripId: Long): SurveyDoc = SurveyDoc.fromJson(files.surveyFile(tripId).readText())

    private fun t(index: Int): Long = SurveyFixtures.tNs(index)

    private fun assertNear(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
        assertEquals(expected.z, actual.z, 1e-9)
    }

    // --- entering, loading, selecting ---

    @Test
    fun enteringSeedsTheStationsSavesThemAndLooksFromTheTop() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()

        val ui = vm.ui.value
        assertTrue(ui.surveyMode)
        val survey = survey(vm)
        assertEquals(listOf("Start", "Junction 1", "C1", "End"), survey.state.doc.stations.map { it.name })
        assertEquals(survey.state.doc, savedDoc(id))
        assertEquals(t(0), survey.state.cursorNs)
        assertEquals(3, survey.legs.size)
        assertEquals(15.0, survey.totals.pathM, 1e-9)
        assertTrue(survey.magnetic)
        assertNull(survey.northWarning)
        assertNull(survey.error)
        assertEquals(OrbitCamera.MAX_PITCH_RAD, vm.camera.value.pitchRad)
        assertEquals(0.0, vm.camera.value.yawRad)
        assertSame(survey.geometry.framed, ui.sceneResult)
        assertNull(ui.sceneOverlay)
    }

    @Test
    fun leavingKeepsTheCameraAndTheSurveyInMemory() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()
        val doc = survey(vm).state.doc
        vm.zoom(2f)
        val camera = vm.camera.value

        vm.toggleSurvey()
        assertFalse(vm.ui.value.surveyMode)
        assertNull(vm.ui.value.survey)
        assertEquals(camera, vm.camera.value)
        assertSame(vm.ui.value.shownResult, vm.ui.value.sceneResult)

        // Re-entering uses the survey in memory: the file is not read again.
        files.surveyFile(id).delete()
        vm.toggleSurvey()
        assertSame(doc, survey(vm).state.doc)
        assertFalse(files.surveyFile(id).exists())
    }

    @Test
    fun existingFileIsLoadedNotReseeded() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val doc = SurveyDoc(stations = listOf(Station(id = 7, kind = StationKind.USER, name = "Pillar", tNs = t(12))))
        files.surveyFile(id).writeText(doc.toJson())
        val before = files.surveyFile(id).readText()
        val vm = viewer(id)
        vm.toggleSurvey()
        assertEquals(doc, survey(vm).state.doc)
        assertEquals(before, files.surveyFile(id).readText())
    }

    @Test
    fun malformedFileIsReadOnlyAndLeftAsItIs() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        files.surveyFile(id).writeText("{ not json")
        val vm = viewer(id)
        vm.toggleSurvey()

        val survey = survey(vm)
        assertTrue(survey.state.readOnly)
        assertEquals(4, survey.state.doc.stations.size)
        assertTrue(assertNotNull(survey.error).startsWith("survey.json could not be read ("))
        assertEquals("{ not json", files.surveyFile(id).readText())
        // Selecting still works read-only.
        vm.surveyTap(SurveyHit.OnPath(7.5))
        assertEquals(SurveySelection.Stretch(2, 4), survey(vm).state.selection)
    }

    @Test
    fun pathTapSelectsTheStretchStationTapsBuildAChainAndAMissDoesNothing() = runBlocking<Unit> {
        val vm = viewer(processedTrip(SurveyFixtures.lWalk()))
        vm.toggleSurvey()

        vm.surveyTap(SurveyHit.OnPath(7.5))
        val stretch = survey(vm)
        assertEquals(SurveySelection.Stretch(2, 4), stretch.state.selection)
        assertEquals(t(15), stretch.state.cursorNs)
        assertEquals(listOf("Junction 1", "C1"), assertIs<SurveyReadout.Stretch>(stretch.readout).names)
        assertEquals(
            LayerSelection(stretchNs = t(10) to t(20), stretchIds = listOf(2, 4)),
            SurveyController.layerSelection(stretch.state, stretch.geometry),
        )

        vm.surveyTap(SurveyHit.Miss)
        assertSame(stretch, vm.ui.value.survey)

        vm.surveyTap(SurveyHit.OnStation(1))
        vm.surveyTap(SurveyHit.OnStation(4))
        assertEquals(SurveySelection.Chain(listOf(1, 4)), survey(vm).state.selection)
        assertIs<SurveyReadout.Chain>(survey(vm).readout)
    }

    @Test
    fun cursorAndLegCallsReachTheController() = runBlocking<Unit> {
        val vm = viewer(processedTrip(SurveyFixtures.lWalk()))
        vm.toggleSurvey()
        vm.setSurveyCursor(7.5)
        assertEquals(t(15), survey(vm).state.cursorNs)
        vm.stepSurveyCursor(1)
        assertEquals(t(16), survey(vm).state.cursorNs)
        vm.selectLeg(1, 2)
        assertEquals(SurveySelection.Stretch(1, 2), survey(vm).state.selection)
        vm.clearSurveySelection()
        assertEquals(SurveySelection.None, survey(vm).state.selection)
    }

    @Test
    fun cursorMovesReuseWhatWalksTheWholePath() = runBlocking<Unit> {
        val vm = viewer(processedTrip(SurveyFixtures.lWalk()))
        vm.toggleSurvey()
        vm.surveyTap(SurveyHit.OnPath(7.5))
        val before = survey(vm)

        // The scrubber sends this on every drag frame: only the layer may be rebuilt.
        vm.setSurveyCursor(12.5)
        val after = survey(vm)
        assertEquals(t(25), after.state.cursorNs)
        assertNear(Vec3(2.5, 10.0, 0.0), assertNotNull(after.layer.cursor))
        assertSame(before.geometry, after.geometry)
        assertSame(before.north, after.north)
        assertSame(before.legs, after.legs)
        assertSame(before.totals, after.totals)
        assertSame(before.readout, after.readout)
    }

    @Test
    fun rawToggleRepublishesTheStationsOnTheRawPath() = runBlocking<Unit> {
        val walk = SurveyFixtures.lWalk()
        // The raw path lies 1 m east of the corrected one.
        val shifted = walk.copy(rawPoints = walk.points.map { it.copy(p = it.p + Vec3(1.0, 0.0, 0.0)) })
        val vm = viewer(processedTrip(shifted))
        vm.toggleSurvey()
        assertNear(Vec3.ZERO, survey(vm).layer.stations.first { it.id == 1 }.position)

        vm.toggleRaw()
        val survey = survey(vm)
        assertTrue(survey.geometry.raw)
        assertNear(Vec3(1.0, 0.0, 0.0), survey.layer.stations.first { it.id == 1 }.position)
        assertSame(survey.geometry.framed, vm.ui.value.sceneResult)
    }

    @Test
    fun nothingShownMeansNothingToMeasure() = runBlocking<Unit> {
        val id = trips.createTrip(
            TripEntity(
                name = "walk",
                mode = TripMode.POCKET,
                carryPosition = CarryPosition.HAND,
                startedAtEpochMs = 1L,
                status = TripStatus.RECORDING,
            ),
        )
        val vm = viewer(id)
        vm.toggleSurvey()
        assertFalse(vm.ui.value.surveyMode)
        assertEquals(SurveyMessage("Nothing to measure yet", undoable = false), vm.ui.value.surveyMessage)
    }
}
