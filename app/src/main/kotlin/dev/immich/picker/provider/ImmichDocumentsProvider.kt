package dev.immich.picker.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import androidx.annotation.WorkerThread
import dev.immich.picker.BuildConfig
import dev.immich.picker.R
import dev.immich.picker.ui.PickerNotifications
import dev.immich.picker.cache.ThumbnailSize
import dev.immich.picker.data.AssetPage
import dev.immich.picker.data.ImmichService
import dev.immich.picker.data.PickerAlbum
import dev.immich.picker.data.PickerAsset
import dev.immich.picker.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

/**
 * Exposes an Immich library to Android's Storage Access Framework.
 *
 * Structure of the document tree:
 *
 * ```
 * Immich (root)
 * ├── Albums
 * │   └── <album name>
 * │       └── <assets>
 * ├── Timeline
 * │   └── <YYYY-MM>
 * │       └── <assets>
 * └── Favorites
 *     └── <assets>
 * ```
 *
 * Threading contract: DocumentsProvider methods are called on a binder thread pool,
 * never the main thread, so blocking network calls here are correct. `openDocument`
 * still runs its download on a coroutine with a caller-supplied [CancellationSignal]
 * so DocumentsUI cancelling the picker aborts the transfer instead of finishing it.
 *
 * Paging: every listing is capped at [MAX_CHILDREN]. A folder with 100k assets
 * returns the newest page only, because SAF has no cursor-paging API that
 * DocumentsUI honours; returning 100k rows would block the picker for seconds.
 * The month buckets in `Timeline` are what keep huge libraries navigable.
 */
class ImmichDocumentsProvider : DocumentsProvider() {

    private lateinit var service: ImmichService

