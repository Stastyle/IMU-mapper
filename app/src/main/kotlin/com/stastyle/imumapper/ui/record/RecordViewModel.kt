package com.stastyle.imumapper.ui.record

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.capture.SensorStats
import com.stastyle.imumapper.data.CalibrationRepository
import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.process.TripProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the recording screen is in its flow. */
enum class RecordPhase {
    /** Choosing the carry position, waiting for Start. */
    SETUP,
    /** Start pressed; trip and log are being created. */
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
)

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
                _ui.update {
                    // Adopt a recording that is already running (screen re-entered) as well as our own.
                    val adopting = it.phase == RecordPhase.SETUP || it.phase == RecordPhase.STARTING ||
                        it.phase == RecordPhase.RECORDING
                    if (adopting) {
                        it.copy(phase = RecordPhase.RECORDING, recording = state, error = null)
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

    /** Called once the required permissions are granted. */
    fun start() {
        val current = _ui.value
        if (current.phase != RecordPhase.SETUP) return
        _ui.update { it.copy(phase = RecordPhase.STARTING, error = null) }
        viewModelScope.launch {
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
            AnnotationKind.REORIENT -> "Re-orienting: walk straight for ten steps"
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
