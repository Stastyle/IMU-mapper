package com.stastyle.imumapper.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.pipeline.core.PathResult
import com.stastyle.imumapper.render.PathProfile
import com.stastyle.imumapper.ui.common.BrandButton
import com.stastyle.imumapper.ui.common.GlassCard
import com.stastyle.imumapper.ui.common.SectionHeader
import com.stastyle.imumapper.ui.common.StatGrid
import com.stastyle.imumapper.ui.common.StatTileData
import com.stastyle.imumapper.ui.theme.imuColors
import com.stastyle.imumapper.ui.triplist.TripFormat

private val CardGap = 12.dp
private val CardPadding = 16.dp

/**
 * The Path tab under the canvas: the Trip Summary tiles and the elevation profile of the shown result. It
 * scrolls on its own, so the canvas above it keeps its size.
 */
@Composable
internal fun PathTabCards(shown: PathResult, modifier: Modifier = Modifier) {
    val profile = remember(shown) { PathProfile.elevation(shown.points) }
    val tiles = remember(shown) { summaryTiles(shown) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = CardGap),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        CardSection("Trip Summary", Icons.Filled.Insights) { StatGrid(tiles, maxColumns = 3) }
        if (showsElevation(shown.points.size, profile)) {
            CardSection("Elevation", Icons.Filled.Landscape) { ElevationChart(profile) }
        }
    }
}

/** The Graph tab: the elevation profile large, and the height numbers of the shown result under it. */
@Composable
internal fun GraphTab(shown: PathResult, modifier: Modifier = Modifier) {
    val profile = remember(shown) { PathProfile.elevation(shown.points) }
    val tiles = remember(shown) {
        val climbs = PathProfile.climbs(shown.points, ViewerStats.CLIMB_DEAD_BAND_M)
        val stats = shown.stats
        listOf(
            tile("Min", Icons.Filled.ArrowDownward, ViewerStats.min(stats)),
            tile("Max", Icons.Filled.ArrowUpward, ViewerStats.max(stats)),
            tile("Net change", Icons.Filled.SwapVert, ViewerStats.netChange(shown.points)),
            tile("Climb", Icons.AutoMirrored.Filled.TrendingUp, ViewerStats.climb(climbs)),
            tile("Descent", Icons.AutoMirrored.Filled.TrendingDown, ViewerStats.descent(climbs)),
        )
    }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = CardGap),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        CardSection("Elevation profile", Icons.Filled.Landscape) {
            if (showsElevation(shown.points.size, profile)) {
                ElevationChart(profile, plotHeight = 240.dp)
            } else {
                Text(
                    "The path is too short for a profile.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        CardSection("Height", Icons.Filled.Height) { StatGrid(tiles, maxColumns = 4) }
    }
}

/**
 * The Details tab: which run is shown and which is overlaid, the raw path switch, the trip's facts, and the
 * trip's actions. [onExport] is null when no exporter is wired, which hides Export ZIP as it hides Share.
 */
@Composable
internal fun DetailsTab(
    ui: ViewerUiState,
    onSelectRun: (Int) -> Unit,
    onSelectOverlay: (Int?) -> Unit,
    onToggleRaw: () -> Unit,
    onSurveyMode: () -> Unit,
    onExport: (() -> Unit)?,
    onOpenDebug: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = CardGap),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        if (ui.runs.isNotEmpty()) {
            CardSection("Runs", Icons.Filled.History) { RunList(ui, onSelectRun, onSelectOverlay) }
        }
        CardSection("Path", Icons.Filled.Route) { RawPathRow(ui, onToggleRaw) }
        val trip = ui.trip
        if (trip != null) {
            val facts = tripFacts(
                trip,
                runDurationS = ui.result?.stats?.durationS,
                north = northText(ui.shownResult?.diagnostics),
                date = TripFormat::date,
            )
            CardSection("Trip", Icons.Filled.Info) { TripFactsList(facts) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BrandButton(onClick = onSurveyMode, modifier = Modifier.fillMaxWidth()) {
                ButtonLabel(Icons.Filled.SquareFoot, "Survey mode")
            }
            if (onExport != null) {
                OutlinedButton(onClick = onExport, enabled = ui.shareEnabled, modifier = Modifier.fillMaxWidth()) {
                    if (ui.busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Export ZIP")
                    } else {
                        ButtonLabel(Icons.Filled.FolderZip, "Export ZIP")
                    }
                }
            }
            OutlinedButton(onClick = onOpenDebug, modifier = Modifier.fillMaxWidth()) {
                ButtonLabel(Icons.Filled.BugReport, "Debug")
            }
        }
    }
}

