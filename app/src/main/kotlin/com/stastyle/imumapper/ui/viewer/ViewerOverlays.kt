package com.stastyle.imumapper.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.render.CameraPreset
import com.stastyle.imumapper.render.ColorMode
import com.stastyle.imumapper.render.OrbitCamera
import com.stastyle.imumapper.ui.common.BrandButton
import com.stastyle.imumapper.ui.common.RoundIconButton

/*
 * What sits over the canvas. The drawn map never mirrors, so neither do its controls: callers place these
 * with absolute alignment (AbsoluteAlignment.TopRight and so on), which a right-to-left language leaves alone.
 */

/** The top-right column: fit to path, and outside Survey mode the ruler that enters it. */
@Composable
internal fun CanvasTopButtons(
    surveyMode: Boolean,
    onFit: () -> Unit,
    onSurvey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RoundIconButton(onClick = onFit, icon = Icons.Filled.NearMe, contentDescription = "Fit to path")
        if (!surveyMode) {
            RoundIconButton(onClick = onSurvey, icon = Icons.Filled.SquareFoot, contentDescription = "Survey mode")
        }
    }
}

/**
 * The bottom-right group: the 3D/2D switch, named for where it goes (a top-down camera offers "3D"), the
 * View options behind Layers, and the switch between the Path tab's short canvas and the 3D tab's tall one.
 *
 * A column over the 3D tab's tall canvas. [horizontal] lays it out as a row, for the Path tab: a column there
 * needs 156 dp under the top-right one's 104 dp, more than the short canvas has with a larger Screen zoom or
 * 3-button navigation, and the overlapping 3D/2D button took taps meant for Survey mode. The row keeps its
 * order in a right-to-left language, like the canvas it sits on.
 */
@Composable
internal fun CanvasBottomButtons(
    ui: ViewerUiState,
    vm: ViewerViewModel,
    camera: OrbitCamera,
    fullHeight: Boolean,
    onFullHeight: (Boolean) -> Unit,
    horizontal: Boolean,
    modifier: Modifier = Modifier,
) {
    if (horizontal) {
        Row(modifier = modifier, horizontalArrangement = Arrangement.Absolute.spacedBy(4.dp)) {
            BottomButtons(ui, vm, camera, fullHeight, onFullHeight)
        }
    } else {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BottomButtons(ui, vm, camera, fullHeight, onFullHeight)
        }
    }
}

@Composable
private fun BottomButtons(
    ui: ViewerUiState,
    vm: ViewerViewModel,
    camera: OrbitCamera,
    fullHeight: Boolean,
    onFullHeight: (Boolean) -> Unit,
) {
    val topDown = camera.isTopDown
    RoundIconButton(
        onClick = { vm.applyPreset(if (topDown) CameraPreset.THREE_D else CameraPreset.TOP) },
        text = if (topDown) "3D" else "2D",
        contentDescription = if (topDown) "Switch to 3D view" else "Switch to top view",
    )
    ViewOptionsButton(ui, vm)
    if (fullHeight) {
        RoundIconButton(
            onClick = { onFullHeight(false) },
            icon = Icons.Filled.FullscreenExit,
            contentDescription = "Exit full height",
        )
    } else {
        RoundIconButton(
            onClick = { onFullHeight(true) },
            icon = Icons.Filled.Fullscreen,
            contentDescription = "Full height",
        )
    }
}

/** Layers: how the path is coloured, what else is drawn, and the raw path. */
@Composable
private fun ViewOptionsButton(ui: ViewerUiState, vm: ViewerViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundIconButton(onClick = { open = true }, icon = Icons.Filled.Layers, contentDescription = "View options")
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuCaption("Colour by")
            ColorModeItem("Progress (distance walked)", ColorMode.PROGRESS, ui.options.colorMode, vm::setColorMode)
            ColorModeItem("Elapsed time", ColorMode.TIME, ui.options.colorMode, vm::setColorMode)
            ColorModeItem("Altitude", ColorMode.ALTITUDE, ui.options.colorMode, vm::setColorMode)
            ColorModeItem("Source (PDR vs VIO)", ColorMode.SOURCE, ui.options.colorMode, vm::setColorMode)
            HorizontalDivider()
            ToggleItem("Floor grid", ui.options.showGrid, vm::toggleGrid)
            ToggleItem("Point cloud", ui.options.showPointCloud, vm::togglePointCloud)
            ToggleItem("Markers", ui.options.showMarkers, vm::toggleMarkers)
            HorizontalDivider()
            RawPathItem(ui, vm)
        }
    }
}

