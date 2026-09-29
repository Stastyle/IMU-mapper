package com.stastyle.imumapper.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.pipeline.survey.ReferenceLine
import com.stastyle.imumapper.pipeline.survey.SurveyCsv
import java.util.Locale
import kotlin.math.abs

/**
 * A hand-compass bearing for the selected pair or stretch. It previews the turn before anything is
 * saved, with a warning for each way a reading usually goes wrong, so a slip is caught here and not
 * in the map.
 */
@Composable
fun SetAzimuthDialog(
    fromName: String,
    toName: String,
    magnetic: Boolean,
    preview: (bearingDeg: Double, backBearing: Boolean, line: ReferenceLine) -> AzimuthPreview?,
    onConfirm: (bearingDeg: Double, backBearing: Boolean, line: ReferenceLine) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var backBearing by rememberSaveable { mutableStateOf(false) }
    var line by rememberSaveable { mutableStateOf(ReferenceLine.CHORD) }
    val bearing = SurveyFormat.parseDegrees(text)
    // The current readings do not depend on the bearing, so 0 stands in until one is typed.
    val shown = remember(bearing, backBearing, line) { preview(bearing ?: 0.0, backBearing, line) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set azimuth") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("$fromName → $toName", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Now: point to point ${SurveyFormat.azimuth(shown?.chordDeg, magnetic)} · " +
                        "passage ${SurveyFormat.azimuth(shown?.fittedDeg, magnetic)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Compass bearing (°)") },
                    singleLine = true,
                    isError = text.isNotBlank() && bearing == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = backBearing, role = Role.Checkbox, onValueChange = { backBearing = it })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = backBearing, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Back-bearing: read at $toName looking back to $fromName")
                }
                LineOption(ReferenceLine.CHORD, line, "Point to point (the straight line)") { line = it }
                LineOption(ReferenceLine.FITTED, line, "Passage direction (the fitted line)") { line = it }
                if (bearing != null && shown != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        turnText(shown),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    for (warning in AzimuthWarning.entries) {
                        if (warning in shown.warnings) {
                            Text(
                                warningText(warning, backBearing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            // Without a chord on the path the reading could never be used, so it is not offered.
            TextButton(
                onClick = { if (bearing != null) onConfirm(bearing, backBearing, line) },
                enabled = bearing != null && shown?.chordDeg != null,
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The north chip's sheet: the manual rotation (steppers and a typed value, off while compass readings
 * set north), each reading with its residual, and Reset north.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NorthSheet(
    survey: SurveyUi,
    onNudge: (Double) -> Unit,
    onSet: (Double) -> Unit,
    onDeleteReference: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val doc = survey.state.doc
    val readOnly = survey.state.readOnly
    val manualEnabled = !readOnly && doc.references.isEmpty()
    var typed by rememberSaveable { mutableStateOf("") }
    val typedDeg = SurveyFormat.parseDegrees(typed)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
            Text("North", style = MaterialTheme.typography.titleMedium)
            Text(
                "${SurveyFormat.northChip(survey.north, survey.magnetic)}: ${SurveyCsv.correctionText(survey.north)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text("Turn by hand", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((step, label) in STEPS) {
                    OutlinedButton(
                        onClick = { onNudge(step) },
                        enabled = manualEnabled,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text(label) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Rotation (°)") },
                    singleLine = true,
                    enabled = manualEnabled,
                    isError = typed.isNotBlank() && typedDeg == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        if (typedDeg != null) {
                            onSet(typedDeg)
                            typed = ""
                        }
                    },
                    enabled = manualEnabled && typedDeg != null,
                ) { Text("Set") }
            }
            if (doc.references.isNotEmpty()) {
                Text(
                    "Compass readings set north. Delete them to turn by hand.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("Compass readings", style = MaterialTheme.typography.titleSmall)
            if (doc.references.isEmpty()) {
                Text(
                    "None yet. Select two stations or a straight stretch and use Set azimuth.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (reference in doc.references) {
                val fit = survey.north.fits.firstOrNull { it.referenceId == reference.id }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        SurveyFormat.reference(reference, fit),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onDeleteReference(reference.id) }, enabled = !readOnly) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete compass reading")
                    }
                }
            }
            if (survey.north.disagree) {
                Text(
                    SurveyFormat.DISAGREE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            val corrected =
                doc.references.isNotEmpty() || doc.manualRotationDeg != 0.0 || doc.manualRotationRunId != null
            TextButton(onClick = onReset, enabled = !readOnly && corrected) { Text("Reset north") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Asked when the manual rotation was set on another run: a re-process can change the heading offset,
 * so the same angle may not fit this run.
 */
@Composable
fun ManualRotationDialog(rotationDeg: Double, fromRunId: Int?, toRunId: Int, onAnswer: (apply: Boolean) -> Unit) {
    val from = fromRunId?.let { "run $it" } ?: "another run"
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("Turn this run too?") },
        text = {
            Text(
                "North was turned ${SurveyFormat.rotation(rotationDeg)} by hand on $from. A re-process can " +
                    "change the heading, so check that the same turn fits run $toRunId.",
            )
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Apply on run $toRunId") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Not now") } },
    )
}

@Composable
private fun LineOption(value: ReferenceLine, current: ReferenceLine, label: String, onSelect: (ReferenceLine) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = value == current, role = Role.RadioButton, onClick = { onSelect(value) })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == current, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** The change is what the user sees happen; the total is added when earlier corrections exist. */
private fun turnText(preview: AzimuthPreview): String {
    val turn = "The map turns ${SurveyFormat.rotation(preview.changeDeg)} about the start"
    val earlier = abs(preview.rotationDeg - preview.changeDeg) >= SAME_TURN_DEG
    return if (earlier) "$turn (north ${SurveyFormat.rotation(preview.rotationDeg)} in all)" else turn
}

/**
 * A warning's line in the Set azimuth dialog. With [backBearing] ticked a turn over 45° cannot be an
 * unticked back-bearing, so that line asks the user to check the reading and the box instead.
 */
internal fun warningText(warning: AzimuthWarning, backBearing: Boolean): String = when (warning) {
    AzimuthWarning.SHORT -> String.format(
        Locale.US,
        "Under %.0f m across: a small error in the path is a large angle here.",
        SurveyController.MIN_REFERENCE_HORIZONTAL_M,
    )
    AzimuthWarning.CROOKED -> "The path bends here: point to point and the passage direction differ."
    AzimuthWarning.LARGE_CHANGE ->
        String.format(Locale.US, "This turns the map by more than %.0f°.", SurveyController.LARGE_CHANGE_DEG)
    AzimuthWarning.BACK_BEARING -> if (backBearing) {
        String.format(
            Locale.US,
            "Over %.0f° as a back-bearing: check the reading and the Back-bearing box.",
            SurveyController.BACK_BEARING_CHANGE_DEG,
        )
    } else {
        String.format(Locale.US, "Over %.0f°: was it a back-bearing?", SurveyController.BACK_BEARING_CHANGE_DEG)
    }
}

/** Below a twentieth of a degree the total and the change print the same. */
private const val SAME_TURN_DEG = 0.05

private val STEPS = listOf(-5.0 to "-5°", -0.5 to "-0.5°", 0.5 to "+0.5°", 5.0 to "+5°")
