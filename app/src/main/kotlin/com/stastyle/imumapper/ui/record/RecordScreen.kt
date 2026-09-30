package com.stastyle.imumapper.ui.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SignalCellular0Bar
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularAlt1Bar
import androidx.compose.material.icons.filled.SignalCellularAlt2Bar
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stastyle.imumapper.R
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.capture.SensorHealth
import com.stastyle.imumapper.capture.SensorKind
import com.stastyle.imumapper.capture.SensorStats
import com.stastyle.imumapper.capture.modeLabel
import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.ui.common.AppScaffold
import com.stastyle.imumapper.ui.common.AppTopBar
import com.stastyle.imumapper.ui.common.BatteryIndicator
import com.stastyle.imumapper.ui.common.BrandButton
import com.stastyle.imumapper.ui.common.BrandFilterChip
import com.stastyle.imumapper.ui.common.GlassCard
import com.stastyle.imumapper.ui.common.RoundIconButton
import com.stastyle.imumapper.ui.common.SectionHeader
import com.stastyle.imumapper.ui.common.StatGrid
import com.stastyle.imumapper.ui.common.StatTileData
import com.stastyle.imumapper.ui.common.appContainer
import com.stastyle.imumapper.ui.common.findActivity
import com.stastyle.imumapper.ui.common.rememberBatteryState
import com.stastyle.imumapper.ui.theme.imuColors
import com.stastyle.imumapper.ui.triplist.TripFormat

/**
 * Recording screen for the given [mode]. Calls [onFinished] with the new trip id once the
 * recording is stopped and saved, or [onCancelled] if nothing was recorded. From the recording on,
 * everything it shows comes from the adopted recording, not from [mode].
 */
