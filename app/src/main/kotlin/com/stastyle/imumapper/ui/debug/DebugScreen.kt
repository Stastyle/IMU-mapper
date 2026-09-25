package com.stastyle.imumapper.ui.debug

import androidx.compose.runtime.Composable
import com.stastyle.imumapper.ui.common.PlaceholderScreen

/** Live sensor plots, log export and re-processing. [tripId] selects a recorded trip, if any. */
@Composable
fun DebugScreen(tripId: Long?, onBack: () -> Unit) {
    PlaceholderScreen(title = "Debug", onBack = onBack)
}
