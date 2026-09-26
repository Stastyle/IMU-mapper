package com.stastyle.imumapper.ui.tuning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stastyle.imumapper.pipeline.tuning.WalkShape
import com.stastyle.imumapper.ui.calibration.TripPicker
import com.stastyle.imumapper.ui.common.appContainer

/**
 * Assisted tuning: describe a recorded walk, hand the pipeline's numbers to a chat model, paste
 * its proposal back, re-process the walk with it and keep it when it scores better.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuningScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = appContainer()
    val vm: TuningViewModel = viewModel {
        TuningViewModel(
            appContext = context.applicationContext,
            trips = container.tripRepository,
            files = container.tripFiles,
            calibration = container.calibrationRepository,
            tripProcessor = container.tripProcessor,
            processor = container.processor,
        )
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.message) {
        val text = ui.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        vm.dismissMessage()
    }
    LaunchedEffect(ui.pendingIntent) {
        val intent = ui.pendingIntent ?: return@LaunchedEffect
        runCatching { context.startActivity(intent) }
        vm.consumeIntent()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assisted tuning") },
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
            IntroCard()
            TripCard(ui, vm)
            if (ui.tripId != null) {
                TruthCard(ui, vm)
                PromptCard(ui, vm)
                AnswerCard(ui, vm)
            }
        }
    }
}

@Composable
private fun IntroCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("How it works", style = MaterialTheme.typography.titleMedium)
            Text(
                "1. Record a walk you can describe exactly (a measured straight line, a rectangle, a loop back " +
                    "to the start) as a normal trip.\n" +
                    "2. Pick it here, fill in what you did, and build the prompt. It holds the pipeline's numbers " +
                    "for that walk, the current configuration and instructions for the model; no raw samples.\n" +
                    "3. Send it to Claude, Gemini or ChatGPT and paste the answer below.\n" +
                    "4. The walk is re-processed with the proposed values and scored against your description. " +
                    "Save it only when it is better.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TripCard(ui: TuningUiState, vm: TuningViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1 · The walk", style = MaterialTheme.typography.titleMedium)
            if (ui.trips.isEmpty()) {
                Text("No recorded trips yet. Record the walk as a trip first.", style = MaterialTheme.typography.bodyMedium)
            } else {
                TripPicker(trips = ui.trips, selectedId = ui.tripId, enabled = !ui.busy, onSelect = vm::selectTrip)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TruthCard(ui: TuningUiState, vm: TuningViewModel) {
    val form = ui.form
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("2 · What you actually did", style = MaterialTheme.typography.titleMedium)
            Text("Only what you fill in is scored; leave unknown fields empty.", style = MaterialTheme.typography.bodySmall)
            Text("Shape", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (shape in WalkShape.entries) {
                    FilterChip(
                        selected = shape == form.shape,
                        onClick = { vm.setShape(shape) },
                        label = { Text(shape.label) },
                        enabled = !ui.busy,
                    )
                }
            }
            Text(form.shape.hint, style = MaterialTheme.typography.bodySmall)
            NumberField("Distance walked (m)", form.distanceText, ui.formErrors[TruthField.DISTANCE], "measured, if you know it", vm::setDistance)
            NumberField("Turns of 90° or more", form.turnsText, ui.formErrors[TruthField.TURNS], "how many corners you turned", vm::setTurns, whole = true)
            NumberField("Height change (m)", form.heightText, ui.formErrors[TruthField.HEIGHT], "end above start; negative going down", vm::setHeight)
            OutlinedTextField(
                value = form.description,
                onValueChange = vm::setDescription,
                label = { Text("Description") },
                placeholder = { Text("Pace, stops, stairs, how the phone was carried, anything odd") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = vm::buildPrompt, enabled = !ui.busy && ui.formErrors.isEmpty()) { Text("Build prompt") }
                if (ui.prompt is PromptPhase.Building) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("Processing the walk…")
                }
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    text: String,
    error: String?,
    hint: String,
    onChange: (String) -> Unit,
    whole: Boolean = false,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = { Text(error ?: hint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (whole) KeyboardType.Number else KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptCard(ui: TuningUiState, vm: TuningViewModel) {
    val phase = ui.prompt
    if (phase is PromptPhase.Idle || phase is PromptPhase.Building) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("3 · Prompt", style = MaterialTheme.typography.titleMedium)
            when (phase) {
                is PromptPhase.Failed -> Text(phase.message, color = MaterialTheme.colorScheme.error)
                is PromptPhase.Ready -> {
                    Text("With the current calibration this walk comes out as:", style = MaterialTheme.typography.bodySmall)
                    MetricsSummary(phase.report.metrics)
                    ScoreBody(phase.truth, phase.report.metrics)
                    Text(
                        "The prompt is " + phase.text.length + " characters. Send it to your chat model, then paste " +
                            "the answer below.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::copyPrompt) { Text("Copy") }
                        OutlinedButton(onClick = vm::sharePromptText) { Text("Share as text") }
                        OutlinedButton(onClick = vm::sharePromptFile) { Text("Share as file") }
                    }
                }
                else -> Unit
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerCard(ui: TuningUiState, vm: TuningViewModel) {
    if (ui.prompt !is PromptPhase.Ready) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("4 · The model's answer", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = ui.responseText,
                onValueChange = vm::setResponse,
                label = { Text("Paste the answer here") },
                placeholder = { Text("The whole reply is fine; the JSON block is picked out of it.") },
                minLines = 4,
                maxLines = 12,
                enabled = ui.proposal !is ProposalPhase.Evaluating,
                modifier = Modifier.fillMaxWidth(),
            )
            val phase = ui.proposal
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::checkProposal, enabled = !ui.busy && ui.responseText.isNotBlank()) { Text("Check answer") }
                if (phase is ProposalPhase.Ready) {
                    Button(onClick = vm::evaluate, enabled = !ui.busy && phase.proposal.changes.isNotEmpty()) {
                        Text("Evaluate on this walk")
                    }
                }
            }
            when (phase) {
                is ProposalPhase.Idle -> Unit
                is ProposalPhase.Rejected -> {
                    Text("The answer was not applied:", color = MaterialTheme.colorScheme.error)
                    for (e in phase.errors) Text("• $e", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                is ProposalPhase.Ready -> ProposalBody(phase.proposal)
                is ProposalPhase.Evaluating -> {
                    ProposalBody(phase.proposal)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Re-processing with the proposal…")
                    }
                }
                is ProposalPhase.Evaluated -> {
                    ProposalBody(phase.proposal)
                    val truth = (ui.prompt as PromptPhase.Ready).truth
                    ComparisonBody(truth, phase.before, phase.after, phase.runId)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ui.applied) {
                            Text("Saved as the current calibration.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Button(onClick = vm::applyProposal) { Text("Save as calibration") }
                        }
                        TextButton(onClick = vm::nextRound) { Text(if (ui.applied) "Next round" else "Discard") }
                    }
                }
                is ProposalPhase.Failed -> {
                    ProposalBody(phase.proposal)
                    Text("Evaluation failed: " + phase.message, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
