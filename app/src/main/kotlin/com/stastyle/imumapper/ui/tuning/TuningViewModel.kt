package com.stastyle.imumapper.ui.tuning

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.imumapper.data.CalibrationRepository
import com.stastyle.imumapper.data.TripExporter
import com.stastyle.imumapper.data.TripFiles
import com.stastyle.imumapper.data.TripRepository
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.Processor
import com.stastyle.imumapper.pipeline.log.LogReader
import com.stastyle.imumapper.pipeline.log.RawLog
import com.stastyle.imumapper.pipeline.tuning.ConfigSchema
import com.stastyle.imumapper.pipeline.tuning.GroundTruth
import com.stastyle.imumapper.pipeline.tuning.PromptBundle
import com.stastyle.imumapper.pipeline.tuning.Proposal
import com.stastyle.imumapper.pipeline.tuning.ProposalOutcome
import com.stastyle.imumapper.pipeline.tuning.ProposalParser
import com.stastyle.imumapper.pipeline.tuning.RecordingInfo
import com.stastyle.imumapper.pipeline.tuning.TuningAnalysis
import com.stastyle.imumapper.pipeline.tuning.TuningReport
import com.stastyle.imumapper.pipeline.tuning.WalkShape
import com.stastyle.imumapper.process.TripProcessor
import com.stastyle.imumapper.ui.calibration.Fmt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** The ground-truth form as typed; parsed into a [GroundTruth] when the prompt is built. */
data class TruthForm(
    val shape: WalkShape = WalkShape.FREE,
    val distanceText: String = "",
    val turnsText: String = "",
    val heightText: String = "",
    val description: String = "",
)

enum class TruthField { DISTANCE, TURNS, HEIGHT }

sealed interface PromptPhase {
    data object Idle : PromptPhase
    data object Building : PromptPhase

    /** The bundle is ready to copy or share; [report] is the run it describes. */
    data class Ready(val truth: GroundTruth, val report: TuningReport, val text: String) : PromptPhase

    data class Failed(val message: String) : PromptPhase
}

sealed interface ProposalPhase {
    data object Idle : ProposalPhase
    data class Rejected(val errors: List<String>) : ProposalPhase
    data class Ready(val proposal: Proposal) : ProposalPhase
    data class Evaluating(val proposal: Proposal) : ProposalPhase

    /** The proposal was run over the same log and stored as run [runId]; [before] is the prompt's run. */
    data class Evaluated(val proposal: Proposal, val before: TuningReport, val after: TuningReport, val runId: Int) : ProposalPhase

    data class Failed(val proposal: Proposal, val message: String) : ProposalPhase
}

data class TuningUiState(
    val trips: List<TripEntity> = emptyList(),
    val tripId: Long? = null,
    /** The saved calibration; the base every proposal is applied on. */
    val config: PipelineConfig = PipelineConfig(),
    val form: TruthForm = TruthForm(),
    val formErrors: Map<TruthField, String> = emptyMap(),
    val prompt: PromptPhase = PromptPhase.Idle,
    val responseText: String = "",
    val proposal: ProposalPhase = ProposalPhase.Idle,
    /** Proposals evaluated on the selected trip in this session, oldest first; the next prompt lists them. */
    val history: List<TuningReport> = emptyList(),
    /** True after the evaluated proposal was saved as the calibration. */
    val applied: Boolean = false,
    /** A share-sheet intent for the screen to start. */
    val pendingIntent: Intent? = null,
    val message: String? = null,
) {
    val busy: Boolean get() = prompt is PromptPhase.Building || proposal is ProposalPhase.Evaluating
}

/**
 * The assisted-tuning loop: pick a recorded trip, describe the walk, build a prompt from the
 * pipeline's own numbers, paste a model's answer back, re-process the trip with the proposed
 * config, compare, and save the config as the calibration when it is better. The log is read
 * once per trip and kept while the screen lives.
 */
