package com.stastyle.imumapper.ui.triplist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus

/** The actions of one trip, from its card's actions button or a long press on the card. */
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

/** Shown when the Failed pill of a trip is tapped: the stored error and what to do about it. */
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
