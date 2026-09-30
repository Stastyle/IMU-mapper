package com.stastyle.imumapper.ui.triplist

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stastyle.imumapper.data.TripExporter
import com.stastyle.imumapper.data.TripImporter
import com.stastyle.imumapper.data.TripRepository
import com.stastyle.imumapper.data.TripThumbnails
import com.stastyle.imumapper.process.TripProcessor
import com.stastyle.imumapper.render.PathThumbnail
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Transient screen state next to the trip list itself, which comes straight from the database. */
data class TripListUiState(
    /** Trip ids with an export, re-process or delete in flight; cards show a spinner. */
    val busyTripIds: Set<Long> = emptySet(),
    val importing: Boolean = false,
    /** One-shot: a chooser intent the screen must start, then clear with [TripListViewModel.consumeShare]. */
    val pendingShare: Intent? = null,
    /** One-shot snackbar text. */
    val message: String? = null,
)

/** The trips as the list shows them. */
data class TripListContent(
    /** Every trip in the database, before the search and filter; 0 shows the onboarding card. */
    val totalCount: Int,
    /** The trips that pass the current [TripListQuery], in its order. */
    val items: List<TripListItem>,
)

class TripListViewModel(
    private val trips: TripRepository,
    private val processor: TripProcessor,
    private val exporter: TripExporter,
    private val importer: TripImporter,
    private val thumbnails: TripThumbnails,
    json: Json,
    /**
     * Keeps the search, filter and sort across process death. The list's scroll position survives it, and an index
     * restored into a different, unfiltered list would open part-way down the wrong trips.
     */
    private val savedState: SavedStateHandle = SavedStateHandle(),
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _query = MutableStateFlow(
        TripListQuery.restore(
            text = savedState[KEY_TEXT],
            filterName = savedState[KEY_FILTER],
            sortName = savedState[KEY_SORT],
        ),
    )
    val query: StateFlow<TripListQuery> = _query.asStateFlow()

    /**
     * Null until the database first answers, so the screen shows no empty state for a moment before the trips. The
     * stats are decoded once per database change, not per keystroke; both steps run off the main thread.
     */
    val content: StateFlow<TripListContent?> = combine(
        trips.observeTripRows().map { rows -> rows.map { TripListItem.of(it, json) } },
        _query,
    ) { all, query -> TripListContent(totalCount = all.size, items = query.apply(all)) }
        .flowOn(computeDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _ui = MutableStateFlow(TripListUiState())
    val ui: StateFlow<TripListUiState> = _ui.asStateFlow()

    fun setSearchText(text: String) {
        setQuery { it.copy(text = text) }
    }

    fun setFilter(filter: TripFilter) {
        setQuery { it.copy(filter = filter) }
    }

    fun setSort(sort: TripSort) {
        setQuery { it.copy(sort = sort) }
    }

    /** Shows every trip again; the sort order stays. */
    fun clearFilters() {
        setQuery { it.copy(text = "", filter = TripFilter.ALL) }
    }

    /** The only writer of the query, so [savedState] always holds what the list shows. Enums are saved by name. */
    private fun setQuery(transform: (TripListQuery) -> TripListQuery) {
        val query = _query.updateAndGet(transform)
        savedState[KEY_TEXT] = query.text
        savedState[KEY_FILTER] = query.filter.name
        savedState[KEY_SORT] = query.sort.name
    }

    /** A thumbnail already in memory, for a card's first frame. */
    fun cachedThumbnail(tripId: Long, runId: Int): PathThumbnail? = thumbnails.cached(tripId, runId)

    /** Loads or makes a card's thumbnail; the card calls it while it is on screen. */
    suspend fun thumbnail(tripId: Long, runId: Int): PathThumbnail? = thumbnails.get(tripId, runId)

    fun rename(tripId: Long, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            // Name-only write: a processing run finishing at the same moment keeps its status update.
            runCatching { trips.renameTrip(tripId, name) }
                .onFailure { showMessage("Rename failed: ${it.describe()}") }
        }
    }

    fun delete(tripId: Long) {
        launchBusy(tripId) {
            runCatching { trips.deleteTrip(tripId) }
                .onFailure { showMessage("Delete failed: ${it.describe()}") }
        }
    }

    fun reprocess(tripId: Long) {
        launchBusy(tripId) {
            runCatching { processor.process(tripId) }
                .onSuccess { showMessage("Processed as run ${it.runId} (${it.label})") }
                .onFailure { showMessage("Processing failed: ${it.describe()}") }
        }
    }

    fun export(tripId: Long) {
        launchBusy(tripId) {
            runCatching { exporter.export(tripId) }
                .onSuccess { export -> _ui.update { it.copy(pendingShare = export.shareIntent) } }
                .onFailure { showMessage("Export failed: ${it.describe()}") }
        }
    }

    fun consumeShare() {
        _ui.update { it.copy(pendingShare = null) }
    }

    fun importTrip(uri: Uri) {
        if (_ui.value.importing) return
        _ui.update { it.copy(importing = true) }
        viewModelScope.launch {
            runCatching { importer.importFrom(uri) }
                .onSuccess { result ->
                    val suffix = if (result.resultCount > 0) " with ${result.resultCount} result(s)" else ""
                    showMessage("Imported \"${result.name}\"$suffix")
                }
                .onFailure { showMessage("Import failed: ${it.describe()}") }
            _ui.update { it.copy(importing = false) }
        }
    }

    fun dismissMessage() {
        _ui.update { it.copy(message = null) }
    }

    private fun launchBusy(tripId: Long, block: suspend () -> Unit) {
        if (tripId in _ui.value.busyTripIds) return
        _ui.update { it.copy(busyTripIds = it.busyTripIds + tripId) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _ui.update { it.copy(busyTripIds = it.busyTripIds - tripId) }
            }
        }
    }

    private fun showMessage(text: String) {
        _ui.update { it.copy(message = text) }
    }

    private fun Throwable.describe(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        // Saved-state keys; renaming one drops the saved value once, which only resets the query.
        const val KEY_TEXT = "query.text"
        const val KEY_FILTER = "query.filter"
        const val KEY_SORT = "query.sort"
    }
}
