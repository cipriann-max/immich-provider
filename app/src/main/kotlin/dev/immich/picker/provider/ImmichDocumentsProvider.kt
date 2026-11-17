package dev.immich.picker.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import dev.immich.picker.BuildConfig
import dev.immich.picker.R
import dev.immich.picker.data.AssetPage
import dev.immich.picker.data.ImmichService
import dev.immich.picker.data.PickerAlbum
import dev.immich.picker.data.PickerAsset
import dev.immich.picker.net.ApiException
import dev.immich.picker.util.DocumentIds
import dev.immich.picker.util.MimeFilters
import dev.immich.picker.util.RankedNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves Immich assets as read-only `content://` documents and registers one SAF root.
 *
 * ## One surface for both picker actions
 *
 * This root is the app's *only* picker UI, on purpose. An earlier version also shipped a
 * custom `GET_CONTENT` activity, which meant the library looked different depending on
 * which action the calling app used: a bespoke grid for `GET_CONTENT`, DocumentsUI for
 * `ACTION_OPEN_DOCUMENT`. Since DocumentsUI owns the sort of any root it renders, the two
 * could never agree on ordering, so they would drift apart on every change.
 *
 * Now `ACTION_OPEN_DOCUMENT` and `ACTION_GET_CONTENT` callers both land here, through
 * DocumentsUI, and see the same tree, the same order and the same behaviour.
 *
 * ## Tree
 *
 * ```
 * Immich            the 50 newest assets, plus an Albums folder
 * └── Albums        one folder per album
 *     └── <album>   that album's 50 newest assets
 * ```
 *
 * The timeline lives directly in the root rather than in a `timeline` subfolder: the file
 * someone attaches is almost always recent, so an extra tap to reach it is pure friction.
 *
 * ## Ordering
 *
 * Assets are requested newest-first **and** sorted again locally before being written.
 * The second sort is not redundant: a SAF folder's order is ultimately rendered by
 * DocumentsUI, so returning the list in the intended order is what makes the first row
 * today's photo rather than whatever the server happened to stream first.
 *
 * ## Paging
 *
 * DocumentsUI has no "load more" callback, so paging has to be query-driven. A folder
 * returns [PAGE_SIZE] rows and sets [DocumentsContract.EXTRA_LOADING]; when the user
 * reaches the end of the list DocumentsUI re-queries, and the next [PAGE_SIZE] is appended.
 *
 * The critical detail is that this provider does **not** push content-change notifications
 * from the listing path. Doing so made DocumentsUI re-query immediately, which appended
 * another chunk, which notified again — the whole library arrived within seconds. Paging
 * now only advances in response to a query, so it tracks what the user actually asked for.
 *
 * ## Read-only by construction
 *
 * There is no `createDocument`, `deleteDocument`, `renameDocument` or write path, the root
 * does not advertise [Root.FLAG_SUPPORTS_CREATE], and [openDocument] rejects any mode other
 * than `r`.
 *
 * ## Availability
 *
 * The root is always listed so a signed-out install is still discoverable: its summary says
 * a sign-in is needed, and DocumentsUI's settings affordance opens the sign-in screen.
 */
class ImmichDocumentsProvider : DocumentsProvider() {

    private lateinit var service: ImmichService

    /** Assets resolved during this process, so metadata survives between calls. */
    private val assetCache = java.util.concurrent.ConcurrentHashMap<String, PickerAsset>()

    /** Albums known to this process, so `queryDocument` on an album folder costs no call. */
    private val albumCache = java.util.concurrent.ConcurrentHashMap<String, PickerAlbum>()

    /**
     * One pager per assets folder, keyed by document id (`root` or `album:<id>`).
     *
     * Per-folder rather than one shared list: the timeline and an album page independently,
     * so opening an album cannot truncate the timeline.
     */
    private val pagers = java.util.concurrent.ConcurrentHashMap<String, AssetPager>()

    /**
     * Credential generation everything above was built under.
     *
     * Compared per query, not only in the listener: the provider may not be alive when
     * Settings signs in, so a generation check is what stops one account's assets being
     * served under another account's root.
     */
    @Volatile
    private var cachedGeneration: Long = -1L

