package com.stastyle.imumapper.ui.triplist

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stastyle.imumapper.capture.RecordingController
import com.stastyle.imumapper.data.db.TripEntity
import com.stastyle.imumapper.pipeline.core.TripMode
import com.stastyle.imumapper.render.PathThumbnail
import com.stastyle.imumapper.ui.common.AppScaffold
import com.stastyle.imumapper.ui.common.BrandButton
import com.stastyle.imumapper.ui.common.BrandFilterChip
import com.stastyle.imumapper.ui.common.GlassCard
import com.stastyle.imumapper.ui.common.ScreenHeader
import com.stastyle.imumapper.ui.common.SectionHeader
import com.stastyle.imumapper.ui.common.appContainer
import com.stastyle.imumapper.ui.theme.imuColors
import kotlinx.coroutines.flow.drop

/**
 * Home screen and the Trips tab: the recorded trips as cards under a search field and filter chips, with import and
 * Debug in the header's menu.
 *
 * [onOpenRecording] is called instead of [onOpenTrip] for the trip being recorded right now, with the mode it is
 * recorded in. [onNewTrip] opens the Record tab, from the onboarding card. [banner] is a slot at the top of the list
 * for the updater's "new version available" banner (`com.stastyle.imumapper.ui.settings.UpdateBanner`), and
 * [bottomBar] the slot for the tab bar, both wired by the navigation graph.
 */
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
            exporter = container.tripExporter,
            importer = container.tripImporter,
            thumbnails = container.tripThumbnails,
            json = container.json,
        )
    }
    val content by vm.content.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val recorder = remember(context) { RecordingController.get(context) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    // Remembered so every card gets the same instances and can skip recomposing when the list around it changes.
    val cachedThumbnail = remember(vm) { vm::cachedThumbnail }
    val loadThumbnail = remember(vm) { vm::thumbnail }
    // The field's own state, updated in the same frame as the keystroke; a text field fed back from a StateFlow
    // arrives a frame late and can drop fast typing. The ViewModel follows it for the filtering.
    var searchText by remember { mutableStateOf(vm.query.value.text) }

    var sheetTrip by remember { mutableStateOf<TripEntity?>(null) }
    var renameTrip by remember { mutableStateOf<TripEntity?>(null) }
    var deleteTrip by remember { mutableStateOf<TripEntity?>(null) }
    var errorTrip by remember { mutableStateOf<TripEntity?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importTrip(uri)
    }
    val startImport = { importLauncher.launch(IMPORT_MIME_TYPES) }

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
    LaunchedEffect(listState) {
        // A new search, filter or sort starts the results just under the controls, instead of wherever the old list
        // was scrolled to. Skips the current value, so coming back to the tab keeps its scroll position.
        vm.query.drop(1).collect {
            if (listState.firstVisibleItemIndex > CONTROLS_INDEX) listState.scrollToItem(CONTROLS_INDEX)
        }
    }

    AppScaffold(
        topBar = {
            Column {
                ScreenHeader(
                    title = "IMU Mapper",
                    subtitle = "INDOOR PATH TRACKING",
                    tagline = true,
                    actions = { HeaderMenu(ui.importing, onImport = startImport, onOpenDebug = onOpenDebug) },
                )
                if (ui.importing) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Importing a trip" },
                    )
                }
            }
        },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        // Nothing until the database answers, so a list of trips never flashes the onboarding card first.
        val loaded = content ?: return@AppScaffold
        LazyColumn(
            state = listState,
            // The list scrolls under the translucent tab bar but not under the transparent header.
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding(),
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                ),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp),
        ) {
            tripListItems(
                content = loaded,
                query = query,
                searchText = searchText,
                busyTripIds = ui.busyTripIds,
                importing = ui.importing,
                listState = listState,
                banner = banner,
                onSearchText = {
                    searchText = it
                    vm.setSearchText(it)
                },
                onFilter = vm::setFilter,
                onSort = vm::setSort,
                onClearFilters = {
                    searchText = ""
                    vm.clearFilters()
                },
                onNewTrip = onNewTrip,
                onImport = startImport,
                onOpen = { item ->
                    // Read on tap only: the recorder ticks four times a second and must not recompose the list.
                    val mode = recordingModeFor(item, recorder.state.value)
                    if (mode != null) onOpenRecording(mode) else onOpenTrip(item.id)
                },
                onActions = { sheetTrip = it.trip },
                onShowError = { errorTrip = it.trip },
                cachedThumbnail = cachedThumbnail,
                loadThumbnail = loadThumbnail,
            )
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

/**
 * The list: the update banner, then either the onboarding card, or the sticky search and filters followed by the
 * section row and the cards (or the no-match message). Every item has a stable key, so a card keeps its thumbnail and
 * state as the list is filtered and re-sorted.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.tripListItems(
    content: TripListContent,
    query: TripListQuery,
    searchText: String,
    busyTripIds: Set<Long>,
    importing: Boolean,
    listState: LazyListState,
    banner: @Composable () -> Unit,
    onSearchText: (String) -> Unit,
    onFilter: (TripFilter) -> Unit,
    onSort: (TripSort) -> Unit,
    onClearFilters: () -> Unit,
    onNewTrip: () -> Unit,
    onImport: () -> Unit,
    onOpen: (TripListItem) -> Unit,
    onActions: (TripListItem) -> Unit,
    onShowError: (TripListItem) -> Unit,
    cachedThumbnail: (tripId: Long, runId: Int) -> PathThumbnail?,
    loadThumbnail: suspend (tripId: Long, runId: Int) -> PathThumbnail?,
) {
    // The banner renders nothing unless there is an update to act on.
    item(key = KEY_BANNER, contentType = KEY_BANNER) { banner() }
    if (content.totalCount == 0) {
        item(key = KEY_ONBOARDING, contentType = KEY_ONBOARDING) {
            OnboardingCard(
                importing = importing,
                onNewTrip = onNewTrip,
                onImport = onImport,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        return
    }
    // Sticky rather than a plain item: a focused field in a plain item is disposed when it scrolls away, closing the
    // keyboard mid-word.
    stickyHeader(key = KEY_CONTROLS, contentType = KEY_CONTROLS) {
        SearchAndFilters(
            text = searchText,
            filter = query.filter,
            listState = listState,
            onText = onSearchText,
            onFilter = onFilter,
        )
    }
    if (content.items.isEmpty()) {
        item(key = KEY_NO_MATCH, contentType = KEY_NO_MATCH) { NoMatch(onClearFilters) }
        return
    }
    item(key = KEY_SECTION, contentType = KEY_SECTION) {
        SectionHeader(
            title = if (query.sort == TripSort.NEWEST) "Recent Trips" else "Trips",
            action = { SortMenu(query.sort, onSort) },
            modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 4.dp),
        )
    }
    items(content.items, key = { it.id }, contentType = { CARD_CONTENT_TYPE }) { item ->
        TripCard(
            item = item,
            busy = item.id in busyTripIds,
            onOpen = { onOpen(item) },
            onActions = { onActions(item) },
            onShowError = { onShowError(item) },
            cachedThumbnail = cachedThumbnail,
            loadThumbnail = loadThumbnail,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/** The header's overflow menu: import, which the empty list also offers, and the global Debug screen. */
@Composable
private fun HeaderMenu(importing: Boolean, onImport: () -> Unit, onOpenDebug: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Import trip…") },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                enabled = !importing,
                onClick = {
                    expanded = false
                    onImport()
                },
            )
            DropdownMenuItem(
                text = { Text("Debug") },
                leadingIcon = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenDebug()
                },
            )
        }
    }
}