/** The runs as radio choices: the one shown, and (with two or more) the one drawn dimmed under it. */
@Composable
internal fun RunList(ui: ViewerUiState, onSelectRun: (Int) -> Unit, onSelectOverlay: (Int?) -> Unit) {
    Column {
        ListCaption("Show run")
        Column(modifier = Modifier.selectableGroup()) {
            for (run in ui.runs) {
                ChoiceRow(runLabel(run), selected = run.runId == ui.selectedRunId) { onSelectRun(run.runId) }
            }
        }
        if (ui.runs.size > 1) {
            Spacer(Modifier.height(8.dp))
            ListCaption("Overlay (dimmed)")
            Column(modifier = Modifier.selectableGroup()) {
                ChoiceRow("None", selected = ui.overlayRunId == null) { onSelectOverlay(null) }
                for (run in ui.runs) {
                    if (run.runId == ui.selectedRunId) continue
                    ChoiceRow(runLabel(run), selected = run.runId == ui.overlayRunId) { onSelectOverlay(run.runId) }
                }
            }
        }
    }
}

/** The overflow's Runs…: the same choices as the Details tab, from any tab. */
@Composable
internal fun RunsDialog(
    ui: ViewerUiState,
    onSelectRun: (Int) -> Unit,
    onSelectOverlay: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Runs") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                RunList(ui, onSelectRun, onSelectOverlay)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** The Raw path switch with [rawPathHint] under its label; the whole row toggles it. */
@Composable
internal fun RawPathRow(ui: ViewerUiState, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = ui.showRaw, role = Role.Switch, onValueChange = { onToggle() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Raw path", style = MaterialTheme.typography.bodyLarge)
            Text(
                rawPathHint(ui),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = ui.showRaw, onCheckedChange = null)
    }
}

@Composable
private fun TripFactsList(facts: TripFacts) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (fact in facts.rows) {
            Row(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Text(
                    fact.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 96.dp).padding(end = 12.dp),
                )
                Text(fact.value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        if (facts.endedUnexpectedly) {
            val warning = MaterialTheme.imuColors.warning
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = warning,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${RecordingController.ENDED_UNEXPECTEDLY_NOTE}. The app found it later, so when it stopped is " +
                        "not known.",
                    style = MaterialTheme.typography.bodySmall,
                    color = warning,
                )
            }
        }
    }
}

/** A glass card with a section header and [content] under it. */
@Composable
private fun CardSection(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(title = title, icon = icon)
            content()
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ListCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun ButtonLabel(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(text)
}

/** The Trip Summary's six tiles, in the mockup's order. */
private fun summaryTiles(shown: PathResult): List<StatTileData> {
    val stats = shown.stats
    return listOf(
        tile("Distance", Icons.Filled.Route, ViewerStats.distance(stats)),
        tile("Duration", Icons.Filled.Timer, ViewerStats.duration(stats, shown.diagnostics)),
        tile("Steps", Icons.AutoMirrored.Filled.DirectionsWalk, ViewerStats.steps(stats)),
        tile("Vertical", Icons.Filled.Height, ViewerStats.vertical(stats), spokenLabel = "Vertical range"),
        tile("Closure", Icons.Filled.TrackChanges, ViewerStats.closure(stats)),
        tile("VIO", Icons.Filled.SignalCellularAlt, ViewerStats.vio(stats)),
    )
}

/** A tile from [text]; when it has its own spoken words, they replace what TalkBack would read. */
private fun tile(label: String, icon: ImageVector, text: StatText, spokenLabel: String = label): StatTileData =
    StatTileData(
        label = label,
        value = text.value,
        icon = icon,
        detail = text.detail,
        contentDescription = text.spoken?.let { "$spokenLabel: $it" },
    )
