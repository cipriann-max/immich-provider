package dev.immich.picker.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import dev.immich.picker.BuildConfig
import dev.immich.picker.data.ImmichService
import dev.immich.picker.data.PickerAsset
import dev.immich.picker.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves Immich assets as read-only `content://` documents.
 *
 * ## Deliberately rootless
 *
 * [queryRoots] returns nothing. A root would make DocumentsUI render its own browser
 * for Immich — a second picker, with a layout and sort order we cannot control — which
 * is exactly what this app is avoiding.
 *
 * The single picker is [dev.immich.picker.ui.picker.PickerActivity]. It resolves an asset
 * the user tapped to a document URI on this provider
 * (`content://<applicationId>.documents/document/asset:<assetId>`) and returns that URI
 * to the calling app. The receiver then reads the bytes through [openDocument] here, so
 * the original file flows over a normal SAF grant. Nothing is copied, and the provider
 * never needs to be browsable for any of that to work.
 *
 * ## Read-only by construction
 *
 * There is no `createDocument`, `deleteDocument`, `renameDocument` or `openDocument` write
 * path, and `openDocument` rejects any mode other than `r`.
 */
class ImmichDocumentsProvider : DocumentsProvider() {

    private lateinit var service: ImmichService

    /** Assets resolved during this process, so metadata survives between calls. */
    private val assetCache = java.util.concurrent.ConcurrentHashMap<String, PickerAsset>()

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        ImmichService.initialize(ctx)
        service = ImmichService
        Log.i(TAG, "provider created; built for Immich ${BuildConfig.TESTED_IMMICH_VERSION}")
        return true
    }

    // ------------------------------------------------------------------ roots

    /**
     * Always empty: see the class comment. Suppressing the root is what guarantees a
     * single picker, and it means no stale or duplicate Immich entry can appear in
     * DocumentsUI, logged in or not.
     */
    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

    // -------------------------------------------------------------- documents

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val assetId = assetIdOf(documentId) ?: return cursor

        val asset = assetCache[assetId] ?: resolveAsset(assetId) ?: return cursor
        assetCache[assetId] = asset
        asset.writeTo(cursor)
        return cursor
    }

    /**
     * No folder hierarchy exists, because there is no browsable root. Returning an empty
     * cursor keeps the contract honest rather than fabricating a tree.
     */
    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        extras: android.os.Bundle?,
    ): Cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

    override fun getDocumentType(documentId: String): String {
        val assetId = assetIdOf(documentId) ?: return Document.MIME_TYPE_DIR
        val asset = assetCache[assetId] ?: resolveAsset(assetId)
        return asset?.mimeType ?: Document.MIME_TYPE_DIR
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        assetIdOf(documentId) != null

    // ------------------------------------------------------------- open assets

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val assetId = assetIdOf(documentId)
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
        val assetId = assetIdOf(documentId)
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

    // --------------------------------------------------------------- internals

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

    /** Looks up an asset by id, tolerating a server that has since deleted it. */
    private fun resolveAsset(assetId: String): PickerAsset? {
        val session = service.session() ?: return null
        return runCatching {
            runBlocking {
                withTimeoutOrNull(CALL_TIMEOUT_MS) { session.repository.asset(assetId) }
            }
        }.onFailure { Log.w(TAG, "asset lookup failed for $assetId", it) }.getOrNull()
    }

    private fun assetIdOf(documentId: String): String? =
        if (documentId.startsWith(PREFIX_ASSET)) documentId.removePrefix(PREFIX_ASSET) else null

    // ----------------------------------------------------------------- constants

    private companion object {
        const val TAG = "ImmichDocProvider"

        /** Document-id prefix used in `content://<authority>/document/asset:<id>`. */
        const val PREFIX_ASSET = "asset:"

        const val MODE_READ = "r"

        /** Upper bound on a single network round trip from a binder thread. */
        const val CALL_TIMEOUT_MS = 45_000L

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
        )

        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/** Thrown when the caller cancelled; DocumentsProvider treats it as a no-op. */
class OperationCanceledException : java.util.concurrent.CancellationException("Cancelled")

/**
 * Writes one asset row into a cursor, keeping the SAF column mapping in a single place.
 */
private fun PickerAsset.writeTo(cursor: MatrixCursor) {
    val row = cursor.newRow()
    row.add(Document.COLUMN_DOCUMENT_ID, "asset:$id")
    row.add(Document.COLUMN_DISPLAY_NAME, displayName)
    row.add(Document.COLUMN_MIME_TYPE, mimeType)
    row.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_THUMBNAIL)
    row.add(Document.COLUMN_SIZE, sizeBytes)
    row.add(Document.COLUMN_LAST_MODIFIED, takenAtMillis)
}
