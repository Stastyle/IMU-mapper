package com.stastyle.imumapper.ui.nav

import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.update.UpdateState

/**
 * What the Record tab's dot says, null when it has none. The dot shows while a recording runs, and while
 * it is being saved, since a new trip cannot start until then; TalkBack tells the two apart, because a
 * trip being saved has ended and cannot be returned to.
 */
fun recordingBadge(state: RecordingState): String? = when (state) {
    is RecordingState.Recording -> "Recording in progress"
    RecordingState.Stopping -> "Saving the last trip"
    RecordingState.Idle -> null
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
 * the tab has no dot. [recording] is [recordingBadge]'s text. The dot itself is decorative, so this is
 * the only way a TalkBack user learns that a recording runs or an update waits.
 */
fun tabBadgeDescription(route: String, recording: String?, updateAvailable: Boolean): String? = when {
    route == Routes.NEW_TRIP && recording != null -> recording
    route == Routes.SETTINGS && updateAvailable -> "Update available"
    else -> null
}
