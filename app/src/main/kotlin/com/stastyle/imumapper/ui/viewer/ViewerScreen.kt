package com.stastyle.imumapper.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stastyle.imumapper.data.SurveyShare
import com.stastyle.imumapper.render.CameraPreset
import com.stastyle.imumapper.render.PathScene
import com.stastyle.imumapper.render.SceneModel
import com.stastyle.imumapper.render.SurveyHit
import com.stastyle.imumapper.ui.common.AppScaffold
import com.stastyle.imumapper.ui.common.AppTopBar
import com.stastyle.imumapper.ui.common.GridScaleChip
import com.stastyle.imumapper.ui.common.SegmentedTabs
import com.stastyle.imumapper.ui.common.appContainer
import com.stastyle.imumapper.ui.theme.imuColors
import com.stastyle.imumapper.ui.triplist.TripFormat

/** The Path tab's canvas takes this share of the height under the tabs; the cards scroll in the rest. */
private const val PATH_CANVAS_WEIGHT = 0.42f

/** Room the bottom-right buttons need beside a card over the 3D canvas, so they stay tappable. */
private val OverlayEndClearance = 64.dp
private val CanvasShape = RoundedCornerShape(20.dp)

/**
 * One list for every composition: the content under the top bar recomposes on each camera frame while the path
 * is dragged, and a new list each time would make the tabs recompose with it.
 */
private val TAB_LABELS = ViewerTab.entries.map { it.label }

