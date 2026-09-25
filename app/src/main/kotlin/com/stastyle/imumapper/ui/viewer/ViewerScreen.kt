package com.stastyle.imumapper.ui.viewer

import androidx.compose.runtime.Composable
import com.stastyle.imumapper.ui.common.PlaceholderScreen

/** 3D path viewer for one trip. */
@Composable
fun ViewerScreen(
    tripId: Long,
    onBack: () -> Unit,
    onOpenDebug: (tripId: Long) -> Unit,
) {
    PlaceholderScreen(title = "Trip $tripId", onBack = onBack)
}
