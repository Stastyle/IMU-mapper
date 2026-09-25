package com.stastyle.imumapper.ui.debug

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.imumapper.capture.SensorLogger
import com.stastyle.imumapper.capture.SensorStats
import com.stastyle.imumapper.data.CalibrationRepository
import com.stastyle.imumapper.data.TripFiles
import com.stastyle.imumapper.data.TripRepository
import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.LogMeta
import com.stastyle.imumapper.pipeline.core.PathStats
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Processor
import com.stastyle.imumapper.pipeline.log.LogReader
import com.stastyle.imumapper.pipeline.log.RawLog
import com.stastyle.imumapper.pipeline.pdr.PdrProcessor
import com.stastyle.imumapper.pipeline.vio.VioProcessor
import com.stastyle.imumapper.process.TripProcessor
import com.stastyle.imumapper.process.readResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** What [LogReader] found in a trip's raw log, without keeping the samples in memory. */
data class LogSummary(
    val fileSizeBytes: Long,
    /** Record type name to count, in the order the log format lists them. */
    val counts: List<Pair<String, Int>>,
    val durationS: Double,
    val truncated: Boolean,
    val unknownRecords: Int,
    val hasVio: Boolean,
    val meta: LogMeta?,
    /** Raw meta JSON when it did not parse as [LogMeta]. */
    val metaJsonFallback: String?,
    val annotations: List<String>,
    val events: List<String>,
)

/** One stored run with its stats decoded; diagnostics come from the result file on demand. */
data class RunInfo(
    val entity: PathResultEntity,
    val stats: PathStats?,
    val config: PipelineConfig?,
    val diagnostics: Map<String, String>? = null,
    val diagnosticsLoading: Boolean = false,
    val diagnosticsError: String? = null,
)

data class DebugUiState(
    /** The user wants the live view; sensors run only while the screen is started. */
    val liveEnabled: Boolean = false,
    val liveRunning: Boolean = false,
    val stats: SensorStats = SensorStats(),
    val charts: LiveCharts = LiveCharts.EMPTY,
    val trips: List<TripEntity> = emptyList(),
    val tripId: Long? = null,
    val trip: TripEntity? = null,
    val summary: LogSummary? = null,
    val summaryLoading: Boolean = false,
    val summaryError: String? = null,
    val runs: List<RunInfo> = emptyList(),
    val expandedRunId: Int? = null,
    val savedConfig: PipelineConfig = PipelineConfig(),
    val draft: ConfigDraft = ConfigDraft.from(PipelineConfig()),
    val errors: Map<ConfigField, String> = emptyMap(),
    /** True once the user edited a field; the saved calibration then no longer overwrites the draft. */
    val draftDirty: Boolean = false,
    val processing: Boolean = false,
    val message: String? = null,
)

/**
 * Debug screen state: a live sensor view through its own [SensorLogger] (no writer) and, for one
 * trip, the raw-log summary, the stored runs and a config editor that re-processes the trip.
 */
