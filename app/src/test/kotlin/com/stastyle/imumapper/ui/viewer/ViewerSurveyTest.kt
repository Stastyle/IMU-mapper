package com.stastyle.imumapper.ui.viewer

import com.stastyle.imumapper.SurveyFixtures
import com.stastyle.imumapper.data.FakeTripRepository
import com.stastyle.imumapper.data.SurveyCsvFile
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
import com.stastyle.imumapper.pipeline.survey.Detail
import com.stastyle.imumapper.pipeline.survey.NorthSource
import com.stastyle.imumapper.pipeline.survey.ReferenceLine
import com.stastyle.imumapper.pipeline.survey.Station
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.SurveyCsv
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
import com.stastyle.imumapper.process.TripProcessor
import com.stastyle.imumapper.render.Bounds
import com.stastyle.imumapper.render.LayerSelection
import com.stastyle.imumapper.render.OrbitCamera
import com.stastyle.imumapper.render.Projector
import com.stastyle.imumapper.render.SurveyHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin
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

    private fun planPoints(vm: ViewerViewModel): List<Vec3> = assertNotNull(vm.ui.value.sceneResult).points.map { it.p }

    /** Every point of the drawn plan is on screen above the panel's top edge at [panelTop], and centred there. */
    private fun assertPlanAbove(vm: ViewerViewModel, panelTop: Float) {
        val projector = Projector(vm.camera.value, 1080f, 1920f)
        val projected = planPoints(vm).map { assertNotNull(projector.project(it)) }
        val ys = projected.map { it.y }
        assertTrue(projected.all { it.x in 0f..1080f }, "plan leaves the sides")
        assertTrue(ys.min() >= 0f && ys.max() <= panelTop, "plan spans ${ys.min()}..${ys.max()}, panel at $panelTop")
        assertEquals(panelTop / 2f, (ys.min() + ys.max()) / 2f, 1f)
    }

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
    fun thePlanIsFramedAboveTheSurveyPanel() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()
        // The panel is measured once it is drawn, after the Top view was set: the untouched view is fitted again.
        vm.setBottomInset(700f)
        assertPlanAbove(vm, panelTop = 1220f)
        // A selection's readout grows the panel; nobody moved the fitted view, so it is fitted again.
        vm.setBottomInset(900f)
        assertPlanAbove(vm, panelTop = 1020f)
        // Double-tap fits above the panel too.
        vm.pan(300f, -600f)
        vm.fitToPath()
        assertPlanAbove(vm, panelTop = 1020f)
    }

    @Test
    fun aMovedViewFollowsThePanelByHalfItsChangeAndLeavingKeepsIt() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()
        vm.setBottomInset(700f)
        vm.zoom(2f)
        val corner = Vec3(0.0, 10.0, 0.0)
        val before = assertNotNull(Projector(vm.camera.value, 1080f, 1920f).project(corner))
        val distance = vm.camera.value.distance

        // The user zoomed, so the panel growing does not refit: the view moves up by half the growth.
        vm.setBottomInset(900f)
        val after = assertNotNull(Projector(vm.camera.value, 1080f, 1920f).project(corner))
        assertEquals(before.x, after.x, 0.01f)
        assertEquals(before.y - 100f, after.y, 0.01f)
        assertEquals(distance, vm.camera.value.distance)

        val camera = vm.camera.value
        vm.toggleSurvey()
        assertEquals(camera, vm.camera.value)
        // Outside Survey mode nothing covers the path, so a double-tap fits the whole canvas as before.
        vm.fitToPath()
        assertEquals(Bounds.of(planPoints(vm)).center, vm.camera.value.target)
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

        // Stations drawn on one spot: the one after C1 joins the chain.
        vm.surveyTap(SurveyHit.OnStations(listOf(1, 3)))
        assertEquals(SurveySelection.Chain(listOf(1, 4, 3)), survey(vm).state.selection)
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

    // --- station edits, Detail and undo ---

    @Test
    fun addedStationIsSavedWithAnUndoableMessageAndUndoFollowsToTheFile() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()

        vm.addStationAt(7.5)
        val added = survey(vm).state.doc.stations.single { it.name == "S1" }
        assertEquals(Station(id = 5, kind = StationKind.USER, name = "S1", tNs = t(15)), added)
        assertEquals(SurveyMessage("Added S1", undoable = true), vm.ui.value.surveyMessage)
        assertEquals(survey(vm).state.doc, savedDoc(id))

        vm.surveyUndo()
        assertEquals(4, survey(vm).state.doc.stations.size)
        assertNull(vm.ui.value.surveyMessage)
        assertTrue(savedDoc(id).stations.none { it.name == "S1" })
    }

    @Test
    fun leavingSurveyModeDropsTheUndoMessage() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()
        vm.addStationAt(7.5)
        assertEquals(SurveyMessage("Added S1", undoable = true), vm.ui.value.surveyMessage)

        // Undo works only in Survey mode, so the snackbar must not stay up offering it.
        vm.toggleSurvey()
        assertNull(vm.ui.value.surveyMessage)
        vm.surveyUndo()
        assertTrue(savedDoc(id).stations.any { it.name == "S1" })
    }

    @Test
    fun selectedCornerMovesToTheCursorAndAStationCanBeAddedNextToIt() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()
        vm.surveyTap(SurveyHit.OnStation(4))
        vm.setSurveyCursor(12.5)

        vm.moveSelectedStationToCursor()
        val moved = Station(id = 4, kind = StationKind.USER, name = "C1", tNs = t(25))
        assertEquals(moved, survey(vm).state.doc.stations.single { it.id == 4 })
        assertEquals(SurveyMessage("Moved C1 to the cursor", undoable = true), vm.ui.value.surveyMessage)
        assertEquals(moved, savedDoc(id).stations.single { it.id == 4 })

        vm.stepSurveyCursor(1)
        vm.addStationAtCursor()
        assertEquals(t(26), savedDoc(id).stations.single { it.name == "S1" }.tNs)
    }

    @Test
    fun aStationOnAnotherStationsPointIsRefusedWithAMessageThatNamesIt() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()
        val seeded = savedDoc(id)
        // Nothing changed, so there is nothing to undo.
        fun assertRefused(text: String) = assertEquals(SurveyMessage(text, undoable = false), vm.ui.value.surveyMessage)

        // The cursor starts on Start, so "+ Station" before touching the scrubber would hide under it.
        vm.addStationAtCursor()
        assertRefused("No station added: Start is already here")
        // The scrubber's far end is End.
        vm.setSurveyCursor(15.0)
        vm.addStationAtCursor()
        assertRefused("No station added: End is already here")
        vm.addStationAt(5.0)
        assertRefused("No station added: Junction 1 is already here")

        // "Move here" onto Junction 1.
        vm.surveyTap(SurveyHit.OnStation(4))
        vm.setSurveyCursor(5.0)
        vm.moveSelectedStationToCursor()
        assertRefused("C1 not moved: Junction 1 is already here")

        assertEquals(seeded, survey(vm).state.doc)
        assertTrue(survey(vm).state.undo.isEmpty())
        assertEquals(seeded, savedDoc(id))
    }

    @Test
    fun renameAndDeleteReachTheFile() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()

        vm.renameStation(2, "  Big room ")
        assertEquals("Big room", savedDoc(id).stations.single { it.id == 2 }.name)
        assertNull(vm.ui.value.surveyMessage)

        vm.deleteStation(4)
        assertTrue(savedDoc(id).stations.none { it.id == 4 })
        assertEquals(SurveyMessage("Deleted C1", undoable = true), vm.ui.value.surveyMessage)
    }

    @Test
    fun detailBringsBackADeletedCornerAndCanBeUndone() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()
        vm.deleteStation(4)
        assertEquals(mapOf(Detail.COARSE to 1, Detail.NORMAL to 1, Detail.FINE to 1), vm.cornerCounts())

        vm.setDetail(Detail.FINE)
        val fine = survey(vm).state.doc
        assertEquals(Detail.FINE, fine.detail)
        assertEquals(listOf(t(20)), fine.stations.filter { it.kind == StationKind.CORNER }.map { it.tNs })
        assertEquals(SurveyMessage("Fine (0.25 m): 1 corner", undoable = true), vm.ui.value.surveyMessage)
        assertEquals(fine, savedDoc(id))

        vm.surveyUndo()
        assertEquals(Detail.NORMAL, savedDoc(id).detail)
        assertTrue(savedDoc(id).stations.none { it.kind == StationKind.CORNER })
    }

    @Test
    fun readOnlyRefusesEditsWithAMessageAndNeverWrites() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        files.surveyFile(id).writeText("{ not json")
        val vm = viewer(id)
        vm.toggleSurvey()

        vm.addStationAt(7.5)
        vm.renameStation(1, "Entrance")
        vm.surveyUndo()
        val doc = survey(vm).state.doc
        assertEquals(4, doc.stations.size)
        assertEquals("Start", doc.stations.single { it.id == 1 }.name)
        vm.renameStation(1, "Entrance")
        assertEquals(SurveyMessage(ViewerViewModel.READ_ONLY_MESSAGE, undoable = false), vm.ui.value.surveyMessage)
        assertEquals("{ not json", files.surveyFile(id).readText())
    }

    // --- north, the manual-rotation prompt and the CSV ---

    @Test
    fun compassReadingTurnsTheMapAndMakesAnArbitraryNorthMagnetic() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk(magnetic = false))
        val vm = viewer(id)
        vm.toggleSurvey()
        val before = survey(vm)
        assertFalse(before.magnetic)
        assertNotNull(before.northWarning)

        vm.surveyTap(SurveyHit.OnStation(1))
        vm.surveyTap(SurveyHit.OnStation(4))
        vm.addReference(10.0, backBearing = false, line = ReferenceLine.CHORD)

        val after = survey(vm)
        assertEquals(NorthSource.REFERENCES, after.north.source)
        assertEquals(10.0, after.north.rotationDeg, 1e-9)
        assertTrue(after.magnetic)
        assertNull(after.northWarning)
        assertEquals(SurveyMessage("Compass reading added", undoable = true), vm.ui.value.surveyMessage)
        // Turned about the start: the corner (0, 10) is now 10 degrees east of north.
        val theta = Math.toRadians(10.0)
        assertNear(Vec3(10.0 * sin(theta), 10.0 * cos(theta), 0.0), after.geometry.framed.points[20].p)
        assertSame(after.geometry.framed, vm.ui.value.sceneResult)
        val chain = assertIs<SurveyReadout.Chain>(after.readout)
        assertEquals(10.0, assertNotNull(chain.measure.straight.azimuthDeg), 1e-9)
        assertEquals(1, savedDoc(id).references.size)

        // References set north, so a manual rotation is refused; reset clears them and can be undone.
        vm.setRotation(3.0)
        assertEquals(10.0, survey(vm).north.rotationDeg, 1e-9)
        vm.resetNorth()
        assertEquals(0.0, survey(vm).north.rotationDeg)
        assertEquals(SurveyMessage("North reset", undoable = true), vm.ui.value.surveyMessage)
        vm.surveyUndo()
        assertEquals(10.0, survey(vm).north.rotationDeg, 1e-9)
        vm.deleteReference(1)
        assertTrue(savedDoc(id).references.isEmpty())
    }

    @Test
    fun manualRotationFromAnotherRunIsOfferedAndApplyUsesIt() = runBlocking<Unit> {
        // Two runs with equal results: the state flow then keeps run 1's instance on screen, so the
        // survey must follow the selected run id, not only the result instance.
        val id = processedTrip(SurveyFixtures.lWalk(), SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.selectRun(1)
        vm.toggleSurvey()
        vm.setRotation(5.0)
        vm.nudgeRotation(-0.5)
        assertEquals(4.5, survey(vm).north.rotationDeg, 1e-9)
        assertEquals(1, savedDoc(id).manualRotationRunId)

        vm.selectRun(2)
        val asked = survey(vm)
        assertTrue(asked.askManualRotation)
        assertEquals(0.0, asked.north.rotationDeg)

        vm.answerManualRotation(apply = true)
        val applied = survey(vm)
        assertFalse(applied.askManualRotation)
        assertEquals(4.5, applied.north.rotationDeg, 1e-9)
        assertEquals(2, savedDoc(id).manualRotationRunId)
    }

    @Test
    fun notNowHidesTheManualRotationPromptForThatRun() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk(), SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.selectRun(1)
        vm.toggleSurvey()
        vm.setRotation(5.0)
        vm.selectRun(2)
        assertTrue(survey(vm).askManualRotation)

        vm.answerManualRotation(apply = false)
        val dismissed = survey(vm)
        assertFalse(dismissed.askManualRotation)
        assertEquals(0.0, dismissed.north.rotationDeg)
        assertEquals(1, savedDoc(id).manualRotationRunId)
    }

    @Test
    fun csvIsWrittenForTheShareSheetOnce() = runBlocking<Unit> {
        val id = processedTrip(SurveyFixtures.lWalk())
        val vm = viewer(id)
        vm.toggleSurvey()

        vm.exportSurveyCsv()
        val share = assertNotNull(vm.ui.value.pendingCsv)
        assertEquals(File(files.exportDir(), SurveyCsvFile.fileName("Cave loop", id, 1, raw = false)), share.file)
        val bytes = share.file.readBytes()
        assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF })
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.startsWith(SurveyCsv.BOM + SurveyCsv.HEADER + SurveyCsv.EOL))
        // The header, three legs, and nothing after the last line end.
        assertEquals(5, text.split(SurveyCsv.EOL).size)
        assertEquals("IMU Mapper survey: Cave loop", share.subject)
        assertEquals(SurveyFormat.shareText("Cave loop", 1, raw = false, north = survey(vm).north), share.text)

        vm.consumeCsvShare()
        assertNull(vm.ui.value.pendingCsv)
    }
}