    /**
     * Per-generation listing caches. Keyed by folder document id, invalidated when
     * credentials change (login/logout) so a stale tree is never shown.
     */
    private val listingCache = ConcurrentHashMap<String, CachedListing>()
    private var cacheGeneration = -1L

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        ImmichService.initialize(ctx)
        service = ImmichService
        Log.i(TAG, "provider created; tested against Immich ${BuildConfig.TESTED_IMMICH_VERSION}")
        return true
    }

    // ------------------------------------------------------------------ roots

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

        val credentials = runCatching { service.credentialStore.current() }.getOrNull()
        if (credentials == null || !credentials.isComplete) {
            // Logged out (or never logged in): expose no root at all so the source
            // disappears from DocumentsUI instead of showing an empty, broken folder.
            return cursor
        }

        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, DOC_ROOT)
            add(Root.COLUMN_TITLE, context?.getString(R.string.provider_root_title) ?: "Immich")
            add(Root.COLUMN_SUMMARY, summaryFor(credentials))
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_RECENTS,
            )
            add(Root.COLUMN_MIME_TYPES, "image/*\nvideo/*")
            add(Root.COLUMN_AVAILABLE_BYTES, null)
        }
        return cursor
    }

    private fun summaryFor(credentials: dev.immich.picker.data.ImmichCredentials): String {
        val who = credentials.accountLabel
            ?: credentials.userEmail
            ?: credentials.userName
        val version = credentials.serverVersion
        return listOfNotNull(who, version?.let { "Immich $it" }).joinToString(" · ")
    }

    // -------------------------------------------------------------- documents

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val node = resolveNode(documentId) ?: return cursor
        node.writeTo(cursor)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        return populateChildren(parentDocumentId, cursor)
    }

    /**
     * API 26+ variant taking an extras [Bundle].
     *
     * Strategy: always *try* to return rows synchronously, with a short deadline.
     * A warm cache or a fast server therefore fills the folder immediately.
     *
     * Only if the fetch misses the deadline do we hand back `EXTRA_LOADING` and let
     * the background worker finish. Returning an empty cursor without the loading
     * flag is what produces the "No items" dead-end: DocumentsUI will not re-query
     * on its own, so the flag (and the matching `notifyChange`) is what makes the
     * spinner turn into content.
     */
    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        extras: Bundle?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        val children = loadChildrenOrNull(parentDocumentId, deadlineMs = INTERACTIVE_DEADLINE_MS)
        if (children != null) {
            for (child in children) {
                child.writeTo(cursor)
            }
            return cursor
        }

        cursor.extras = Bundle().apply {
            putBoolean(DocumentsContract.EXTRA_LOADING, true)
            putBoolean(DocumentsContract.EXTRA_INFO, false)
        }
        scheduleBackgroundLoad(parentDocumentId)
        return cursor
    }

    private fun populateChildren(
        parentDocumentId: String,
        cursor: MatrixCursor,
    ): Cursor {
        val children = loadChildren(parentDocumentId) ?: return cursor
        for (child in children) {
            child.writeTo(cursor)
        }
        return cursor
    }

    private fun isListingCached(documentId: String): Boolean {
        val cached = listingCache[documentId] ?: return false
        return SystemClock.elapsedRealtime() - cached.loadedAt < LISTING_TTL_MS
    }

    /**
     * Fetches a folder on a worker thread, then notifies DocumentsUI that the
     * cursor it received earlier is now stale. One in-flight load per folder.
     */
    private fun scheduleBackgroundLoad(documentId: String) {
        if (!loadingFolders.add(documentId)) return

        Thread({
            try {
                loadChildren(documentId)
                context?.contentResolver?.let {
                    it.notifyChange(
                        DocumentsContract.buildChildDocumentsUri(AUTHORITY, documentId),
                        null,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "background load failed for $documentId", e)
            } finally {
                loadingFolders.remove(documentId)
            }
        }, "immich-listing-$documentId").start()
    }

    private val loadingFolders = ConcurrentHashMap.newKeySet<String>()

    private val AUTHORITY: String
        get() = "${BuildConfig.APPLICATION_ID}.documents"

    /**
     * Recent documents, used by the "Recent" view in DocumentsUI. Only assets are
     * returned, newest first.
     */
    override fun queryRecentDocuments(
        rootId: String,
        projection: Array<out String>?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (rootId != ROOT_ID) return cursor

        val session = service.session() ?: return cursor
        val page = runCatching {
            blocking { session.repository.allAssets(cursor = null, pageSize = RECENT_LIMIT) }
        }.getOrNull() ?: return cursor

        for (asset in page.assets) {
            nodeFor(asset).writeTo(cursor)
        }
        return cursor
    }

    /** Search is limited to what the SAF search box can do without an index. */
    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (rootId != ROOT_ID || query.isBlank()) return cursor

        // Filter the cached listings instead of issuing an unbounded server search:
        // search results would otherwise be truncated arbitrarily and cost a round trip
        // per keystroke.
        val needle = query.trim().lowercase()
        val seen = HashSet<String>()
        for (cached in listingCache.values) {
            for (child in cached.children) {
                if (child is DocumentNode.AssetNode &&
                    (child.asset.displayName.lowercase().contains(needle))
                ) {
                    if (seen.add(child.asset.id)) child.writeTo(cursor)
                }
            }
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = FolderRef.parse(parentDocumentId) ?: return false
        val child = FolderRef.parse(documentId)

        if (child == null) {
            // Not a folder id, so it is an asset id: assets live directly under the
            // root (recent photos), an album, a month bucket, or the favourites folder.
            return parent.kind in setOf(
                FolderKind.ROOT,
                FolderKind.ALBUM,
                FolderKind.MONTH,
                FolderKind.FAVORITES,
            )
        }
        return when (parent.kind) {
            FolderKind.ROOT -> child.kind in setOf(FolderKind.ALBUMS, FolderKind.TIMELINE, FolderKind.FAVORITES)
            FolderKind.ALBUMS -> child.kind == FolderKind.ALBUM
            FolderKind.TIMELINE -> child.kind == FolderKind.MONTH
            FolderKind.ALBUM, FolderKind.MONTH, FolderKind.FAVORITES -> false
        }
    }

    override fun getDocumentType(documentId: String): String {
        val node = resolveNode(documentId) ?: return Document.MIME_TYPE_DIR
        return node.mimeType
    }

    // ------------------------------------------------------------- open assets

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val assetId = assetIdOf(documentId)
            ?: throw FileNotFoundException("Not an asset document: $documentId")

        // The provider contract is read-only. Reject write modes explicitly rather
        // than pretending they worked.
        if (mode != "r") {
            throw FileNotFoundException("Immich Picker is read-only (requested mode: $mode)")
        }

        signal?.throwIfCanceled()

        val session = service.session()
            ?: throw FileNotFoundException("Not signed in to Immich")

        val asset = cachedAsset(assetId)
            ?: resolveAsset(assetId, session)
            ?: throw FileNotFoundException("Asset $assetId no longer exists on the server")

        return try {
            val file = blocking(signal) {
                originalFileFor(asset, session, signal)
            }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: ApiException.Unauthorized) {
            Log.w(TAG, "openDocument: token rejected", e)
            throw FileNotFoundException("Immich session expired; open Immich Picker to sign in again")
        } catch (e: ApiException.Cancelled) {
            throw OperationCanceledException()
        } catch (e: Exception) {
            Log.e(TAG, "openDocument failed for $assetId", e)
            throw FileNotFoundException("Could not download ${asset.displayName}: ${e.message}")
        }
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: android.graphics.Point,
        signal: CancellationSignal?,
    ): android.content.res.AssetFileDescriptor {
        val assetId = assetIdOf(documentId)
            ?: throw FileNotFoundException("Not an asset document: $documentId")

        signal?.throwIfCanceled()

        val session = service.session()
            ?: throw FileNotFoundException("Not signed in to Immich")

        val size = ThumbnailSize.forHint(sizeHint.x, sizeHint.y)
        val asset = cachedAsset(assetId) ?: resolveAsset(assetId, session)

        val file = service.thumbnails.get(assetId, size, asset?.thumbhash)
            ?: try {
                blocking(signal) {
                    downloadThumbnail(assetId, size, asset?.thumbhash, session, signal)
                } ?: throw FileNotFoundException("Thumbnail unavailable for $assetId")
            } catch (e: ApiException.Cancelled) {
                // DocumentsUI cancels thumbnail requests constantly while scrolling.
                // That is normal flow control, not an error worth surfacing.
                throw OperationCanceledException()
            }

        return android.content.res.AssetFileDescriptor(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH,
        )
    }

    // --------------------------------------------------------------- internals

    private fun originalFileFor(
        asset: PickerAsset,
        session: ImmichService.Session,
        signal: CancellationSignal?,
    ): File {
        service.originals.get(asset.id, asset.displayName)?.let { return it }

        val target = service.originals.fileFor(asset.id, asset.displayName)
        target.parentFile?.mkdirs()

        runBlocking {
            session.repository.downloadOriginal(
                assetId = asset.id,
                sink = target,
                signal = signal,
            )
        }
        return service.originals.commit(asset.id, asset.displayName)
    }

    private fun downloadThumbnail(
        assetId: String,
        size: ThumbnailSize,
        thumbhash: String?,
        session: ImmichService.Session,
        signal: CancellationSignal?,
    ): File? {
        val key = service.thumbnails.fileFor(assetId, size, thumbhash).name
        return service.thumbnails.withLock(key) {
            // Another binder thread may have finished the download while we waited.
            service.thumbnails.get(assetId, size, thumbhash)?.let { return@withLock it }

            val temp = File(service.thumbnailsDirTemp, "$assetId-${size.wireName}.part")
            temp.parentFile?.mkdirs()
            val ok = runCatching {
                runBlocking {
                    session.repository.downloadThumbnail(
                        assetId = assetId,
                        size = size,
                        sink = temp,
                        signal = signal,
                    )
                }
            }.onFailure { error ->
                // Cancellations are normal (grid recycling); anything else is worth a log.
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

    private fun cachedAsset(assetId: String): PickerAsset? {
        for (cached in listingCache.values) {
            for (child in cached.children) {
                if (child is DocumentNode.AssetNode && child.asset.id == assetId) return child.asset
            }
        }
        return null
    }

    private fun resolveAsset(assetId: String, session: ImmichService.Session): PickerAsset? =
        runCatching { blocking { session.repository.asset(assetId) } }
            .onFailure { Log.w(TAG, "asset lookup failed for $assetId", it) }
            .getOrNull()

    /**
     * Loads a folder's children, memoised for [LISTING_TTL_MS].
     *
     * This is where `EXTRA_LOADING` matters: DocumentsUI calls
     * `queryChildDocuments` once, synchronously. A slow server means a slow folder
     * open, so the result is cached and the picker's own progress indicator is
     * driven by the notification posted after the first successful load.
     */
    /**
     * [loadChildren] with a deadline. Returns null when the fetch could not finish
     * within [deadlineMs], which is the signal to fall back to `EXTRA_LOADING`.
     *
     * Also returns null (never an empty list) when there is no session, so the caller
     * cannot mistake "not signed in" for "empty folder".
     */
    private fun loadChildrenOrNull(documentId: String, deadlineMs: Long): List<DocumentNode>? {
        if (service.session() == null) {
            Log.w(TAG, "loadChildrenOrNull($documentId): no session")
            return null
        }
        listingCache[documentId]?.let { cached ->
            if (SystemClock.elapsedRealtime() - cached.loadedAt < LISTING_TTL_MS) {
                Log.d(TAG, "loadChildrenOrNull($documentId): cache hit (${cached.children.size})")
                return cached.children
            }
        }
        return try {
            blockingWithDeadline(deadlineMs) { buildChildrenFor(documentId) }
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.i(TAG, "loadChildrenOrNull($documentId): deadline exceeded, deferring to background")
            null
        } catch (e: Exception) {
            Log.w(TAG, "loadChildrenOrNull($documentId): failed", e)
            null
        }
    }

    private fun loadChildren(documentId: String): List<DocumentNode>? {
        val session = service.session()
        if (session == null) {
            Log.w(TAG, "loadChildren($documentId): no session")
            return emptyList()
        }

        synchronized(this) {
            if (cacheGeneration != service.currentGeneration()) {
                listingCache.clear()
                cacheGeneration = service.currentGeneration()
            }
        }

        listingCache[documentId]?.let { cached ->
            if (SystemClock.elapsedRealtime() - cached.loadedAt < LISTING_TTL_MS) {
                return cached.children
            }
        }

        val ref = FolderRef.parse(documentId) ?: return emptyList()

        val children = try {
            blocking { buildChildren(ref, session) }
        } catch (e: ApiException.Unauthorized) {
            // Token expired mid-session: drop the root and flag the UI so the
            // notification/login flow can take over. An empty folder is never silent
            // because queryRoots stops returning a root as well.
            Log.w(TAG, "loadChildren($documentId): unauthorised", e)
            service.onAuthFailure(e.message)
            context?.let { PickerNotifications.notifyAuthExpired(it, e.message) }
            return null
        } catch (e: ApiException.Network) {
            Log.w(TAG, "loadChildren($documentId): offline", e)
            service.onNetworkFailure(e.message)
            context?.let { PickerNotifications.notifyUnreachable(it, e.message) }
            return emptyList()
        } catch (e: ApiException.IncompatibleSchema) {
            Log.e(TAG, "loadChildren($documentId): schema mismatch on ${e.endpoint}", e)
            service.onIncompatibleServer(e)
            context?.let { PickerNotifications.notifyIncompatible(it, e.message) }
            return emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "loadChildren($documentId) failed", e)
            return emptyList()
        }

        listingCache[documentId] = CachedListing(children, SystemClock.elapsedRealtime())
        return children
    }

    @WorkerThread
    private suspend fun buildChildren(
        ref: FolderRef,
        session: ImmichService.Session,
    ): List<DocumentNode> =
        when (ref.kind) {
            // The root doubles as the landing view: DocumentsUI always sorts
            // directories above files, so returning the three navigational folders
            // followed by the newest assets gives "folders at the top, most recent
            // photos straight away" without the user having to drill in.
            FolderKind.ROOT -> buildList {
                add(DocumentNode.FolderNode(DOC_ALBUMS, "Albums", 0L))
                add(DocumentNode.FolderNode(DOC_FAVORITES, "Favorites", 0L))
                add(DocumentNode.FolderNode(DOC_TIMELINE, "Timeline (by month)", 0L))
                val recent = runCatching {
                    session.repository
                        .allAssets(cursor = null, pageSize = ROOT_RECENT_LIMIT)
                        .assets
                }.getOrElse { emptyList() }
                recent.forEach { add(nodeFor(it)) }
            }

            FolderKind.ALBUMS -> session.repository.albums(forceRefresh = true)
                .map { album -> album.toNode() }

            FolderKind.TIMELINE -> session.repository.timelineBuckets()
                .map { bucket ->
                    DocumentNode.FolderNode(
                        documentId = DOC_TIMELINE + "/" + bucket.yearMonth,
                        displayName = bucket.displayName,
                        lastModified = bucket.epochMillisApprox,
                    )
                }

            FolderKind.ALBUM -> {
                val albumId = ref.value ?: return emptyList()
                val page = session.repository.albumAssets(albumId, pageSize = MAX_CHILDREN)
                page.assets.map { nodeFor(it) }
            }

            FolderKind.MONTH -> {
                val yearMonth = ref.value ?: return emptyList()
                val bucketKey = "$yearMonth-01T00:00:00.000Z"
                val assets = session.repository.bucketAssets(bucketKey, limit = MAX_CHILDREN)
                assets.map { nodeFor(it) }
            }

            FolderKind.FAVORITES -> {
                val page = session.repository.favoriteAssets(pageSize = MAX_CHILDREN)
                page.assets.map { nodeFor(it) }
            }
        }

    private fun PickerAlbum.toNode(): DocumentNode.FolderNode = DocumentNode.FolderNode(
        documentId = DOC_ALBUMS + "/" + id,
        displayName = name,
        lastModified = updatedAtMillis,
    )

    private fun nodeFor(asset: PickerAsset): DocumentNode.AssetNode = DocumentNode.AssetNode(asset)

    private fun resolveNode(documentId: String): DocumentNode? {
        assetIdOf(documentId)?.let { assetId ->
            val session = service.session() ?: return null
            val asset = cachedAsset(assetId) ?: resolveAsset(assetId, session) ?: return null
            return DocumentNode.AssetNode(asset)
        }

        val ref = FolderRef.parse(documentId) ?: return null
        return when (ref.kind) {
            FolderKind.ROOT -> DocumentNode.FolderNode(DOC_ROOT, "Immich", 0L)
            FolderKind.ALBUMS -> DocumentNode.FolderNode(DOC_ALBUMS, "Albums", 0L)
            FolderKind.TIMELINE -> DocumentNode.FolderNode(DOC_TIMELINE, "Timeline", 0L)
            FolderKind.FAVORITES -> DocumentNode.FolderNode(DOC_FAVORITES, "Favorites", 0L)
            FolderKind.ALBUM -> {
                val albumId = ref.value ?: return null
                val session = service.session() ?: return null
                val album = runCatching { blocking { session.repository.album(albumId) } }.getOrNull()
                    ?: return null
                album.toNode()
            }
            FolderKind.MONTH -> {
                val yearMonth = ref.value ?: return null
                DocumentNode.FolderNode(
                    documentId = DOC_TIMELINE + "/" + yearMonth,
                    displayName = yearMonth,
                    lastModified = parseYearMonth(yearMonth),
                )
            }
        }
    }

    private fun parseYearMonth(yearMonth: String): Long {
        val year = yearMonth.substringBefore('-').toIntOrNull() ?: 1970
        val month = yearMonth.substringAfter('-', "1").toIntOrNull() ?: 1
        return runCatching {
            java.time.LocalDate.of(year, month, 1)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    /**
     * Fetches and caches one folder. Shared by the interactive deadline path and the
     * background worker so both produce identical rows.
     */
    private fun buildChildrenFor(documentId: String): List<DocumentNode>? {
        val session = service.session() ?: run {
            Log.w(TAG, "buildChildrenFor($documentId): no session")
            return null
        }
        val ref = FolderRef.parse(documentId) ?: run {
            Log.w(TAG, "buildChildrenFor($documentId): unparseable document id")
            return null
        }

        synchronized(this) {
            if (cacheGeneration != service.currentGeneration()) {
                listingCache.clear()
                cacheGeneration = service.currentGeneration()
            }
        }

        val children = try {
            blocking { buildChildren(ref, session) }
        } catch (e: ApiException.Unauthorized) {
            Log.w(TAG, "buildChildrenFor($documentId): unauthorised", e)
            service.onAuthFailure(e.message)
            context?.let { PickerNotifications.notifyAuthExpired(it, e.message) }
            return null
        } catch (e: ApiException.Network) {
            Log.w(TAG, "buildChildrenFor($documentId): offline", e)
            service.onNetworkFailure(e.message)
            context?.let { PickerNotifications.notifyUnreachable(it, e.message) }
            return emptyList()
        } catch (e: ApiException.IncompatibleSchema) {
            Log.e(TAG, "buildChildrenFor($documentId): schema mismatch on ${e.endpoint}", e)
            service.onIncompatibleServer(e)
            context?.let { PickerNotifications.notifyIncompatible(it, e.message) }
            return emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "buildChildrenFor($documentId) failed", e)
            return emptyList()
        }

        listingCache[documentId] = CachedListing(children, SystemClock.elapsedRealtime())
        Log.d(TAG, "buildChildrenFor($documentId): ${children.size} children")
        return children
    }

    /**
     * Runs a suspending network call from a binder thread, honouring cancellation and
     * a hard timeout so a hung server cannot wedge DocumentsUI indefinitely.
     */
    private fun <T> blocking(
        signal: CancellationSignal? = null,
        block: suspend () -> T,
    ): T = runBlocking {
        val result = withTimeoutOrNull(CALL_TIMEOUT_MS) {
            block()
        }
        if (result == null) {
            throw java.io.IOException("Immich server did not respond within ${CALL_TIMEOUT_MS / 1000}s")
        }
        signal?.throwIfCanceled()
        result
    }

    /**
     * Like [blocking] but fails fast, so the interactive caller can fall back to
     * `EXTRA_LOADING` rather than stalling DocumentsUI's binder thread.
     */
    private fun <T> blockingWithDeadline(deadlineMs: Long, block: suspend () -> T): T =
        runBlocking {
            withTimeoutOrNull(deadlineMs) { block() }
                ?: throw java.util.concurrent.TimeoutException(
                    "listing not ready within ${deadlineMs}ms",
                )
        }

    private fun assetIdOf(documentId: String): String? =
        if (documentId.startsWith(PREFIX_ASSET)) documentId.removePrefix(PREFIX_ASSET) else null

    private class CachedListing(val children: List<DocumentNode>, val loadedAt: Long)

    // ----------------------------------------------------------------- constants

    private companion object {
        const val TAG = "ImmichDocProvider"

        const val ROOT_ID = "immich"
        const val DOC_ROOT = "root"
        const val DOC_ALBUMS = "albums"
        const val DOC_TIMELINE = "timeline"
        const val DOC_FAVORITES = "favorites"
        const val PREFIX_ASSET = "asset:"

        /**
         * Upper bound on rows returned per folder. 1000 keeps the IPC payload around
         * 100 KB (SAF marshals the whole cursor through a Binder transaction with a
         * 1 MB limit) and keeps DocumentsUI responsive.
         */
        const val MAX_CHILDREN = 1000

        /**
         * How long an interactive folder open will wait for the server before handing
         * back `EXTRA_LOADING` and finishing in the background.
         */
        const val INTERACTIVE_DEADLINE_MS = 2500L

        const val RECENT_LIMIT = 100

        /**
         * How many newest assets are shown directly in the root. Kept well below
         * [MAX_CHILDREN] so the landing view stays a single fast request; older
         * photos remain reachable through the Timeline month folders.
         */
        const val ROOT_RECENT_LIMIT = 300

        const val LISTING_TTL_MS = 45_000L
        const val CALL_TIMEOUT_MS = 45_000L

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES,
        )

        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_ICON,
        )
    }
}

