package com.stastyle.imumapper.ui.triplist

import androidx.compose.runtime.Composable
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.ui.common.PlaceholderScreen

/** Home screen: list of recorded trips, start a new one, and entry points to the other screens. */
@Composable
fun TripListScreen(
    onNewTrip: (TripMode) -> Unit,
    onOpenTrip: (tripId: Long) -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    PlaceholderScreen(title = "Trips")
}