    /** Kept as a property so [shutdown] can remove the exact same instance. */
    private val onCredentialsChanged: () -> Unit = {
        clearCachesForNewCredential()
        notifyRootsChanged()
    }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        ImmichService.initialize(ctx)
        service = ImmichService
        ImmichService.addListener(onCredentialsChanged)
        Log.i(TAG, "provider created; built for Immich ${BuildConfig.TESTED_IMMICH_VERSION}")
        return true
    }

    override fun shutdown() {
        ImmichService.removeListener(onCredentialsChanged)
        super.shutdown()
    }

    // ------------------------------------------------------------------ roots

    /**
     * One root, always present while the provider is registered.
     *
     * It deliberately does **not** disappear when signed out. Hiding it left someone who
     * had just installed the app with no indication it existed and nothing to tap, which is
     * a dead end. The root is always listed, its summary says when a sign-in is needed, and
     * the settings flag adds DocumentsUI's "settings" affordance, which routes
     * [DocumentsContract.ACTION_DOCUMENT_ROOT_SETTINGS] to the provider's declared
     * `android:settingsActivity` — this app's settings screen.
     */
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context ?: return cursor

        val signedIn = service.isLoggedIn()
        val credentials = runCatching { service.credentialStore.current() }.getOrNull()
        val summary = when {
            signedIn -> credentials?.accountLabel ?: credentials?.userEmail
            else -> ctx.getString(R.string.provider_root_summary_signed_out)
        }

        val row = cursor.newRow()
        for (column in cursor.columnNames) {
            when (column) {
                Root.COLUMN_ROOT_ID -> row.add(ROOT_ID)
                Root.COLUMN_DOCUMENT_ID -> row.add(DocumentIds.ROOT_DOCUMENT_ID)
                Root.COLUMN_TITLE -> row.add(ctx.getString(R.string.provider_root_title))
                Root.COLUMN_SUMMARY -> row.add(summary)
                Root.COLUMN_FLAGS -> row.add(
                    if (signedIn) ROOT_FLAGS else ROOT_FLAGS or FLAG_HAS_SETTINGS_COMPAT,
                )
                Root.COLUMN_ICON -> row.add(R.mipmap.ic_launcher)
                // COLUMN_MIME_TYPES is deliberately left null, which declares "may serve
                // anything". Naming only image/* and video/* would hide this root from a
                // caller that asks solely for a document type, and being absent is worse
                // than being present and explaining what the library holds: the listing
                // returns EXTRA_ERROR for a request Immich cannot satisfy.
                else -> row.add(null)
            }
        }
        cursor.setNotificationUri(ctx.contentResolver, DocumentsContract.buildRootsUri(authority()))
        return cursor
    }

    // -------------------------------------------------------------- documents

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val ctx = context ?: return cursor

        when {
            DocumentIds.isRoot(documentId) -> {
                writeDirectoryRow(cursor, documentId, ctx.getString(R.string.provider_root_title))
            }

            DocumentIds.isAlbums(documentId) -> {
                writeDirectoryRow(cursor, documentId, ctx.getString(R.string.provider_albums_title))
            }

            DocumentIds.isAlbum(documentId) -> {
                val albumId = DocumentIds.albumIdOf(documentId) ?: return cursor
                val title = albumCache[albumId]?.name ?: resolveAlbumTitle(albumId) ?: return cursor
                writeDirectoryRow(cursor, documentId, title)
            }

            else -> {
                val assetId = DocumentIds.assetIdOf(documentId) ?: return cursor
                val asset = assetCache[assetId] ?: resolveAsset(assetId) ?: return cursor
                assetCache[assetId] = asset
                writeAssetRow(cursor, asset)
            }
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = childCursor(parentDocumentId, projection, queryArgs = null, sortOrder = sortOrder)

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        extras: Bundle?,
    ): Cursor = childCursor(parentDocumentId, projection, queryArgs = extras, sortOrder = null)

    /**
     * `QUERY_ARG_*` are compile-time String constants inlined by javac, so referencing
     * them on API 26 is safe even though the constant was published in API 29.
     */
    @Suppress("InlinedApi")
    private fun childCursor(
        parentDocumentId: String,
        projection: Array<out String>?,
        queryArgs: Bundle?,
        sortOrder: String?,
    ): Cursor {
        val ctx = context ?: return MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        val filters = MimeFilters.parseMany(
            queryArgs?.getStringArray(DocumentsContract.QUERY_ARG_MIME_TYPES),
        )

        // Logged because the order a provider returns is advisory: this is the one place
        // to see what DocumentsUI actually asked for if a list ever renders oddly.
        Log.d(TAG, "query $parentDocumentId sortOrder=$sortOrder filters=$filters")

        val cursor = when {
            DocumentIds.isAlbums(parentDocumentId) -> albumListCursor(projection, filters)

            // The root mixes the timeline with the Albums folder; an album is assets only.
            DocumentIds.isRoot(parentDocumentId) ->
                rootCursor(projection, filters)

            DocumentIds.isAlbum(parentDocumentId) ->
                assetListCursor(parentDocumentId, projection, filters)

            else -> MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        }

        cursor.setNotificationUri(
            ctx.contentResolver,
            DocumentsContract.buildChildDocumentsUri(authority(), parentDocumentId),
        )
        return cursor
    }

    override fun getDocumentType(documentId: String): String {
        if (DocumentIds.isDirectory(documentId)) return Document.MIME_TYPE_DIR
        val assetId = DocumentIds.assetIdOf(documentId) ?: return Document.MIME_TYPE_DIR
        val asset = assetCache[assetId] ?: resolveAsset(assetId)
        return asset?.mimeType ?: Document.MIME_TYPE_DIR
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        when {
            DocumentIds.isRoot(parentDocumentId) ->
                DocumentIds.isAlbums(documentId) || DocumentIds.isAsset(documentId)

            DocumentIds.isAlbums(parentDocumentId) -> DocumentIds.isAlbum(documentId)

            DocumentIds.isAlbum(parentDocumentId) -> DocumentIds.isAsset(documentId)

            else -> false
        }

    /**
     * The root's children: the newest assets, then the Albums folder.
     *
     * Assets come first because that is what the root is for; putting the folder last
     * keeps it out of the way of the common case while still being reachable.
     */
    private fun rootCursor(
        projection: Array<out String>?,
        filters: List<String>,
    ): Cursor {
        val ctx = context
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        val pager = pagerFor(DocumentIds.ROOT_DOCUMENT_ID)
        val state = pagedState(pager, filters)

        cursor.writeRankedAssets(state.assets)
        writeDirectoryRow(
            cursor,
            DocumentIds.ALBUMS_DOCUMENT_ID,
            ctx?.getString(R.string.provider_albums_title) ?: "Albums",
            summary = ctx?.getString(R.string.provider_albums_summary),
        )

        return cursor.withExtras(state)
    }

    // ------------------------------------------------------- recents / search

    /**
     * Feeds DocumentsUI's "Recent" surfaces. Advertised through
     * [Root.FLAG_SUPPORTS_RECENTS].
     */
    override fun queryRecentDocuments(
        rootId: String,
        projection: Array<out String>?,
    ): Cursor = recentCursor(rootId, projection, queryArgs = null)

    override fun queryRecentDocuments(
        rootId: String,
        projection: Array<out String>?,
        queryArgs: Bundle?,
        signal: CancellationSignal?,
    ): Cursor = recentCursor(rootId, projection, queryArgs)

    @Suppress("InlinedApi")
    private fun recentCursor(
        rootId: String,
        projection: Array<out String>?,
        queryArgs: Bundle?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (rootId != ROOT_ID) return cursor

        val filters = MimeFilters.parseMany(
            queryArgs?.getStringArray(DocumentsContract.QUERY_ARG_MIME_TYPES),
        )
        // Answered from what the timeline pager already fetched, so opening Recent does not
        // start an independent library scan.
        val assets = pagerFor(DocumentIds.ROOT_DOCUMENT_ID)
            .snapshot().assets
            .filter { MimeFilters.matches(filters, it.mimeType) }
            .sortedNewestFirst()
            .take(RECENT_LIMIT)
        cursor.writeRankedAssets(assets)
        return cursor
    }

    /**
     * Search runs over what has already been fetched, across the timeline and every album
     * opened in this session.
     *
     * Advertised through [Root.FLAG_SUPPORTS_SEARCH]; intentionally local rather than a
     * server query per keystroke, so typing stays instant.
     */
    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor = searchCursor(rootId, projection, query, queryArgs = null)

    @Suppress("InlinedApi")
    override fun querySearchDocuments(
        rootId: String,
        projection: Array<out String>?,
        queryArgs: Bundle,
    ): Cursor = searchCursor(
        rootId = rootId,
        projection = projection,
        query = queryArgs.getString(DocumentsContract.QUERY_ARG_DISPLAY_NAME).orEmpty(),
        queryArgs = queryArgs,
    )

    @Suppress("InlinedApi")
    private fun searchCursor(
        rootId: String,
        projection: Array<out String>?,
        query: String,
        queryArgs: Bundle?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (rootId != ROOT_ID) return cursor

        val filters = MimeFilters.parseMany(
            queryArgs?.getStringArray(DocumentsContract.QUERY_ARG_MIME_TYPES),
        )
        val needle = query.trim().takeIf { it.isNotEmpty() }

        val matches = pagers.values
            .flatMap { it.snapshot().assets }
            .distinctBy { it.id }
            .filter {
                MimeFilters.matches(filters, it.mimeType) && matchesName(it.displayName, needle)
            }
            .sortedNewestFirst()
        cursor.writeRankedAssets(matches)
        return cursor
    }

    // ------------------------------------------------------------- open assets

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val assetId = DocumentIds.assetIdOf(documentId)
            ?: throw FileNotFoundException("Not an asset document: $documentId")

        // Read-only contract: refuse write modes instead of pretending they worked.
        if (mode != MODE_READ) {
            throw FileNotFoundException("Immich File Picker is read-only (requested mode: $mode)")
        }

        signal?.throwIfCanceled()

        val session = service.session()
            ?: throw FileNotFoundException("Not signed in to Immich")

        val asset = assetCache[assetId] ?: resolveAsset(assetId)
            ?: throw FileNotFoundException("Asset $assetId no longer exists on the server")
        assetCache[assetId] = asset

        return try {
            val file = runBlocking {
                val cached = service.originals.get(asset.id, asset.displayName)
                if (cached != null) {
                    cached
                } else {
                    val target = service.originals.fileFor(asset.id, asset.displayName)
                    target.parentFile?.mkdirs()
                    withTimeoutOrNull(CALL_TIMEOUT_MS) {
                        session.repository.downloadOriginal(
                            assetId = asset.id,
                            sink = target,
                            signal = signal,
                        )
                    } ?: throw java.io.IOException(
                        "Immich did not send the file within ${CALL_TIMEOUT_MS / 1000}s",
                    )
                    service.originals.commit(asset.id, asset.displayName)
                }
            }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: ApiException.Unauthorized) {
            Log.w(TAG, "openDocument: credential rejected", e)
            service.onAuthFailure(e.message)
            throw FileNotFoundException(
                "Immich session expired; open Immich File Picker to sign in again, or use an API key",
            )
        } catch (e: ApiException.Forbidden) {
            // Permission problem, not an expired session: the UI must not be told to
            // sign in again, because doing so would change nothing.
            Log.w(TAG, "openDocument: permission denied", e)
            throw FileNotFoundException("${e.message} — check the API key's permissions")
        } catch (e: ApiException.Cancelled) {
            throw OperationCanceledException()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The receiver surfaces this string, so it must be actionable.
            Log.e(TAG, "openDocument failed for $assetId", e)
            throw FileNotFoundException("Could not download ${asset.displayName}: ${e.message}")
        }
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: android.graphics.Point,
        signal: CancellationSignal?,
    ): android.content.res.AssetFileDescriptor {
        val assetId = DocumentIds.assetIdOf(documentId)
            ?: throw FileNotFoundException("Not an asset document: $documentId")

        signal?.throwIfCanceled()

        val session = service.session()
            ?: throw FileNotFoundException("Not signed in to Immich")

        val size = dev.immich.picker.cache.ThumbnailSize
            .forHint(sizeHint.x, sizeHint.y)
        val asset = assetCache[assetId] ?: resolveAsset(assetId)
        asset?.let { assetCache[assetId] = it }

        val file = service.thumbnails.get(assetId, size, asset?.thumbhash)
            ?: try {
                runBlocking {
                    downloadThumbnail(assetId, size, asset?.thumbhash, session, signal)
                } ?: throw FileNotFoundException("Thumbnail unavailable for $assetId")
            } catch (e: ApiException.Cancelled) {
                // DocumentsUI cancels thumbnail requests while scrolling; not an error.
                throw OperationCanceledException()
            }

        return android.content.res.AssetFileDescriptor(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH,
        )
    }

    // --------------------------------------------------------------- listings

    /**
     * Query-driven pager for one assets folder.
     *
     * Holds the assets fetched so far, the server cursor for the next chunk, and whether
     * the folder has been read to the end. It never fetches on its own: a fetch happens
     * only inside a query, which is what keeps the growth tied to user action.
     */
    private class AssetPager {
        private val lock = Any()

        private val assets = ArrayList<PickerAsset>()
        private val seen = HashSet<String>()
        private var cursor: String? = null
        private var exhausted = false
        private var lastError: String? = null

        /** When the last chunk finished; throttles the many queries one render produces. */
        private var lastFetchAtMillis: Long = 0L

        fun snapshot(): PagerState = synchronized(lock) {
            PagerState(
                assets = assets.toList(),
                loading = !exhausted,
                exhausted = exhausted,
                error = lastError,
            )
        }

        /**
         * True when another chunk may be fetched right now.
         *
         * Two conditions, both necessary:
         *
         *  - the folder is not exhausted, and
         *  - either nothing has been loaded yet, or [MIN_FETCH_INTERVAL_MS] has passed
         *    since the last chunk landed.
         *
         * The interval is what fixes the runaway load. DocumentsUI issues several
         * overlapping `queryChildDocuments` calls for a single screen — one per projection
         * it needs — so "advance one chunk per query" advanced seven chunks per render:
         * 350 rows before the first screen had even been drawn. Spacing chunks in time
         * ties paging to the user scrolling, which is the only thing a person actually
         * does seconds apart.
         */
        fun canAdvance(nowMillis: Long): Boolean = synchronized(lock) {
            when {
                exhausted -> false
                assets.isEmpty() -> true
                else -> nowMillis - lastFetchAtMillis >= MIN_FETCH_INTERVAL_MS
            }
        }

        /** Appends one server page, dropping duplicates that a cursor can repeat. */
        fun append(page: AssetPage, nextCursor: String?) = synchronized(lock) {
            page.assets.forEach { asset ->
                if (seen.add(asset.id)) assets += asset
            }
            cursor = nextCursor
            exhausted = nextCursor == null
            lastError = null
            lastFetchAtMillis = System.currentTimeMillis()
        }

        fun fail(message: String) = synchronized(lock) {
            lastError = message
            // Do not retry in a loop against a failing server; reopening tries again.
            exhausted = true
            lastFetchAtMillis = System.currentTimeMillis()
        }

        fun nextCursor(): String? = synchronized(lock) { cursor }
    }

    private data class PagerState(
        val assets: List<PickerAsset>,
        val loading: Boolean,
        val exhausted: Boolean,
        val error: String?,
    )

    /**
     * Returns the pager for [documentId], creating it if needed.
     *
     * Also the single place the credential generation is checked, so a stale account's
     * assets can never be served.
     */
    private fun pagerFor(documentId: String): AssetPager {
        val generation = service.currentGeneration()
        if (generation != cachedGeneration) {
            cachedGeneration = generation
            assetCache.clear()
            albumCache.clear()
            pagers.clear()
        }
        return pagers.computeIfAbsent(documentId) { AssetPager() }
    }

    /**
     * Advances [pager] by exactly one [PAGE_SIZE] chunk if it still has more, then returns
     * the state to render.
     *
     * The fetch is synchronous on the calling binder thread. That is what makes "load 50
     * more" deterministic: the rows are in the cursor DocumentsUI is about to read, with no
     * notification and therefore no possibility of a self-sustaining re-query loop.
     */
    private fun pagedState(pager: AssetPager, filters: List<String>): PagerState {
        if (!service.isLoggedIn()) {
            return PagerState(
                assets = emptyList(),
                loading = false,
                exhausted = true,
                error = context?.getString(R.string.provider_sign_in_hint)
                    ?: "Not signed in to Immich.",
            )
        }
        if (!MimeFilters.allowsMedia(filters)) {
            return PagerState(
                assets = emptyList(),
                loading = false,
                exhausted = true,
                error = "Immich only stores photos and videos; nothing can satisfy " +
                    "${MimeFilters.describe(filters)}.",
            )
        }

        if (pager.canAdvance(System.currentTimeMillis())) fetchChunk(pager)

        val state = pager.snapshot()
        // Local re-sort: the order this provider returns is what the user sees, so it must
        // be newest-first regardless of how the server or DocumentsUI ordered anything.
        return state.copy(assets = state.assets.sortedNewestFirst())
    }

    /** Fetches exactly one [PAGE_SIZE] chunk for [pager]. */
    private fun fetchChunk(pager: AssetPager) {
        val session = service.session()
        if (session == null) {
            pager.fail("Not signed in to Immich. Open Immich File Picker to sign in.")
            return
        }

        try {
            val page = runBlocking {
                withTimeoutOrNull(CALL_TIMEOUT_MS) {
                    fetchPage(pager, session)
                }
            }
            if (page == null) {
                pager.fail(
                    "Immich did not answer within ${CALL_TIMEOUT_MS / 1000}s. " +
                        "Open Immich File Picker to check the connection.",
                )
            } else {
                pager.append(page, page.nextCursor)
                page.assets.forEach { assetCache[it.id] = it }
            }
        } catch (e: ApiException.Unauthorized) {
            Log.w(TAG, "listing: credential rejected", e)
            service.onAuthFailure(e.message)
            pager.fail(
                "Immich rejected the saved credential. Open Immich File Picker to sign in again.",
            )
        } catch (e: ApiException.Forbidden) {
            Log.w(TAG, "listing: permission denied", e)
            pager.fail("${e.message} — check the API key's permissions.")
        } catch (e: Exception) {
            Log.w(TAG, "listing failed", e)
            pager.fail("Could not load photos from Immich: ${e.message}")
        }
    }

    /**
     * One [PAGE_SIZE] request for whichever folder [pager] belongs to.
     *
     * The pager key is resolved by identity against [pagers], because a pager records no
     * folder of its own and adding one would duplicate state that must stay in sync.
     */
    private suspend fun fetchPage(
        pager: AssetPager,
        session: ImmichService.Session,
    ): AssetPage {
        val documentId = pagers.entries.firstOrNull { it.value === pager }?.key
            ?: DocumentIds.ROOT_DOCUMENT_ID

        val albumId = DocumentIds.albumIdOf(documentId)
        return if (albumId != null) {
            session.repository.albumAssets(
                albumId = albumId,
                cursor = pager.nextCursor(),
                pageSize = PAGE_SIZE,
                newestFirst = true,
            )
        } else {
            session.repository.allAssets(
                cursor = pager.nextCursor(),
                pageSize = PAGE_SIZE,
                newestFirst = true,
            )
        }
    }

    /** Builds an album's asset listing. */
    private fun assetListCursor(
        documentId: String,
        projection: Array<out String>?,
        filters: List<String>,
    ): Cursor {
        val pager = pagerFor(documentId)
        val state = pagedState(pager, filters)
        return buildDocumentsCursor(projection, state)
    }

    /**
     * Writes a page of assets in timeline order.
     *
     * The rows carry a rank prefix (`0001_`, `0002_`, …) so DocumentsUI's fixed
     * "File name (A to Z)" sort reproduces this order instead of alphabetising by the
     * original filename. See [RankedNames]; the prefix is cosmetic and affects only what
     * the grid displays, never the picked file's name or bytes.
     */
    private fun MatrixCursor.writeRankedAssets(assets: List<PickerAsset>) {
        val widthCount = assets.size
        assets.forEachIndexed { index, asset ->
            writeAssetRow(this, asset, rankedName = RankedNames.apply(index + 1, widthCount, asset.displayName))
        }
    }

    private fun Cursor.withExtras(state: PagerState): Cursor {
        val bundle = Bundle()
        state.error?.let { bundle.putString(DocumentsContract.EXTRA_ERROR, it) }
        if (state.loading) bundle.putBoolean(DocumentsContract.EXTRA_LOADING, true)
        if (!bundle.isEmpty) extras = bundle
        return this
    }

    /**
     * Lists the albums as folders.
     *
     * Albums are a short list, so they are fetched once and cached for the process rather
     * than paged. A failure yields a cursor message instead of an exception, because a
     * thrown exception makes DocumentsUI show a bare "couldn't load" toast.
     */
    private fun albumListCursor(
        projection: Array<out String>?,
        filters: List<String>,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        if (!service.isLoggedIn()) {
            return cursor.withError(
                context?.getString(R.string.provider_sign_in_hint) ?: "Not signed in to Immich.",
            )
        }
        if (!MimeFilters.allowsMedia(filters)) return cursor

        val session = service.session() ?: return cursor
        val albums = try {
            runBlocking {
                withTimeoutOrNull(CALL_TIMEOUT_MS) {
                    session.repository.albums(forceRefresh = albumCache.isEmpty())
                }
            }
        } catch (e: ApiException.Unauthorized) {
            Log.w(TAG, "album list: credential rejected", e)
            service.onAuthFailure(e.message)
            null
        } catch (e: ApiException.Forbidden) {
            Log.w(TAG, "album list: permission denied", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "album list failed", e)
            null
        }

        if (albums == null) {
            return cursor.withError(
                "Could not load albums from Immich. Open Immich File Picker to retry.",
            )
        }

        albums.forEach { album ->
            albumCache[album.id] = album
            writeDirectoryRow(
                cursor,
                DocumentIds.forAlbum(album.id),
                album.name,
                summary = albumSummary(album),
            )
        }
        return cursor
    }

    private fun albumSummary(album: PickerAlbum): String =
        when (album.assetCount) {
            0 -> "Empty album"
            1 -> "1 item"
            else -> "${album.assetCount} items"
        }

    // --------------------------------------------------------------- internals

    private fun buildDocumentsCursor(
        projection: Array<out String>?,
        state: PagerState,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        cursor.writeRankedAssets(state.assets)
        return cursor.withExtras(state)
    }

    /** Looks up an asset by id, tolerating a server that has since deleted it. */
    private fun resolveAsset(assetId: String): PickerAsset? {
        val session = service.session() ?: return null
        return runCatching {
            runBlocking {
                withTimeoutOrNull(CALL_TIMEOUT_MS) { session.repository.asset(assetId) }
            }
        }.onFailure { Log.w(TAG, "asset lookup failed for $assetId", it) }.getOrNull()
    }

    /**
     * Resolves an album's name when only its id is known.
     *
     * Happens when DocumentsUI restores a stack from a previous process: the folder id is
     * durable but [albumCache] is not.
     */
    private fun resolveAlbumTitle(albumId: String): String? {
        val session = service.session() ?: return null
        val albums = runCatching {
            runBlocking {
                withTimeoutOrNull(CALL_TIMEOUT_MS) {
                    session.repository.albums(forceRefresh = false)
                }
            }
        }.onFailure { Log.w(TAG, "album lookup failed for $albumId", it) }.getOrNull()
            ?: return null

        albums.forEach { albumCache[it.id] = it }
        return albumCache[albumId]?.name
    }

    private suspend fun downloadThumbnail(
        assetId: String,
        size: dev.immich.picker.cache.ThumbnailSize,
        thumbhash: String?,
        session: ImmichService.Session,
        signal: CancellationSignal?,
    ): File? {
        val key = service.thumbnails.fileFor(assetId, size, thumbhash).name
        return service.thumbnails.withLock(key) {
            // Another binder thread may have finished this while we waited for the lock.
            service.thumbnails.get(assetId, size, thumbhash)?.let { return@withLock it }

            val temp = File(service.thumbnailsDirTemp, "$assetId-${size.wireName}.part")
            temp.parentFile?.mkdirs()

            val ok = runCatching {
                runBlocking {
                    withTimeoutOrNull(CALL_TIMEOUT_MS) {
                        session.repository.downloadThumbnail(
                            assetId = assetId,
                            size = size,
                            sink = temp,
                            signal = signal,
                        )
                    } ?: error("thumbnail request timed out")
                }
            }.onFailure { error ->
                if (error !is ApiException.Cancelled) {
                    Log.w(TAG, "thumbnail download failed for $assetId", error)
                }
            }.isSuccess

            if (!ok) {
                temp.delete()
                return@withLock null
            }
            val committed = service.thumbnails.put(assetId, size, thumbhash, temp)
            temp.delete()
            committed
        }
    }

    /** Clears every cached shape when the credential generation moved. */
    private fun clearCachesForNewCredential() {
        cachedGeneration = -1L
        assetCache.clear()
        albumCache.clear()
        pagers.clear()
    }

    private fun authority(): String =
        (context?.packageName ?: BuildConfig.APPLICATION_ID) + AUTHORITY_SUFFIX

    /**
     * Tells DocumentsUI that this provider's roots and folder list changed.
     *
     * Called on credential changes only — never from the listing path, because that would
     * make DocumentsUI re-query and page the whole library without user action.
     */
    private fun notifyRootsChanged() {
        val ctx = context ?: return
        val resolver = ctx.contentResolver
        runCatching {
            resolver.notifyChange(DocumentsContract.buildRootsUri(authority()), null)
        }.onFailure { Log.w(TAG, "could not notify roots change", it) }
        listOf(
            DocumentIds.ROOT_DOCUMENT_ID,
            DocumentIds.ALBUMS_DOCUMENT_ID,
        ).forEach { documentId ->
            runCatching {
                resolver.notifyChange(
                    DocumentsContract.buildChildDocumentsUri(authority(), documentId),
                    null,
                )
            }.onFailure { Log.w(TAG, "could not notify change for $documentId", it) }
        }
        runCatching {
            resolver.notifyChange(
                DocumentsContract.buildRecentDocumentsUri(authority(), ROOT_ID),
                null,
            )
        }.onFailure { Log.w(TAG, "could not notify recents change", it) }
    }

    // ----------------------------------------------------------------- constants

    private companion object {
        const val TAG = "ImmichDocProvider"

        /** Root id reported in [queryRoots]. */
        const val ROOT_ID = "immich"

        /** Matches the manifest's `${applicationId}.documents` authority. */
        const val AUTHORITY_SUFFIX = ".documents"

        const val MODE_READ = "r"

        /** Upper bound on a single network round trip from a binder thread. */
        const val CALL_TIMEOUT_MS = 45_000L

        /**
         * Items per page. Small enough that a folder opens instantly and the first screen
         * is exactly one page; each further page is one deliberate scroll.
         */
        const val PAGE_SIZE = 50

        /**
         * Minimum gap between chunks.
         *
         * One render fires several queries; without this each advanced the pager and the
         * whole library arrived before the first screen was drawn. A person scrolling
         * cannot ask twice inside this window, so it costs nothing in normal use.
         */
        const val MIN_FETCH_INTERVAL_MS = 1_200L

        /** Recents is a shortcut surface; a few hundred entries is already generous. */
        const val RECENT_LIMIT = 200

        val ROOT_FLAGS = Root.FLAG_SUPPORTS_RECENTS or Root.FLAG_SUPPORTS_SEARCH or
            Root.FLAG_SUPPORTS_IS_CHILD

        /**
         * `DocumentsContract.Root.FLAG_HAS_SETTINGS`, which is `@SystemApi`.
         *
         * Spelling out the bit is what the constant compiles to anyway; referencing the
         * hidden symbol would fail the build. It makes DocumentsUI show a settings
         * affordance that routes ACTION_DOCUMENT_ROOT_SETTINGS to the provider's declared
         * `android:settingsActivity` — this app's settings screen.
         */
        const val FLAG_HAS_SETTINGS_COMPAT = 1 shl 17

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
        )

        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        fun matchesName(displayName: String, needle: String?): Boolean =
            needle.isNullOrEmpty() || displayName.contains(needle, ignoreCase = true)
    }
}

