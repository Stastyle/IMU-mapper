package com.stastyle.imumapper.ui.triplist

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.data.TripExporter
import com.stastyle.imumapper.data.TripImporter
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.data.db.TripStatus
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.ui.common.appContainer

/**
 * Home screen and the Trips tab: the recorded trips, the way to a new one, import and Debug.
 *
 * [onOpenRecording] is called instead of [onOpenTrip] for the trip being recorded right now, with the
 * mode it is recorded in. [onNewTrip] opens the Record tab. [banner] is a slot above the list for the
 * updater's "new version available" banner (`com.stastyle.imumapper.ui.settings.UpdateBanner`), and
 * [bottomBar] the slot for the tab bar, both wired by the navigation graph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripListScreen(
    onOpenTrip: (Long) -> Unit,
    onOpenRecording: (TripMode) -> Unit,
    onNewTrip: () -> Unit,
    onOpenDebug: () -> Unit,
    banner: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val container = appContainer()
    val vm: TripListViewModel = viewModel {
        TripListViewModel(
            trips = container.tripRepository,
            processor = container.tripProcessor,
            exporter = TripExporter(context.applicationContext, container.tripRepository, container.tripFiles),
            importer = TripImporter(context.applicationContext, container.tripRepository, container.tripFiles),
        )
    }
    val trips by vm.tripList.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val recorder = remember(context) { RecordingController.get(context) }
    // Read only when a row is tapped, so the recorder's ticks do not recompose the list.
    val recording by recorder.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var sheetTrip by remember { mutableStateOf<TripEntity?>(null) }
    var renameTrip by remember { mutableStateOf<TripEntity?>(null) }
    var deleteTrip by remember { mutableStateOf<TripEntity?>(null) }
    var errorTrip by remember { mutableStateOf<TripEntity?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importTrip(uri)
    }

    LaunchedEffect(ui.message) {
        val text = ui.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        vm.dismissMessage()
    }
    LaunchedEffect(ui.pendingShare) {
        val intent = ui.pendingShare ?: return@LaunchedEffect
        runCatching { context.startActivity(intent) }
        vm.consumeShare()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trips") },
                actions = {
                    IconButton(onClick = { importLauncher.launch(IMPORT_MIME_TYPES) }, enabled = !ui.importing) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Import trip")
                    }
                    IconButton(onClick = onOpenDebug) {
                        Icon(Icons.Filled.BugReport, contentDescription = "Debug")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("New trip") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = onNewTrip,
            )
        },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            banner()
            if (ui.importing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (trips.isEmpty()) {
                EmptyState(modifier = Modifier.fillMaxSize())
            } else {
                TripList(
                    trips = trips,
                    busyTripIds = ui.busyTripIds,
                    onOpen = { trip ->
                        val mode = recordingModeFor(trip, recording)
                        if (mode != null) onOpenRecording(mode) else onOpenTrip(trip.id)
                    },
                    onLongPress = { sheetTrip = it },
                    onShowError = { errorTrip = it },
                )
            }
        }
    }

    sheetTrip?.let { trip ->
        TripActionsSheet(
            trip = trip,
            busy = trip.id in ui.busyTripIds,
            onDismiss = { sheetTrip = null },
            onRename = { sheetTrip = null; renameTrip = trip },
            onExport = { sheetTrip = null; vm.export(trip.id) },
            onReprocess = { sheetTrip = null; vm.reprocess(trip.id) },
            onDelete = { sheetTrip = null; deleteTrip = trip },
        )
    }
    renameTrip?.let { trip ->
        RenameTripDialog(
            trip = trip,
            onDismiss = { renameTrip = null },
            onRename = { name -> renameTrip = null; vm.rename(trip.id, name) },
        )
    }
    deleteTrip?.let { trip ->
        DeleteTripDialog(
            trip = trip,
            onDismiss = { deleteTrip = null },
            onConfirm = { deleteTrip = null; vm.delete(trip.id) },
        )
    }
    errorTrip?.let { trip ->
        TripErrorDialog(
            trip = trip,
            onDismiss = { errorTrip = null },
            onReprocess = { errorTrip = null; vm.reprocess(trip.id) },
        )
    }
}

@Composable
private fun TripList(
    trips: List<TripEntity>,
    busyTripIds: Set<Long>,
    onOpen: (TripEntity) -> Unit,
    onLongPress: (TripEntity) -> Unit,
    onShowError: (TripEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Room for the FAB so the last row is not hidden behind it.
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        items(trips, key = { it.id }) { trip ->
            TripRow(
                trip = trip,
                busy = trip.id in busyTripIds,
                onOpen = { onOpen(trip) },
                onLongPress = { onLongPress(trip) },
                onShowError = { onShowError(trip) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TripRow(
    trip: TripEntity,
    busy: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onShowError: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(trip.name, maxLines = 1) },
        supportingContent = { Text(TripFormat.summaryLine(trip.startedAtEpochMs, trip.durationS, trip.distanceM)) },
        leadingContent = {
            Icon(TripFormat.modeIcon(trip.mode), contentDescription = TripFormat.modeLabel(trip.mode))
        },
        trailingContent = {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                StatusChip(status = trip.status, onShowError = onShowError)
            }
        },
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    )
}

@Composable
private fun StatusChip(status: TripStatus, onShowError: () -> Unit) {
    when (status) {
        TripStatus.RECORDING -> AssistChip(
            onClick = {},
            label = { Text("Recording") },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
        )
        TripStatus.RECORDED -> AssistChip(onClick = {}, label = { Text("Recorded") })
        TripStatus.PROCESSED -> AssistChip(
            onClick = {},
            label = { Text("Processed") },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        )
        TripStatus.FAILED -> AssistChip(
            onClick = onShowError,
            label = { Text("Failed") },
            leadingIcon = {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = "Show error",
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                labelColor = MaterialTheme.colorScheme.onErrorContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Explore,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text("No trips yet", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "1. Open the Calibrate tab and do the still and stride steps once; the heading step is " +
                    "optional.\n" +
                    "2. Open the Record tab, pick a mode and tap Continue, then pick where the phone is carried " +
                    "and wait for the compass to find north.\n" +
                    "3. Walk, then stop: the route appears as a 3D path you can rotate.\n\n" +
                    "Every raw log is kept, so a trip can be re-processed after calibration improves. " +
                    "Use Import to open a ZIP or .imul exported from another phone.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val IMPORT_MIME_TYPES = arrayOf("application/zip", "application/octet-stream", "*/*")