class DebugViewModel(
    private val appContext: Context,
    initialTripId: Long?,
    private val trips: TripRepository,
    private val files: TripFiles,
    private val calibration: CalibrationRepository,
    private val tripProcessor: TripProcessor,
    private val json: Json,
) : ViewModel() {

    private val _ui = MutableStateFlow(DebugUiState(tripId = initialTripId))
    val ui: StateFlow<DebugUiState> = _ui.asStateFlow()

    /** True when the screen was opened for a specific trip; the picker is hidden then. */
    val fixedTrip: Boolean = initialTripId != null

    private var logger: SensorLogger? = null
    private val accumulator = LiveAccumulator()
    private var liveJobs: List<Job> = emptyList()
    private var tripJobs: List<Job> = emptyList()

    init {
        viewModelScope.launch {
            trips.observeTrips().collect { all ->
                val usable = all.filter { it.status != TripStatus.RECORDING }
                _ui.update { it.copy(trips = usable) }
                if (_ui.value.tripId == null && usable.isNotEmpty()) selectTrip(usable[0].id)
            }
        }
        viewModelScope.launch {
            calibration.observeConfig().collect { c ->
                _ui.update { s ->
                    if (s.draftDirty) s.copy(savedConfig = c) else s.copy(savedConfig = c, draft = ConfigDraft.from(c))
                }
            }
        }
        if (initialTripId != null) selectTrip(initialTripId)
    }

    fun dismissMessage() {
        _ui.update { it.copy(message = null) }
    }

    // --- live section ---

    fun setLiveEnabled(enabled: Boolean) {
        _ui.update { it.copy(liveEnabled = enabled) }
        if (enabled) startLive() else stopLive()
    }

    /** The screen went to the background: release the sensors but remember the wish. */
    fun onScreenStopped() = stopLive()

    fun onScreenStarted() {
        if (_ui.value.liveEnabled) startLive()
    }

    private fun startLive() {
        if (_ui.value.liveRunning) return
        val sensorLogger = logger ?: SensorLogger(appContext).also { logger = it }
        synchronized(accumulator) { accumulator.clear() }
        sensorLogger.start(null)
        _ui.update { it.copy(liveRunning = true, charts = LiveCharts.EMPTY) }
        val statsJob = viewModelScope.launch {
            sensorLogger.stats.collect { s -> _ui.update { it.copy(stats = s) } }
        }
        val liveJob = viewModelScope.launch(Dispatchers.Default) {
            sensorLogger.live.collect { r -> synchronized(accumulator) { accumulator.accept(r) } }
        }
        val tickJob = viewModelScope.launch {
            // Ten redraws a second is plenty for a plot and keeps recomposition off the sensor rate.
            while (true) {
                delay(CHART_PERIOD_MS)
                val snapshot = withContext(Dispatchers.Default) {
                    synchronized(accumulator) { accumulator.snapshot() }
                }
                _ui.update { it.copy(charts = snapshot) }
            }
        }
        liveJobs = listOf(statsJob, liveJob, tickJob)
    }

    private fun stopLive() {
        if (!_ui.value.liveRunning) return
        for (j in liveJobs) j.cancel()
        liveJobs = emptyList()
        _ui.update { it.copy(liveRunning = false) }
        val l = logger ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { l.stop() }.onFailure { Log.w(TAG, "live stop failed", it) }
        }
    }

    // --- trip section ---

    fun selectTrip(tripId: Long) {
        for (j in tripJobs) j.cancel()
        _ui.update {
            it.copy(
                tripId = tripId,
                trip = it.trips.firstOrNull { t -> t.id == tripId },
                summary = null,
                summaryLoading = true,
                summaryError = null,
                runs = emptyList(),
                expandedRunId = null,
            )
        }
        val tripJob = viewModelScope.launch {
            trips.observeTrip(tripId).collect { t -> _ui.update { it.copy(trip = t) } }
        }
        val runsJob = viewModelScope.launch {
            trips.observeResults(tripId).collect { entities -> mergeRuns(entities) }
        }
        val summaryJob = viewModelScope.launch {
            val outcome = runCatching { withContext(Dispatchers.IO) { summarise(files.rawLog(tripId)) } }
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            _ui.update { s ->
                if (s.tripId != tripId) {
                    s
                } else {
                    s.copy(
                        summary = outcome.getOrNull(),
                        summaryLoading = false,
                        summaryError = outcome.exceptionOrNull()?.let { describe(it) },
                    )
                }
            }
        }
        tripJobs = listOf(tripJob, runsJob, summaryJob)
    }

    private fun mergeRuns(entities: List<PathResultEntity>) {
        _ui.update { s ->
            val old = s.runs.associateBy { it.entity.runId }
            val merged = entities.map { e ->
                val previous = old[e.runId]
                if (previous != null && previous.entity == e) {
                    previous
                } else {
                    RunInfo(
                        entity = e,
                        stats = runCatching { json.decodeFromString(PathStats.serializer(), e.statsJson) }.getOrNull(),
                        config = runCatching {
                            json.decodeFromString(PipelineConfig.serializer(), e.configJson)
                        }.getOrNull(),
                    )
                }
            }
            s.copy(runs = merged)
        }
    }

    fun toggleRun(runId: Int) {
        val expanded = _ui.value.expandedRunId
        if (expanded == runId) {
            _ui.update { it.copy(expandedRunId = null) }
            return
        }
        _ui.update { it.copy(expandedRunId = runId) }
        val run = _ui.value.runs.firstOrNull { it.entity.runId == runId } ?: return
        if (run.diagnostics != null || run.diagnosticsLoading) return
        updateRun(runId) { it.copy(diagnosticsLoading = true, diagnosticsError = null) }
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { files.readResult(run.entity) }
            }
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            updateRun(runId) { r ->
                val result = outcome.getOrNull()
                r.copy(
                    diagnostics = result?.diagnostics ?: emptyMap(),
                    stats = result?.stats ?: r.stats,
                    diagnosticsLoading = false,
                    diagnosticsError = outcome.exceptionOrNull()?.let { describe(it) },
                )
            }
        }
    }

    private fun updateRun(runId: Int, transform: (RunInfo) -> RunInfo) {
        _ui.update { s -> s.copy(runs = s.runs.map { if (it.entity.runId == runId) transform(it) else it }) }
    }

    // --- config editor ---

    fun setField(field: ConfigField, text: String) {
        _ui.update { s ->
            val draft = s.draft.with(field, text)
            s.copy(draft = draft, errors = ConfigFields.parse(draft).errors, draftDirty = true)
        }
    }

    fun resetDraft() {
        _ui.update { it.copy(draft = ConfigDraft.from(it.savedConfig), errors = emptyMap(), draftDirty = false) }
    }

    fun loadDraftFrom(config: PipelineConfig) {
        _ui.update { it.copy(draft = ConfigDraft.from(config), errors = emptyMap(), draftDirty = true) }
    }

    /** The edited config when every field is valid, otherwise null (and the errors are shown). */
    private fun draftConfig(): PipelineConfig? {
        val parsed = ConfigFields.parse(_ui.value.draft)
        _ui.update { it.copy(errors = parsed.errors) }
        if (parsed.config == null) _ui.update { it.copy(message = "Fix the highlighted fields first") }
        return parsed.config
    }

    fun saveDraftAsCalibration() {
        val config = draftConfig() ?: return
        viewModelScope.launch {
            val outcome = runCatching { calibration.saveConfig(config, "debug editor") }
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val message = outcome.fold({ "Saved as the current calibration" }, { e -> "Save failed: " + describe(e) })
            _ui.update { it.copy(draftDirty = !outcome.isSuccess, message = message) }
        }
    }

    /** Re-processes the selected trip with the edited config through the default processor. */
    fun reprocess() = runProcessor(null)

    fun runPdrOnly() = runProcessor(PdrProcessor())

    fun runVioOnly() = runProcessor(VioProcessor())

    private fun runProcessor(processor: Processor?) {
        val tripId = _ui.value.tripId ?: return
        if (_ui.value.processing) return
        val config = draftConfig() ?: return
        _ui.update { it.copy(processing = true) }
        viewModelScope.launch {
            val outcome = runCatching {
                if (processor == null) {
                    tripProcessor.process(tripId, config, ConfigFields.runLabel(config))
                } else {
                    // The label is left blank so the processor's own "PDR" / "VIO" default applies.
                    tripProcessor.processWith(tripId, processor, config, "")
                }
            }
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            _ui.update {
                it.copy(
                    processing = false,
                    message = outcome.fold(
                        { e -> "Stored run " + e.runId + (if (e.label.isNotBlank()) " (" + e.label + ")" else "") },
                        { e -> "Processing failed: " + describe(e) },
                    ),
                )
            }
        }
    }

    override fun onCleared() {
        for (j in liveJobs) j.cancel()
        logger?.release()
        logger = null
    }

    // --- helpers ---

    private fun summarise(file: File): LogSummary {
        if (!file.isFile) throw IllegalStateException("Raw log missing")
        val log: RawLog = LogReader.read(file)
        val counts = listOf(
            "Accel" to log.accel.size,
            "Gyro" to log.gyro.size,
            "Mag" to log.mag.size,
            "Baro" to log.baro.size,
            "Game rotation" to log.gameRotation.size,
            "Rotation vector" to log.fusedRotation.size,
            "Hardware steps" to log.steps.size,
            "Accel uncal" to log.accelUncal.size,
            "Gyro uncal" to log.gyroUncal.size,
            "Mag uncal" to log.magUncal.size,
            "ARCore poses" to log.poses.size,
            "Point clouds" to log.pointClouds.size,
            "Keyframes" to log.keyframes.size,
            "Annotations" to log.annotations.size,
            "Events" to log.events.size,
        ).filter { it.second > 0 }
        val t0 = log.firstTimestampNs
        return LogSummary(
            fileSizeBytes = file.length(),
            counts = counts,
            durationS = log.durationS,
            truncated = log.truncated,
            unknownRecords = log.unknownRecords,
            hasVio = log.hasVio,
            meta = log.meta,
            metaJsonFallback = if (log.meta == null) log.metaJson else null,
            annotations = log.annotations.map { a ->
                secondsAt(a.tNs, t0) + " " + a.kind.name + (if (a.note.isNotBlank()) ": " + a.note else "")
            },
            events = log.events.map { e -> secondsAt(e.tNs, t0) + " " + e.kind.name },
        )
    }

    private fun secondsAt(tNs: Long, t0: Long): String = "[" + ((tNs - t0) / 1e9).toInt() + " s]"

    private fun describe(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    private companion object {
        const val TAG = "DebugViewModel"
        const val CHART_PERIOD_MS = 100L
    }
}
