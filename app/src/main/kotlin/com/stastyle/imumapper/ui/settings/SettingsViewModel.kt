package com.stastyle.imumapper.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.imumapper.data.CalibrationRepository
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.update.UpdateManager
import com.stastyle.imumapper.update.UpdateState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings state: recording defaults from the database and DataStore, updater state from the singleton. */
class SettingsViewModel(
    private val calibration: CalibrationRepository,
    private val updates: UpdateManager,
) : ViewModel() {

    val carryPosition: StateFlow<CarryPosition> = calibration.observeCarryPosition()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CarryPosition.HAND)

    val defaultTripMode: StateFlow<TripMode> = updates.preferences.defaultTripMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TripMode.POCKET)

    val updateState: StateFlow<UpdateState> = updates.state

    val installedVersion: String get() = updates.installedVersion

    /** Non-null when this build cannot be updated from GitHub (debug builds); shown instead of the check button. */
    val updatesUnavailableReason: String? get() = updates.updatesUnavailableReason

    val releasesPageUrl: String get() = updates.releasesPageUrl

    fun setCarryPosition(position: CarryPosition) {
        viewModelScope.launch {
            runCatching { calibration.saveCarryPosition(position) }
                .onFailure { Log.w(TAG, "carry position save failed", it) }
        }
    }

    fun setDefaultTripMode(mode: TripMode) {
        viewModelScope.launch {
            runCatching { updates.preferences.setDefaultTripMode(mode) }
                .onFailure { Log.w(TAG, "trip mode save failed", it) }
        }
    }

    fun checkForUpdates() = updates.checkNow()

    fun downloadUpdate() = updates.download()

    fun cancelDownload() = updates.cancelDownload()

    fun installUpdate() = updates.install()

    fun dismissUpdate() = updates.dismiss()

    fun retryUpdate() = updates.retry()

    private companion object {
        const val TAG = "SettingsViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
