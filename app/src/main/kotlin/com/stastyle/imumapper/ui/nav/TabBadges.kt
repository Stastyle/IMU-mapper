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

/**
 * What TalkBack says for the dot on the tab of [route], as the item's state description; null when
 * the tab has no dot. The dot itself is decorative, so this is the only way a TalkBack user learns
 * that a recording runs or an update waits.
 */
fun tabBadgeDescription(route: String, recordingActive: Boolean, updateAvailable: Boolean): String? = when {
    route == Routes.NEW_TRIP && recordingActive -> "Recording in progress"
    route == Routes.SETTINGS && updateAvailable -> "Update available"
    else -> null
}
