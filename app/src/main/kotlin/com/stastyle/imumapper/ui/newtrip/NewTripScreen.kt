package com.stastyle.imumapper.ui.newtrip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.capture.RecordingState
import com.stastyle.imumapper.capture.formatElapsed
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.ui.common.appContainer
import com.stastyle.imumapper.ui.triplist.TripFormat

/**
 * The Record tab: picks the capture mode of a new trip and hands it to the recording screen, whose
 * setup step takes the carry position and starts the recording. While a recording runs the page only
 * returns to it ([onReturnToRecording] with the running mode), and while one is being saved it offers
 * nothing. [bottomBar] is the slot for the tab bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTripScreen(
    onContinue: (TripMode) -> Unit,
    onReturnToRecording: (TripMode) -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val container = appContainer()
    val recorder = remember(context) { RecordingController.get(context) }
    val vm: NewTripViewModel = viewModel {
        NewTripViewModel(
            recording = recorder.state,
            defaultTripMode = container.updateManager.preferences.defaultTripMode,
        )
    }
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("New trip") }) },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val state = ui) {
                is NewTripUiState.Recording -> RecordingCard(
                    recording = state.recording,
                    onReturn = { onReturnToRecording(state.recording.mode) },
                )
                NewTripUiState.Stopping -> StoppingCard()
                is NewTripUiState.Choose -> ModeChoice(
                    mode = state.mode,
                    onSelect = vm::selectMode,
                    onContinue = onContinue,
                )
            }
        }
    }
}

@Composable
private fun RecordingCard(recording: RecordingState.Recording, onReturn: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recording in progress", style = MaterialTheme.typography.titleMedium)
            val status = listOfNotNull(
                TripFormat.modeLabel(recording.mode),
                formatElapsed(recording.elapsedNs),
                "paused".takeIf { recording.paused },
            )
            Text(status.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            Text(
                "A new trip can start once this one is stopped.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onReturn) { Text("Return to recording") }
        }
    }
}

@Composable
private fun StoppingCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Saving the last trip…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** [mode] is null while the Settings default loads; no option is selected and Continue waits. */
@Composable
private fun ModeChoice(mode: TripMode?, onSelect: (TripMode) -> Unit, onContinue: (TripMode) -> Unit) {
    Text("Capture mode", style = MaterialTheme.typography.labelLarge)
    Column(modifier = Modifier.selectableGroup()) {
        for (option in TripMode.entries) {
            ModeOption(mode = option, selected = option == mode, onSelect = { onSelect(option) })
        }
    }
    Text(
        "Where the phone is carried is chosen on the next step.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = { if (mode != null) onContinue(mode) },
        enabled = mode != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Continue") }
}

@Composable
private fun ModeOption(mode: TripMode, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(TripFormat.modeLabel(mode), style = MaterialTheme.typography.bodyLarge)
            Text(
                TripFormat.modeExplanation(mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
