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
import dev.immich.picker.api.TimeBucketAssetResponseDto
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

    suspend fun ping(): Boolean = api.pingServer().res == "pong"

    /** Returns the signed-in user's display data; validates the credential. */
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

    suspend fun album(albumId: String, forceRefresh: Boolean = false): PickerAlbum? {
        if (!forceRefresh) albumCache[albumId]?.let { return it }
        val album = runCatching { api.getAlbumInfo(albumId) }.getOrNull() ?: return null
        return album.toPickerAlbum().also { albumCache[it.id] = it }
    }

    // ------------------------------------------------------------- timeline buckets

    /**
     * Month buckets for the timeline, newest first.
     *
     * Immich truncates the timeline bucket key to a month (`date_trunc('MONTH',
     * localDateTime)`), which is exactly the `/Timeline/<YYYY-MM>` grouping this
     * provider needs, and it is computed server-side so 100k+ libraries stay fast.
     */
    suspend fun timelineBuckets(): List<MonthBucket> =
        api.getTimeBuckets(order = "desc")
            .map { bucket ->
                val yearMonth = bucket.timeBucket.take(7)
                MonthBucket(
                    yearMonth = yearMonth,
                    bucketKey = bucket.timeBucket,
                    count = bucket.count.toInt(),
                )
            }
            .filter { it.yearMonth.length == 7 }
            .distinctBy { it.yearMonth }
            .sortedByDescending { it.yearMonth }

    /** Assets inside a single month bucket. */
    suspend fun bucketAssets(bucketKey: String, limit: Int = PAGE_SIZE): List<PickerAsset> {
        val response = api.getTimeBucket(
            timeBucket = bucketKey,
            order = "desc",
            withStacked = false,
        )
        return response.toPickerAssets().take(limit)
    }

    // ------------------------------------------------------------- album / favorites

    /**
     * One page of assets in an album, newest first, using cursor paging so a 5k+
     * album never materialises in one response.
     */
    suspend fun albumAssets(
        albumId: String,
        cursor: String? = null,
        pageSize: Int = PAGE_SIZE,
    ): AssetPage {
        // The modern filter uses SearchFilter.albumIds (IdsFilter). The top-level
        // MetadataSearchDto.albumIds list was deprecated in Immich v3.2.0.
        val body = searchBody(
            cursor = cursor,
            pageSize = pageSize,
            filter = SearchFilter(albumIds = IdsFilter(any = listOf(albumId))),
        )
        return api.searchAssets(body = body).toPage()
    }

    /**
     * One page of favourites, newest first.
     *
     * Uses the v3 `filter` object rather than the deprecated top-level
     * `isFavorite`/`type` fields, which Immich removed from the search DTO.
     */
    suspend fun favoriteAssets(
        cursor: String? = null,
        pageSize: Int = PAGE_SIZE,
    ): AssetPage {
        val body = searchBody(
            cursor = cursor,
            pageSize = pageSize,
            filter = SearchFilter(isFavorite = BoolFilter(eq = true)),
        )
        return api.searchAssets(body = body).toPage()
    }

    /** Everything, for the "All" pseudo-folder fallback and search. */
    suspend fun allAssets(
        cursor: String? = null,
        pageSize: Int = PAGE_SIZE,
    ): AssetPage = api.searchAssets(body = searchBody(cursor, pageSize)).toPage()

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
        filter: SearchFilter? = null,
    ): MetadataSearchDto = MetadataSearchDto(
        size = pageSize,
        cursor = cursor,
        page = null,
        withExif = true,
        withPeople = false,
        withStacked = false,
        orderBy = SearchOrder(
            field = SearchOrderFieldValues.FILECREATEDAT,
            direction = "desc",
        ),
        filter = filter,
    )

    private fun dev.immich.picker.api.SearchResponseDto.toPage(): AssetPage {
        val page = assets
        return AssetPage(
            assets = page?.items.orEmpty().mapNotNull { it.toPickerAsset() },
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

    /**
     * Converts Immich's column-oriented time-bucket response into a flat list.
     *
     * `getTimeBucket` returns parallel arrays (`id[]`, `isImage[]`, ...) rather than
     * an array of objects. Sizes must be read defensively: a server that adds or
     * drops a column leaves the arrays different lengths.
     */
    private fun TimeBucketAssetResponseDto.toPickerAssets(): List<PickerAsset> {
        val count = id.size
        val result = ArrayList<PickerAsset>(count)
        for (i in 0 until count) {
            val assetId = id.getOrNull(i) ?: continue
            val image = isImage.getOrNull(i) ?: true
            val taken = parseIso(fileCreatedAt.getOrNull(i)) ?: 0L
            val mime = if (image) "image/jpeg" else "video/mp4"
            result.add(
                PickerAsset(
                    id = assetId,
                    mimeType = mime,
                    displayName = displayNameFor(null, assetId, mime, taken),
                    sizeBytes = null,
                    takenAtMillis = taken,
                    isVideo = !image,
                    isFavorite = isFavorite.getOrNull(i) ?: false,
                    thumbhash = thumbhash.getOrNull(i),
                ),
            )
        }
        return result
    }

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

internal suspend fun ImmichApiClient.validateToken(): Boolean {
    val endpoints = ImmichEndpoints(this)
    return endpoints.validateAccessToken().authStatus
}
