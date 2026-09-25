package com.stastyle.imumapper.ui.calibration

import androidx.compose.runtime.Composable
import com.stastyle.imumapper.ui.common.PlaceholderScreen

/** Calibration flows: still bias, stride walk, heading offset, square test, ARCore vs PDR. */
@Composable
fun CalibrationScreen(onBack: () -> Unit) {
    PlaceholderScreen(title = "Calibration", onBack = onBack)
}
