package com.stastyle.imumapper.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stastyle.imumapper.data.SurveyShare
import com.stastyle.imumapper.data.db.PathResultEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.render.CameraPreset
import com.stastyle.imumapper.render.ColorMode
import com.stastyle.imumapper.render.PathScene
import com.stastyle.imumapper.render.SceneModel
import com.stastyle.imumapper.render.SurveyHit
import com.stastyle.imumapper.ui.common.appContainer

/** 3D path viewer for one trip, with Survey mode for measuring between points of the walk. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    tripId: Long,
    onBack: () -> Unit,
    onOpenDebug: (tripId: Long) -> Unit,
) {
    val context = LocalContext.current
    val container = appContainer()
    val vm: ViewerViewModel = viewModel(key = "viewer-$tripId") {
        ViewerViewModel(
            tripId,
            container.tripRepository,
            container.tripFiles,
            container.tripProcessor,
            surveys = container.surveyStore,
        )
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val camera by vm.camera.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val survey = ui.survey?.takeIf { ui.surveyMode }

    var showLegs by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    var showSetAzimuth by remember { mutableStateOf(false) }
    var showNorth by remember { mutableStateOf(false) }
    var stationSheetId by remember { mutableStateOf<Int?>(null) }

    // Survey mode draws the north-corrected path without the overlay run or the scene's markers:
    // stations take the markers' place, so a tap can only mean one thing.
    val result = ui.sceneResult
    val overlay = ui.sceneOverlay
    val options = if (ui.surveyMode) ui.options.copy(showMarkers = false) else ui.options
    // Building the scene walks every point once; toggles, run changes and a new north rotation
    // invalidate it. Survey edits do not: they rebuild only the survey layer.
    val scene: SceneModel? = remember(result, overlay, options) {
        result?.let { PathScene.build(it, options, overlay) }
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
            onSurveyTap = vm::surveyTap,
            onSurveyLongPress = { hit ->
                if (hit is SurveyHit.OnStation) {
                    stationSheetId = hit.stationId
                } else if (hit is SurveyHit.OnPath) {
                    vm.addStationAt(hit.distanceM)
                }
            },
        )
    }

    BackHandler(enabled = ui.surveyMode) { vm.toggleSurvey() }
    LaunchedEffect(ui.surveyMessage) {
        val message = ui.surveyMessage ?: return@LaunchedEffect
        val answer = snackbar.showSnackbar(
            message = message.text,
            actionLabel = if (message.undoable) "Undo" else null,
            // With an action the default would be Indefinite; a long snackbar still goes away.
            duration = if (message.undoable) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (answer == SnackbarResult.ActionPerformed) vm.surveyUndo()
        vm.dismissSurveyMessage()
    }
    LaunchedEffect(ui.pendingCsv) {
        val csv = ui.pendingCsv ?: return@LaunchedEffect
        runCatching { context.startActivity(SurveyShare.intent(context, csv.file, csv.subject, csv.text)) }
        vm.consumeCsvShare()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ViewerTitle(ui, tripId) },
                navigationIcon = {
                    IconButton(onClick = { if (ui.surveyMode) vm.toggleSurvey() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (ui.surveyMode) {
                        if (survey != null) {
                            TextButton(onClick = { showNorth = true }) {
                                Text(SurveyFormat.northChip(survey.north, survey.magnetic), maxLines = 1)
                            }
                            IconButton(onClick = vm::surveyUndo, enabled = survey.state.undo.isNotEmpty()) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                            }
                        }
                        SurveyToggle(surveyMode = true, onToggle = vm::toggleSurvey)
                        SurveyMenu(ui, vm, onDetail = { showDetail = true })
                    } else {
                        RunMenu(ui, vm)
                        PresetMenu(vm)
                        ViewMenu(ui, vm)
                        SurveyToggle(surveyMode = false, onToggle = vm::toggleSurvey)
                        IconButton(onClick = { onOpenDebug(tripId) }) {
                            Icon(Icons.Filled.BugReport, contentDescription = "Debug")
                        }
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
                survey = survey?.layer,
                orbitLocked = ui.surveyMode,
            )
            if (scene == null) {
                StatusOverlay(ui, vm, modifier = Modifier.align(Alignment.Center))
            }
            if (survey != null) {
                SurveyBanners(survey, modifier = Modifier.align(Alignment.TopCenter))
            }
            Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                // The snackbar stacks above the panels instead of covering them (a Scaffold host would):
                // an undoable message stays up for 10 s, over the survey panel's buttons.
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.CenterHorizontally))
                val marker = ui.selectedMarker
                if (!ui.surveyMode && marker != null && selectedIndex >= 0) {
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
                val shown = ui.shownResult
                if (survey != null) {
                    SurveyPanel(
                        survey = survey,
                        startedAtEpochMs = ui.trip?.startedAtEpochMs,
                        onClear = vm::clearSurveySelection,
                        onCursor = vm::setSurveyCursor,
                        onStep = vm::stepSurveyCursor,
                        onAddStation = vm::addStationAtCursor,
                        onMoveHere = vm::moveSelectedStationToCursor,
                        onShowLegs = { showLegs = true },
                        onSetAzimuth = { showSetAzimuth = true },
                    )
                } else if (!ui.surveyMode && shown != null) {
                    StatsPanel(shown.stats)
                }
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

    if (survey != null) {
        if (showLegs) {
            LegsSheet(
                legs = survey.legs,
                totals = survey.totals,
                magnetic = survey.magnetic,
                onSelect = { fromId, toId ->
                    vm.selectLeg(fromId, toId)
                    showLegs = false
                },
                onDismiss = { showLegs = false },
            )
        }
        val sheetStation = stationSheetId?.let { id -> survey.state.doc.stations.firstOrNull { it.id == id } }
        if (sheetStation != null) {
            StationSheet(
                station = sheetStation,
                readOnly = survey.state.readOnly,
                onRename = { name ->
                    vm.renameStation(sheetStation.id, name)
                    stationSheetId = null
                },
                onDelete = {
                    vm.deleteStation(sheetStation.id)
                    stationSheetId = null
                },
                onDismiss = { stationSheetId = null },
            )
        }
        if (showDetail) {
            // Corner detection runs three times; only a doc or path change makes the counts stale.
            val counts = remember(survey.state.doc, survey.geometry) { vm.cornerCounts() }
            DetailDialog(
                current = survey.state.doc.detail,
                counts = counts,
                onSelect = { detail ->
                    vm.setDetail(detail)
                    showDetail = false
                },
                onDismiss = { showDetail = false },
            )
        }
        val names = survey.readout?.names
        if (showSetAzimuth && names != null && names.size >= 2) {
            SetAzimuthDialog(
                fromName = names.first(),
                toName = names.last(),
                magnetic = survey.magnetic,
                preview = vm::azimuthPreview,
                onConfirm = { bearingDeg, backBearing, line ->
                    vm.addReference(bearingDeg, backBearing, line)
                    showSetAzimuth = false
                },
                onDismiss = { showSetAzimuth = false },
            )
        }
        if (showNorth) {
            NorthSheet(
                survey = survey,
                onNudge = vm::nudgeRotation,
                onSet = vm::setRotation,
                onDeleteReference = vm::deleteReference,
                onReset = vm::resetNorth,
                onDismiss = { showNorth = false },
            )
        }
        if (survey.askManualRotation) {
            ManualRotationDialog(
                rotationDeg = survey.state.doc.manualRotationDeg,
                fromRunId = survey.state.doc.manualRotationRunId,
                toRunId = survey.geometry.runId,
                onAnswer = vm::answerManualRotation,
            )
        }
    }
}

@Composable
private fun ViewerTitle(ui: ViewerUiState, tripId: Long) {
    Column {
        Text(ui.trip?.name ?: "Trip $tripId", maxLines = 1, overflow = TextOverflow.Ellipsis)
        val run = ui.runs.firstOrNull { it.runId == ui.selectedRunId }
        val parts = buildList {
            if (ui.surveyMode) add("Survey (beta)")
            if (run != null) add(runLabel(run) + if (ui.showRaw && ui.rawResult != null) " · raw" else "")
        }
        if (parts.isNotEmpty()) {
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The ruler: enters and leaves Survey mode, tinted while it is on. */
@Composable
private fun SurveyToggle(surveyMode: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            Icons.Filled.SquareFoot,
            contentDescription = if (surveyMode) "Leave Survey mode" else "Survey mode",
            tint = if (surveyMode) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}

