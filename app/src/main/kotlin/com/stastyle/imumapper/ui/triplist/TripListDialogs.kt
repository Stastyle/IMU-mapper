package com.stastyle.imumapper.ui.triplist

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.CarryPosition
import com.stastyle.imumapper.pipeline.core.TripMode

/**
 * Mode and carry-position picker shown by the "New trip" button. [initialMode] is the Settings
 * default; the selection resets if it changes while the dialog is open (only during the first
 * DataStore read), otherwise the user's choice stays.
 */
@Composable
fun NewTripDialog(
    initialMode: TripMode,
    initialCarryPosition: CarryPosition,
    onDismiss: () -> Unit,
    onStart: (TripMode, CarryPosition) -> Unit,
) {
    var mode by rememberSaveable(initialMode) { mutableStateOf(initialMode) }
    var carry by rememberSaveable { mutableStateOf(initialCarryPosition) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New trip") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Capture mode", style = MaterialTheme.typography.labelLarge)
                for (m in TripMode.entries) {
                    ModeOption(mode = m, selected = m == mode, onSelect = { mode = m })
                }
                Spacer(Modifier.height(12.dp))
                Text("Phone carried in", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    for (p in CarryPosition.entries) {
                        FilterChip(
                            selected = p == carry,
                            onClick = { carry = p },
                            label = { Text(TripFormat.carryLabel(p)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(mode, carry) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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

/** Long-press menu for one trip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripActionsSheet(
    trip: TripEntity,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onReprocess: () -> Unit,
    onDelete: () -> Unit,
) {
    val canProcess = trip.status != TripStatus.RECORDING && !busy
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Text(
            trip.name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        SheetAction(text = "Rename", icon = Icons.Filled.Edit, enabled = true, onClick = onRename)
        SheetAction(text = "Export as ZIP", icon = Icons.Filled.Share, enabled = canProcess, onClick = onExport)
        SheetAction(
            text = if (trip.latestRunId == null) "Process" else "Re-process with current calibration",
            icon = Icons.Filled.Refresh,
            enabled = canProcess,
            onClick = onReprocess,
        )
        SheetAction(text = "Delete", icon = Icons.Filled.Delete, enabled = canProcess, onClick = onDelete)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SheetAction(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        headlineContent = { Text(text, color = color) },
        leadingContent = { Icon(icon, contentDescription = null, tint = color) },
        modifier = Modifier.selectable(selected = false, enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

@Composable
fun RenameTripDialog(trip: TripEntity, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by rememberSaveable(trip.id) { mutableStateOf(trip.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename trip") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DeleteTripDialog(trip: TripEntity, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete trip?") },
        text = { Text("\"${trip.name}\" and its raw log, results and photos will be removed. This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shown when the FAILED chip of a trip is tapped: the stored error and what to do about it. */
@Composable
fun TripErrorDialog(trip: TripEntity, onDismiss: () -> Unit, onReprocess: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Processing failed") },
        text = {
            Column {
                Text(trip.lastError ?: "No error message was recorded.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "The raw log is kept. Check the calibration or the log in the debug screen, then re-process.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onReprocess) { Text("Re-process") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
