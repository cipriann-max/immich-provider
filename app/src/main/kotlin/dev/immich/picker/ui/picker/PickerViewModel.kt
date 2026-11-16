package dev.immich.picker.ui.picker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.immich.picker.data.ImmichService
import dev.immich.picker.data.AssetPage
import dev.immich.picker.data.PickerAlbum
import dev.immich.picker.data.PickerAsset
import dev.immich.picker.net.ApiException
import dev.immich.picker.util.MimeFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the in-app picker is currently looking. */
sealed interface PickerLocation {
    data object Recents : PickerLocation
    data object Favorites : PickerLocation
    data object Albums : PickerLocation
    data class Album(val album: PickerAlbum) : PickerLocation
}

/** Ordering options, chosen by us instead of by DocumentsUI. */
enum class SortOrder(val label: String) {
    NEWEST_FIRST("Newest first"),
    OLDEST_FIRST("Oldest first"),
    ;

    fun toggle(): SortOrder = if (this == NEWEST_FIRST) OLDEST_FIRST else NEWEST_FIRST
}

/** One page of results plus its load state. */
data class GridState(
    val assets: List<PickerAsset> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
    val nextCursor: String? = null,
)

data class SelectionState(
    val selected: Map<String, PickerAsset> = emptyMap(),
    /**
     * Upper bound on how many assets may be picked.
     *
     * `1` means single selection, `Int.MAX_VALUE` means unlimited, and `0` means
     * picking is disabled entirely (browsing mode, where there is no caller to
     * return a result to).
     */
    val max: Int = 1,
) {
    val count: Int get() = selected.size
    val isEnabled: Boolean get() = max > 0
    val isMulti: Boolean get() = max > 1
    val canSelectMore: Boolean get() = isEnabled && count < max
}

data class PickerUiState(
    val location: PickerLocation = PickerLocation.Recents,
    val sort: SortOrder = SortOrder.NEWEST_FIRST,
    val grid: GridState = GridState(),
    val selection: SelectionState = SelectionState(),
    val albums: List<PickerAlbum> = emptyList(),
    val albumsLoading: Boolean = false,
    val albumsError: String? = null,
    val needsLogin: Boolean = false,
    /**
     * Non-null when the caller asked for a type an Immich library cannot provide, e.g.
     * `application/pdf`. The value is a human-readable description of the request, shown
     * instead of an unselectable grid.
     */
    val unsupportedRequest: String? = null,
)

/**
 * State holder for [PickerActivity].
 *
 * Paging matters here: Recents is the full library, which can be 100k assets, so the
 * grid loads one page at a time and appends as the user scrolls. Immich's cursor API
 * is used rather than offset paging so results stay stable while the library changes.
 */
class PickerViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PickerUiState())
    val state: StateFlow<PickerUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var albumLoadJob: Job? = null

    /** Set by the activity from the incoming Intent; caps how many items may be picked. */
    fun configure(maxSelection: Int) {
        _state.update { it.copy(selection = it.selection.copy(max = maxSelection)) }
    }

    fun start() {
        if (!ImmichService.isLoggedIn()) {
            _state.update { it.copy(needsLogin = true) }
            return
        }
        refreshCurrent()
    }

    // ------------------------------------------------------------------ location

    fun open(location: PickerLocation) {
        _state.update { it.copy(location = location) }
        when (location) {
            PickerLocation.Albums -> loadAlbums()
            else -> refreshCurrent()
        }
    }

    fun back(): Boolean {
        val current = _state.value.location
        return when (current) {
            is PickerLocation.Album -> {
                open(PickerLocation.Albums)
                true
            }
            PickerLocation.Albums -> {
                open(PickerLocation.Recents)
                true
            }
            else -> false
        }
    }

    fun refreshCurrent() {
        val location = _state.value.location
        if (location == PickerLocation.Albums) {
            loadAlbums()
            return
        }
        reloadAssets()
    }

    fun toggleSort() {
        _state.update { it.copy(sort = it.sort.toggle()) }
        // Ordering is applied server-side so paging stays correct; toggling restarts
        // the listing rather than re-sorting a partial page.
        if (_state.value.location != PickerLocation.Albums) {
            reloadAssets()
        }
    }

    // -------------------------------------------------------------------- assets

    private fun reloadAssets() {
        loadJob?.cancel()
        val session = ImmichService.session()
        if (session == null) {
            _state.update { it.copy(needsLogin = true) }
            return
        }
        val location = _state.value.location
        val sort = _state.value.sort

        _state.update {
            it.copy(grid = GridState(loading = true, error = null))
        }

        loadJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    fetchPage(location, cursor = null, sort = sort, pageSize = PAGE_SIZE)
                }
                _state.update {
                    it.copy(
                        grid = GridState(
                            assets = page.assets,
                            loading = false,
                            endReached = page.nextCursor == null,
                            nextCursor = page.nextCursor,
                        ),
                    )
                }
            } catch (e: ApiException.Unauthorized) {
                ImmichService.onAuthFailure(e.message)
                _state.update {
                    it.copy(
                        needsLogin = true,
                        grid = GridState(loading = false, error = "Session expired"),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        grid = GridState(
                            loading = false,
                            error = e.message ?: "Could not load photos from Immich",
                        ),
                    )
                }
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.grid.loading || current.grid.loadingMore || current.grid.endReached) return
        val cursor = current.grid.nextCursor ?: return
        val session = ImmichService.session() ?: return

        _state.update { it.copy(grid = it.grid.copy(loadingMore = true)) }

        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    fetchPage(current.location, cursor, current.sort, PAGE_SIZE)
                }
                _state.update {
                    it.copy(
                        grid = it.grid.copy(
                            assets = it.grid.assets + page.assets,
                            loadingMore = false,
                            endReached = page.nextCursor == null,
                            nextCursor = page.nextCursor,
                        ),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(grid = it.grid.copy(loadingMore = false, error = e.message))
                }
            }
        }
    }

    private suspend fun fetchPage(
        location: PickerLocation,
        cursor: String?,
        sort: SortOrder,
        pageSize: Int,
    ): AssetPage {
        val repository = ImmichService.session()!!.repository
        val newestFirst = sort == SortOrder.NEWEST_FIRST

        val page = when (location) {
            PickerLocation.Recents -> repository.allAssets(
                cursor = cursor,
                pageSize = pageSize,
                newestFirst = newestFirst,
            )

            PickerLocation.Favorites -> repository.favoriteAssets(
                cursor = cursor,
                pageSize = pageSize,
                newestFirst = newestFirst,
            )

            is PickerLocation.Album -> repository.albumAssets(
                albumId = location.album.id,
                cursor = cursor,
                pageSize = pageSize,
                newestFirst = newestFirst,
            )

            PickerLocation.Albums ->
                throw IllegalStateException("Albums is not a paged asset list")
        }

        // Defensive: order again locally so the grid is still correct if an older
        // server ignores the requested direction. Because the server also orders,
        // this is a no-op on v3.4.1 and never interleaves pages.
        val ordered = if (newestFirst) {
            page.assets.sortedByDescending { it.takenAtMillis }
        } else {
            page.assets.sortedBy { it.takenAtMillis }
        }
        return page.copy(assets = ordered)
    }

    // -------------------------------------------------------------------- albums

    private fun loadAlbums() {
        albumLoadJob?.cancel()
        val session = ImmichService.session()
        if (session == null) {
            _state.update { it.copy(needsLogin = true) }
            return
        }
        _state.update { it.copy(albumsLoading = true, albumsError = null) }

        albumLoadJob = viewModelScope.launch {
            try {
                val albums = withContext(Dispatchers.IO) {
                    session.repository.albums(forceRefresh = true)
                }
                _state.update {
                    it.copy(
                        albums = albums.sortedByDescending { album -> album.updatedAtMillis },
                        albumsLoading = false,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(albumsLoading = false, albumsError = e.message)
                }
            }
        }
    }

    // ----------------------------------------------------------------- selection

    fun toggleSelection(asset: PickerAsset) {
        _state.update { current ->
            val selection = current.selection
            if (!selection.isEnabled) return@update current

            val already = selection.selected.containsKey(asset.id)
            when {
                already -> current.copy(
                    selection = selection.copy(selected = selection.selected - asset.id),
                )

                selection.canSelectMore -> current.copy(
                    selection = selection.copy(selected = selection.selected + (asset.id to asset)),
                )

                // At a single-selection cap: replace the previous pick, which is what
                // users expect when the receiving app only allows one file.
                selection.max == 1 -> current.copy(
                    selection = selection.copy(selected = mapOf(asset.id to asset)),
                )

                // Multi-select cap reached: ignore, keeping the existing selection.
                else -> current
            }
        }
    }

    /**
     * Selection that respects the caller's MIME filter.
     *
     * When an app asks only for images, video tiles stay visible so the library still
     * looks familiar, but they cannot be picked. Hiding them instead would look like a
     * bug to anyone who knows what is in their account.
     */
    fun toggleAssetFiltered(asset: PickerAsset) {
        if (!matchesFilter(asset)) return
        toggleSelection(asset)
    }

    fun matchesFilter(asset: PickerAsset): Boolean =
        MimeFilters.matches(mimeFilter, asset.mimeType)

    /**
     * Set from the calling intent's type and `EXTRA_MIME_TYPES`; empty means "everything".
     *
     * The picker is registered for all MIME types, so a request can name a type Immich
     * cannot serve. That case is recorded in the UI state so the screen can say so
     * instead of showing a grid where no tile can be selected.
     */
    fun setMimeFilter(types: List<String>) {
        mimeFilter = types
        val unsupported = if (MimeFilters.allowsMedia(types)) {
            null
        } else {
            MimeFilters.describe(types)
        }
        _state.update { it.copy(unsupportedRequest = unsupported) }
    }

    private var mimeFilter: List<String> = emptyList()

    fun clearSelection() {
        _state.update { it.copy(selection = it.selection.copy(selected = emptyMap())) }
    }

    fun selectedAssets(): List<PickerAsset> =
        _state.value.selection.selected.values.sortedBy { it.takenAtMillis }

    private companion object {
        /** Comfortably above one screen of tiles, small enough to feel instant. */
        const val PAGE_SIZE = 120
    }
}
