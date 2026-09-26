package com.stastyle.imumapper.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.imumapper.data.TripFiles
import com.stastyle.imumapper.data.TripRepository
import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.post.RawPath
import com.stastyle.imumapper.process.TripProcessor
import com.stastyle.imumapper.render.Bounds
import com.stastyle.imumapper.render.CameraPreset
import com.stastyle.imumapper.render.ColorMode
import com.stastyle.imumapper.render.OrbitCamera
import com.stastyle.imumapper.render.SceneMarker
import com.stastyle.imumapper.render.SceneOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
) {
    /** What the canvas draws as the main path and what the stats panel describes. */
    val shownResult: PathResult? get() = if (showRaw) rawResult ?: result else result

    /**
     * The dimmed path under the main one: the chosen overlay run, or, in raw view with no overlay
     * run, the corrected path of the same run so the two can be compared in place.
     */
    val shownOverlay: PathResult? get() = overlayResult ?: if (showRaw && rawResult != null) result else null

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

    /** Processes the trip once when it has been recorded but never processed. */
    private fun maybeProcess() {
        if (processingStarted || !runsLoaded || !tripLoaded) return
        val trip = _ui.value.trip ?: return
        if (_ui.value.runs.isNotEmpty() || trip.status == TripStatus.RECORDING) return
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
            val loaded = withContext(Dispatchers.IO) {
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
            updateBounds()
            if (needsFit) fitIfPossible()
        }
    }

    /** The raw view of [result], or null when it stores no raw path distinct from the final one. */
    private fun rawViewOf(runId: Int, result: PathResult): PathResult? {
        if (!RawPath.isAvailable(result)) return null
        return rawCache.getOrPut(runId) { RawPath.view(result) }
    }

    /** Fit and presets frame the path on screen, so they follow the raw toggle. */
    private fun updateBounds() {
        val shown = _ui.value.shownResult ?: return
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
     * selected marker is dropped because its position belongs to the other path.
     */
    fun toggleRaw() {
        val state = _ui.value
        val show = !state.showRaw
        val runId = state.selectedRunId
        val result = state.result
        val raw = if (show && runId != null && result != null) rawViewOf(runId, result) else null
        _ui.update { it.copy(showRaw = show, rawResult = raw, selectedMarker = null) }
        updateBounds()
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
            val decoded = withContext(Dispatchers.IO) {
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