/**
 * Newest first, newest tie-break by display name.
 *
 * Applied on the way out of every listing so the visible order never depends on server
 * ordering, cursor stability, or DocumentsUI's own sorting.
 */
private fun List<PickerAsset>.sortedNewestFirst(): List<PickerAsset> =
    sortedWith(
        compareByDescending<PickerAsset> { it.takenAtMillis }
            .thenBy { it.displayName },
    )

/**
 * Attaches a user-visible message to a cursor.
 *
 * [DocumentsContract.EXTRA_ERROR] is shown by DocumentsUI in place of a bare empty folder,
 * which is the difference between "this source is broken" and "this source is telling you
 * to sign in".
 */
private fun Cursor.withError(message: String): Cursor {
    extras = (extras ?: Bundle()).apply {
        putString(DocumentsContract.EXTRA_ERROR, message)
    }
    return this
}

/** Thrown when the caller cancelled; DocumentsProvider treats it as a no-op. */
class OperationCanceledException : java.util.concurrent.CancellationException("Cancelled")

/**
 * Writes a directory row by column name.
 *
 * Projections are chosen by the caller, so rows must be built from `columnNames` rather
 * than from a fixed column order — DocumentsUI asks for its own set and a positional
 * writer would silently mislabel or crash on it.
 */
