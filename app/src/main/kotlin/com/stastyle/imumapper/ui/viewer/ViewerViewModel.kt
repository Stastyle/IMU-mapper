package com.stastyle.imumapper.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.imumapper.data.SurveyCsvFile
import com.stastyle.imumapper.data.SurveyStore
import com.stastyle.imumapper.data.TripFiles
import com.stastyle.imumapper.data.TripRepository
import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.post.RawPath
import com.stastyle.imumapper.pipeline.survey.Detail
import com.stastyle.imumapper.pipeline.survey.LegTotals
import com.stastyle.imumapper.pipeline.survey.NorthSolution
import com.stastyle.imumapper.pipeline.survey.NorthSolver
import com.stastyle.imumapper.pipeline.survey.ReferenceLine
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.SurveyCsv
import com.stastyle.imumapper.pipeline.survey.SurveyDoc
import com.stastyle.imumapper.pipeline.survey.SurveyStations
import com.stastyle.imumapper.pipeline.survey.Traverse
import com.stastyle.imumapper.pipeline.survey.TraverseLeg
import com.stastyle.imumapper.process.TripProcessor
import com.stastyle.imumapper.render.Bounds
import com.stastyle.imumapper.render.CameraPreset
import com.stastyle.imumapper.render.ColorMode
import com.stastyle.imumapper.render.OrbitCamera
import com.stastyle.imumapper.render.SceneMarker
import com.stastyle.imumapper.render.SceneOptions
import com.stastyle.imumapper.render.SurveyHit
import com.stastyle.imumapper.render.SurveyLayer
import com.stastyle.imumapper.ui.calibration.CalibrationMath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Snackbar text from a survey edit; Undo is offered when [undoable]. */
data class SurveyMessage(val text: String, val undoable: Boolean)

/** A written CSV waiting for the share sheet; the screen builds the Intent (SurveyShare), keeping this JVM-testable. */
data class SurveyCsvShare(val file: File, val subject: String, val text: String)

/** Everything the survey panel, sheets and layer need, rebuilt after each change. */
data class SurveyUi(
    val state: SurveyState,
    val geometry: SurveyGeometry,
    val north: NorthSolution,
    /** Suffix M when true, R when false (NorthSolver.isMagnetic). */
    val magnetic: Boolean,
    val readout: SurveyReadout?,
    val legs: List<TraverseLeg>,
    val totals: LegTotals,
    val layer: SurveyLayer,
    /** CalibrationMath.northProblem's reason, set on an R run without references, for the banner. */
    val northWarning: String?,
    /** The manual rotation was set on another run: ask before using it (ManualRotationDialog). */
    val askManualRotation: Boolean,
    /** SurveyOpen.error while read-only. */
    val error: String?,
)

data class ViewerUiState(
    val trip: TripEntity? = null,
    val runs: List<PathResultEntity> = emptyList(),
    val selectedRunId: Int? = null,
    val result: PathResult? = null,
    val overlayRunId: Int? = null,
    val overlayResult: PathResult? = null,
    val options: SceneOptions = SceneOptions(),
    /** Draw the selected run's path before loop closure and smoothing instead of the corrected one. */
    val showRaw: Boolean = false,
    /** The raw view of [result], null while [showRaw] is off or the run stores no separate raw path. */
    val rawResult: PathResult? = null,
    /** True until the trip row and run list have both arrived. */
    val loading: Boolean = true,
    /** A processing run started by this screen is in progress. */
    val processing: Boolean = false,
    /** Blocking problem: processing failed or the result file could not be read. */
    val error: String? = null,
    val selectedMarker: SceneMarker? = null,
    val photo: Bitmap? = null,
    val photoTitle: String = "",
    val photoLoading: Boolean = false,
    val photoError: String? = null,
    /** Plan view, orbit locked, the survey panel instead of the stats panel. */
    val surveyMode: Boolean = false,
    /** Null while Survey mode is off or its file is loading. */
    val survey: SurveyUi? = null,
    val surveyMessage: SurveyMessage? = null,
    /** One-shot: the screen hands it to the share sheet, then calls consumeCsvShare. */
    val pendingCsv: SurveyCsvShare? = null,
) {
    /** What the canvas draws as the main path and what the stats panel describes. */
    val shownResult: PathResult? get() = if (showRaw) rawResult ?: result else result

    /**
     * The dimmed path under the main one: the chosen overlay run, or, in raw view with no overlay
     * run, the corrected path of the same run so the two can be compared in place.
     */
    val shownOverlay: PathResult? get() = overlayResult ?: if (showRaw && rawResult != null) result else null

    /** What the canvas draws: in Survey mode the north-corrected path. */
    val sceneResult: PathResult? get() = if (surveyMode) survey?.geometry?.framed ?: shownResult else shownResult

    /** Survey mode hides the overlay run. */
    val sceneOverlay: PathResult? get() = if (surveyMode) null else shownOverlay

    /** True when the selected run predates stored raw paths, so the raw toggle can say why it changes nothing. */
    val rawUnavailable: Boolean get() = result != null && result.pipelineVersion < RAW_POINTS_VERSION

    companion object {
        /** First pipeline version whose results store [PathResult.rawPoints]. */
        const val RAW_POINTS_VERSION = 2
    }
}

