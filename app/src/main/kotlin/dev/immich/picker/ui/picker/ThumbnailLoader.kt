package dev.immich.picker.ui.picker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.immich.picker.cache.ThumbnailSize
import dev.immich.picker.data.ImmichService
import dev.immich.picker.data.PickerAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Thumbnail loading for the in-app picker grid.
 *
 * Deliberately not Coil: the only images this app ever shows are Immich thumbnails
 * that are already cached on disk by [dev.immich.picker.cache.ThumbnailCache], so a
 * memory LRU over that same cache is enough and avoids a dependency.
 *
 * Two-level design:
 *  - [memory] holds decoded bitmaps, sized to an eighth of the heap
 *  - disk comes from ThumbnailCache, which the DocumentsProvider also populates,
 *    so scrolling the grid in the provider and in this activity share one cache
 */
object ThumbnailLoader {

    private val memory = object : LruCache<String, ImageBitmap>(maxMemoryEntries()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * 4
    }

    private fun maxMemoryEntries(): Int {
        val heapBytes = Runtime.getRuntime().maxMemory()
        // Quarter of the heap is a conventional budget for a photo grid; the
        // cache is measured in bytes via sizeOf above, so this is a real cap.
        return (heapBytes / 4).toInt().coerceAtLeast(8 * 1024 * 1024)
    }

    private fun memoryKey(assetId: String, size: ThumbnailSize, thumbhash: String?) =
        "$assetId|${size.wireName}|${thumbhash ?: "nohash"}"

    fun cached(asset: PickerAsset, size: ThumbnailSize): ImageBitmap? =
        memory.get(memoryKey(asset.id, size, asset.thumbhash))

    fun putMemory(asset: PickerAsset, size: ThumbnailSize, bitmap: ImageBitmap) {
        memory.put(memoryKey(asset.id, size, asset.thumbhash), bitmap)
    }

    /**
     * Returns a thumbnail for [asset], fetching it if necessary.
     * Returns null when the asset cannot be loaded (offline, deleted, cancelled).
     */
    suspend fun load(asset: PickerAsset, size: ThumbnailSize): ImageBitmap? {
        cached(asset, size)?.let { return it }

        val session = ImmichService.session() ?: return null
        val diskFile: File = withContext(Dispatchers.IO) {
            ImmichService.thumbnails.get(asset.id, size, asset.thumbhash)
                ?: downloadToDisk(asset, size, session)
        } ?: return null

        val bitmap = withContext(Dispatchers.IO) { decode(diskFile) } ?: return null
        val image = bitmap.asImageBitmap()
        putMemory(asset, size, image)
        return image
    }

    private suspend fun downloadToDisk(
        asset: PickerAsset,
        size: ThumbnailSize,
        session: ImmichService.Session,
    ): File? {
        val temp = File(ImmichService.thumbnailsDirTemp, "${asset.id}-${size.wireName}.part")
        temp.parentFile?.mkdirs()
        val ok = runCatching {
            session.repository.downloadThumbnail(asset.id, size, temp)
        }.isSuccess
        if (!ok) {
            temp.delete()
            return null
        }
        val committed = ImmichService.thumbnails.put(asset.id, size, asset.thumbhash, temp)
        temp.delete()
        return committed
    }

    private fun decode(file: File): Bitmap? {
        // Two-pass decode: bounds first so a large preview does not blow the heap
        // when the grid only needs a tile-sized bitmap.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX &&
            bounds.outHeight / (sample * 2) >= TARGET_PX
        ) {
            sample *= 2
        }

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        )
    }

    fun clear() {
        memory.evictAll()
    }

    /** Tile render size; 2x the typical 3-column cell on a 1080p phone. */
    private const val TARGET_PX = 360
}
