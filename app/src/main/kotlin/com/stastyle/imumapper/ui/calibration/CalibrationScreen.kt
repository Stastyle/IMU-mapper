package com.stastyle.imumapper.ui.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.ui.common.appContainer

/** Calibration flows: still bias, stride walk, heading offset, square test, ARCore vs PDR, assisted tuning. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(onBack: () -> Unit, onOpenTuning: () -> Unit) {
    val context = LocalContext.current
    val container = appContainer()
    val vm: CalibrationViewModel = viewModel {
        CalibrationViewModel(
            appContext = context.applicationContext,
            calibration = container.calibrationRepository,
            trips = container.tripRepository,
            files = container.tripFiles,
        )
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // A flow must not keep the fastest sensor rate running once the screen is gone or the app is
    // in the background, and the screen should not lock while the user is walking a square.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.cancelActive()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.cancelActive()
        }
    }
    val view = LocalView.current
    DisposableEffect(view, ui.isRecording) {
        view.keepScreenOn = ui.isRecording
        onDispose { view.keepScreenOn = false }
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
                title = { Text("Calibration") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CurrentConfigCard(ui)
            StillBiasCard(ui, vm)
            StrideWalkCard(ui, vm)
            HeadingOffsetCard(ui, vm)
            SquareTestCard(ui, vm)
            VioCard(ui, vm)
            TuningCard(onOpenTuning)
        }
    }
}

@Composable
private fun TuningCard(onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Assisted tuning", style = MaterialTheme.typography.titleMedium)
            Text(
                "For the thresholds no guided flow measures (step detection, magnetometer gate, smoothing): " +
                    "record a walk you can describe, let a chat model read the pipeline's numbers for it and " +
                    "propose values, then check the proposal on the same walk before saving it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpen) { Text("Open") }
        }
    }
}

@Composable
private fun CurrentConfigCard(ui: CalibrationUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Current calibration", style = MaterialTheme.typography.titleMedium)
            for ((label, value) in Fmt.configSummary(ui.config)) ValueRow(label, value)
            ValueRow("Carry position", Fmt.carry(ui.carry))
            Text(
                "Values apply to every new recording and re-processing. The carry position is chosen on the " +
                    "recording screen; calibrate the heading offset with the phone carried that way.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StillBiasCard(ui: CalibrationUiState, vm: CalibrationViewModel) {
    FlowCard(
        kind = FlowKind.STILL,
        instructions = "Put the phone flat on a table, tap Start and do not touch it for ten seconds. " +
            "Gives the gyroscope bias and the accelerometer noise floor.",
        phase = ui.phase(FlowKind.STILL),
        enabled = ui.activeFlow == null,
        onStart = { vm.start(FlowKind.STILL) },
        onStop = { vm.stop(FlowKind.STILL) },
        onSave = { vm.save(FlowKind.STILL) },
        onDiscard = { vm.discard(FlowKind.STILL) },
        result = { r -> if (r is FlowResult.StillBias) StillBiasResult(r) },
    )
}

@Composable
private fun StrideWalkCard(ui: CalibrationUiState, vm: CalibrationViewModel) {
    FlowCard(
        kind = FlowKind.STRIDE,
        instructions = "Measure a straight distance (20 m by default), enter it, tap Start, walk it at your " +
            "normal pace and tap Stop at the end. Gives the stride length and the Weinberg gain.",
        phase = ui.phase(FlowKind.STRIDE),
        enabled = ui.activeFlow == null,
        onStart = { vm.start(FlowKind.STRIDE) },
        onStop = { vm.stop(FlowKind.STRIDE) },
        onSave = { vm.save(FlowKind.STRIDE) },
        onDiscard = { vm.discard(FlowKind.STRIDE) },
        setup = {
            OutlinedTextField(
                value = ui.strideDistanceText,
                onValueChange = vm::setStrideDistance,
                label = { Text("Distance (m)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        result = { r -> if (r is FlowResult.Stride) StrideResult(r) },
    )
}

@Composable
private fun HeadingOffsetCard(ui: CalibrationUiState, vm: CalibrationViewModel) {
    FlowCard(
        kind = FlowKind.HEADING,
        instructions = "Carry the phone the way you will during trips (" + Fmt.carry(ui.carry) + "), tap Start, " +
            "walk straight for about ten steps and tap Stop. The offset between the phone's axis and your " +
            "walking direction is relative to that direction.",
        phase = ui.phase(FlowKind.HEADING),
        enabled = ui.activeFlow == null,
        onStart = { vm.start(FlowKind.HEADING) },
        onStop = { vm.stop(FlowKind.HEADING) },
        onSave = { vm.save(FlowKind.HEADING) },
        onDiscard = { vm.discard(FlowKind.HEADING) },
        result = { r -> if (r is FlowResult.Heading) HeadingResult(r) },
    )
}

@Composable
private fun SquareTestCard(ui: CalibrationUiState, vm: CalibrationViewModel) {
    FlowCard(
        kind = FlowKind.SQUARE,
        instructions = "Tap Start, walk a square of about 5 m x 5 m and return to the exact starting point, " +
            "then tap Stop. Reports the closure error with the current calibration.",
        phase = ui.phase(FlowKind.SQUARE),
        enabled = ui.activeFlow == null,
        onStart = { vm.start(FlowKind.SQUARE) },
        onStop = { vm.stop(FlowKind.SQUARE) },
        onSave = { vm.save(FlowKind.SQUARE) },
        onDiscard = { vm.discard(FlowKind.SQUARE) },
        saveLabel = "Keep as baseline",
        result = { r -> if (r is FlowResult.Square) SquareResult(r) },
    )
}

@Composable
private fun VioCard(ui: CalibrationUiState, vm: CalibrationViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ARCore vs PDR", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pick a trip recorded in a camera mode. Both the ARCore path and the step-based path are " +
                    "computed from the same log and compared.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (ui.vioTrips.isEmpty()) {
                Text("No Flashlight or Illuminated trips recorded yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                TripPicker(
                    trips = ui.vioTrips,
                    selectedId = ui.vioTripId,
                    enabled = ui.vioPhase !is VioPhase.Running,
                    onSelect = vm::selectVioTrip,
                )
            }
            when (val phase = ui.vioPhase) {
                is VioPhase.Idle -> Unit
                is VioPhase.Running -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Running both processors…")
                    }
                }
                is VioPhase.Done -> VioComparisonBody(phase.result)
                is VioPhase.Failed -> Text(phase.message, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = vm::runVioComparison,
                enabled = ui.vioTripId != null && ui.vioPhase !is VioPhase.Running,
            ) { Text("Compare") }
        }
    }
}

/** Button that opens a dropdown of trips; kept plain so it needs no experimental Material API. */
@Composable
fun TripPicker(trips: List<TripEntity>, selectedId: Long?, enabled: Boolean, onSelect: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = trips.firstOrNull { it.id == selectedId }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(selected?.name ?: "Choose a trip", modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for (trip in trips) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(trip.name)
                        Text(
                            Fmt.tripLine(trip),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                onClick = {
                    open = false
                    onSelect(trip.id)
                },
            )
        }
    }
}