class TuningViewModel(
    private val appContext: Context,
    private val trips: TripRepository,
    private val files: TripFiles,
    private val calibration: CalibrationRepository,
    private val tripProcessor: TripProcessor,
    private val processor: Processor,
) : ViewModel() {

    private val _ui = MutableStateFlow(TuningUiState())
    val ui: StateFlow<TuningUiState> = _ui.asStateFlow()

    private var cachedLog: Pair<Long, RawLog>? = null

    init {
        viewModelScope.launch {
            trips.observeTrips().collect { all ->
                val usable = all.filter { it.status != TripStatus.RECORDING }
                _ui.update { s ->
                    val keep = s.tripId?.takeIf { id -> usable.any { it.id == id } }
                    s.copy(trips = usable, tripId = keep ?: usable.firstOrNull()?.id)
                }
            }
        }
        viewModelScope.launch {
            calibration.observeConfig().collect { c -> _ui.update { it.copy(config = c) } }
        }
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    fun consumeIntent() = _ui.update { it.copy(pendingIntent = null) }

    fun selectTrip(tripId: Long) {
        if (_ui.value.busy || tripId == _ui.value.tripId) return
        cachedLog = null
        _ui.update {
            it.copy(
                tripId = tripId,
                prompt = PromptPhase.Idle,
                proposal = ProposalPhase.Idle,
                responseText = "",
                history = emptyList(),
                applied = false,
            )
        }
    }

    // --- ground truth form ---

    fun setShape(shape: WalkShape) = editForm { it.copy(shape = shape) }

    fun setDistance(text: String) = editForm { it.copy(distanceText = text) }

    fun setTurns(text: String) = editForm { it.copy(turnsText = text) }

    fun setHeight(text: String) = editForm { it.copy(heightText = text) }

    fun setDescription(text: String) = editForm { it.copy(description = text) }

    private fun editForm(transform: (TruthForm) -> TruthForm) {
        _ui.update { s ->
            val form = transform(s.form)
            s.copy(form = form, formErrors = parseTruth(form).second)
        }
    }

    /** The parsed truth and the field errors; the truth is null when any error exists. */
    private fun parseTruth(form: TruthForm): Pair<GroundTruth?, Map<TruthField, String>> {
        val errors = LinkedHashMap<TruthField, String>()
        fun number(text: String, field: TruthField, min: Double, max: Double): Double? {
            val t = text.trim().replace(',', '.')
            if (t.isEmpty()) return null
            val v = t.toDoubleOrNull()
            if (v == null || v.isNaN()) {
                errors[field] = "Enter a number or leave it empty"
                return null
            }
            if (v < min || v > max) {
                errors[field] = "Must be between " + ConfigSchema.num(min) + " and " + ConfigSchema.num(max)
                return null
            }
            return v
        }
        val distance = number(form.distanceText, TruthField.DISTANCE, 0.5, 100_000.0)
        val turns = number(form.turnsText, TruthField.TURNS, 0.0, 1000.0)?.let { v ->
            if (v != Math.floor(v)) {
                errors[TruthField.TURNS] = "Enter a whole number"
                null
            } else {
                v.toInt()
            }
        }
        val height = number(form.heightText, TruthField.HEIGHT, -1000.0, 1000.0)
        val truth = GroundTruth(form.shape, distance, turns, height, form.description.trim())
        return Pair(if (errors.isEmpty()) truth else null, errors)
    }

    // --- prompt ---

    fun buildPrompt() {
        val tripId = _ui.value.tripId ?: return
        if (_ui.value.busy) return
        val (truth, errors) = parseTruth(_ui.value.form)
        if (truth == null) {
            _ui.update { it.copy(formErrors = errors, message = "Fix the highlighted fields first") }
            return
        }
        _ui.update { it.copy(prompt = PromptPhase.Building, proposal = ProposalPhase.Idle, applied = false) }
        val config = _ui.value.config
        val history = _ui.value.history
        viewModelScope.launch {
            val outcome = runCatching {
                val trip = trips.getTrip(tripId) ?: throw IllegalStateException("Trip not found")
                val log = loadLog(tripId)
                val master = withContext(Dispatchers.IO) { readMasterPrompt() }
                withContext(Dispatchers.Default) {
                    val report = TuningAnalysis.analyse(log, config, processor)
                    val text = PromptBundle.render(master, recordingInfo(trip, log), log, truth, report, history)
                    PromptPhase.Ready(truth, report, text)
                }
            }
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val phase: PromptPhase = outcome.fold(
                { it },
                { e ->
                    Log.w(TAG, "prompt failed", e)
                    PromptPhase.Failed(describe(e))
                },
            )
            _ui.update { s -> if (s.tripId != tripId) s else s.copy(prompt = phase) }
        }
    }

    fun copyPrompt() {
        val ready = _ui.value.prompt as? PromptPhase.Ready ?: return
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            _ui.update { it.copy(message = "Clipboard not available") }
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("IMU Mapper tuning prompt", ready.text))
        _ui.update { it.copy(message = "Prompt copied (" + ready.text.length + " characters)") }
    }

    /** Shares the prompt as plain text, which chat apps accept straight into the message box. */
    fun sharePromptText() {
        val ready = _ui.value.prompt as? PromptPhase.Ready ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "IMU Mapper tuning prompt")
            putExtra(Intent.EXTRA_TEXT, ready.text)
        }
        _ui.update { it.copy(pendingIntent = Intent.createChooser(send, "Send prompt to")) }
    }

    /** Shares the prompt as a Markdown file, for apps that cap shared text or prefer attachments. */
    fun sharePromptFile() {
        val ready = _ui.value.prompt as? PromptPhase.Ready ?: return
        val tripId = _ui.value.tripId ?: return
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    files.pruneExports(EXPORT_MAX_AGE_MS)
                    val file = File(files.exportDir(), "tuning-prompt-" + tripId + ".md")
                    file.writeText(ready.text)
                    val uri = FileProvider.getUriForFile(appContext, TripExporter.authority(appContext), file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/markdown"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "IMU Mapper tuning prompt")
                        clipData = ClipData.newRawUri(file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    Intent.createChooser(send, "Send prompt file to").apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
            }
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            _ui.update { s ->
                outcome.fold({ s.copy(pendingIntent = it) }, { e -> s.copy(message = "Could not write the file: " + describe(e)) })
            }
        }
    }

    // --- answer ---

    fun setResponse(text: String) {
        _ui.update { s ->
            // A new paste invalidates whatever was checked before, but not an evaluation in progress.
            val proposal = if (s.proposal is ProposalPhase.Evaluating) s.proposal else ProposalPhase.Idle
            s.copy(responseText = text, proposal = proposal, applied = false)
        }
    }

    fun checkProposal() {
        val s = _ui.value
        if (s.busy) return
        val phase = when (val outcome = ProposalParser.parse(s.responseText, s.config)) {
            is ProposalOutcome.Accepted -> ProposalPhase.Ready(outcome.proposal)
            is ProposalOutcome.Rejected -> ProposalPhase.Rejected(outcome.errors)
        }
        _ui.update { it.copy(proposal = phase, applied = false) }
    }

    /**
     * Re-processes the trip with the proposal through the trip processor, so the run is stored and
     * can be opened in the viewer, then measures it the way the prompt measured the baseline.
     */
    fun evaluate() {
        val s = _ui.value
        val tripId = s.tripId ?: return
        val ready = s.proposal as? ProposalPhase.Ready ?: return
        val baseline = s.prompt as? PromptPhase.Ready
        if (baseline == null) {
            _ui.update { it.copy(message = "Build the prompt first so there is a baseline to compare with") }
            return
        }
        if (s.busy) return
        val proposal = ready.proposal
        _ui.update { it.copy(proposal = ProposalPhase.Evaluating(proposal)) }
        viewModelScope.launch {
            val outcome = runCatching {
                val log = loadLog(tripId)
                val capture = CapturingProcessor(processor)
                val entity = tripProcessor.processWith(tripId, capture, proposal.config, runLabel(proposal))
                val result = capture.last ?: throw IllegalStateException("The processor produced no result")
                val after = withContext(Dispatchers.Default) { TuningAnalysis.report(log, proposal.config, result) }
                ProposalPhase.Evaluated(proposal, baseline.report, after, entity.runId)
            }
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            _ui.update { st ->
                if (st.tripId != tripId) return@update st
                outcome.fold(
                    { evaluated -> st.copy(proposal = evaluated, history = st.history + evaluated.after) },
                    { e -> Log.w(TAG, "evaluation failed", e); st.copy(proposal = ProposalPhase.Failed(proposal, describe(e))) },
                )
            }
        }
    }

    fun applyProposal() {
        val s = _ui.value
        val evaluated = s.proposal as? ProposalPhase.Evaluated ?: return
        val trip = s.trips.firstOrNull { it.id == s.tripId }
        val note = buildString {
            append("Assisted tuning on \"").append(trip?.name ?: "trip").append("\": ")
            append(evaluated.proposal.changes.joinToString(", ") { it.key + " " + it.from + " -> " + it.to })
            evaluated.proposal.reasoning?.let { append(". ").append(it.take(400)) }
        }
        viewModelScope.launch {
            val outcome = runCatching { calibration.saveConfig(evaluated.proposal.config, note) }
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            _ui.update { st ->
                outcome.fold(
                    { st.copy(applied = true, message = "Saved as the current calibration") },
                    { e -> st.copy(message = "Save failed: " + describe(e)) },
                )
            }
        }
    }

    /** Clears the answer so the next prompt (which now lists this attempt) can be built and sent. */
    fun nextRound() {
        if (_ui.value.busy) return
        _ui.update { it.copy(responseText = "", proposal = ProposalPhase.Idle, prompt = PromptPhase.Idle, applied = false) }
    }

    // --- helpers ---

    private suspend fun loadLog(tripId: Long): RawLog {
        cachedLog?.let { if (it.first == tripId) return it.second }
        val log = withContext(Dispatchers.IO) {
            val file = files.rawLog(tripId)
            if (!file.isFile) throw IllegalStateException("Raw log missing")
            LogReader.read(file, LogReader.UNCALIBRATED_TYPES)
        }
        if (log.totalRecords == 0) throw IllegalStateException("Raw log is empty")
        cachedLog = tripId to log
        return log
    }

    private fun readMasterPrompt(): String =
        appContext.assets.open(MASTER_PROMPT_ASSET).bufferedReader().use { it.readText() }

    private fun recordingInfo(trip: TripEntity, log: RawLog): RecordingInfo {
        val meta = log.meta
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.US).format(Date(trip.startedAtEpochMs))
        return RecordingInfo(
            tripName = trip.name,
            recordedAt = date,
            mode = trip.mode.name.lowercase(),
            carryPosition = Fmt.carry(trip.carryPosition).lowercase(),
            deviceModel = meta?.deviceModel ?: "unknown device",
            appVersion = meta?.appVersion ?: "unknown",
        )
    }

    private fun runLabel(proposal: Proposal): String {
        val keys = proposal.changes.joinToString(" ") { it.key + "=" + it.to }
        return ("tuning " + keys).take(80)
    }

    private fun describe(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    /** Hands the result to the caller as well as to the trip processor, which only stores it. */
    private class CapturingProcessor(private val inner: Processor) : Processor {
        @Volatile
        var last: PathResult? = null

        override val version: Int get() = inner.version

        override fun process(log: RawLog, config: PipelineConfig): PathResult =
            inner.process(log, config).also { last = it }
    }

    private companion object {
        const val TAG = "TuningVM"
        const val MASTER_PROMPT_ASSET = "tuning/master_prompt.md"
        const val EXPORT_MAX_AGE_MS = 24L * 60L * 60L * 1000L
    }
}