/** Thrown when the caller cancelled; DocumentsProvider treats it as a no-op. */
class OperationCanceledException : java.util.concurrent.CancellationException("Cancelled")

// ----------------------------------------------------------------------------
// Document node model
// ----------------------------------------------------------------------------

/**
 * A node in the document tree, either a folder or an asset, with the SAF column
 * mapping kept in one place so folder and asset rows cannot drift apart.
 */
sealed class DocumentNode {
    abstract val documentId: String
    abstract val displayName: String
    abstract val mimeType: String
    abstract val lastModified: Long

    fun writeTo(cursor: MatrixCursor) {
        val row = cursor.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, documentId)
        row.add(Document.COLUMN_DISPLAY_NAME, displayName)
        row.add(Document.COLUMN_MIME_TYPE, mimeType)
        row.add(Document.COLUMN_FLAGS, flags())
        row.add(Document.COLUMN_SIZE, size())
        row.add(Document.COLUMN_LAST_MODIFIED, lastModified)
        row.add(Document.COLUMN_ICON, null)
    }

    protected abstract fun flags(): Int
    protected open fun size(): Long? = null

    data class FolderNode(
        override val documentId: String,
        override val displayName: String,
        override val lastModified: Long,
    ) : DocumentNode() {
        override val mimeType: String = Document.MIME_TYPE_DIR
        override fun flags(): Int = Document.FLAG_DIR_PREFERS_GRID
    }

    data class AssetNode(val asset: PickerAsset) : DocumentNode() {
        override val documentId: String get() = "asset:" + asset.id
        override val displayName: String get() = asset.displayName
        override val mimeType: String get() = asset.mimeType
        override val lastModified: Long get() = asset.takenAtMillis

        override fun flags(): Int =
            Document.FLAG_SUPPORTS_THUMBNAIL or Document.FLAG_PARTIAL

        override fun size(): Long? = asset.sizeBytes
    }
}

/** Typed representation of a folder document id. */
data class FolderRef(val kind: FolderKind, val value: String? = null) {
    companion object {
        fun parse(documentId: String): FolderRef? = when {
            documentId == "root" -> FolderRef(FolderKind.ROOT)
            documentId == "albums" -> FolderRef(FolderKind.ALBUMS)
            documentId == "timeline" -> FolderRef(FolderKind.TIMELINE)
            documentId == "favorites" -> FolderRef(FolderKind.FAVORITES)
            documentId.startsWith("albums/") ->
                FolderRef(FolderKind.ALBUM, documentId.removePrefix("albums/"))
            documentId.startsWith("timeline/") ->
                FolderRef(FolderKind.MONTH, documentId.removePrefix("timeline/"))
            else -> null
        }
    }
}

enum class FolderKind { ROOT, ALBUMS, TIMELINE, FAVORITES, ALBUM, MONTH }
