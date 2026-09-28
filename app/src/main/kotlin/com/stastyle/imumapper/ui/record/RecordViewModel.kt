package com.stastyle.imumapper.ui.record

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.imumapper.capture.CompassLock
import com.stastyle.imumapper.capture.CompassReading
import com.stastyle.imumapper.capture.CompassStatus
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.capture.SensorKind
import com.stastyle.imumapper.capture.SensorStats
import com.stastyle.imumapper.data.CalibrationRepository
import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.LogRecord
import com.stastyle.imumapper.pipeline.core.MagSample
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.RotationSample
import com.stastyle.imumapper.pipeline.core.RotationSource
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.process.TripProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
    val mode: TripMode,
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
) {
    /**
     * Offer to record without a settled north once the wait has gone on for a while: indoors or near
     * metal the compass may never lock, and recording must stay possible.
     */
    val canSkipCompass: Boolean
        get() = phase == RecordPhase.COMPASS && compassWaitS >= SKIP_COMPASS_AFTER_S &&
            compass?.status != CompassStatus.LOCKED

    companion object {
        const val SKIP_COMPASS_AFTER_S = 8
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

    private val _ui = MutableStateFlow(RecordUiState(mode = mode))
    val ui: StateFlow<RecordUiState> = _ui.asStateFlow()

    /** Trip id of the recording we were showing, for when it is stopped from the notification. */
    private var shownTripId: Long? = null

    /** Feeds the compass preview into [RecordUiState.compass]; runs only in [RecordPhase.COMPASS]. */
    private var compassJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = runCatching { calibration.getCarryPosition() }.getOrDefault(CarryPosition.HAND)
            _ui.update { it.copy(carry = saved) }
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
                _ui.update {
                    // Adopt a recording that is already running (screen re-entered) as well as our own.
                    val adopting = it.phase == RecordPhase.SETUP || it.phase == RecordPhase.COMPASS ||
                        it.phase == RecordPhase.STARTING || it.phase == RecordPhase.RECORDING
                    if (adopting) {
                        it.copy(phase = RecordPhase.RECORDING, recording = state, error = null, compass = null)
                    } else {
                        it.copy(recording = state)
                    }
                }
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

    /** "Start recording" in the compass dialog, once north is locked. */
    fun confirmCompass() {
        if (_ui.value.phase != RecordPhase.COMPASS) return
        leaveCompass()
        startRecording()
    }

    /**
     * "Start without compass": records anyway. The pipeline still takes north from the start of the
     * trip, and its diagnostics tell when that north came from a compass that had not settled.
     */
    fun skipCompass() {
        if (_ui.value.phase != RecordPhase.COMPASS) return
        Log.i(TAG, "recording started without a compass lock")
        leaveCompass()
        startRecording()
    }

    /** Cancel or Back in the compass dialog: stops the preview and returns to the setup. */
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
        val lock = CompassLock()
        val enteredNs = SystemClock.elapsedRealtimeNanos()
        _ui.update {
            it.copy(phase = RecordPhase.COMPASS, compass = lock.reading(enteredNs), compassWaitS = 0, error = null)
        }
        compassJob?.cancel()
        compassJob = viewModelScope.launch {
            // The live stream carries every sensor at about 50 Hz; sort it off the main thread. The lock
            // is shared with the publisher below, hence the synchronized blocks.
            launch(Dispatchers.Default) {
                controller.sensorLogger.live.collect { r -> synchronized(lock) { feed(lock, r) } }
            }
            launch {
                controller.sensorLogger.stats.collect { s ->
                    synchronized(lock) { lock.onMagAccuracy(s.of(SensorKind.MAG).accuracy) }
                }
            }
            while (isActive) {
                val now = SystemClock.elapsedRealtimeNanos()
                val reading = synchronized(lock) { lock.reading(now) }
                val waitedS = ((now - enteredNs) / 1_000_000_000L).toInt()
                _ui.update {
                    if (it.phase == RecordPhase.COMPASS) it.copy(compass = reading, compassWaitS = waitedS) else it
                }
                delay(COMPASS_PUBLISH_MS)
            }
        }
    }

    private fun feed(lock: CompassLock, record: LogRecord) {
        when (record) {
            is RotationSample -> when (record.source) {
                RotationSource.GAME -> lock.onGame(record.tNs, record.toQuat())
                RotationSource.FUSED -> lock.onFused(record.tNs, record.toQuat(), record.headingAccuracyRad.toDouble())
            }
            is MagSample -> lock.onMag(record.tNs, record.x.toDouble(), record.y.toDouble(), record.z.toDouble())
            else -> Unit
        }
    }

    /** Stops feeding the dialog. The preview's sensors keep running: a recording may be taking them over. */
    private fun leaveCompass() {
        compassJob?.cancel()
        compassJob = null
    }

    private fun startRecording() {
        val current = _ui.value
        _ui.update { it.copy(phase = RecordPhase.STARTING, error = null, compass = null) }
        viewModelScope.launch {
            // On failure the controller also stops a compass preview that was running for this start.
            runCatching { controller.start(current.mode, current.carry) }
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

        /** About 15 dialog updates a second: smooth enough for the dial, far below the sensor rate. */
        const val COMPASS_PUBLISH_MS = 66L
    }
}
