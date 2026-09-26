package com.stastyle.imumapper.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.render.CameraPreset
import com.stastyle.imumapper.render.ColorMode
import com.stastyle.imumapper.render.PathScene
import com.stastyle.imumapper.render.SceneModel
import com.stastyle.imumapper.ui.common.appContainer

/** 3D path viewer for one trip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    tripId: Long,
    onBack: () -> Unit,
    onOpenDebug: (tripId: Long) -> Unit,
) {
    val container = appContainer()
    val vm: ViewerViewModel = viewModel(key = "viewer-$tripId") {
        ViewerViewModel(tripId, container.tripRepository, container.tripFiles, container.tripProcessor)
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val camera by vm.camera.collectAsStateWithLifecycle()

    // In raw view the main path is the run's path before loop closure and smoothing.
    val result = ui.shownResult
    val overlay = ui.shownOverlay
    // Building the scene walks every point once; only toggles and run changes invalidate it.
    val scene: SceneModel? = remember(result, overlay, ui.options) {
        result?.let { PathScene.build(it, ui.options, overlay) }
    }
    val selectedMarker = ui.selectedMarker
    val selectedIndex = if (scene == null || selectedMarker == null) -1 else scene.markers.indexOf(selectedMarker)
    // The gesture callbacks are created once; the tap handler reads the scene through a state holder
    // so a rebuilt scene (toggle, run change) is used without recreating the pointerInput.
    val latestScene = rememberUpdatedState(scene)
    val gestures = remember(vm) {
        CanvasGestures(
            onViewport = vm::setViewport,
            onOrbit = vm::orbit,
            onZoom = vm::zoom,
            onPan = vm::pan,
            onDoubleTap = vm::fitToPath,
            onTap = { index ->
                val markers = latestScene.value?.markers
                vm.selectMarker(if (markers != null && index in markers.indices) markers[index] else null)
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ui.trip?.name ?: "Trip $tripId", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val run = ui.runs.firstOrNull { it.runId == ui.selectedRunId }
                        if (run != null) {
                            Text(
                                runLabel(run) + if (ui.showRaw && ui.rawResult != null) " · raw" else "",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    RunMenu(ui, vm)
                    PresetMenu(vm)
                    ViewMenu(ui, vm)
                    IconButton(onClick = { onOpenDebug(tripId) }) {
                        Icon(Icons.Filled.BugReport, contentDescription = "Debug")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            ViewerCanvas(
                scene = scene,
                camera = camera,
                selectedMarker = selectedIndex,
                gestures = gestures,
            )
            if (scene == null) {
                StatusOverlay(ui, vm, modifier = Modifier.align(Alignment.Center))
            }
            Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                val marker = ui.selectedMarker
                if (marker != null && selectedIndex >= 0) {
                    MarkerCard(
                        marker = marker,
                        onOpenPhoto = { vm.openPhoto(marker) },
                        onClose = { vm.selectMarker(null) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (ui.error != null && scene != null) {
                    Text(
                        ui.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (result != null) StatsPanel(result.stats)
            }
        }
    }

    if (ui.photo != null || ui.photoLoading || ui.photoError != null) {
        PhotoDialog(
            title = ui.photoTitle,
            bitmap = ui.photo,
            loading = ui.photoLoading,
            error = ui.photoError,
            onDismiss = vm::closePhoto,
        )
    }
}

@Composable
private fun StatusOverlay(ui: ViewerUiState, vm: ViewerViewModel, modifier: Modifier = Modifier) {
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
                Button(onClick = vm::retryProcessing) { Text("Retry") }
            }
            ui.trip?.status == TripStatus.RECORDING -> {
                Text("This trip is still being recorded.", style = MaterialTheme.typography.bodyLarge)
            }
            ui.loading || ui.runs.isNotEmpty() -> CircularProgressIndicator()
            else -> {
                Text("No processed path yet.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Button(onClick = vm::retryProcessing) { Text("Process now") }
            }
        }
    }
}

@Composable
private fun RunMenu(ui: ViewerUiState, vm: ViewerViewModel) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, enabled = ui.runs.isNotEmpty()) {
        Icon(Icons.Filled.Layers, contentDescription = "Runs")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        Text(
            "Show run",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        for (run in ui.runs) {
            DropdownMenuItem(
                text = { Text(runLabel(run)) },
                onClick = {
                    open = false
                    vm.selectRun(run.runId)
                },
                trailingIcon = { if (run.runId == ui.selectedRunId) CheckIcon() },
            )
        }
        if (ui.runs.size > 1) {
            HorizontalDivider()
            Text(
                "Overlay (dimmed)",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            DropdownMenuItem(
                text = { Text("None") },
                onClick = {
                    open = false
                    vm.selectOverlay(null)
                },
                trailingIcon = { if (ui.overlayRunId == null) CheckIcon() },
            )
            for (run in ui.runs) {
                if (run.runId == ui.selectedRunId) continue
                DropdownMenuItem(
                    text = { Text(runLabel(run)) },
                    onClick = {
                        open = false
                        vm.selectOverlay(run.runId)
                    },
                    trailingIcon = { if (run.runId == ui.overlayRunId) CheckIcon() },
                )
            }
        }
    }
}

@Composable
private fun PresetMenu(vm: ViewerViewModel) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.ViewInAr, contentDescription = "View presets")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text("3D") }, onClick = { open = false; vm.applyPreset(CameraPreset.THREE_D) })
        DropdownMenuItem(text = { Text("Top") }, onClick = { open = false; vm.applyPreset(CameraPreset.TOP) })
        DropdownMenuItem(text = { Text("Side") }, onClick = { open = false; vm.applyPreset(CameraPreset.SIDE) })
        HorizontalDivider()
        DropdownMenuItem(text = { Text("Fit to path") }, onClick = { open = false; vm.fitToPath() })
    }
}

@Composable
private fun ViewMenu(ui: ViewerUiState, vm: ViewerViewModel) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.Tune, contentDescription = "View options")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        Text(
            "Colour by",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        ColorModeItem("Elapsed time", ColorMode.TIME, ui.options.colorMode) { vm.setColorMode(it) }
        ColorModeItem("Altitude", ColorMode.ALTITUDE, ui.options.colorMode) { vm.setColorMode(it) }
        ColorModeItem("Source (PDR vs VIO)", ColorMode.SOURCE, ui.options.colorMode) { vm.setColorMode(it) }
        HorizontalDivider()
        ToggleItem("Floor grid", ui.options.showGrid, vm::toggleGrid)
        ToggleItem("Point cloud", ui.options.showPointCloud, vm::togglePointCloud)
        ToggleItem("Markers", ui.options.showMarkers, vm::toggleMarkers)
        HorizontalDivider()
        ToggleItem("Raw path", ui.showRaw, vm::toggleRaw)
        val hint = when {
            ui.rawUnavailable -> "Re-process this run to store its raw path"
            ui.showRaw && ui.rawResult == null && ui.result != null -> "Nothing was corrected in this run"
            else -> "Before loop closure and smoothing; the corrected path is dimmed"
        }
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).widthIn(max = 260.dp),
        )
    }
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

@Composable
private fun CheckIcon() {
    Icon(Icons.Filled.Check, contentDescription = null)
}

private fun runLabel(run: PathResultEntity): String {
    val base = "Run ${run.runId} · v${run.pipelineVersion}"
    return if (run.label.isBlank()) base else "$base · ${run.label}"
}
