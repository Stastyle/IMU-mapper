package com.stastyle.imumapper.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stastyle.imumapper.capture.SensorKind
import com.stastyle.imumapper.capture.SensorStats
import com.stastyle.imumapper.capture.formatRate
import com.stastyle.imumapper.ui.calibration.TripPicker
import com.stastyle.imumapper.ui.common.appContainer

/** Live sensor plots, raw-log summary, stored runs and re-processing. [tripId] selects a trip, if any. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(tripId: Long?, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = appContainer()
    val vm: DebugViewModel = viewModel(key = "debug-" + (tripId ?: "any")) {
        DebugViewModel(
            appContext = context.applicationContext,
            initialTripId = tripId,
            trips = container.tripRepository,
            files = container.tripFiles,
            calibration = container.calibrationRepository,
            tripProcessor = container.tripProcessor,
            json = container.json,
        )
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Sensors at the fastest rate must not outlive the screen or run while the app is in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.onScreenStarted()
                Lifecycle.Event.ON_STOP -> vm.onScreenStopped()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.onScreenStopped()
        }
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
                title = { Text("Debug") },
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
            LiveCard(ui, onToggle = vm::setLiveEnabled)
            if (!vm.fixedTrip) {
                if (ui.trips.isEmpty()) {
                    Text("No recorded trips yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    TripPicker(
                        trips = ui.trips,
                        selectedId = ui.tripId,
                        enabled = !ui.processing,
                        onSelect = vm::selectTrip,
                    )
                }
            }
            if (ui.tripId != null) {
                LogSummaryCard(ui)
                RunsCard(ui, onToggle = vm::toggleRun, onLoadConfig = vm::loadDraftFrom)
                ConfigEditorCard(
                    ui = ui,
                    onField = vm::setField,
                    onReset = vm::resetDraft,
                    onSave = vm::saveDraftAsCalibration,
                    onReprocess = vm::reprocess,
                    onPdrOnly = vm::runPdrOnly,
                    onVioOnly = vm::runVioOnly,
                )
            }
        }
    }
}

@Composable
private fun LiveCard(ui: DebugUiState, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Live sensors", style = MaterialTheme.typography.titleMedium)
                Switch(checked = ui.liveEnabled, onCheckedChange = onToggle)
            }
            if (!ui.liveRunning) {
                Text(
                    "Turn on to plot the last ten seconds of the accelerometer, the vertical acceleration " +
                        "(game rotation vector, ENU), the heading and the pressure, without recording anything.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                RateChips(ui.stats)
                val c = ui.charts
                LineChart("Accel magnitude", "m/s²", c.accelMag)
                LineChart("Vertical accel (steps marked: " + c.stepsSeen + ")", "m/s²", c.vertical)
                LineChart(
                    "Heading (game; fused thin)",
                    "°",
                    c.headingGame,
                    decimals = 0,
                    fixedMin = -180f,
                    fixedMax = 180f,
                    secondary = c.headingFused,
                )
                LineChart("Pressure", "hPa", c.pressure, decimals = 2)
                val writeError = ui.stats.writeError
                if (writeError != null) Text(writeError, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RateChips(stats: SensorStats) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (kind in SensorKind.entries) {
            val h = stats.of(kind)
            if (!h.available) continue
            val text = if (kind == SensorKind.STEP) {
                kind.label + " " + stats.stepCount
            } else {
                kind.label + " " + formatRate(h.rateHz) + (if (h.stalled) " (stalled)" else "")
            }
            AssistChip(onClick = {}, label = { Text(text, style = MaterialTheme.typography.labelSmall) })
        }
    }
}
