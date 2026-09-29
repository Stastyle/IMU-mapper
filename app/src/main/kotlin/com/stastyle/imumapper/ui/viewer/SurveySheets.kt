package com.stastyle.imumapper.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.pipeline.survey.Detail
import com.stastyle.imumapper.pipeline.survey.LegTotals
import com.stastyle.imumapper.pipeline.survey.Station
import com.stastyle.imumapper.pipeline.survey.StationKind
import com.stastyle.imumapper.pipeline.survey.TraverseLeg

/** The traverse as a table, one row per leg; a tap on a row selects that leg on the map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegsSheet(
    legs: List<TraverseLeg>,
    totals: LegTotals,
    magnetic: Boolean,
    onSelect: (fromId: Int, toId: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            "Legs",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        TableRow(SurveyFormat.tableHeader(magnetic), bold = true)
        HorizontalDivider()
        // Not filling: the totals row stays on screen under a long table.
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(legs) { leg ->
                TableRow(
                    SurveyFormat.tableRow(leg),
                    modifier = Modifier.clickable { onSelect(leg.from.id, leg.to.id) },
                )
            }
        }
        HorizontalDivider()
        TableRow(SurveyFormat.totalsRow(totals), bold = true)
        Spacer(Modifier.height(24.dp))
    }
}

/** A long press on a station: rename or delete it. Both are refused while the survey is read-only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationSheet(
    station: Station,
    readOnly: Boolean,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(station.id) { mutableStateOf(station.name) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(station.name, style = MaterialTheme.typography.titleMedium)
            Text(
                kindLabel(station.kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                enabled = !readOnly,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete, enabled = !readOnly) {
                    Text("Delete", color = MaterialTheme.colorScheme.error.copy(alpha = if (readOnly) 0.38f else 1f))
                }
                TextButton(
                    onClick = { onRename(name) },
                    enabled = !readOnly && name.isNotBlank() && name.trim() != station.name,
                ) { Text("Rename") }
            }
            if (station.kind == StationKind.CORNER) {
                Text(
                    "A deleted corner comes back only when the corner detail changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (readOnly) {
                Text(
                    "survey.json could not be read, so nothing can be changed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** How finely corners are found; each choice shows how many corners it would give now. */
@Composable
fun DetailDialog(current: Detail, counts: Map<Detail, Int>, onSelect: (Detail) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Corner detail") },
        text = {
            Column {
                Text(
                    "A change places the automatic corners again. Stations you added or moved stay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                for (detail in Detail.entries) {
                    val selected = detail == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(detail) })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text("${SurveyFormat.detailLabel(detail)} · ${SurveyFormat.corners(counts[detail] ?: 0)}")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** One row of the legs table; the two name columns get more room than the numbers. */
@Composable
private fun TableRow(cells: List<String>, modifier: Modifier = Modifier, bold: Boolean = false) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        cells.forEachIndexed { i, cell ->
            Text(
                cell,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (bold) FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (i < NAME_COLUMNS) TextAlign.Start else TextAlign.End,
                modifier = Modifier.weight(COLUMN_WEIGHTS.getOrElse(i) { 1f }).padding(horizontal = 2.dp),
            )
        }
    }
}

private fun kindLabel(kind: StationKind): String = when (kind) {
    StationKind.START -> "Start of the path"
    StationKind.END -> "End of the path"
    StationKind.MARK -> "Marked while walking"
    StationKind.CORNER -> "Automatic corner"
    StationKind.USER -> "Added in Survey mode"
}

private const val NAME_COLUMNS = 2

/** From, To, Length, Azimuth, Slope, Δh, Path. */
private val COLUMN_WEIGHTS = floatArrayOf(1.5f, 1.5f, 1f, 1f, 0.8f, 0.9f, 1.1f)
