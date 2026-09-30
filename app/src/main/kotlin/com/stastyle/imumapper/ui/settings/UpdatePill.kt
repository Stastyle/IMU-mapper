package com.stastyle.imumapper.ui.settings

import com.stastyle.imumapper.ui.common.StatusTone
import com.stastyle.imumapper.update.UpdateState

/**
 * The status pill beside the Updates heading in Settings. Pure, so the mapping is tested on the JVM.
 */
data class UpdatePill(val label: String, val tone: StatusTone) {

    companion object {
        /**
         * The pill for the updater's [state], or null for none. [unavailableReason] (set only for debug
         * builds, which cannot update from GitHub) wins over any state. Idle shows nothing: it follows a
         * quiet check at launch and a "Later" as well as a fresh start, so it never means up to date. An
         * error with a release is a failed download or install; without one, the check itself failed.
         */
        fun of(state: UpdateState, unavailableReason: String?): UpdatePill? {
            if (unavailableReason != null) return UpdatePill("Debug build", StatusTone.Neutral)
            return when (state) {
                UpdateState.Idle -> null
                UpdateState.Checking -> UpdatePill("Checking…", StatusTone.Info)
                is UpdateState.UpToDate -> UpdatePill("Up to date", StatusTone.Success)
                is UpdateState.Available, is UpdateState.Downloading, is UpdateState.ReadyToInstall,
                is UpdateState.NeedsInstallPermission,
                -> UpdatePill("Update available", StatusTone.Info)
                is UpdateState.Error ->
                    if (state.release == null) UpdatePill("Check failed", StatusTone.Error)
                    else UpdatePill("Update failed", StatusTone.Error)
            }
        }
    }
}
