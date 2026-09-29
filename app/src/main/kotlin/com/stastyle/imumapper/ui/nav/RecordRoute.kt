package com.stastyle.imumapper.ui.nav

import com.stastyle.imumapper.capture.RecordingState

/**
 * The record route of the running recording, or null when there is none to return to. Everything
 * that returns the user to a recording goes through this, so it opens the mode being recorded rather
 * than one a screen remembered or the Settings default. Null while [RecordingState.Stopping] too: the
 * trip is being saved, and a record route opened then would offer to start another.
 */
fun recordRouteFor(state: RecordingState): String? = when (state) {
    is RecordingState.Recording -> Routes.record(state.mode)
    RecordingState.Idle, RecordingState.Stopping -> null
}
