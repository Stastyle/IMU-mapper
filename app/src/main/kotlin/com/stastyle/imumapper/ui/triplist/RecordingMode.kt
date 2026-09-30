package com.stastyle.imumapper.ui.triplist

import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.TripMode

/**
 * The mode being recorded when [item] is the recording running now, else null. Only that trip reopens the record
 * screen, in the running recording's mode; any other card, a RECORDING one being saved or left behind by a process
 * that died included, opens the viewer.
 */
internal fun recordingModeFor(item: TripListItem, state: RecordingState): TripMode? =
    if (item.trip.status == TripStatus.RECORDING && state is RecordingState.Recording && state.tripId == item.id) {
        state.mode
    } else {
        null
    }
