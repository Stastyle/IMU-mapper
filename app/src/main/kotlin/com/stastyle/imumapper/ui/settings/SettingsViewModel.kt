package com.stastyle.imumapper.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.imumapper.data.CalibrationRepository
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.ui.theme.ThemeMode
import com.stastyle.imumapper.update.UpdateManager
import com.stastyle.imumapper.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings state: the theme and recording defaults from the database and DataStore, the updater from its singleton. */
class SettingsViewModel(
    private val calibration: CalibrationRepository,
    private val updates: UpdateManager,
) : ViewModel() {

    /** Settings → Appearance; null until it has loaded, so no chip shows as chosen before the saved one. */
    val themeMode: StateFlow<ThemeMode?> = updates.preferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val carryPosition: StateFlow<CarryPosition> = calibration.observeCarryPosition()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CarryPosition.HAND)

    val defaultTripMode: StateFlow<TripMode> = updates.preferences.defaultTripMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TripMode.POCKET)

    /** [PipelineConfig.northFromCompass] of the saved calibration. */
    val northFromCompass: StateFlow<Boolean> = calibration.observeConfig().map { it.northFromCompass }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    /**
     * [PipelineConfig.baroConfirmSteps] of the saved calibration, 0 when off; null until it has loaded, so
     * the stepper never shows or steps from a value that is not the saved one.
     */
    val baroConfirmSteps: StateFlow<Int?> = calibration.observeConfig().map { it.baroConfirmSteps }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * [PipelineConfig.baroMaxHeldM] of the saved calibration, which Settings names but does not set (the
     * Debug editor or an applied tuning proposal does); null until it has loaded, so the help text never
     * shows a number that is not the saved one.
     */
    val baroMaxHeldM: StateFlow<Double?> = calibration.observeConfig().map { it.baroMaxHeldM }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val updateState: StateFlow<UpdateState> = updates.state

    val installedVersion: String get() = updates.installedVersion

    /** Non-null when this build cannot be updated from GitHub (debug builds); shown instead of the check button. */
    val updatesUnavailableReason: String? get() = updates.updatesUnavailableReason

    val releasesPageUrl: String get() = updates.releasesPageUrl

    /** Saves the theme; the app re-themes as soon as the new value is stored (ImuMapperApp, MainActivity). */
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            runCatching { updates.preferences.setThemeMode(mode) }
                .onFailure { Log.w(TAG, "theme save failed", it) }
        }
    }

    fun setCarryPosition(position: CarryPosition) {
        viewModelScope.launch {
            runCatching { calibration.saveCarryPosition(position) }
                .onFailure { Log.w(TAG, "carry position save failed", it) }
        }
    }

    /** Saves the switch into the calibration, keeping every other value and the calibration note. */
    fun setNorthFromCompass(on: Boolean) {
        viewModelScope.launch {
            try {
                val current = calibration.getConfig()
                if (current.northFromCompass != on) calibration.saveConfig(current.copy(northFromCompass = on))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "north from compass not saved", e)
            }
        }
    }

    /** Saves the step count into the calibration, keeping every other value and the calibration note. */
    fun setBaroConfirmSteps(steps: Int) {
        viewModelScope.launch {
            try {
                withConfirmSteps(calibration.getConfig(), steps)?.let { calibration.saveConfig(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "steps to confirm a height change not saved", e)
            }
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

/**
 * The calibration Settings saves when the user sets [PipelineConfig.baroConfirmSteps] to [steps]: [current]
 * with only that field changed, or null when [current] already holds it, so nothing is written. A count
 * below 0 is 0 (off). There is no upper clamp here; the stepper stops adding at its own maximum.
 */
internal fun withConfirmSteps(current: PipelineConfig, steps: Int): PipelineConfig? {
    val n = steps.coerceAtLeast(0)
    return if (current.baroConfirmSteps == n) null else current.copy(baroConfirmSteps = n)
}
