package com.stastyle.imumapper.ui.newtrip

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.pipeline.core.TripMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** What the New trip page offers. */
sealed interface NewTripUiState {
    /** A recording runs: the page only returns to it, so a second one in another mode cannot start. */
    data class Recording(val recording: RecordingState.Recording) : NewTripUiState

    /** The last trip is being saved; nothing can start until it is. */
    data object Stopping : NewTripUiState

    /**
     * The mode choice. [mode] is the user's pick, or the Settings default until they pick; null only
     * while the default is still loading, when Continue waits rather than start in a placeholder mode.
     */
    data class Choose(val mode: TripMode?) : NewTripUiState
}

/**
 * State of the Record tab's New trip page. Takes the recorder's state and the "Default trip mode"
 * setting (`UpdatePreferences.defaultTripMode`) as flows rather than their owners, so it runs on the
 * plain JVM.
 */
class NewTripViewModel(
    recording: StateFlow<RecordingState>,
    defaultTripMode: Flow<TripMode>,
) : ViewModel() {

    /** Null follows the default, so a default that loads late or changes in Settings still shows. */
    private val picked = MutableStateFlow<TripMode?>(null)

    val ui: StateFlow<NewTripUiState> =
        combine(recording, defaultTripMode.onStart<TripMode?> { emit(null) }, picked) { state, default, pick ->
            uiState(state, pick ?: default)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), uiState(recording.value, null))

    fun selectMode(mode: TripMode) {
        picked.value = mode
    }

    private fun uiState(state: RecordingState, mode: TripMode?): NewTripUiState = when (state) {
        is RecordingState.Recording -> NewTripUiState.Recording(state)
        RecordingState.Stopping -> NewTripUiState.Stopping
        RecordingState.Idle -> NewTripUiState.Choose(mode)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