@Composable
fun RecordScreen(
    mode: TripMode,
    onFinished: (tripId: Long) -> Unit,
    onCancelled: () -> Unit,
) {
    val context = LocalContext.current
    val container = appContainer()
    val controller = remember(context) { RecordingController.get(context) }
    val vm: RecordViewModel = viewModel(key = "record-${mode.name}") {
        RecordViewModel(mode, controller, container.calibrationRepository, container.tripProcessor)
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showStopDialog by rememberSaveable { mutableStateOf(false) }
    var showNoteDialog by rememberSaveable { mutableStateOf(false) }
    var showLoopDialog by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val missing = requiredPermissions(mode).filter { result[it] != true && !isGranted(context, it) }
        if (missing.isEmpty()) vm.start() else vm.onPermissionsDenied(deniedMessage(missing))
    }
    val onStartClick: () -> Unit = {
        val toRequest = requestedPermissions(mode).filter { !isGranted(context, it) }
        if (toRequest.isEmpty()) vm.start() else permissionLauncher.launch(toRequest.toTypedArray())
    }

    // The compass preview runs the sensors at the fastest rate; like the calibration flows it must not
    // outlive the screen or keep running while the app is in the background, where nobody can tap Start.
    // A recreation (split screen or pop-up view changes the screen layout, which the manifest does not
    // handle) stops and disposes the screen too, but the view model and its wait survive it: keep them.
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) vm.cancelCompass()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (activity?.isChangingConfigurations != true) vm.cancelCompass()
        }
    }

    val busy = ui.phase != RecordPhase.SETUP && ui.phase != RecordPhase.DONE
    val view = LocalView.current
    DisposableEffect(view, busy) {
        // Screen stays on while recording so the annotation buttons remain reachable, and while the
        // compass settles so the wait is not lost to the screen timeout.
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = busy) {
        when (ui.phase) {
            RecordPhase.RECORDING -> showStopDialog = true
            RecordPhase.COMPASS -> vm.cancelCompass()
            else -> Unit
        }
    }
    LaunchedEffect(ui.finishedTripId) {
        val id = ui.finishedTripId
        if (id != null) onFinished(id)
    }
    LaunchedEffect(ui.message) {
        val text = ui.message
        if (text != null) {
            snackbar.showSnackbar(text)
            vm.dismissMessage()
        }
    }

    val recording = ui.recording.takeIf { ui.phase == RecordPhase.RECORDING }
    AppScaffold(
        topBar = { RecordTopBar(ui, onBack = { if (ui.phase == RecordPhase.SETUP) onCancelled() }) },
        // The marks and controls are the bottom bar, so they stay put whatever scrolls above them and
        // the snackbar confirming a mark rises above them instead of covering them.
        bottomBar = {
            if (recording != null) {
                RecordControls(
                    paused = recording.paused,
                    onAnnotate = vm::annotate,
                    onNote = { showNoteDialog = true },
                    onLoop = { showLoopDialog = true },
                    onPause = vm::pause,
                    onResume = vm::resume,
                    onStop = { showStopDialog = true },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (ui.phase) {
                RecordPhase.SETUP, RecordPhase.COMPASS, RecordPhase.STARTING -> SetupContent(
                    ui = ui,
                    onCarry = vm::setCarry,
                    onStart = onStartClick,
                )
                RecordPhase.RECORDING -> if (recording != null) RecordingContent(ui, recording, controller)
                RecordPhase.STOPPING, RecordPhase.DONE -> BusyContent("Saving and processing the trip…")
            }
        }
    }

    if (ui.phase == RecordPhase.COMPASS) {
        CompassDialog(
            reading = ui.compass,
            hint = startPose(ui) + ", away from metal.",
            startLabel = "Start recording",
            onStart = vm::confirmCompass,
            onCancel = vm::cancelCompass,
            canSkip = ui.canSkipCompass,
            onSkip = vm::skipCompass,
        )
    }
    // The dialogs belong to the running recording; one stopped from the notification takes them along.
    if (recording == null) return
    if (showStopDialog) {
        ConfirmDialog(
            title = "Stop recording?",
            text = "The trip is saved and processed. You cannot resume it afterwards.",
            confirmLabel = "Stop",
            destructive = true,
            onConfirm = {
                showStopDialog = false
                vm.stop()
            },
            onDismiss = { showStopDialog = false },
        )
    }
    if (showNoteDialog) {
        NoteDialog(
            onSave = { note ->
                showNoteDialog = false
                vm.annotate(AnnotationKind.NOTE, note)
            },
            onDismiss = { showNoteDialog = false },
        )
    }
    if (showLoopDialog) {
        ConfirmDialog(
            title = "Back at the start?",
            text = "Marks this point as the trip start again so the path can be closed into a loop.",
            confirmLabel = "Yes, I am at the start",
            onConfirm = {
                showLoopDialog = false
                vm.annotate(AnnotationKind.LOOP_CLOSED, "")
            },
            onDismiss = { showLoopDialog = false },
        )
    }
}

/**
 * "Ready to record" with a back arrow before the start; "Recording" or "Paused" without one once it runs
 * (Back then asks whether to stop). The subtitle names what is recorded, read from the adopted recording.
 */
@Composable
private fun RecordTopBar(ui: RecordUiState, onBack: () -> Unit) {
    when (ui.phase) {
        RecordPhase.SETUP, RecordPhase.COMPASS, RecordPhase.STARTING ->
            AppTopBar(title = "Ready to record", subtitle = modeLabel(ui.mode), onBack = onBack)
        RecordPhase.RECORDING -> AppTopBar(
            title = if (ui.recording?.paused == true) "Paused" else "Recording",
            subtitle = RecordStatus.headerSubtitle(ui.mode, ui.carry),
        )
        RecordPhase.STOPPING, RecordPhase.DONE ->
            AppTopBar(title = "Saving trip", subtitle = RecordStatus.headerSubtitle(ui.mode, ui.carry))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupContent(
    ui: RecordUiState,
    onCarry: (CarryPosition) -> Unit,
    onStart: () -> Unit,
) {
    val starting = ui.phase == RecordPhase.STARTING
    val inputsDisabled = starting || ui.phase == RecordPhase.COMPASS
    Column(modifier = Modifier.fillMaxSize()) {
        // The guidance scrolls; the error and Start stay pinned below it, so both are always in reach.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(title = "Before you start", icon = Icons.Filled.TipsAndUpdates)
                    Text(modeDescription(ui.mode), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        northText(ui),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column {
                SectionHeader(title = "Phone carried in")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (position in CarryPosition.entries) {
                        BrandFilterChip(
                            selected = ui.carry == position,
                            onClick = { onCarry(position) },
                            label = TripFormat.carryLabel(position),
                            enabled = !inputsDisabled,
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.error != null) {
                Text(ui.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            BrandButton(
                onClick = onStart,
                enabled = !inputsDisabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                if (starting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .semantics { contentDescription = "Starting the recording" },
                        color = MaterialTheme.colorScheme.onSurface,
                        strokeWidth = 3.dp,
                    )
                } else {
                    Text("Start recording", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/**
 * The live part above the pinned controls. Camera modes split it between the preview and the cards; the
 * preview stays composed for the whole recording, since leaving composition would restart ARCore, and keeps
 * square corners because a Compose clip cannot round a SurfaceView. Pocket gives the cards all of it.
 */
@Composable
private fun RecordingContent(ui: RecordUiState, recording: RecordingState.Recording, controller: RecordingController) {
    val pocket = ui.mode == TripMode.POCKET
    Column(modifier = Modifier.fillMaxSize()) {
        if (!pocket) {
            ArSection(mode = ui.mode, controller = controller, modifier = Modifier.fillMaxWidth().weight(1f))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(ui, recording)
            SensorTiles(ui.stats, recording.elapsedNs)
            StatsCard(ui, recording, pocket)
        }
    }
}

/**
 * The status headline with its dot, what is recorded and how it is processed, and the battery. Tapping it
 * opens the full per-sensor list with rates, which used to be always on screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusCard(ui: RecordUiState, recording: RecordingState.Recording) {
    val status = RecordStatus.status(ui.stats, recording)
    var expanded by rememberSaveable { mutableStateOf(false) }
    val battery = rememberBatteryState()
    val scheme = MaterialTheme.colorScheme
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "Hide sensor list" else "Show all sensors",
                ) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Decorative: the headline says the same in words.
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(toneColor(status.tone)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    status.text,
                    // Follows the words, not the layout: in Hebrew "3 sensors not delivering" must not put its
                    // count after the sentence.
                    style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                    color = if (status.tone == RecordTone.Good) scheme.onSurface else toneColor(status.tone),
                )
                Text(
                    RecordStatus.statusSubtitle(ui.mode, ui.carry),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            BatteryIndicator(battery)
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
            )
        }
        if (status.detail != null) {
            Text(
                status.detail,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (kind in SensorKind.entries) SensorChip(ui.stats.of(kind))
            }
        }
    }
}

@Composable
private fun SensorChip(health: SensorHealth) {
    val scheme = MaterialTheme.colorScheme
    val (background, foreground) = when (RecordStatus.chipTone(health)) {
        RecordTone.Bad -> scheme.errorContainer to scheme.onErrorContainer
        RecordTone.Good -> scheme.secondaryContainer to scheme.onSecondaryContainer
        RecordTone.Neutral, RecordTone.Warning -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = background, contentColor = foreground) {
        Text(
            text = RecordStatus.chipText(health),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** IMU, Steps, Heading and Mag, each a state word in its colour, read by TalkBack as one sentence. */
@Composable
private fun SensorTiles(stats: SensorStats, activeNs: Long) {
    val magnet = ImageVector.vectorResource(R.drawable.ic_magnet)
    val tiles = listOf(
        sensorTile("IMU", Icons.Filled.Sensors, RecordStatus.imuTile(stats, activeNs)),
        sensorTile("Steps", Icons.AutoMirrored.Filled.DirectionsWalk, RecordStatus.stepsTile(stats)),
        sensorTile("Heading", Icons.Filled.Explore, RecordStatus.headingTile(stats, activeNs)),
        sensorTile("Mag", magnet, RecordStatus.magTile(stats, activeNs)),
    )
    StatGrid(tiles = tiles, maxColumns = 4)
}

@Composable
private fun sensorTile(label: String, icon: ImageVector, state: SensorTileState): StatTileData {
    val valueIcon = when {
        state.bars != null -> barsIcon(state.bars)
        state.tone == RecordTone.Good -> Icons.Filled.Check
        state.tone == RecordTone.Warning -> Icons.Filled.WarningAmber
        state.tone == RecordTone.Bad -> Icons.Filled.ErrorOutline
        else -> null
    }
    return StatTileData(
        label = label,
        value = state.value,
        icon = icon,
        detail = state.detail,
        contentDescription = state.spoken,
        valueColor = toneColor(state.tone),
        valueIcon = valueIcon,
    )
}

private fun barsIcon(bars: Int): ImageVector = when {
    bars >= 3 -> Icons.Filled.SignalCellularAlt
    bars == 2 -> Icons.Filled.SignalCellularAlt2Bar
    bars == 1 -> Icons.Filled.SignalCellularAlt1Bar
    else -> Icons.Filled.SignalCellular0Bar
}

/**
 * The live counters. Pocket has no preview to look at, so its card leads with a large clock and says when
 * the path appears; camera modes show the time as a tile next to the others.
 */
@Composable
private fun StatsCard(ui: RecordUiState, recording: RecordingState.Recording, pocket: Boolean) {
    val n = RecordStatus.numbers(recording, ui.stats, ui.strideLengthM, ui.timeExcludesPauses)
    val tiles = buildList {
        if (!pocket) add(StatTileData("Time", n.clock, Icons.Filled.Timer, n.clockDetail))
        add(StatTileData("Steps", n.steps, Icons.AutoMirrored.Filled.DirectionsWalk, n.stepsDetail))
        add(StatTileData("Marks", n.marks, Icons.Filled.Flag))
        add(StatTileData("Distance ≈", n.distance, Icons.Filled.Route, n.distanceDetail, n.distanceSpoken))
        add(StatTileData("Cadence", n.cadence, Icons.Filled.Speed, n.cadenceDetail, n.cadenceSpoken))
    }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (pocket) {
                Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    Text(
                        n.clock,
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            textDirection = TextDirection.Ltr,
                            // Equal-width digits, so the clock does not shuffle sideways every second.
                            fontFeatureSettings = "tnum",
                        ),
                        maxLines = 1,
                    )
                    if (n.clockDetail != null) {
                        Text(
                            n.clockDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "The path is computed when you stop.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            StatGrid(tiles = tiles, maxColumns = 3, framed = false)
        }
    }
}

/**
 * Pinned under the live part in every mode: the marks as a 3 + 2 grid that never scrolls, the volume-key
 * hint, and Pause, Stop and the waypoint flag, large enough to hit while walking.
 */
@Composable
private fun RecordControls(
    paused: Boolean,
    onAnnotate: (AnnotationKind, String) -> Unit,
    onNote: () -> Unit,
    onLoop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Each row takes its tallest button's height, so one label wrapping at a large text size does not
        // leave its neighbours shorter.
        val markRow = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = markRow) {
            MarkButton("Junction", Modifier.weight(1f)) { onAnnotate(AnnotationKind.JUNCTION, "") }
            MarkButton("Chamber", Modifier.weight(1f)) { onAnnotate(AnnotationKind.CHAMBER, "") }
            MarkButton("Note", Modifier.weight(1f), onNote)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = markRow) {
            MarkButton("Back at start", Modifier.weight(1f), onLoop)
            MarkButton("Re-orient", Modifier.weight(1f)) { onAnnotate(AnnotationKind.REORIENT, "") }
        }
        Text(
            "Volume keys also mark a waypoint.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RoundIconButton(
                onClick = if (paused) onResume else onPause,
                icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = if (paused) "Resume recording" else "Pause recording",
                size = 64.dp,
            )
            StopPill(onClick = onStop, modifier = Modifier.weight(1f))
            RoundIconButton(
                onClick = { onAnnotate(AnnotationKind.WAYPOINT, "") },
                icon = Icons.Filled.Flag,
                contentDescription = "Mark waypoint",
                size = 64.dp,
            )
        }
    }
}

@Composable
private fun MarkButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 56.dp)
            .fillMaxHeight(),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        // Wraps to a second line rather than clipping at large text sizes; the button grows with it.
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** The red Stop pill; "Stop Recording" when that fits beside the two round buttons, otherwise "Stop". */
@Composable
private fun StopPill(onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.imuColors
    val style = MaterialTheme.typography.titleMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val fullWidth = remember(measurer, style, density) {
            with(density) { measurer.measure(STOP_FULL, style, maxLines = 1).size.width.toDp() }
        }
        val fits = fullWidth + StopIconSize + StopIconGap + StopPaddingH * 2 <= maxWidth
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = colors.stopRed, contentColor = colors.onStopRed),
            contentPadding = PaddingValues(horizontal = StopPaddingH),
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(StopIconSize))
            Spacer(Modifier.width(StopIconGap))
            Text(
                if (fits) STOP_FULL else "Stop",
                style = style,
                maxLines = 1,
                modifier = Modifier.semantics { contentDescription = "Stop recording" },
            )
        }
    }
}

private const val STOP_FULL = "Stop Recording"
private val StopIconSize = 24.dp
private val StopIconGap = 8.dp
private val StopPaddingH = 20.dp

@Composable
private fun toneColor(tone: RecordTone): Color = when (tone) {
    RecordTone.Good -> MaterialTheme.imuColors.success
    RecordTone.Warning -> MaterialTheme.imuColors.warning
    RecordTone.Bad -> MaterialTheme.colorScheme.error
    RecordTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun BusyContent(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NoteDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("What is here?") },
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(note.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun modeDescription(mode: TripMode): String = when (mode) {
    TripMode.POCKET ->
        "IMU only. Put the phone in a pocket, hand or chest pocket; the screen may turn off. " +
            "Use the buttons or a volume key to mark points. " +
            "Don't turn while you take the phone out or put it away: the path can't see that turn."
    TripMode.FLASHLIGHT ->
        "Camera tracking with the torch on. Hold the phone in front of you, camera facing forward. " +
            "When tracking is lost the path continues from steps."
    TripMode.ILLUMINATED ->
        "Camera tracking in a lit space with automatic photos. Hold the phone in front of you."
}

/**
 * How to hold the phone when the recording starts. A calibrated heading offset is applied to the start
 * of every recording, so with one the recording must start in the pose it was calibrated in; a start in
 * the hand would turn the map by the offset.
 */
private fun startPose(ui: RecordUiState): String =
    if (ui.offsetCalibrated) {
        "Start in the pose you calibrated the heading offset in"
    } else {
        "Start with the phone in your hand"
    }

/** Where north comes from for the next trip, and how to hold the phone at the start ([startPose]). */
private fun northText(ui: RecordUiState): String {
    val start = startPose(ui) + "; the path keeps its direction when you put the phone in a pocket."
    return when {
        ui.northFromCompass -> "North is taken from the compass before the recording starts. $start"
        !ui.compassSensors ->
            "This phone lacks the sensors to find north with the compass, so north on the map is the " +
                "gyroscope's own direction for this trip. $start"
        else ->
            "North on the map is the gyroscope's own direction for this trip, not the compass's; Settings can " +
                "turn North from compass on. $start"
    }
}

/** Everything worth asking for; some may be declined without blocking the recording. */
private fun requestedPermissions(mode: TripMode): List<String> {
    val list = ArrayList<String>()
    list.add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list.add(Manifest.permission.POST_NOTIFICATIONS)
    if (mode != TripMode.POCKET) list.add(Manifest.permission.CAMERA)
    return list
}

/** Without these the recording cannot run: the health foreground service and, for AR modes, the camera. */
private fun requiredPermissions(mode: TripMode): List<String> {
    val list = ArrayList<String>()
    list.add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (mode != TripMode.POCKET) list.add(Manifest.permission.CAMERA)
    return list
}

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun deniedMessage(missing: List<String>): String {
    val names = missing.map {
        when (it) {
            Manifest.permission.ACTIVITY_RECOGNITION -> "physical activity"
            Manifest.permission.CAMERA -> "camera"
            else -> it.substringAfterLast('.')
        }
    }
    return "Recording needs the ${names.joinToString(" and ")} permission. Grant it in system settings and try again."
}