/**
 * The search field and the filter chips. Once stuck to the top it gets the page colour and a divider, so cards
 * scrolling under it do not show through; before that it is transparent over the page gradient.
 */
@Composable
private fun SearchAndFilters(
    text: String,
    filter: TripFilter,
    listState: LazyListState,
    onText: (String) -> Unit,
    onFilter: (TripFilter) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val colors = MaterialTheme.imuColors
    val stuck by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex > CONTROLS_INDEX ||
                (listState.firstVisibleItemIndex == CONTROLS_INDEX && listState.firstVisibleItemScrollOffset > 0)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (stuck) colors.backgroundTop else Color.Transparent),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp),
            placeholder = { Text("Search trips…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (text.isEmpty()) {
                null
            } else {
                {
                    IconButton(onClick = { onText("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // The list filters as the user types, so Search only closes the keyboard.
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.cardFill,
                unfocusedContainerColor = colors.cardFill,
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TripFilter.entries.forEach { option ->
                BrandFilterChip(selected = option == filter, onClick = { onFilter(option) }, label = option.label)
            }
        }
        if (stuck) HorizontalDivider(color = colors.cardBorder)
    }
}

/** The sort button beside the section title, and its menu with the current order ticked. */
@Composable
private fun SortMenu(sort: TripSort, onSort: (TripSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "Sort order: ${sort.label}" },
        ) {
            Text(sort.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TripSort.entries.forEach { option ->
                val chosen = option == sort
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = {
                        // Keeps every label at the same indent; the tick is the only difference.
                        if (chosen) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSort(option)
                    },
                    modifier = Modifier.semantics { selected = chosen },
                )
            }
        }
    }
}

/** Shown under the search and chips when trips exist but none passes them. */
@Composable
private fun NoMatch(onClearFilters: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("No trips match", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Try another name, or another filter.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClearFilters) { Text("Clear filters") }
    }
}

/** The first thing a new user sees: what to do, in the order that gives a good first path. */
@Composable
private fun OnboardingCard(
    importing: Boolean,
    onNewTrip: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Filled.Explore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(40.dp),
            )
            Text("No trips yet", style = MaterialTheme.typography.titleLarge)
            Text(
                "Record a walk and it appears here as a path you can explore in 3D.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OnboardingStep(
                1,
                "Open the Calibrate tab and do the still and stride steps once; the heading step is optional.",
            )
            OnboardingStep(
                2,
                "Open the Record tab, pick a mode and tap Continue, then choose where the phone is carried and wait " +
                    "for the compass to find north.",
            )
            OnboardingStep(3, "Walk, then stop: the route appears as a 3D path you can rotate.")
            Text(
                "Every raw log is kept, so a trip can be re-processed after calibration improves. Import opens a ZIP " +
                    "or .imul log exported from another phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BrandButton(onClick = onNewTrip, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.RadioButtonChecked, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Record a trip")
            }
            OutlinedButton(onClick = onImport, enabled = !importing, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Import a trip")
            }
        }
    }
}

/** A numbered step, read by TalkBack as one item ("1, Open the Calibrate tab…"). */
@Composable
private fun OnboardingStep(number: Int, text: String) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

private val IMPORT_MIME_TYPES = arrayOf("application/zip", "application/octet-stream", "*/*")

// Item keys and content types. The controls follow the banner, so they are always item 1.
private const val KEY_BANNER = "banner"
private const val KEY_ONBOARDING = "onboarding"
private const val KEY_CONTROLS = "controls"
private const val KEY_NO_MATCH = "no-match"
private const val KEY_SECTION = "section"
private const val CARD_CONTENT_TYPE = "trip"
private const val CONTROLS_INDEX = 1
