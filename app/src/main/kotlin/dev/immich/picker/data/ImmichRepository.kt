package dev.immich.picker.data

import android.util.Log
import dev.immich.picker.api.AlbumResponseDto
import dev.immich.picker.api.ApiJson
import dev.immich.picker.api.AssetResponseDto
import dev.immich.picker.api.AssetTypeEnumValues
import dev.immich.picker.api.ImmichEndpoints
import dev.immich.picker.api.LoginCredentialDto
import dev.immich.picker.api.MetadataSearchDto
import dev.immich.picker.api.SearchOrder
import dev.immich.picker.api.SearchOrderFieldValues
import dev.immich.picker.api.ServerVersionResponseDto
import dev.immich.picker.api.SearchFilter
import dev.immich.picker.api.BoolFilter
import dev.immich.picker.api.DateFilter
import dev.immich.picker.api.IdsFilter
import dev.immich.picker.net.ApiException
import dev.immich.picker.net.ImmichApiClient
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only view of an Immich library, shaped for a DocumentsProvider.
 *
 * All methods are `suspend` and must be called off the main thread. Nothing here
 * touches Android UI state, so it is trivially unit-testable against MockWebServer.
 */
class ImmichRepository(
    private val client: ImmichApiClient,
    private val credentials: ImmichCredentials,
) {
    private val api = ImmichEndpoints(client)

    /** Cached per-process so paging through a big album does not re-hit `/albums`. */
    private val albumCache = ConcurrentHashMap<String, PickerAlbum>()

    // ------------------------------------------------------------------- probing

    suspend fun serverVersion(): ServerVersionResponseDto = api.getServerVersion()

    /**
     * Returns the signed-in user's display data, and validates the credential.
     *
     * This is the single validation path used by login, "Test connection" and app start.
     * `GET /users/me` requires `user.read`, so that permission is required and not
     * optional. There is no fallback to a library call: a credential
     * that cannot read the profile is rejected up front with a clear message, instead of
     * being accepted here and failing later with a confusing error.
     */
    suspend fun me(): Pair<String?, String?> {
        val user = api.getMyUser()
        return user.name to user.email
    }

    // -------------------------------------------------------------------- albums

    /** All albums visible to the user, newest activity first. */
    suspend fun albums(forceRefresh: Boolean = false): List<PickerAlbum> {
        if (!forceRefresh && albumCache.isNotEmpty()) {
            // Cached list is only trusted within a single provider session; the
            // provider calls forceRefresh=true on the first query of each session.
            return albumCache.values.sortedByDescending { it.updatedAtMillis }
        }
        val remote = api.getAllAlbums()
        albumCache.clear()
        remote.map { it.toPickerAlbum() }.forEach { albumCache[it.id] = it }
        return albumCache.values.sortedByDescending { it.updatedAtMillis }
    }

    // ------------------------------------------------------------- timeline buckets

    // ------------------------------------------------------------- album / favorites

    /**
     * One page of assets in an album, newest first, using cursor paging so a 5k+
     * album never materialises in one response.
     */
    suspend fun albumAssets(
        albumId: String,
        cursor: String? = null,
        pageSize: Int = PAGE_SIZE,
        newestFirst: Boolean = true,
    ): AssetPage {
        // The modern filter uses SearchFilter.albumIds (IdsFilter). The top-level
        // MetadataSearchDto.albumIds list was deprecated in Immich v3.2.0.
        val body = searchBody(
            cursor = cursor,
            pageSize = pageSize,
            newestFirst = newestFirst,
            filter = SearchFilter(albumIds = IdsFilter(any = listOf(albumId))),
        )
        return api.searchAssets(body = body).toPage()
    }

    /** Everything, for the timeline and for search. */
    suspend fun allAssets(
        cursor: String? = null,
        pageSize: Int = PAGE_SIZE,
        newestFirst: Boolean = true,
    ): AssetPage = api.searchAssets(
        body = searchBody(cursor, pageSize, newestFirst = newestFirst),
    ).toPage()

    suspend fun asset(assetId: String): PickerAsset? =
        runCatching { api.getAssetInfo(assetId).toPickerAsset() }
            .onFailure { if (it is ApiException.IncompatibleSchema) throw it }
            .getOrNull()

    // ------------------------------------------------------------------ binaries

    /**
     * Downloads the untouched original into [sink].
     *
     * Always the `original` variant: the acceptance criteria require the picked
     * file to be byte-identical to what the user uploaded, and Immich serves
     * edited derivatives only when `edited=true` is passed (never here).
     */
    suspend fun downloadOriginal(
        assetId: String,
        sink: java.io.File,
        signal: android.os.CancellationSignal? = null,
    ): Long = api.downloadAsset(
        id = assetId,
        edited = false,
        sink = sink,
        signal = signal,
    )

    /** Downloads a thumbnail or preview JPEG/WebP into [sink]. */
    suspend fun downloadThumbnail(
        assetId: String,
        size: dev.immich.picker.cache.ThumbnailSize,
        sink: java.io.File,
        signal: android.os.CancellationSignal? = null,
    ): Long = api.viewAsset(
        id = assetId,
        size = size.wireName,
        sink = sink,
        signal = signal,
    )

    // ------------------------------------------------------------------- mapping

    private fun searchBody(
        cursor: String?,
        pageSize: Int,
        newestFirst: Boolean = true,
        filter: SearchFilter? = null,
    ): MetadataSearchDto = MetadataSearchDto(
        size = pageSize,
        cursor = cursor,
        page = null,
        withExif = true,
        withPeople = false,
        withStacked = false,
        // Ordering is pushed to the server so that cursor paging walks the same
        // direction as the display order. Sorting only the client side would make
        // page 2 sort relative to page 1, producing interleaved results.
        orderBy = SearchOrder(
            field = SearchOrderFieldValues.FILECREATEDAT,
            direction = if (newestFirst) "desc" else "asc",
        ),
        filter = filter,
    )

    /**
     * One page of favourites, newest first.
     *
     * Uses the v3 `filter` object rather than the deprecated top-level `isFavorite` field,
     * which Immich removed from the search DTO.
     */
    suspend fun favoriteAssets(
        cursor: String? = null,
        pageSize: Int = PAGE_SIZE,
        newestFirst: Boolean = true,
    ): AssetPage = api.searchAssets(
        body = searchBody(
            cursor = cursor,
            pageSize = pageSize,
            newestFirst = newestFirst,
            filter = SearchFilter(isFavorite = BoolFilter(eq = true)),
        ),
    ).toPage()

    private fun dev.immich.picker.api.SearchResponseDto.toPage(): AssetPage {
        val page = assets
        return AssetPage(
            // Trashed assets are dropped here rather than by the server. Immich 3.4.1's
            // MetadataSearchDto has no `isTrashed` filter, only the deprecated
            // `trashedAfter`/`trashedBefore` date bounds, so asking for "not trashed" is
            // not expressible in a request. Filtering on the response keeps trashed photos
            // out of the picker, which is the behaviour that matters, at the cost of an
            // occasionally short page.
            assets = page?.items.orEmpty()
                .filterNot { it.isTrashed }
                .mapNotNull { it.toPickerAsset() },
            // The server's cursor is passed through untouched: paging must follow the
            // server's own position, not the number of rows that survived filtering.
            nextCursor = page?.nextCursor,
        )
    }

    private fun AssetResponseDto.toPickerAsset(): PickerAsset? {
        val isVideo = type.equals(AssetTypeEnumValues.VIDEO, ignoreCase = true)
        val mime = originalMimeType?.takeIf { it.isNotBlank() }
            ?: if (isVideo) "video/mp4" else "image/jpeg"
        val taken = parseIso(fileCreatedAt) ?: parseIso(localDateTime) ?: parseIso(createdAt) ?: 0L
        return PickerAsset(
            id = id,
            mimeType = mime,
            displayName = displayNameFor(originalFileName, id, mime, taken),
            sizeBytes = exifInfo?.fileSizeInByte?.takeIf { it > 0 },
            takenAtMillis = taken,
            isVideo = isVideo,
            isFavorite = isFavorite,
            thumbhash = thumbhash,
        )
    }

    private fun AlbumResponseDto.toPickerAlbum(): PickerAlbum = PickerAlbum(
        id = id,
        name = albumName.ifBlank { "Untitled album" },
        assetCount = assetCount.toInt(),
        updatedAtMillis = parseIso(updatedAt) ?: parseIso(endDate) ?: 0L,
        coverAssetId = albumThumbnailAssetId,
    )

    companion object {
        private const val TAG = "ImmichRepository"

        /** 500 keeps a 5k album at 10 round trips and each response under ~1 MB. */
        const val PAGE_SIZE = 500

        fun parseIso(value: String?): Long? {
            if (value.isNullOrBlank()) return null
            return runCatching { java.time.Instant.parse(value).toEpochMilli() }
                .recoverCatching {
                    java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
                }
                .recoverCatching {
                    java.time.LocalDateTime.parse(value)
                        .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
                }
                .getOrNull()
        }

        /**
         * Builds a stable, human-readable file name. DocumentsUI shows
         * `COLUMN_DISPLAY_NAME` verbatim and Chrome uses it for the uploaded
         * filename, so it must carry the real extension.
         */
        fun displayNameFor(
            original: String?,
            assetId: String,
            mimeType: String,
            takenAtMillis: Long,
        ): String {
            val trimmed = original?.trim()
            if (!trimmed.isNullOrEmpty()) {
                // Immich keeps the original upload name; trust it unless it is
                // obviously unusable (path separators would break SAF).
                return trimmed.replace('/', '_').replace('\\', '_')
            }
            val stamp = if (takenAtMillis > 0) {
                java.time.Instant.ofEpochMilli(takenAtMillis)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US))
            } else {
                assetId.take(8)
            }
            val prefix = if (mimeType.startsWith("video/")) "VID" else "IMG"
            return "${prefix}_$stamp.${extensionFor(mimeType)}"
        }

        fun extensionFor(mimeType: String): String = when (mimeType.lowercase(Locale.US)) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            "image/avif" -> "avif"
            "image/tiff" -> "tiff"
            "image/bmp" -> "bmp"
            "image/svg+xml" -> "svg"
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "video/x-matroska" -> "mkv"
            "video/webm" -> "webm"
            "video/3gpp" -> "3gp"
            "video/x-msvideo" -> "avi"
            "audio/mpeg" -> "mp3"
            else -> mimeType.substringAfterLast('/', "bin").substringBefore('+')
        }
    }
}

/** Small helper so login can build a client without a repository instance. */
internal suspend fun ImmichApiClient.loginWithPassword(email: String, password: String): String {
    val endpoints = ImmichEndpoints(this)
    val response = endpoints.login(LoginCredentialDto(email = email, password = password))
    return response.accessToken
}