/**
 * Loads a trip and its processing runs for [ViewerScreen] and owns the camera so it survives
 * rotation. Result JSON is read on the IO dispatcher and cached per run, since runs are never
 * overwritten (see docs/CONVENTIONS.md).
 */
class ViewerViewModel(
    private val tripId: Long,
    private val trips: TripRepository,
    private val files: TripFiles,
    private val tripProcessor: TripProcessor,
    private val surveys: SurveyStore = SurveyStore(files),
    /** Tests pass Dispatchers.Unconfined so file work finishes inside the call. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _ui = MutableStateFlow(ViewerUiState())
    val ui: StateFlow<ViewerUiState> = _ui.asStateFlow()

    private val _camera = MutableStateFlow(OrbitCamera())
    val camera: StateFlow<OrbitCamera> = _camera.asStateFlow()

    private val cache = HashMap<Int, PathResult>()
    /** Raw views by run, built on first use: re-placing markers walks the whole path once. */
    private val rawCache = HashMap<Int, PathResult>()
    private var runsLoaded = false
    private var tripLoaded = false
    private var processingStarted = false
    private var viewportWidth = 0f
    private var viewportHeight = 0f
    /** The first result to arrive is framed once the viewport size is known. */
    private var needsFit = true
    private var currentBounds: Bounds = Bounds.EMPTY

    // Survey mode. Declared before init, which may already publish a loaded run.
    /** Kept after leaving Survey mode, so undo survives a re-entry; null until the mode first opens. */
    private var surveyState: SurveyState? = null
    /** The geometry last published (already rotated); reused while the shown result and the angle stay. */
    private var surveyGeometry: SurveyGeometry? = null
    /** What the snapshot computed over the whole path; reused until the doc, run, result or raw view changes. */
    private var surveyBase: SurveyBase? = null
    /** The last readout; a stretch's refits the path, so it is reused until the selection changes. */
    private var readoutCache: ReadoutCache? = null
    /** The drawn result the bounds were last taken from; they copy every point, so only a new one updates them. */
    private var boundsSource: PathResult? = null
    /** Why survey.json could not be read; shown while the survey is read-only. */
    private var surveyError: String? = null
    /** "Not now" on the manual-rotation prompt, for this run and this screen only. */
    private var manualPromptDismissedRunId: Int? = null
    /** The newest doc not yet written; saves run one at a time and skip docs a later edit overtook. */
    private var docToSave: SurveyDoc? = null
    private val saveLock = Mutex()

    init {
        viewModelScope.launch {
            trips.observeTrip(tripId).collect { trip ->
                tripLoaded = true
                _ui.update { it.copy(trip = trip, loading = !(runsLoaded && tripLoaded)) }
                maybeProcess()
            }
        }
        viewModelScope.launch {
            trips.observeResults(tripId).collect { runs ->
                runsLoaded = true
                onRuns(runs)
                maybeProcess()
            }
        }
    }

    private fun onRuns(runs: List<PathResultEntity>) {
        val current = _ui.value.selectedRunId
        val keepCurrent = current != null && runs.any { it.runId == current }
        val selected = if (keepCurrent) current else runs.maxOfOrNull { it.runId }
        val overlay = _ui.value.overlayRunId?.takeIf { id -> id != selected && runs.any { it.runId == id } }
        val loading = !(runsLoaded && tripLoaded)
        _ui.update { it.copy(runs = runs, selectedRunId = selected, overlayRunId = overlay, loading = loading) }
        if (selected != null && selected != current) loadRun(selected, overlay = false)
        if (overlay == null && _ui.value.overlayResult != null) _ui.update { it.copy(overlayResult = null) }
    }

    /**
     * Processes the trip once when it has been recorded but never processed. A trip whose last run
     * failed is left alone: the recording screen has already tried once, and repeating a run that
     * ran out of memory on every open of the viewer is what used to crash the app. The stored error
     * is shown with a Retry button instead ([retryProcessing]).
     */
    private fun maybeProcess() {
        if (processingStarted || !runsLoaded || !tripLoaded) return
        val trip = _ui.value.trip ?: return
        if (_ui.value.runs.isNotEmpty()) return
        if (trip.status == TripStatus.RECORDING || trip.status == TripStatus.FAILED) return
        startProcessing()
    }

    private fun startProcessing() {
        processingStarted = true
        _ui.update { it.copy(processing = true, error = null) }
        viewModelScope.launch {
            val outcome = runCatching { tripProcessor.process(tripId) }
            _ui.update { state ->
                state.copy(
                    processing = false,
                    error = outcome.exceptionOrNull()?.let { e -> "Processing failed: ${describe(e)}" },
                )
            }
            // The new run arrives through observeResults; nothing else to do on success.
        }
    }

    /** Re-runs processing after a failure (the Retry button). */
    fun retryProcessing() {
        if (_ui.value.processing) return
        startProcessing()
    }

    fun selectRun(runId: Int) {
        if (runId == _ui.value.selectedRunId) return
        val overlay = _ui.value.overlayRunId?.takeIf { it != runId }
        _ui.update { it.copy(selectedRunId = runId, overlayRunId = overlay, selectedMarker = null, rawResult = null) }
        if (overlay == null) _ui.update { it.copy(overlayResult = null) }
        loadRun(runId, overlay = false)
    }

    /** Draws [runId] dimmed under the selected run; null clears the overlay. */
    fun selectOverlay(runId: Int?) {
        val id = runId?.takeIf { it != _ui.value.selectedRunId }
        _ui.update { it.copy(overlayRunId = id) }
        if (id == null) _ui.update { it.copy(overlayResult = null) } else loadRun(id, overlay = true)
    }

    private fun loadRun(runId: Int, overlay: Boolean) {
        val cached = cache[runId]
        if (cached != null) {
            applyResult(runId, cached, overlay)
            return
        }
        viewModelScope.launch {
            val loaded = withContext(ioDispatcher) {
                runCatching { PathResult.fromJson(files.resultFile(tripId, runId).readText()) }
            }
            loaded.onSuccess { result ->
                cache[runId] = result
                applyResult(runId, result, overlay)
            }.onFailure { e ->
                _ui.update { it.copy(error = "Could not read run $runId: ${describe(e)}") }
            }
        }
    }

    private fun applyResult(runId: Int, result: PathResult, overlay: Boolean) {
        if (overlay) {
            // Ignore a late load for an overlay the user has since changed.
            if (_ui.value.overlayRunId != runId) return
            _ui.update { it.copy(overlayResult = result) }
        } else {
            if (_ui.value.selectedRunId != runId) return
            val raw = if (_ui.value.showRaw) rawViewOf(runId, result) else null
            _ui.update { it.copy(result = result, rawResult = raw, error = null) }
            // In Survey mode the stations are placed again on the new run; either way the bounds follow.
            publishSurvey()
            if (needsFit) fitIfPossible()
        }
    }

    /** The raw view of [result], or null when it stores no raw path distinct from the final one. */
    private fun rawViewOf(runId: Int, result: PathResult): PathResult? {
        if (!RawPath.isAvailable(result)) return null
        return rawCache.getOrPut(runId) { RawPath.view(result) }
    }

    /** Fit and presets frame the path on screen, so they follow the raw toggle and Survey mode's north. */
    private fun updateBounds() {
        val shown = _ui.value.sceneResult ?: return
        currentBounds = Bounds.of(shown.points.map { p -> p.p })
    }

    // --- view options ---

    fun setColorMode(mode: ColorMode) = _ui.update { it.copy(options = it.options.copy(colorMode = mode)) }
    fun toggleGrid() = _ui.update { it.copy(options = it.options.copy(showGrid = !it.options.showGrid)) }
    fun togglePointCloud() = _ui.update {
        it.copy(options = it.options.copy(showPointCloud = !it.options.showPointCloud))
    }
    fun toggleMarkers() = _ui.update {
        val show = !it.options.showMarkers
        it.copy(options = it.options.copy(showMarkers = show), selectedMarker = if (show) it.selectedMarker else null)
    }

    /**
     * Switches between the corrected path and the one before loop closure and smoothing. The
     * selected marker is dropped because its position belongs to the other path; survey stations
     * are stored by time, so they follow.
     */
    fun toggleRaw() {
        val state = _ui.value
        val show = !state.showRaw
        val runId = state.selectedRunId
        val result = state.result
        val raw = if (show && runId != null && result != null) rawViewOf(runId, result) else null
        _ui.update { it.copy(showRaw = show, rawResult = raw, selectedMarker = null) }
        publishSurvey()
    }

    fun selectMarker(marker: SceneMarker?) = _ui.update { it.copy(selectedMarker = marker) }

    // --- camera ---

    fun setViewport(widthPx: Float, heightPx: Float) {
        if (widthPx <= 0f || heightPx <= 0f) return
        val changed = widthPx != viewportWidth || heightPx != viewportHeight
        viewportWidth = widthPx
        viewportHeight = heightPx
        if (changed && needsFit) fitIfPossible()
    }

    private fun fitIfPossible() {
        if (viewportWidth <= 0f || _ui.value.result == null) return
        _camera.update { it.fitted(currentBounds, viewportWidth, viewportHeight) }
        needsFit = false
    }

    fun orbit(dxPx: Float, dyPx: Float) {
        _camera.update { it.orbited(dxPx * ORBIT_RAD_PER_PX, dyPx * ORBIT_RAD_PER_PX) }
    }

    fun zoom(factor: Float) {
        _camera.update { it.zoomed(factor.toDouble()) }
    }

    fun pan(dxPx: Float, dyPx: Float) {
        if (viewportHeight <= 0f) return
        _camera.update { it.panned(dxPx, dyPx, viewportHeight) }
    }

    fun fitToPath() {
        if (viewportWidth <= 0f) return
        _camera.update { it.fitted(currentBounds, viewportWidth, viewportHeight) }
    }

    fun applyPreset(preset: CameraPreset) {
        if (viewportWidth <= 0f) return
        _camera.update { it.withPreset(preset, currentBounds, viewportWidth, viewportHeight) }
    }

    // --- survey mode ---

    /**
     * Enters or leaves Survey mode. Leaving keeps the camera where it is and the survey in memory, so
     * undo survives a re-entry. The first entry reads survey.json (seeding and saving it when the trip
     * has none yet), then shows the north-corrected plan from the top.
     */
    fun toggleSurvey() {
        if (_ui.value.surveyMode) {
            // The snackbar's Undo works only in Survey mode, so a pending message leaves with it.
            _ui.update { it.copy(surveyMode = false, surveyMessage = null) }
            publishSurvey()
            return
        }
        val shown = _ui.value.shownResult
        if (shown == null || shown.points.size < 2) {
            _ui.update { it.copy(surveyMessage = SurveyMessage("Nothing to measure yet", undoable = false)) }
            return
        }
        _ui.update { it.copy(surveyMode = true, selectedMarker = null) }
        if (surveyState != null) {
            showSurvey()
            return
        }
        viewModelScope.launch {
            val load = withContext(ioDispatcher) { surveys.load(tripId) }
            // Leaving while the file was read, or a second entry that got there first, makes this load stale.
            if (!_ui.value.surveyMode || surveyState != null) return@launch
            val ui = _ui.value
            val current = ui.shownResult?.takeIf { it.points.isNotEmpty() } ?: return@launch
            val runId = ui.selectedRunId ?: return@launch
            // A new doc has no correction yet, so it is seeded on the uncorrected path.
            val geometry = SurveyGeometry.of(current, runId, raw = ui.showRaw && ui.rawResult != null)
            val opened = SurveyController.open(load, geometry)
            surveyGeometry = geometry
            surveyState = opened.state
            surveyError = opened.error
            if (opened.seeded) persist(opened.state.doc)
            showSurvey()
        }
    }

    /** A tap in Survey mode: a station extends the chain, the path selects a stretch, a miss does nothing. */
    fun surveyTap(hit: SurveyHit) = changeSurvey { state, geometry ->
        when (hit) {
            is SurveyHit.OnStation -> SurveyController.tapStation(state, hit.stationId)
            is SurveyHit.OnStations -> SurveyController.tapStations(state, hit.stationIds)
            is SurveyHit.OnPath -> SurveyController.tapPath(state, geometry, hit.distanceM)
            SurveyHit.Miss -> state
        }
    }

    fun clearSurveySelection() = changeSurvey { state, _ -> SurveyController.clearSelection(state) }

    /** The scrubber: [distanceM] along the shown path. */
    fun setSurveyCursor(distanceM: Double) =
        changeSurvey { state, geometry -> SurveyController.setCursor(state, geometry, distanceM) }

    /** The scrubber's arrows: [delta] path points back or forward. */
    fun stepSurveyCursor(delta: Int) =
        changeSurvey { state, geometry -> SurveyController.stepCursor(state, geometry, delta) }

    /** A row of the Legs table. */
    fun selectLeg(fromId: Int, toId: Int) = changeSurvey { state, _ -> SurveyController.selectLeg(state, fromId, toId) }

    /** A long press on the path adds a station there. */
    fun addStationAt(distanceM: Double) {
        val geometry = surveyGeometry ?: return
        addStationAtTime(geometry.timeline.timeAtDistance(distanceM))
    }

    /** "+ Station" adds one at the scrubber's cursor, which can pick either pass of an out-and-back. */
    fun addStationAtCursor() {
        val state = surveyState ?: return
        addStationAtTime(state.cursorNs)
    }

    /** "Move here": the one selected corner or user station goes to the cursor. */
    fun moveSelectedStationToCursor() {
        val state = surveyState ?: return
        val id = SurveyController.movableStationId(state) ?: return
        val name = state.doc.stations.firstOrNull { it.id == id }?.name ?: return
        editSurvey({ "Moved $name to the cursor" }) { s, _ -> SurveyController.moveStation(s, id, s.cursorNs) }
    }

    fun renameStation(stationId: Int, name: String) =
        editSurvey { state, _ -> SurveyController.renameStation(state, stationId, name) }

    fun deleteStation(stationId: Int) {
        val name = surveyState?.doc?.stations?.firstOrNull { it.id == stationId }?.name ?: return
        editSurvey({ "Deleted $name" }) { state, _ -> SurveyController.deleteStation(state, stationId) }
    }

    /** Regenerates the automatic corners; the message says how many the new level gave. */
    fun setDetail(detail: Detail) = editSurvey({ next ->
        val corners = next.doc.stations.count { it.kind == StationKind.CORNER }
        "${SurveyFormat.detailLabel(detail)}: ${SurveyFormat.corners(corners)}"
    }) { state, geometry -> SurveyController.setDetail(state, geometry, detail) }

    /** For the Detail dialog; empty outside Survey mode. */
    fun cornerCounts(): Map<Detail, Int> {
        val state = surveyState ?: return emptyMap()
        val geometry = surveyGeometry ?: return emptyMap()
        return SurveyController.cornerCounts(state, geometry)
    }

    /** The top bar's and the snackbar's Undo. */
    fun surveyUndo() {
        if (!_ui.value.surveyMode) return
        val state = surveyState ?: return
        val next = SurveyController.undo(state)
        _ui.update { it.copy(surveyMessage = null) }
        if (next !== state) applySurvey(next)
    }

    fun dismissSurveyMessage() = _ui.update { it.copy(surveyMessage = null) }

    /** What the Set azimuth dialog shows for a bearing on the current pair or stretch. */
    fun azimuthPreview(bearingDeg: Double, backBearing: Boolean, line: ReferenceLine): AzimuthPreview? {
        val state = surveyState ?: return null
        val geometry = surveyGeometry ?: return null
        return SurveyController.azimuthPreview(state, geometry, bearingDeg, backBearing, line)
    }

    fun addReference(bearingDeg: Double, backBearing: Boolean, line: ReferenceLine) =
        editSurvey({ "Compass reading added" }) { state, geometry ->
            SurveyController.addReference(state, geometry, bearingDeg, backBearing, line)
        }

    fun deleteReference(referenceId: Int) =
        editSurvey({ "Compass reading deleted" }) { state, _ -> SurveyController.deleteReference(state, referenceId) }

    /** The North sheet's steppers start from the rotation in use, which is what the user sees. */
    fun nudgeRotation(deltaDeg: Double) {
        val current = _ui.value.survey?.north?.rotationDeg ?: return
        setRotation(current + deltaDeg)
    }

    /** A manual rotation, tagged with the shown run. */
    fun setRotation(rotationDeg: Double) =
        editSurvey { state, geometry -> SurveyController.setManualRotation(state, rotationDeg, geometry.runId) }

    fun resetNorth() = editSurvey({ "North reset" }) { state, _ -> SurveyController.resetNorth(state) }

    /** The manual-rotation prompt: Apply tags the rotation with this run, Not now hides it for this run. */
    fun answerManualRotation(apply: Boolean) {
        if (apply) {
            editSurvey { state, geometry -> SurveyController.confirmManualRotation(state, geometry.runId) }
        } else {
            manualPromptDismissedRunId = surveyGeometry?.runId
            publishSurvey()
        }
    }

    /**
     * Writes the traverse as CSV into the export cache and hands it to the screen for the share sheet.
     * The numbers are the ones on screen: the shown run, raw or not, north-corrected.
     */
    fun exportSurveyCsv() {
        val survey = _ui.value.survey ?: return
        val geometry = survey.geometry
        val tripName = _ui.value.trip?.name ?: "Trip $tripId"
        val origin = geometry.framed.points.first().p
        val text = SurveyCsv.text(survey.legs, geometry.timeline.startNs, origin, survey.magnetic)
        val fileName = SurveyCsvFile.fileName(tripName, tripId, geometry.runId, geometry.raw)
        val shareText = SurveyFormat.shareText(tripName, geometry.runId, geometry.raw, survey.north)
        viewModelScope.launch {
            runCatching { withContext(ioDispatcher) { SurveyCsvFile.write(files.exportDir(), fileName, text) } }
                .onSuccess { file ->
                    _ui.update { it.copy(pendingCsv = SurveyCsvShare(file, "IMU Mapper survey: $tripName", shareText)) }
                }
                .onFailure { e ->
                    val message = SurveyMessage("Could not export the CSV: ${describe(e)}", undoable = false)
                    _ui.update { it.copy(surveyMessage = message) }
                }
        }
    }

    fun consumeCsvShare() = _ui.update { it.copy(pendingCsv = null) }

    private fun addStationAtTime(tNs: Long) {
        val name = SurveyStations.nextUserName(surveyState?.doc?.stations ?: return)
        editSurvey({ "Added $name" }) { state, _ -> SurveyController.addStation(state, tNs) }
    }

    private fun showSurvey() {
        publishSurvey()
        applyPreset(CameraPreset.TOP)
    }

    /** Runs a selection or cursor change; these need no file and work read-only too. */
    private fun changeSurvey(change: (SurveyState, SurveyGeometry) -> SurveyState) {
        if (!_ui.value.surveyMode) return
        val state = surveyState ?: return
        val geometry = surveyGeometry ?: return
        val next = change(state, geometry)
        if (next !== state) applySurvey(next)
    }

    /**
     * Runs one doc edit. Read-only refuses it with a message; an edit the controller refused leaves
     * everything as it was; a real one is saved, and [message] (given the new state) offers Undo.
     */
    private fun editSurvey(
        message: (SurveyState) -> String? = { null },
        edit: (SurveyState, SurveyGeometry) -> SurveyState,
    ) {
        if (!_ui.value.surveyMode) return
        val state = surveyState ?: return
        val geometry = surveyGeometry ?: return
        if (state.readOnly) {
            _ui.update { it.copy(surveyMessage = SurveyMessage(READ_ONLY_MESSAGE, undoable = false)) }
            return
        }
        val next = edit(state, geometry)
        if (next === state) return
        applySurvey(next, message(next)?.let { SurveyMessage(it, undoable = true) })
    }

    /** Stores [next], saves its doc when an edit changed it, and republishes. */
    private fun applySurvey(next: SurveyState, message: SurveyMessage? = null) {
        val previous = surveyState
        surveyState = next
        val docChanged = previous != null && next.doc !== previous.doc
        if (docChanged && !next.readOnly) persist(next.doc)
        publishSurvey()
        // A doc edit replaces the last message, so the snackbar's Undo always undoes the edit it names.
        if (docChanged) _ui.update { it.copy(surveyMessage = message) }
    }

    /**
     * Saves the whole doc after every edit. Saves run one at a time, and one that starts after a newer
     * edit writes the newest doc, so the file never goes back to an older state.
     */
    private fun persist(doc: SurveyDoc) {
        docToSave = doc
        viewModelScope.launch {
            saveLock.withLock {
                val latest = docToSave ?: return@withLock
                docToSave = null
                runCatching { withContext(ioDispatcher) { surveys.save(tripId, latest) } }.onFailure { e ->
                    val message = SurveyMessage("Could not save the survey: ${describe(e)}", undoable = false)
                    _ui.update { it.copy(surveyMessage = message) }
                }
            }
        }
    }

    /**
     * Rebuilds the survey snapshot for what is shown now (null outside Survey mode). The bounds copy every
     * point, so they are recomputed only when a different result is drawn, not on each cursor move.
     */
    private fun publishSurvey() {
        _ui.update { it.copy(survey = surveySnapshot()) }
        val drawn = _ui.value.sceneResult
        if (drawn !== boundsSource) {
            boundsSource = drawn
            updateBounds()
        }
    }

    /**
     * The survey as the panel and the layer need it, on the shown run turned onto the solved north. What
     * walks the whole path comes from [surveyBase] and the readout from [readoutCache], so a cursor move
     * rebuilds only the layer.
     */
    private fun surveySnapshot(): SurveyUi? {
        val ui = _ui.value
        val state = surveyState
        val shown = ui.shownResult
        val runId = ui.selectedRunId
        if (!ui.surveyMode || state == null || shown == null || runId == null || shown.points.isEmpty()) return null
        val raw = ui.showRaw && ui.rawResult != null
        val base = surveyBase?.takeIf { it.isFor(state.doc, shown, runId, raw) }
            ?: buildSurveyBase(state.doc, shown, runId, raw).also { surveyBase = it }
        val geometry = base.geometry
        val readout = readoutCache?.takeIf { it.base === base && it.selection == state.selection }
            ?: ReadoutCache(base, state.selection, SurveyController.readout(state, geometry)).also { readoutCache = it }
        val selection = SurveyController.layerSelection(state, geometry)
        return SurveyUi(
            state = state,
            geometry = geometry,
            north = base.north,
            magnetic = base.magnetic,
            readout = readout.value,
            legs = base.legs,
            totals = base.totals,
            layer = SurveyLayer.build(geometry.timeline, state.doc.stations, selection, state.cursorNs),
            northWarning = base.northWarning,
            askManualRotation = NorthSolver.manualNeedsConfirmation(state.doc, runId) &&
                manualPromptDismissedRunId != runId,
            error = if (state.readOnly) surveyError else null,
        )
    }

    /** The north solve, the framed path, the legs and their totals for [doc] on the shown run. */
    private fun buildSurveyBase(doc: SurveyDoc, shown: PathResult, runId: Int, raw: Boolean): SurveyBase {
        // The run id is checked too: the state flow keeps the old instance when a new run's result is equal.
        val unturned = surveyGeometry?.takeIf { it.shown === shown && it.runId == runId && it.raw == raw }
            ?: SurveyGeometry.of(shown, runId, raw)
        val north = NorthSolver.solve(doc, unturned.plain, runId)
        val geometry = unturned.rotated(north.rotationDeg)
        surveyGeometry = geometry
        val magnetic = NorthSolver.isMagnetic(shown.diagnostics, north)
        val legs = Traverse.legs(doc.stations, geometry.timeline)
        return SurveyBase(
            doc = doc,
            shown = shown,
            runId = runId,
            raw = raw,
            geometry = geometry,
            north = north,
            magnetic = magnetic,
            legs = legs,
            totals = Traverse.totals(legs),
            northWarning = if (!magnetic && doc.references.isEmpty()) {
                CalibrationMath.northProblem(shown.diagnostics)
            } else {
                null
            },
        )
    }

    /**
     * The parts of the survey snapshot that walk the whole path. The doc and the shown result are compared
     * as instances: an edit or a new run always brings a new one, and an equal copy costs one rebuild at most.
     */
    private class SurveyBase(
        val doc: SurveyDoc,
        val shown: PathResult,
        val runId: Int,
        val raw: Boolean,
        val geometry: SurveyGeometry,
        val north: NorthSolution,
        val magnetic: Boolean,
        val legs: List<TraverseLeg>,
        val totals: LegTotals,
        val northWarning: String?,
    ) {
        fun isFor(doc: SurveyDoc, shown: PathResult, runId: Int, raw: Boolean): Boolean =
            doc === this.doc && shown === this.shown && runId == this.runId && raw == this.raw
    }

    /** A readout and what it was computed from. */
    private class ReadoutCache(val base: SurveyBase, val selection: SurveySelection, val value: SurveyReadout?)

    // --- photos ---

    private var photoJob: Job? = null
    /** Bumped by every open/close so a decode can tell whether its dialog is still the current one. */
    private var photoRequest = 0

    fun openPhoto(marker: SceneMarker) {
        val name = marker.fileName ?: return
        photoJob?.cancel()
        val request = ++photoRequest
        _ui.update { it.copy(photoLoading = true, photoError = null, photo = null, photoTitle = name) }
        photoJob = viewModelScope.launch {
            val decoded = withContext(ioDispatcher) {
                runCatching { decodeDownsampled(File(files.photosDir(tripId), name), MAX_PHOTO_PX) }
            }
            // A decode that outlived its dialog (closed, or replaced by another keyframe) must not
            // reopen it or put the wrong image under the new title.
            if (request != photoRequest) return@launch
            val bitmap = decoded.getOrNull()
            val failure = decoded.exceptionOrNull()
            val message = when {
                failure != null -> failure.message ?: "Could not decode photo"
                bitmap == null -> "Photo file is missing or unreadable"
                else -> null
            }
            _ui.update { it.copy(photoLoading = false, photo = bitmap, photoError = message) }
        }
    }

    fun closePhoto() {
        photoJob?.cancel()
        photoJob = null
        photoRequest++
        _ui.update { it.copy(photo = null, photoLoading = false, photoError = null, photoTitle = "") }
    }

    private fun describe(e: Throwable): String = e.message ?: e.javaClass.simpleName

    companion object {
        /** Full-width drag on a ~1000 px screen turns the scene by roughly 290 degrees. */
        const val ORBIT_RAD_PER_PX = 0.005
        const val MAX_PHOTO_PX = 1600

        /** Shown when an edit is tried on a survey whose file could not be read. */
        const val READ_ONLY_MESSAGE = "Survey mode is read-only: survey.json could not be read"

        /** Decodes [file] with a power-of-two sample size so the longest side is at most [maxPx]. */
        fun decodeDownsampled(file: File, maxPx: Int): Bitmap? {
            if (!file.isFile) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            // Keyframes are saved as the camera delivers them and carry an EXIF orientation tag.
            val orientation = runCatching {
                ExifInterface(file.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) return bitmap
            val matrix = Matrix().apply { postRotate(degrees) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }
}
