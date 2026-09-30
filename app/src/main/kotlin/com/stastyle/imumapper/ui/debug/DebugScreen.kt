package com.stastyle.imumapper.ui.debug

import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stastyle.imumapper.capture.SensorKind
import com.stastyle.imumapper.capture.SensorStats
import com.stastyle.imumapper.capture.formatRate
import com.stastyle.imumapper.ui.calibration.CalibrationCard
import com.stastyle.imumapper.ui.calibration.Hint
import com.stastyle.imumapper.ui.calibration.TripPicker
import com.stastyle.imumapper.ui.common.AppScaffold
import com.stastyle.imumapper.ui.common.AppTopBar
import com.stastyle.imumapper.ui.common.BrandButton
import com.stastyle.imumapper.ui.common.CardTone
import com.stastyle.imumapper.ui.common.GlassCard
import com.stastyle.imumapper.ui.common.StatusPill
import com.stastyle.imumapper.ui.common.StatusTone
import com.stastyle.imumapper.ui.common.appContainer

/**
 * Live sensor plots, raw-log summary, stored runs and re-processing. [tripId] selects a trip, if any. The
 * top bar names the trip the cards below are about.
 */
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
            markHeadingOffsetResetDone = container.updateManager.preferences::markHeadingOffsetResetDone,
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

    val tripName: String? = ui.trip?.name
    AppScaffold(
        topBar = { AppTopBar("Debug", subtitle = tripName, onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val crash = ui.crashReport
            if (crash != null) CrashCard(crash, onClear = vm::clearCrashReport)
            LiveCard(ui, onToggle = vm::setLiveEnabled)
            if (!vm.fixedTrip) {
                if (ui.trips.isEmpty()) {
                    Hint("No recorded trips yet.")
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

/**
 * The last uncaught exception, kept by [com.stastyle.imumapper.debug.CrashLog], with Copy and Share for a
 * bug report.
 */
@Composable
private fun CrashCard(report: String, onClear: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    GlassCard(modifier = Modifier.fillMaxWidth(), tone = CardTone.Error) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Drawn in the card's own light red rather than SectionHeader's accent, which would clash with it.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.BugReport, contentDescription = null)
                Text(
                    "Last crash",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                report.lineSequence().take(CRASH_PREVIEW_LINES).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "Copy the whole report and paste it into the bug report.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandButton(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("Copy") }
                OutlinedButton(onClick = { shareText(context, report) }) { Text("Share") }
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, "Crash report")) }
}

private const val CRASH_PREVIEW_LINES = 6

@Composable
private fun LiveCard(ui: DebugUiState, onToggle: (Boolean) -> Unit) {
    CalibrationCard(
        title = "Live sensors",
        icon = Icons.Filled.Sensors,
        action = {
            Switch(
                checked = ui.liveEnabled,
                onCheckedChange = onToggle,
                // The heading beside it is a separate node, so the switch names itself.
                modifier = Modifier.semantics { contentDescription = "Live sensors" },
            )
        },
    ) {
        if (!ui.liveRunning) {
            Hint(
                "Turn on to plot the last ten seconds of the accelerometer, the vertical acceleration " +
                    "(game rotation vector, ENU), the heading and the pressure, without recording anything.",
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

/** Each available sensor's rate as a read-only pill; a stalled one is amber. */
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
            StatusPill(text, if (h.stalled) StatusTone.Warning else StatusTone.Neutral)
        }
    }
}