/** Survey mode's overflow: export, corner detail, the raw toggle and the run list (the other menus hide). */
@Composable
private fun SurveyMenu(ui: ViewerUiState, vm: ViewerViewModel, onDetail: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        val survey = ui.survey
        DropdownMenuItem(
            text = { Text("Export CSV") },
            onClick = {
                open = false
                vm.exportSurveyCsv()
            },
            enabled = survey != null,
        )
        DropdownMenuItem(
            text = { Text("Corner detail…") },
            onClick = {
                open = false
                onDetail()
            },
            enabled = survey != null && !survey.state.readOnly,
        )
        ToggleItem("Raw path", ui.showRaw, vm::toggleRaw)
        if (ui.runs.isNotEmpty()) {
            HorizontalDivider()
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
        }
    }
}

/** A read-only survey and an arbitrary north are said at the top, where the plan is not covered by the panel. */
@Composable
private fun SurveyBanners(survey: SurveyUi, modifier: Modifier = Modifier) {
    val error = survey.error
    val north = survey.northWarning
    if (error == null && north == null) return
    Column(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (error != null) {
            Banner(error, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
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
private fun Banner(text: String, container: Color, content: Color) {
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp, 8.dp))
    }
}

@Composable
private fun StatusOverlay(ui: ViewerUiState, vm: ViewerViewModel, modifier: Modifier = Modifier) {
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
                Button(onClick = vm::retryProcessing) { Text("Retry") }
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
                Button(onClick = vm::retryProcessing) { Text("Retry") }
            }
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
