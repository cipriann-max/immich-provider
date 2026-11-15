package dev.immich.picker.data

/**
 * A single media item, reduced to what a DocumentsProvider needs.
 *
 * The generated API models are wide; this is the narrow shape the picker works
 * with so the provider never reasons about Immich-specific shapes.
 */
data class PickerAsset(
    val id: String,
    /** Canonical MIME type, e.g. `image/jpeg` or `video/mp4`. */
    val mimeType: String,
    /** File name including extension, e.g. `IMG_20240104_101500.jpg`. */
    val displayName: String,
    val sizeBytes: Long?,
    /** Epoch millis, used for `COLUMN_LAST_MODIFIED` and sorting. */
    val takenAtMillis: Long,
    val isVideo: Boolean,
    val isFavorite: Boolean,
    /** Immich's cache-busting thumbnail hash; changes when the asset is edited. */
    val thumbhash: String?,
) {
    val isImage: Boolean get() = !isVideo
}

/** An album as shown under `Albums/`. */
data class PickerAlbum(
    val id: String,
    val name: String,
    val assetCount: Int,
    val updatedAtMillis: Long,
    val coverAssetId: String?,
)

/** A month bucket under `Timeline/`. */
data class MonthBucket(
    /** `YYYY-MM` in the asset's local time. */
    val yearMonth: String,
    /** Immich's opaque bucket key (`YYYY-MM-DDT00:00:00.000Z`). */
    val bucketKey: String,
    val count: Int,
) {
    val displayName: String get() = yearMonth
    val epochMillisApprox: Long
        get() {
            val year = yearMonth.substringBefore('-').toIntOrNull() ?: 1970
            val month = yearMonth.substringAfter('-').toIntOrNull() ?: 1
            return java.time.LocalDate.of(year, month, 1)
                .atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        }
}

/** A page of assets plus the cursor to fetch the next one. */
data class AssetPage(
    val assets: List<PickerAsset>,
    val nextCursor: String?,
) {
    val hasMore: Boolean get() = nextCursor != null
}

/** Result of the "test connection" / login validation step. */
data class ConnectionInfo(
    val serverUrl: String,
    val serverVersion: String,
    val userEmail: String?,
    val userName: String?,
    val versionAssessment: VersionAssessment,
)