private fun writeDirectoryRow(
    cursor: MatrixCursor,
    documentId: String,
    displayName: String,
    summary: String? = null,
) {
    val row = cursor.newRow()
    for (column in cursor.columnNames) {
        when (column) {
            Document.COLUMN_DOCUMENT_ID -> row.add(documentId)
            Document.COLUMN_DISPLAY_NAME -> row.add(displayName)
            Document.COLUMN_MIME_TYPE -> row.add(Document.MIME_TYPE_DIR)
            Document.COLUMN_SUMMARY -> row.add(summary)
            // Directories are not writable: no create flags advertised.
            Document.COLUMN_FLAGS -> row.add(0)
            else -> row.add(null)
        }
    }
}

/**
 * Writes one asset row, keeping the SAF column mapping in a single place.
 */
private fun writeAssetRow(
    cursor: MatrixCursor,
    asset: PickerAsset,
    rankedName: String? = null,
) {
    val row = cursor.newRow()
    for (column in cursor.columnNames) {
        when (column) {
            Document.COLUMN_DOCUMENT_ID -> row.add(DocumentIds.forAsset(asset.id))
            Document.COLUMN_DISPLAY_NAME -> row.add(rankedName ?: asset.displayName)
            Document.COLUMN_MIME_TYPE -> row.add(asset.mimeType)
            Document.COLUMN_FLAGS -> row.add(Document.FLAG_SUPPORTS_THUMBNAIL)
            Document.COLUMN_SIZE -> row.add(asset.sizeBytes)
            Document.COLUMN_LAST_MODIFIED -> row.add(asset.takenAtMillis)
            else -> row.add(null)
        }
    }
}
