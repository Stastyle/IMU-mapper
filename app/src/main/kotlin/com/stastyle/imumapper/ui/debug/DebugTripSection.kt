package com.stastyle.imumapper.ui.debug

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.pipeline.core.PathStats
import com.stastyle.imumapper.pipeline.core.PipelineConfig
import com.stastyle.imumapper.ui.calibration.Fmt
import com.stastyle.imumapper.ui.calibration.ValueRow
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogSummaryCard(ui: DebugUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Raw log", style = MaterialTheme.typography.titleMedium)
            val trip = ui.trip
            if (trip != null) {
                val carry = Fmt.carry(trip.carryPosition).lowercase()
                Text(trip.name + " · " + trip.mode.name.lowercase() + " · " + carry)
                if (trip.lastError != null) {
                    Text("Last error: " + trip.lastError, color = MaterialTheme.colorScheme.error)
                }
            }
            when {
                ui.summaryLoading -> CircularProgressIndicator()
                ui.summaryError != null -> Text(ui.summaryError, color = MaterialTheme.colorScheme.error)
                ui.summary != null -> SummaryBody(ui.summary)
                else -> Text("No trip selected.")
            }
        }
    }
}

@Composable
private fun SummaryBody(s: LogSummary) {
    ValueRow("Size", Fmt.num(s.fileSizeBytes / 1_048_576.0, 2) + " MB")
    ValueRow("Duration", Fmt.durationText(s.durationS))
    for ((name, count) in s.counts) ValueRow(name, count.toString())
    if (s.truncated) {
        Text(
            "Truncated: the recorder died mid-write; everything before is valid.",
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (s.unknownRecords > 0) ValueRow("Unknown / corrupt records", s.unknownRecords.toString())
    ValueRow("ARCore tracking", if (s.hasVio) "yes" else "no")
    val meta = s.meta
    if (meta != null) {
        HorizontalDivider()
        ValueRow("App version", meta.appVersion)
        ValueRow("Device", meta.deviceModel + " (SDK " + meta.androidSdk + ")")
        ValueRow("Started", dateText(meta.startedAtEpochMs))
        ValueRow("Stride at recording", Fmt.metres(meta.config.strideLengthM))
        ValueRow("Heading offset at recording", Fmt.degrees(meta.config.headingOffsetRad))
        if (meta.sensorPeriodsUs.isNotEmpty()) {
            val periods = meta.sensorPeriodsUs.entries.joinToString { it.key.removePrefix("TYPE_") + "=" + it.value }
            Text("Sensor periods (µs): $periods", style = MaterialTheme.typography.bodySmall)
        }
        if (meta.notes.isNotBlank()) ValueRow("Notes", meta.notes)
    } else if (s.metaJsonFallback != null) {
        Text(
            "Meta (unparsed): " + s.metaJsonFallback,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    } else {
        Text("No meta record.", style = MaterialTheme.typography.bodySmall)
    }
    if (s.annotations.isNotEmpty()) {
        HorizontalDivider()
        Text("Annotations", style = MaterialTheme.typography.labelLarge)
        for (a in s.annotations) Text(a, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    if (s.events.isNotEmpty()) {
        HorizontalDivider()
        Text("Events", style = MaterialTheme.typography.labelLarge)
        for (e in s.events) Text(e, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun RunsCard(ui: DebugUiState, onToggle: (Int) -> Unit, onLoadConfig: (PipelineConfig) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Processing runs", style = MaterialTheme.typography.titleMedium)
            if (ui.runs.isEmpty()) Text("No runs stored yet.", style = MaterialTheme.typography.bodySmall)
            for (run in ui.runs) {
                RunRow(
                    run = run,
                    expanded = ui.expandedRunId == run.entity.runId,
                    onToggle = { onToggle(run.entity.runId) },
                    onLoadConfig = onLoadConfig,
                )
            }
        }
    }
}

@Composable
private fun RunRow(run: RunInfo, expanded: Boolean, onToggle: () -> Unit, onLoadConfig: (PipelineConfig) -> Unit) {
    val e = run.entity
    HorizontalDivider()
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp)) {
        val label = if (e.label.isNotBlank()) " · " + e.label else ""
        Text(
            "Run " + e.runId + label + " · pipeline v" + e.pipelineVersion,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(dateText(e.createdAtEpochMs), style = MaterialTheme.typography.bodySmall)
        val s = run.stats
        if (s != null) Text(statsLine(s), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    if (expanded) {
        val stats = run.stats
        if (stats != null) {
            ValueRow("Distance", Fmt.metres(stats.distanceM))
            ValueRow("Duration", Fmt.durationText(stats.durationS))
            ValueRow("Steps", stats.stepCount.toString())
            ValueRow("Height range", Fmt.metres(stats.minZ) + " to " + Fmt.metres(stats.maxZ))
            ValueRow("Closure error", stats.closureErrorM?.let { Fmt.metres(it) } ?: "no loop declared")
            ValueRow("VIO fraction", Fmt.percent(stats.vioFraction * 100.0))
        }
        when {
            run.diagnosticsLoading -> CircularProgressIndicator()
            run.diagnosticsError != null -> Text(run.diagnosticsError, color = MaterialTheme.colorScheme.error)
            run.diagnostics != null -> {
                if (run.diagnostics.isEmpty()) {
                    Text("No diagnostics in this run.", style = MaterialTheme.typography.bodySmall)
                }
                for ((k, v) in run.diagnostics) {
                    Text("$k = $v", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
        val config = run.config
        if (config != null) {
            TextButton(onClick = { onLoadConfig(config) }) { Text("Load this run's config into the editor") }
        }
    }
}

private fun statsLine(s: PathStats): String =
    Fmt.metres(s.distanceM) + " · " + s.stepCount + " steps · " + Fmt.durationText(s.durationS) +
        (s.closureErrorM?.let { " · closure " + Fmt.metres(it) } ?: "")

private fun dateText(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(epochMs))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfigEditorCard(
    ui: DebugUiState,
    onField: (ConfigField, String) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onReprocess: () -> Unit,
    onPdrOnly: () -> Unit,
    onVioOnly: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pipeline config", style = MaterialTheme.typography.titleMedium)
            Text(
                if (ui.draftDirty) "Edited (not saved as calibration)" else "Current calibration",
                style = MaterialTheme.typography.bodySmall,
            )
            for (field in ConfigField.entries) {
                when (field.type) {
                    FieldType.BOOL -> BoolField(field, ui.draft.bool(field)) { onField(field, it.toString()) }
                    FieldType.DOUBLE, FieldType.INT -> NumberField(field, ui.draft.text(field), ui.errors[field]) {
                        onField(field, it)
                    }
                }
            }
            val canRun = ui.tripId != null && !ui.processing && ui.errors.isEmpty()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReprocess, enabled = canRun) { Text("Re-process with this config") }
                OutlinedButton(onClick = onPdrOnly, enabled = canRun) { Text("Run PDR only") }
                OutlinedButton(onClick = onVioOnly, enabled = canRun && ui.summary?.hasVio == true) {
                    Text("Run VIO only")
                }
                TextButton(onClick = onSave, enabled = !ui.processing && ui.errors.isEmpty()) {
                    Text("Save as calibration")
                }
                TextButton(onClick = onReset, enabled = ui.draftDirty) { Text("Reset") }
            }
            if (ui.processing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Processing…")
                }
            }
        }
    }
}

@Composable
private fun BoolField(field: ConfigField, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(field.label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(field: ConfigField, text: String, error: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        label = { Text(field.label) },
        isError = error != null,
        supportingText = { Text(error ?: field.hint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (field.type == FieldType.INT) KeyboardType.Number else KeyboardType.Decimal,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
