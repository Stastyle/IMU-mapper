package com.stastyle.imumapper.ui.nav

import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.update.UpdateState

/**
 * Whether the Record tab carries its dot: while a recording runs, and while it is being saved, since
 * a new trip cannot start until then.
 */
fun recordingBadge(state: RecordingState): Boolean = when (state) {
    is RecordingState.Recording, RecordingState.Stopping -> true
    RecordingState.Idle -> false
}

/**
 * Whether the Settings tab carries its dot: in the states the trip list's update banner shows, where
 * there is something for the user to act on.
 */
fun updateBadge(state: UpdateState): Boolean = when (state) {
    is UpdateState.Available, is UpdateState.Downloading, is UpdateState.ReadyToInstall,
    is UpdateState.NeedsInstallPermission,
    -> true
    UpdateState.Idle, UpdateState.Checking, is UpdateState.UpToDate, is UpdateState.Error -> false
}