/**
 * One trip's path, in tabs: Path (a short canvas over the trip summary and elevation profile), 3D (the canvas at
 * full height), Graph (the profile large with height numbers) and Details (runs, raw path, facts and actions).
 * Only one canvas is ever composed. Survey mode, for measuring between points of the walk, hides the tabs and
 * gives the canvas the whole height under its own top bar.
 */
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
            shareTrip = { id ->
                val intent = container.tripExporter.export(id).shareIntent
                ShareRequest { ctx -> ctx.startActivity(intent) }
            },
        )
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val camera by vm.camera.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val survey = ui.survey?.takeIf { ui.surveyMode }
    var tabIndex by rememberSaveable { mutableIntStateOf(ViewerTab.PATH.ordinal) }
    val tab = ViewerTab.entries[tabIndex]

    var showRuns by remember { mutableStateOf(false) }
    var showLegs by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    var showSetAzimuth by remember { mutableStateOf(false) }
    var showNorth by remember { mutableStateOf(false) }
    var confirmStartOver by remember { mutableStateOf(false) }
    var stationSheetId by remember { mutableStateOf<Int?>(null) }
    // Stations drawn on one spot under a long press: the user picks which one the sheet is for.
    var stationChoiceIds by remember { mutableStateOf<List<Int>?>(null) }

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
    val shownMarker = selectedMarker?.takeIf { !ui.surveyMode && selectedIndex >= 0 }
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
            onSurveyDoubleTap = vm::surveyDoubleTap,
            onSurveyLongPress = { hit ->
                when (hit) {
                    is SurveyHit.OnStation -> stationSheetId = hit.stationId
                    is SurveyHit.OnStations -> stationChoiceIds = hit.stationIds
                    is SurveyHit.OnPath -> vm.addStationAt(hit.distanceM)
                    SurveyHit.Miss -> Unit
                }
            },
        )
    }
    // Top view, Side view and Fit to path chosen from the Graph or Details tab bring the canvas back to show it.
    val showView = {
        if (tab == ViewerTab.GRAPH || tab == ViewerTab.DETAILS) tabIndex = ViewerTab.PATH.ordinal
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
    LaunchedEffect(ui.message) {
        val text = ui.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        vm.dismissMessage()
    }
    LaunchedEffect(ui.pendingCsv) {
        val csv = ui.pendingCsv ?: return@LaunchedEffect
        runCatching { context.startActivity(SurveyShare.intent(context, csv.file, csv.subject, csv.text)) }
        vm.consumeCsvShare()
    }
    LaunchedEffect(ui.pendingShare) {
        val request = ui.pendingShare ?: return@LaunchedEffect
        runCatching { request.launch(context) }
        vm.consumeShare()
    }

    AppScaffold(
        topBar = {
            ViewerTopBar(
                ui = ui,
                tripId = tripId,
                survey = survey,
                vm = vm,
                onBack = onBack,
                onShowRuns = { showRuns = true },
                onShowView = showView,
                onOpenDebug = { onOpenDebug(tripId) },
                onShowNorth = { showNorth = true },
                onShowDetail = { showDetail = true },
            )
        },
        // Outside Survey mode Share's result shows on every tab; in Survey mode the host sits on the canvas,
        // above the panel, instead (below).
        snackbarHost = { if (!ui.surveyMode) SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val tabsShown = !ui.surveyMode && scene != null
            if (tabsShown) {
                SegmentedTabs(
                    tabs = TAB_LABELS,
                    selected = tabIndex,
                    onSelect = { tabIndex = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            // Null while the canvas has the whole height: Survey mode, and while there is no path to tab through.
            val shownTab = tab.takeIf { tabsShown }
            val error = ui.error?.takeIf { scene != null }
            if (error != null && (shownTab == ViewerTab.GRAPH || shownTab == ViewerTab.DETAILS)) ErrorLine(error)
            val gridChip = gridChipText(scene, options)
            val canvasModifier = when (shownTab) {
                null, ViewerTab.THREE_D -> Modifier.weight(1f).fillMaxWidth()
                ViewerTab.PATH -> Modifier
                    .weight(PATH_CANVAS_WEIGHT)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(CanvasShape)
                    .border(1.dp, MaterialTheme.imuColors.cardBorder, CanvasShape)
                ViewerTab.GRAPH, ViewerTab.DETAILS -> null
            }
            if (canvasModifier != null) {
                Box(modifier = canvasModifier) {
                    ViewerCanvas(
                        scene = scene,
                        camera = camera,
                        selectedMarker = selectedIndex,
                        gestures = gestures,
                        survey = survey?.layer,
                        orbitLocked = ui.surveyMode,
                    )
                    if (scene == null) {
                        StatusOverlay(ui, onRetry = vm::retryProcessing, modifier = Modifier.align(Alignment.Center))
                    }
                    if (survey != null) {
                        SurveyBanners(
                            survey,
                            onStartOver = { confirmStartOver = true },
                            // Clear of the fit button in the top-right corner.
                            modifier = Modifier.align(Alignment.TopCenter).absolutePadding(right = 52.dp),
                        )
                    }
                    if (scene != null) {
                        CanvasTopButtons(
                            surveyMode = ui.surveyMode,
                            onFit = vm::fitToPath,
                            onSurvey = vm::toggleSurvey,
                            modifier = Modifier.align(AbsoluteAlignment.TopRight).padding(4.dp),
                        )
                        if (!ui.surveyMode) {
                            CanvasBottomButtons(
                                ui = ui,
                                vm = vm,
                                camera = camera,
                                fullHeight = shownTab == ViewerTab.THREE_D,
                                onFullHeight = { full ->
                                    tabIndex = if (full) ViewerTab.THREE_D.ordinal else ViewerTab.PATH.ordinal
                                },
                                modifier = Modifier.align(AbsoluteAlignment.BottomRight).padding(4.dp),
                            )
                        }
                    }
                    when {
                        ui.surveyMode -> Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                            // The snackbar stacks above the panel instead of covering it (a Scaffold host
                            // would): an undoable message stays up for 10 s, over the panel's buttons.
                            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.CenterHorizontally))
                            if (gridChip != null) {
                                GridScaleChip(
                                    gridChip,
                                    Modifier.align(AbsoluteAlignment.Left).absolutePadding(left = 12.dp, bottom = 8.dp),
                                )
                            }
                            if (error != null) ErrorLine(error)
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
                                    // The panel covers the canvas's bottom, so the camera frames the plan above
                                    // it. The snackbar and the grid chip are left out: the view jumping when a
                                    // message times out would move a station out from under a finger about to
                                    // tap it.
                                    modifier = Modifier.onSizeChanged { vm.setBottomInset(it.height.toFloat()) },
                                )
                            }
                        }
                        shownTab == ViewerTab.THREE_D -> Column(
                            modifier = Modifier
                                .align(AbsoluteAlignment.BottomLeft)
                                .fillMaxWidth()
                                .absolutePadding(left = 8.dp, right = OverlayEndClearance, bottom = 8.dp),
                        ) {
                            if (error != null) ErrorLine(error)
                            if (shownMarker != null) {
                                MarkerCard(
                                    marker = shownMarker,
                                    onOpenPhoto = { vm.openPhoto(shownMarker) },
                                    onClose = { vm.selectMarker(null) },
                                )
                            } else if (gridChip != null) {
                                // The column's own start is the right edge in a right-to-left language.
                                GridScaleChip(
                                    gridChip,
                                    Modifier.align(AbsoluteAlignment.Left).absolutePadding(left = 4.dp, bottom = 4.dp),
                                )
                            }
                        }
                        scene != null && gridChip != null -> GridScaleChip(
                            gridChip,
                            Modifier.align(AbsoluteAlignment.BottomLeft).absolutePadding(left = 12.dp, bottom = 16.dp),
                        )
                    }
                }
            }
            val shown = ui.shownResult
            when (shownTab) {
                ViewerTab.PATH -> Column(modifier = Modifier.weight(1f - PATH_CANVAS_WEIGHT).fillMaxWidth()) {
                    // Pinned under the canvas, above the scrolling cards: the canvas neither shrinks nor is covered.
                    if (shownMarker != null) {
                        MarkerCard(
                            marker = shownMarker,
                            onOpenPhoto = { vm.openPhoto(shownMarker) },
                            onClose = { vm.selectMarker(null) },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                        )
                    }
                    if (error != null) ErrorLine(error)
                    if (shown != null) PathTabCards(shown, Modifier.weight(1f).fillMaxWidth())
                }
                ViewerTab.GRAPH -> if (shown != null) GraphTab(shown, Modifier.weight(1f).fillMaxWidth())
                ViewerTab.DETAILS -> DetailsTab(
                    ui = ui,
                    onSelectRun = vm::selectRun,
                    onSelectOverlay = vm::selectOverlay,
                    onToggleRaw = vm::toggleRaw,
                    onSurveyMode = vm::toggleSurvey,
                    onExport = if (ui.canShare) vm::share else null,
                    onOpenDebug = { onOpenDebug(tripId) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                ViewerTab.THREE_D, null -> Unit
            }
        }
    }

    if (showRuns) {
        RunsDialog(
            ui = ui,
            onSelectRun = vm::selectRun,
            onSelectOverlay = vm::selectOverlay,
            onDismiss = { showRuns = false },
        )
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
        val choices = stationChoiceIds?.let { ids -> survey.state.doc.stations.filter { it.id in ids } }.orEmpty()
        if (choices.isNotEmpty()) {
            StationChooser(
                stations = choices,
                onChoose = { id ->
                    stationChoiceIds = null
                    stationSheetId = id
                },
                onDismiss = { stationChoiceIds = null },
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
        if (confirmStartOver && survey.canStartOver) {
            StartOverDialog(
                onConfirm = {
                    confirmStartOver = false
                    vm.startSurveyOver()
                },
                onDismiss = { confirmStartOver = false },
            )
        }
    }
}

/**
 * The trip's name over the date, the run and "raw". Outside Survey mode: Share (when an exporter is wired) and
 * the overflow. Inside it the bar keeps Survey mode's own actions: the north chip, Undo, the ruler that leaves
 * it, and its menu.
 */
@Composable
private fun ViewerTopBar(
    ui: ViewerUiState,
    tripId: Long,
    survey: SurveyUi?,
    vm: ViewerViewModel,
    onBack: () -> Unit,
    onShowRuns: () -> Unit,
    onShowView: () -> Unit,
    onOpenDebug: () -> Unit,
    onShowNorth: () -> Unit,
    onShowDetail: () -> Unit,
) {
    val run = ui.runs.firstOrNull { it.runId == ui.selectedRunId }
    val parts = viewerSubtitleParts(
        surveyMode = ui.surveyMode,
        date = ui.trip?.let { TripFormat.date(it.startedAtEpochMs) },
        run = run?.let(::runLabel),
        raw = ui.showRaw && ui.rawResult != null,
    )
    val subtitle: (@Composable () -> Unit)? = parts?.let { p -> @Composable { SubtitleLine(p) } }
    AppTopBar(
        title = ui.trip?.name ?: "Trip $tripId",
        subtitle = subtitle,
        onBack = { if (ui.surveyMode) vm.toggleSurvey() else onBack() },
        actions = {
            if (ui.surveyMode) {
                if (survey != null) {
                    TextButton(onClick = onShowNorth, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(SurveyFormat.northChipShort(survey.north, survey.magnetic), maxLines = 1)
                    }
                    IconButton(onClick = vm::surveyUndo, enabled = survey.state.undo.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                }
                SurveyToggle(surveyMode = true, onToggle = vm::toggleSurvey)
                SurveyMenu(ui, vm, onDetail = onShowDetail)
            } else {
                if (ui.canShare) {
                    IconButton(
                        onClick = vm::share,
                        enabled = ui.shareEnabled,
                        modifier = Modifier.semantics {
                            contentDescription = "Share trip"
                            if (ui.busy) stateDescription = "Preparing the ZIP"
                        },
                    ) {
                        if (ui.busy) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Share, contentDescription = null)
                        }
                    }
                }
                ViewerOverflow(
                    ui = ui,
                    onRuns = onShowRuns,
                    onPreset = { preset ->
                        onShowView()
                        vm.applyPreset(preset)
                    },
                    onFit = {
                        onShowView()
                        vm.fitToPath()
                    },
                    onSurvey = vm::toggleSurvey,
                    onDebug = onOpenDebug,
                )
            }
        },
    )
}

/**
 * The subtitle's parts as separate texts, each giving way in its turn: the date is cut first (and left out
 * when barely a letter of it would show), then the run label; the separators and "raw" never are. A Row
 * cannot do this, since it measures unweighted children in order and weights split the space, so this
 * measures by priority and places in reading order (mirrored in a right-to-left language). TalkBack reads the
 * whole line.
 */
@Composable
private fun SubtitleLine(parts: SubtitleParts) {
    Layout(
        content = {
            Text(parts.date.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(SubtitleParts.SEPARATOR, maxLines = 1)
            Text(parts.label.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(SubtitleParts.SEPARATOR, maxLines = 1)
            Text(SubtitleParts.RAW, maxLines = 1)
        },
        modifier = Modifier.clearAndSetSemantics { contentDescription = parts.text },
    ) { measurables, constraints ->
        val free = constraints.copy(minWidth = 0, minHeight = 0)
        var remaining = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE / 2
        fun measure(index: Int): Placeable =
            measurables[index].measure(free.copy(maxWidth = remaining.coerceAtLeast(0))).also { remaining -= it.width }
        val hasDate = parts.date != null
        val hasLabel = parts.label != null
        val rawSeparator = if (parts.raw) measure(3) else null
        val raw = if (parts.raw) measure(4) else null
        val dateSeparator = if (hasDate && hasLabel) measure(1) else null
        val label = if (hasLabel) measure(2) else null
        val date = if (hasDate && remaining >= MIN_DATE_WIDTH.roundToPx()) measure(0) else null
        val shown = listOfNotNull(date, dateSeparator.takeIf { date != null }, label, rawSeparator, raw)
        val height = shown.maxOfOrNull { it.height } ?: 0
        layout(shown.sumOf { it.width }, height) {
            var x = 0
            for (p in shown) {
                p.placeRelative(x, (height - p.height) / 2)
                x += p.width
            }
        }
    }
}

/** Less room than this for the date and it is left out rather than shown as a letter and an ellipsis. */
private val MIN_DATE_WIDTH = 32.dp

/**
 * The overflow outside Survey mode. The views and Fit act on the canvas, so from the Graph or Details tab they
 * bring it back first (the screen's onPreset and onFit do that).
 */
@Composable
private fun ViewerOverflow(
    ui: ViewerUiState,
    onRuns: () -> Unit,
    onPreset: (CameraPreset) -> Unit,
    onFit: () -> Unit,
    onSurvey: () -> Unit,
    onDebug: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val hasPath = ui.result != null
    fun choose(action: () -> Unit): () -> Unit = {
        open = false
        action()
    }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Runs…") }, onClick = choose(onRuns), enabled = ui.runs.isNotEmpty())
            DropdownMenuItem(
                text = { Text("Top view") },
                onClick = choose { onPreset(CameraPreset.TOP) },
                enabled = hasPath,
            )
            DropdownMenuItem(
                text = { Text("Side view") },
                onClick = choose { onPreset(CameraPreset.SIDE) },
                enabled = hasPath,
            )
            DropdownMenuItem(text = { Text("Fit to path") }, onClick = choose(onFit), enabled = hasPath)
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Survey mode") }, onClick = choose(onSurvey))
            DropdownMenuItem(text = { Text("Debug") }, onClick = choose(onDebug))
        }
    }
}

/** The ruler inside Survey mode's top bar: tinted, and it leaves the mode. */
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
    Box {
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
            RawPathItem(ui, vm)
            if (ui.runs.isNotEmpty()) {
                HorizontalDivider()
                MenuCaption("Show run")
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
}
