package dev.immich.picker.ui.picker

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.immich.picker.cache.ThumbnailSize
import dev.immich.picker.data.PickerAlbum
import dev.immich.picker.data.PickerAsset

/**
 * The in-app picker.
 *
 * This exists because DocumentsUI owns the layout and sort order of a
 * DocumentsProvider root: it ignores the order the provider returns and defaults to
 * "File name (A to Z)". Owning the Activity lets us order by date, page through the
 * whole library and render the grid the way a photo picker should.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(
    state: PickerUiState,
    /**
     * True when opened for browsing (DocumentsUI's root arrow) rather than as a content
     * picker. Selection is disabled because that intent cannot return a result to the
     * app that originally opened the file picker.
     */
    readOnly: Boolean,
    onBack: () -> Unit,
    onSortToggle: () -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenRecents: () -> Unit,
    onOpenAlbum: (PickerAlbum) -> Unit,
    onToggleAsset: (PickerAsset) -> Unit,
    onClearSelection: () -> Unit,
    onConfirm: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onLogin: () -> Unit,
    /**
     * Opens the app's Settings screen (server URL, credentials, cache). Available from
     * every state, including the login prompt and browsing mode, because it is the only
     * place a broken credential can be fixed.
     */
    onOpenSettings: () -> Unit,
) {
    val showingAlbums = state.location == PickerLocation.Albums
    // Derived from the same rule the back button uses, so the top-bar icon can never
    // promise an up-navigation that back would not perform (or vice versa).
    val canGoBack = PickerNavigation.canGoUp(state.location)
    val selectable = !readOnly
    // The album list *and* anything inside an album belong to the Albums tab, so the tab
    // stays highlighted while browsing an album's contents. Before this, entering an album
    // flipped the highlight to Timeline, which contradicted the screen's own title.
    val inAlbumsBranch = state.location is PickerLocation.Album || showingAlbums
    val selectedTab = if (inAlbumsBranch) PickerTab.ALBUMS else PickerTab.TIMELINE

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            // Names the current section in every tab, matching the tab
                            // labels. The app name used to be shown for Timeline only,
                            // so the title read "Immich File Picker" in one tab and
                            // "Albums" in another for the same kind of position.
                            text = when (val loc = state.location) {
                                PickerLocation.Recents -> "Timeline"
                                PickerLocation.Favorites -> "Favorites"
                                PickerLocation.Albums -> "Albums"
                                is PickerLocation.Album -> loc.album.name
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (canGoBack) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        } else {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Filled.Close, contentDescription = "Close")
                            }
                        }
                    },
                    actions = {
                        // Always present. The album list is sortable too (by last
                        // activity), so the toolbar is identical in every tab instead of
                        // changing shape based on where you are.
                        IconButton(onClick = onSortToggle) {
                            Icon(
                                Icons.Filled.Sort,
                                contentDescription = "Sort: ${state.sort.label}",
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Settings",
                            )
                        }
                    },
                )

                // Always visible, including inside an album.
                //
                // Hiding the tabs made entering an album feel like a different screen: the
                // top-level navigation vanished and the only cue was the title. Keeping the
                // tab row means the album is shown as a *deeper view within the Albums
                // tab*, which is what it is. The Albums tab stays selected while inside an
                // album, and tapping either tab is an explicit way back out.
                PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    PickerTab.entries.forEach { tab ->
                        Tab(
                            selected = tab == selectedTab,
                            onClick = {
                                when (tab) {
                                    PickerTab.TIMELINE -> onOpenRecents()
                                    PickerTab.ALBUMS -> onOpenAlbums()
                                }
                            },
                            // The icon carries the meaning; the label stays short so
                            // both tabs fit a narrow phone without truncation.
                            icon = { Icon(tab.icon, contentDescription = null) },
                            text = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (selectable && state.selection.count > 0) {
                SelectionBar(
                    state = state.selection,
                    onClear = onClearSelection,
                    onConfirm = onConfirm,
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.needsLogin -> LoginPrompt(onLogin)
                state.unsupportedRequest != null -> UnsupportedRequestPane(
                    requested = state.unsupportedRequest,
                )

                showingAlbums -> AlbumsPane(
                    state = state,
                    onOpenAlbum = onOpenAlbum,
                    onOpenFavorites = onOpenFavorites,
                )

                state.grid.loading -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center),
                )

                state.grid.error != null && state.grid.assets.isEmpty() -> ErrorPane(
                    message = state.grid.error,
                    onRetry = onRetry,
                )

                state.grid.assets.isEmpty() -> EmptyPane(
                    text = "No photos here yet.",
                )

                else -> AssetGrid(
                    state = state,
                    selected = state.selection.selected,
                    selectable = selectable,
                    onToggle = onToggleAsset,
                    onLoadMore = onLoadMore,
                )
            }
        }
    }
}

