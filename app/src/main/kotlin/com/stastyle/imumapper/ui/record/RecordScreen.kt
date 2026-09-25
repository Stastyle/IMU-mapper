package com.stastyle.imumapper.ui.record

import androidx.compose.runtime.Composable
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.ui.common.PlaceholderScreen

/**
 * Recording screen for the given [mode]. Calls [onFinished] with the new trip id once the
 * recording is stopped and saved, or [onCancelled] if nothing was recorded.
 */
@Composable
fun RecordScreen(
    mode: TripMode,
    onFinished: (tripId: Long) -> Unit,
    onCancelled: () -> Unit,
) {
    PlaceholderScreen(title = "Record (${mode.name.lowercase()})", onBack = onCancelled)
}