/** The Raw path switch and, under it, [rawPathHint]; View options and Survey mode's menu both draw it. */
@Composable
internal fun RawPathItem(ui: ViewerUiState, vm: ViewerViewModel) {
    ToggleItem("Raw path", ui.showRaw, vm::toggleRaw)
    Text(
        rawPathHint(ui),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).widthIn(max = 260.dp),
    )
}

@Composable
internal fun MenuCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
internal fun CheckIcon() {
    Icon(Icons.Filled.Check, contentDescription = null)
}

@Composable
private fun ColorModeItem(label: String, mode: ColorMode, current: ColorMode, onSelect: (ColorMode) -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = { onSelect(mode) },
        trailingIcon = { if (mode == current) CheckIcon() },
    )
}

@Composable
private fun ToggleItem(label: String, checked: Boolean, onToggle: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onToggle,
        trailingIcon = { Switch(checked = checked, onCheckedChange = { onToggle() }) },
    )
}

/** The non-blocking error (a run that could not be read while another is still drawn). */
@Composable
internal fun ErrorLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * A read-only survey and an arbitrary north are said at the top, where the plan is not covered by the
 * panel. An unreadable survey.json's banner offers Start over, so it does not lock the trip's survey; a
 * newer app's file does not, since updating the app edits it and moving it aside would hide it from that app.
 */
@Composable
internal fun SurveyBanners(survey: SurveyUi, onStartOver: () -> Unit, modifier: Modifier = Modifier) {
    val error = survey.error
    val north = survey.northWarning
    if (error == null && north == null) return
    Column(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (error != null) {
            Banner(
                error,
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                actionLabel = if (survey.canStartOver) "Start over" else null,
                onAction = onStartOver,
            )
        }
        if (north != null) {
            Banner(
                "North is arbitrary on this run ($north). Select two stations or a straight stretch and " +
                    "use Set azimuth with a compass bearing.",
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun Banner(
    text: String,
    container: Color,
    content: Color,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp, 8.dp))
            if (actionLabel != null) {
                TextButton(
                    onClick = onAction,
                    colors = ButtonDefaults.textButtonColors(contentColor = content),
                    modifier = Modifier.align(Alignment.End),
                ) { Text(actionLabel) }
            }
        }
    }
}

/** Start over keeps the unreadable file, so the dialog says that nothing is deleted. */
@Composable
internal fun StartOverDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start the survey over?") },
        text = {
            Text(
                "The stations, names and compass readings in survey.json cannot be read. Starting over " +
                    "renames that file and keeps it with the trip, so nothing is deleted, then starts a " +
                    "new survey from the automatic stations. Its edits are saved again.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Start over") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What the canvas shows while there is no path to draw: progress, the error with Retry, or why not. */
@Composable
internal fun StatusOverlay(ui: ViewerUiState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val trip = ui.trip
    Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            ui.processing -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Processing trip…", style = MaterialTheme.typography.bodyLarge)
            }
            ui.error != null -> {
                Text(ui.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                BrandButton(onClick = onRetry) { Text("Retry") }
            }
            trip?.status == TripStatus.RECORDING -> {
                Text("This trip is still being recorded.", style = MaterialTheme.typography.bodyLarge)
            }
            ui.loading || ui.runs.isNotEmpty() -> CircularProgressIndicator()
            trip != null && trip.status == TripStatus.FAILED -> {
                // The run the recording screen started failed; the view model does not repeat it on
                // its own (see ViewerViewModel.maybeProcess), so the stored reason is shown here.
                Text(
                    "Processing failed: " + (trip.lastError ?: "no error message was recorded"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                BrandButton(onClick = onRetry) { Text("Retry") }
            }
            else -> {
                Text("No processed path yet.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                BrandButton(onClick = onRetry) { Text("Process now") }
            }
        }
    }
}