@Composable
private fun SelectionBar(
    state: SelectionState,
    onClear: () -> Unit,
    onConfirm: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        // The activity is edge-to-edge, so the bar must inset itself or the Add /
        // Clear buttons end up underneath the gesture/navigation bar.
        modifier = Modifier.navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClear) { Text("Clear") }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (state.isMulti) {
                    "${state.count} selected"
                } else {
                    "1 selected"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onConfirm) {
                Text(if (state.count > 1) "Add ${state.count}" else "Add")
            }
        }
    }
}

@Composable
private fun AssetGrid(
    state: PickerUiState,
    selected: Map<String, PickerAsset>,
    selectable: Boolean,
    onToggle: (PickerAsset) -> Unit,
    onLoadMore: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    val assets = state.grid.assets

    // Prefetch the next page a little before the end so scrolling does not stall.
    LaunchedEffect(gridState, assets.size, state.grid.endReached) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                if (!state.grid.endReached && lastVisible >= assets.size - 12) {
                    onLoadMore()
                }
            }
    }

    LazyVerticalGrid(
        state = gridState,
        // Two columns: larger, easier-to-hit tiles than DocumentsUI's three.
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(assets, key = { it.id }) { asset ->
            AssetTile(
                asset = asset,
                selected = selected.containsKey(asset.id),
                selectionDisabled = !selectable || (
                    !selected.containsKey(asset.id) &&
                        !state.selection.canSelectMore &&
                        state.selection.isMulti
                    ),
                onClick = { onToggle(asset) },
            )
        }

        if (state.grid.loadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }

        if (state.grid.endReached && assets.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "End of library",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun AssetTile(
    asset: PickerAsset,
    selected: Boolean,
    selectionDisabled: Boolean,
    onClick: () -> Unit,
) {
    val size = ThumbnailSize.THUMBNAIL
    var bitmap by remember(asset.id, asset.thumbhash) {
        mutableStateOf(ThumbnailLoader.cached(asset, size))
    }

    LaunchedEffect(asset.id, asset.thumbhash) {
        if (bitmap == null) {
            bitmap = ThumbnailLoader.load(asset, size)
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .selectable(selected = selected, enabled = !selectionDisabled, onClick = onClick),
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = asset.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (asset.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Video",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (asset.isFavorite) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = "Favorite",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp),
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun AlbumsPane(
    state: PickerUiState,
    onOpenAlbum: (PickerAlbum) -> Unit,
    onOpenFavorites: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            ListItem(
                headlineContent = { Text("Favorites") },
                supportingContent = { Text("Everything you have starred") },
                leadingContent = {
                    Box(
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Favorite, contentDescription = null)
                    }
                },
                modifier = Modifier.selectable(selected = false, onClick = onOpenFavorites),
            )
        }

        if (state.albumsLoading) {
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }

        state.albumsError?.let { message ->
            item {
                Text(
                    text = "Could not load albums: $message",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        if (!state.albumsLoading && state.albumsError == null && state.albums.isEmpty()) {
            item {
                EmptyPane(text = "No albums on this Immich server.")
            }
        }

        items(state.albums, key = { it.id }) { album ->
            AlbumRow(album = album, onClick = { onOpenAlbum(album) })
        }
    }
}

/**
 * Album row with its cover thumbnail.
 *
 * The cover is fetched from the album's `albumThumbnailAssetId` through the same disk and
 * memory caches the grid uses, so opening the album afterwards shows no extra cost.
 */
@Composable
private fun AlbumRow(album: PickerAlbum, onClick: () -> Unit) {
    var cover by remember(album.id, album.coverAssetId) {
        mutableStateOf<ImageBitmap?>(null)
    }

    LaunchedEffect(album.id, album.coverAssetId) {
        val coverId = album.coverAssetId ?: return@LaunchedEffect
        cover = ThumbnailLoader.loadById(coverId, ThumbnailSize.THUMBNAIL)
    }

    ListItem(
        headlineContent = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(if (album.assetCount == 1) "1 item" else "${album.assetCount} items")
        },
        leadingContent = {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val image = cover
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                }
            }
        },
        modifier = Modifier.selectable(selected = false, onClick = onClick),
    )
}

/** Tabs shown above the content on the root screen. */
private enum class PickerTab(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    TIMELINE("Timeline", Icons.Filled.PhotoLibrary),
    ALBUMS("Albums", Icons.Filled.Folder),
}

@Composable
private fun LoginPrompt(onLogin: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Not signed in to Immich", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in once and Immich stays available here and in the system file picker.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onLogin) { Text("Open settings") }
    }
}

/**
 * Shown when the calling app asked for a type nothing in an Immich library can satisfy.
 *
 * Because the picker now appears in every file chooser, a caller may legitimately ask for
 * `application/pdf` or `text/plain`. Explaining that is better than a grid of photos whose
 * tiles refuse every tap.
 */
@Composable
private fun UnsupportedRequestPane(requested: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("This app wants a file Immich cannot provide", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "The request is for: $requested. An Immich library holds photos and videos only, " +
                "so there is nothing here to attach.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Pick the file from another source, or ask the app for a photo or video.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorPane(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Could not load photos", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun EmptyPane(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Thin indeterminate bar shown while a reload is in flight over existing content. */
@Composable
fun RefreshIndicator(visible: Boolean) {
    if (visible) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
