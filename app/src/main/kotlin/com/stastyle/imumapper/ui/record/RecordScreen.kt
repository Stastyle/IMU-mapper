package com.stastyle.imumapper.ui.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.capture.SensorHealth
import com.stastyle.imumapper.capture.SensorKind
import com.stastyle.imumapper.capture.SensorStats
import com.stastyle.imumapper.capture.formatElapsed
import com.stastyle.imumapper.capture.formatRate
import com.stastyle.imumapper.capture.modeLabel
import com.stastyle.imumapper.pipeline.core.AnnotationKind
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.ui.common.appContainer

/**
 * Recording screen for the given [mode]. Calls [onFinished] with the new trip id once the
 * recording is stopped and saved, or [onCancelled] if nothing was recorded.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    val busy = ui.phase == RecordPhase.STARTING || ui.phase == RecordPhase.RECORDING || ui.phase == RecordPhase.STOPPING
    val view = LocalView.current
    DisposableEffect(view, busy) {
        // Screen stays on while recording so the annotation buttons remain reachable.
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = busy) {
        if (ui.phase == RecordPhase.RECORDING) showStopDialog = true
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Record · ${modeLabel(mode)}") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when (ui.phase) {
                                RecordPhase.SETUP -> onCancelled()
                                RecordPhase.RECORDING -> showStopDialog = true
                                else -> Unit
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (ui.phase) {
                RecordPhase.SETUP, RecordPhase.STARTING -> SetupContent(
                    ui = ui,
                    onCarry = vm::setCarry,
                    onStart = onStartClick,
                )
                RecordPhase.RECORDING -> RecordingContent(
                    ui = ui,
                    controller = controller,
                    onAnnotate = vm::annotate,
                    onPause = vm::pause,
                    onResume = vm::resume,
                    onStop = { showStopDialog = true },
                )
                RecordPhase.STOPPING, RecordPhase.DONE -> BusyContent("Saving and processing the trip…")
            }
        }
    }

    if (showStopDialog) {
        ConfirmDialog(
            title = "Stop recording?",
            text = "The trip is saved and processed. You cannot resume it afterwards.",
            confirmLabel = "Stop",
            onConfirm = {
                showStopDialog = false
                vm.stop()
            },
            onDismiss = { showStopDialog = false },
        )
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
    // No vertical scroll here: the weighted spacer needs a bounded height to push Start to the bottom.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(modeDescription(ui.mode), style = MaterialTheme.typography.bodyLarge)
        Text("Carry position", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (position in CarryPosition.entries) {
                FilterChip(
                    selected = ui.carry == position,
                    onClick = { onCarry(position) },
                    label = { Text(carryLabel(position)) },
                    enabled = !starting,
                )
            }
        }
        Text(
            "The heading offset from calibration is tied to the carry position. Pick the one you calibrated.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (ui.error != null) {
            Text(ui.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = onStart,
            enabled = !starting,
            modifier = Modifier.fillMaxWidth().height(80.dp),
        ) {
            if (starting) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("Start recording", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun RecordingContent(
    ui: RecordUiState,
    controller: RecordingController,
    onAnnotate: (AnnotationKind, String) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val recording = ui.recording ?: return
    var showNoteDialog by rememberSaveable { mutableStateOf(false) }
    var showLoopDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (ui.mode != TripMode.POCKET) {
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
            StatusPanel(recording)
            SensorHealthRow(ui.stats)
            AnnotationButtons(
                onWaypoint = { onAnnotate(AnnotationKind.WAYPOINT, "") },
                onJunction = { onAnnotate(AnnotationKind.JUNCTION, "") },
                onChamber = { onAnnotate(AnnotationKind.CHAMBER, "") },
                onNote = { showNoteDialog = true },
                onLoop = { showLoopDialog = true },
                onReorient = { onAnnotate(AnnotationKind.REORIENT, "") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { if (recording.paused) onResume() else onPause() },
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Text(if (recording.paused) "Resume" else "Pause")
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Stop", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    if (showNoteDialog) {
        NoteDialog(
            onSave = { note ->
                showNoteDialog = false
                onAnnotate(AnnotationKind.NOTE, note)
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
                onAnnotate(AnnotationKind.LOOP_CLOSED, "")
            },
            onDismiss = { showLoopDialog = false },
        )
    }
}

@Composable
private fun StatusPanel(recording: RecordingState.Recording) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = formatElapsed(recording.elapsedNs),
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = if (recording.paused) "Paused" else "Recording · ${carryLabel(recording.carryPosition)}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (recording.paused) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(text = recording.stepCount.toString(), style = MaterialTheme.typography.headlineMedium)
            Text(text = "steps", style = MaterialTheme.typography.bodySmall)
            Text(text = "${recording.annotationCount} marks", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SensorHealthRow(stats: SensorStats) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (stats.writeError != null) {
            Text(
                "Log write failed: ${stats.writeError}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (!stats.coreSensorsHealthy) {
            Text(
                "Accelerometer or gyroscope is not delivering",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (kind in SensorKind.entries) {
                SensorChip(stats.of(kind))
            }
        }
    }
}

@Composable
private fun SensorChip(health: SensorHealth) {
    val scheme = MaterialTheme.colorScheme
    val (background, foreground) = when {
        !health.available -> scheme.surfaceVariant to scheme.onSurfaceVariant
        health.stalled -> scheme.errorContainer to scheme.onErrorContainer
        health.sampleCount == 0L -> scheme.surfaceVariant to scheme.onSurfaceVariant
        else -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    val detail = when {
        !health.available -> "none"
        health.stalled -> "stalled"
        health.kind == SensorKind.STEP -> health.sampleCount.toString()
        else -> formatRate(health.rateHz)
    }
    Surface(shape = MaterialTheme.shapes.small, color = background, contentColor = foreground) {
        Text(
            text = "${health.kind.label} $detail",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun AnnotationButtons(
    onWaypoint: () -> Unit,
    onJunction: () -> Unit,
    onChamber: () -> Unit,
    onNote: () -> Unit,
    onLoop: () -> Unit,
    onReorient: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            BigButton("Waypoint", Modifier.weight(1f), onWaypoint)
            BigButton("Junction", Modifier.weight(1f), onJunction)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            BigButton("Chamber", Modifier.weight(1f), onChamber)
            BigButton("Note", Modifier.weight(1f), onNote)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            BigButton("Back at start", Modifier.weight(1f), onLoop)
            BigButton("Re-orient", Modifier.weight(1f), onReorient)
        }
        Text(
            "Volume keys also mark a waypoint.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BigButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(64.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BusyContent(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
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
            "Use the buttons or a volume key to mark points."
    TripMode.FLASHLIGHT ->
        "Camera tracking with the torch on. Hold the phone in front of you, camera facing forward. " +
            "When tracking is lost the path continues from steps."
    TripMode.ILLUMINATED ->
        "Camera tracking in a lit space with automatic photos. Hold the phone in front of you."
}

private fun carryLabel(position: CarryPosition): String = when (position) {
    CarryPosition.HAND -> "Hand"
    CarryPosition.POCKET -> "Pocket"
    CarryPosition.CHEST -> "Chest"
    CarryPosition.HELMET -> "Helmet"
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
