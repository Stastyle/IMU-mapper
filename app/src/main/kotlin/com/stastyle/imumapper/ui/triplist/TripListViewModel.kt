package com.stastyle.imumapper.ui.triplist

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.imumapper.data.CalibrationRepository
import com.stastyle.imumapper.data.TripExporter
import com.stastyle.imumapper.data.TripImporter
import com.stastyle.imumapper.data.TripRepository
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.process.TripProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Transient screen state next to the trip list itself, which comes straight from the database. */
data class TripListUiState(
    /** Trip ids with an export, re-process or delete in flight; rows show a spinner. */
    val busyTripIds: Set<Long> = emptySet(),
    val importing: Boolean = false,
    /** One-shot: a chooser intent the screen must start, then clear with [TripListViewModel.consumeShare]. */
    val pendingShare: Intent? = null,
    /** One-shot snackbar text. */
    val message: String? = null,
)

class TripListViewModel(
    private val trips: TripRepository,
    private val calibration: CalibrationRepository,
    private val processor: TripProcessor,
    private val exporter: TripExporter,
    private val importer: TripImporter,
) : ViewModel() {

    /** Newest first, as the DAO orders them. */
    val tripList: StateFlow<List<TripEntity>> = trips.observeTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val carryPosition: StateFlow<CarryPosition> = calibration.observeCarryPosition()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CarryPosition.HAND)

    private val _ui = MutableStateFlow(TripListUiState())
    val ui: StateFlow<TripListUiState> = _ui.asStateFlow()

    /** Stores the carry position chosen in the new-trip dialog; the recorder reads it from calibration. */
    fun saveCarryPosition(position: CarryPosition) {
        viewModelScope.launch {
            runCatching { calibration.saveCarryPosition(position) }
                .onFailure { Log.w(TAG, "carry position save failed", it) }
        }
    }

    fun rename(tripId: Long, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            val trip = trips.getTrip(tripId) ?: return@launch
            runCatching { trips.updateTrip(trip.copy(name = name)) }
                .onFailure { showMessage("Rename failed: ${it.describe()}") }
        }
    }

    fun delete(tripId: Long) {
        launchBusy(tripId) {
            runCatching { trips.deleteTrip(tripId) }
                .onFailure { showMessage("Delete failed: ${it.describe()}") }
        }
    }

    fun reprocess(tripId: Long) {
        launchBusy(tripId) {
            runCatching { processor.process(tripId) }
                .onSuccess { showMessage("Processed as run ${it.runId} (${it.label})") }
                .onFailure { showMessage("Processing failed: ${it.describe()}") }
        }
    }

    fun export(tripId: Long) {
        launchBusy(tripId) {
            runCatching { exporter.export(tripId) }
                .onSuccess { export -> _ui.update { it.copy(pendingShare = export.shareIntent) } }
                .onFailure { showMessage("Export failed: ${it.describe()}") }
        }
    }

    fun consumeShare() {
        _ui.update { it.copy(pendingShare = null) }
    }

    fun importTrip(uri: Uri) {
        if (_ui.value.importing) return
        _ui.update { it.copy(importing = true) }
        viewModelScope.launch {
            runCatching { importer.importFrom(uri) }
                .onSuccess { result ->
                    val suffix = if (result.resultCount > 0) " with ${result.resultCount} result(s)" else ""
                    showMessage("Imported \"${result.name}\"$suffix")
                }
                .onFailure { showMessage("Import failed: ${it.describe()}") }
            _ui.update { it.copy(importing = false) }
        }
    }

    fun dismissMessage() {
        _ui.update { it.copy(message = null) }
    }

    private fun launchBusy(tripId: Long, block: suspend () -> Unit) {
        if (tripId in _ui.value.busyTripIds) return
        _ui.update { it.copy(busyTripIds = it.busyTripIds + tripId) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _ui.update { it.copy(busyTripIds = it.busyTripIds - tripId) }
            }
        }
    }

    private fun showMessage(text: String) {
        _ui.update { it.copy(message = text) }
    }

    private fun Throwable.describe(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private companion object {
        const val TAG = "TripListViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
