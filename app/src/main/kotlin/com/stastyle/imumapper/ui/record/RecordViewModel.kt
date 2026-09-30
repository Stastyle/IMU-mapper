package com.stastyle.imumapper.ui.record

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.imumapper.capture.CompassFeed
import com.stastyle.imumapper.capture.CompassReading
import com.stastyle.imumapper.capture.CompassStatus
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.capture.SensorKind
import com.stastyle.imumapper.capture.SensorStats
import com.stastyle.imumapper.data.CalibrationRepository
import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.HeadingAxisMode
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.process.TripProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the recording screen is in its flow. */
enum class RecordPhase {
    /** Choosing the carry position, waiting for Start. */
    SETUP,
    /** Start pressed; the sensors run without a log while the compass settles on north. */
    COMPASS,
    /** Recording requested; trip and log are being created. */
    STARTING,
    RECORDING,
    /** Stop pressed; the log is being closed and the trip processed. */
    STOPPING,
    /** Everything saved; the screen navigates to the viewer. */
    DONE,
}

data class RecordUiState(
    /** The route's mode until a recording is adopted, then the recording's own ([adopt]). */
    val mode: TripMode,
    /** The chosen carry until a recording is adopted, then the recording's own ([adopt]). */
    val carry: CarryPosition = CarryPosition.HAND,
    val phase: RecordPhase = RecordPhase.SETUP,
    val recording: RecordingState.Recording? = null,
    val stats: SensorStats = SensorStats(),
    /** Blocking problem shown under the Start button. */
    val error: String? = null,
    /** Transient confirmation shown in a snackbar, e.g. "Waypoint marked". */
    val message: String? = null,
    /** Set once in [RecordPhase.DONE]; the screen calls onFinished with it. */
    val finishedTripId: Long? = null,
    /** Live compass state while in [RecordPhase.COMPASS], null otherwise. */
    val compass: CompassReading? = null,
    /** Whole seconds spent in [RecordPhase.COMPASS] so far. */
    val compassWaitS: Int = 0,
    /** [PipelineConfig.northFromCompass] of the saved calibration. */
    val compassNorthSetting: Boolean = true,
    /** The phone has the rotation vector, game rotation vector and magnetometer the compass step needs. */
    val compassSensors: Boolean = true,
    /**
     * The saved calibration has a heading offset or axis. The pipeline applies them to the start of every
     * recording, so a recording must start in the pose they were calibrated in, not in the hand.
     */
    val offsetCalibrated: Boolean = false,
    /** [PipelineConfig.strideLengthM] of the saved calibration, for the live distance estimate; null until read. */
    val strideLengthM: Double? = null,
    /** The shown recording has been paused at some point, so its clock leaves time out. */
    val timeExcludesPauses: Boolean = false,
) {
    /** North of the next trip comes from the compass: the setting is on and the sensors exist. */
    val northFromCompass: Boolean
        get() = compassNorthSetting && compassSensors

    /**
     * Offer to record without a settled north once the wait has gone on for a while: indoors or near
     * metal the compass may never lock, and recording must stay possible.
     */
    val canSkipCompass: Boolean
        get() = phase == RecordPhase.COMPASS && compassWaitS >= SKIP_COMPASS_AFTER_S &&
            compass?.status != CompassStatus.LOCKED

    /**
     * The state once the controller reports [recording] at [nowNs]. Every phase up to RECORDING adopts it,
     * our own start as well as one already running when the screen opened, and takes its mode and carry: the
     * record route may have been opened with another mode, and from here on the screen shows only what is
     * being recorded. STOPPING and DONE keep their phase and only take the snapshot.
     */
    fun adopt(recording: RecordingState.Recording, nowNs: Long): RecordUiState {
        val sameTrip = this.recording?.tripId == recording.tripId
        val paused = (sameTrip && timeExcludesPauses) || recording.paused ||
            RecordStatus.pausedTimeEvident(recording.startedNs, recording.elapsedNs, nowNs)
        return if (phase in ADOPTING) {
            copy(
                phase = RecordPhase.RECORDING,
                recording = recording,
                mode = recording.mode,
                carry = recording.carryPosition,
                error = null,
                compass = null,
                timeExcludesPauses = paused,
            )
        } else {
            copy(recording = recording, timeExcludesPauses = paused)
        }
    }

    /**
     * The saved carry position, which loads after the screen opens. It is only a preselection: once a
     * recording is adopted the carry is the recording's, and a late read must not relabel it.
     */
    fun withSavedCarry(saved: CarryPosition): RecordUiState =
        if (phase == RecordPhase.SETUP || phase == RecordPhase.COMPASS || phase == RecordPhase.STARTING) {
            copy(carry = saved)
        } else {
            this
        }

    companion object {
        private val ADOPTING =
            setOf(RecordPhase.SETUP, RecordPhase.COMPASS, RecordPhase.STARTING, RecordPhase.RECORDING)

        const val SKIP_COMPASS_AFTER_S = 8

        /** [com.stastyle.imumapper.pipeline.core.LogMeta.notes] of a trip started with "Start anyway". */
        const val COMPASS_SKIPPED_NOTE = "compass not locked at start"
    }
}

/**
 * Drives [RecordScreen]. The recording itself lives in [RecordingController], which outlives this
 * view model; here we only translate its state and the user's taps.
 */
class RecordViewModel(
    mode: TripMode,
    private val controller: RecordingController,
    private val calibration: CalibrationRepository,
    private val tripProcessor: TripProcessor,
) : ViewModel() {

    private val _ui = MutableStateFlow(RecordUiState(mode = mode, compassSensors = hasCompassSensors()))
    val ui: StateFlow<RecordUiState> = _ui.asStateFlow()

    /** Trip id of the recording we were showing, for when it is stopped from the notification. */
    private var shownTripId: Long? = null

    /** Feeds the compass preview into [RecordUiState.compass]; runs only in [RecordPhase.COMPASS]. */
    private var compassJob: Job? = null

    /** The user tapped a carry chip, so the saved position that loads late must not replace it. */
    private var carryPicked = false

    init {
        viewModelScope.launch {
            val saved = runCatching { calibration.getCarryPosition() }.getOrDefault(CarryPosition.HAND)
            if (!carryPicked) _ui.update { it.withSavedCarry(saved) }
        }
        viewModelScope.launch {
            try {
                calibration.observeConfig().collect { c ->
                    val offset = c.headingOffsetRad != 0.0 || c.headingAxis != HeadingAxisMode.AUTO
                    _ui.update {
                        it.copy(
                            compassNorthSetting = c.northFromCompass,
                            offsetCalibrated = offset,
                            strideLengthM = c.strideLengthM,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only the texts depend on it; start() reads the config again.
                Log.w(TAG, "config not observed", e)
            }
        }
        viewModelScope.launch { controller.state.collect { onControllerState(it) } }
        viewModelScope.launch { controller.sensorLogger.stats.collect { s -> _ui.update { it.copy(stats = s) } } }
    }

    private fun onControllerState(state: RecordingState) {
        when (state) {
            is RecordingState.Recording -> {
                shownTripId = state.tripId
                // A recording owns the sensors now; the preview's feed has nothing left to show.
                if (_ui.value.phase == RecordPhase.COMPASS) leaveCompass()
                // Adopt a recording that is already running (screen re-entered) as well as our own.
                val nowNs = SystemClock.elapsedRealtimeNanos()
                _ui.update { it.adopt(state, nowNs) }
            }
            is RecordingState.Stopping -> Unit
            is RecordingState.Idle -> {
                val tripId = shownTripId
                if (_ui.value.phase == RecordPhase.RECORDING && tripId != null) {
                    // Stopped from the notification: finish the same way as a tap on Stop.
                    _ui.update { it.copy(phase = RecordPhase.STOPPING) }
                    viewModelScope.launch { finish(tripId) }
                }
            }
        }
    }

    fun setCarry(position: CarryPosition) {
        carryPicked = true
        _ui.update { it.copy(carry = position) }
        viewModelScope.launch {
            runCatching { calibration.saveCarryPosition(position) }
                .onFailure { Log.w(TAG, "carry position not saved", it) }
        }
    }

    /**
     * Called once the required permissions are granted. With north from the compass on, waits in
     * [RecordPhase.COMPASS] until the user starts the recording from the compass dialog; otherwise
     * starts recording at once.
     */
    fun start() {
        if (_ui.value.phase != RecordPhase.SETUP) return
        // STARTING while the config is read, so a second tap cannot start twice.
        _ui.update { it.copy(phase = RecordPhase.STARTING, error = null) }
        viewModelScope.launch {
            val config = try {
                calibration.getConfig()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "config not read; using the defaults", e)
                PipelineConfig()
            }
            // A recording adopted meanwhile (one started elsewhere) has moved the phase on.
            if (_ui.value.phase != RecordPhase.STARTING) return@launch
            if (config.northFromCompass && hasCompassSensors()) enterCompass() else startRecording()
        }
    }

    /**
     * "Start recording" in the compass dialog, once north is locked. The button stays tappable while it
     * animates out after the lock was lost, so the status is checked here too; "Start anyway" is
     * [skipCompass].
     */
    fun confirmCompass() {
        val ui = _ui.value
        if (ui.phase != RecordPhase.COMPASS || ui.compass?.status != CompassStatus.LOCKED) return
        leaveCompass()
        startRecording()
    }

    /**
     * "Start anyway": records before the compass locked. The pipeline still takes north from the
     * compass at the start of the trip, which may then be several degrees off, and nothing in its
     * diagnostics can tell; the log's meta notes say so instead ([RecordUiState.COMPASS_SKIPPED_NOTE]),
     * and the Debug screen shows them.
     */
    fun skipCompass() {
        if (_ui.value.phase != RecordPhase.COMPASS) return
        Log.i(TAG, "recording started without a compass lock")
        leaveCompass()
        startRecording(notes = RecordUiState.COMPASS_SKIPPED_NOTE)
    }

    /**
     * Cancel or Back in the compass dialog, or the app going to the background: stops the preview and
     * returns to the setup.
     */
    fun cancelCompass() {
        if (_ui.value.phase != RecordPhase.COMPASS) return
        leaveCompass()
        controller.stopCompassPreview()
        _ui.update { it.copy(phase = RecordPhase.SETUP, compass = null, compassWaitS = 0) }
    }

    private fun hasCompassSensors(): Boolean {
        val available = controller.sensorLogger.available
        return available[SensorKind.ROT_VEC] == true && available[SensorKind.GAME_ROT] == true &&
            available[SensorKind.MAG] == true
    }

    private fun enterCompass() {
        controller.startCompassPreview()
        val enteredNs = SystemClock.elapsedRealtimeNanos()
        _ui.update {
            it.copy(
                phase = RecordPhase.COMPASS,
                compass = CompassReading(CompassStatus.WAITING),
                compassWaitS = 0,
                error = null,
            )
        }
        compassJob?.cancel()
        compassJob = viewModelScope.launch {
            CompassFeed.readings(controller.sensorLogger).collect { reading ->
                val waitedS = ((SystemClock.elapsedRealtimeNanos() - enteredNs) / 1_000_000_000L).toInt()
                _ui.update {
                    if (it.phase == RecordPhase.COMPASS) it.copy(compass = reading, compassWaitS = waitedS) else it
                }
            }
        }
    }

    /** Stops feeding the dialog. The preview's sensors keep running: a recording may be taking them over. */
    private fun leaveCompass() {
        compassJob?.cancel()
        compassJob = null
    }

    private fun startRecording(notes: String = "") {
        val current = _ui.value
        _ui.update { it.copy(phase = RecordPhase.STARTING, error = null, compass = null) }
        viewModelScope.launch {
            // On failure the controller also stops a compass preview that was running for this start.
            runCatching { controller.start(current.mode, current.carry, notes) }
                .onFailure { e ->
                    Log.e(TAG, "start failed", e)
                    _ui.update {
                        val reason = e.message ?: e.javaClass.simpleName
                        it.copy(phase = RecordPhase.SETUP, error = "Could not start: $reason")
                    }
                }
        }
    }

    fun onPermissionsDenied(detail: String) {
        _ui.update { it.copy(error = detail) }
    }

    fun annotate(kind: AnnotationKind, note: String = "") {
        val ok = controller.annotate(kind, note)
        val text = when (kind) {
            AnnotationKind.WAYPOINT -> "Waypoint marked"
            AnnotationKind.JUNCTION -> "Junction marked"
            AnnotationKind.CHAMBER -> "Chamber marked"
            AnnotationKind.NOTE -> "Note saved"
            AnnotationKind.LOOP_CLOSED -> "Loop closed at start"
            AnnotationKind.REORIENT -> "Re-orient marked: change the grip now and keep walking straight"
        }
        _ui.update { it.copy(message = if (ok) text else "Not recording") }
    }

    fun pause() = controller.pause()

    fun resume() = controller.resume()

    fun stop() {
        val current = _ui.value
        val tripId = current.recording?.tripId ?: shownTripId ?: return
        if (current.phase != RecordPhase.RECORDING) return
        _ui.update { it.copy(phase = RecordPhase.STOPPING) }
        viewModelScope.launch {
            val stopped = runCatching { controller.stop() }
                .onFailure { Log.e(TAG, "stop failed", it) }
                .getOrNull()
            finish(stopped ?: tripId)
        }
    }

    fun dismissMessage() {
        _ui.update { it.copy(message = null) }
    }

    override fun onCleared() {
        // The preview runs in the controller's scope and would otherwise keep the sensors at the fastest
        // rate with nobody watching.
        if (_ui.value.phase == RecordPhase.COMPASS) controller.stopCompassPreview()
    }

    private suspend fun finish(tripId: Long) {
        // The processor may still be a stub or fail on a short log; the trip is saved either way and
        // can be re-processed from the viewer.
        runCatching { tripProcessor.process(tripId) }
            .onFailure { Log.w(TAG, "processing failed for trip $tripId", it) }
        _ui.update { it.copy(phase = RecordPhase.DONE, finishedTripId = tripId, recording = null) }
    }

    private companion object {
        const val TAG = "RecordViewModel"
    }
}
