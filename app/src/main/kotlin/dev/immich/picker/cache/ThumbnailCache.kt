package dev.immich.picker.cache

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Disk cache for Immich thumbnails with an LRU size cap.
 *
 * Layout: one flat directory, file name `<assetId>_<size>_<hash>.jpg`. The hash
 * comes from Immich's `thumbhash`, so an edited asset invalidates itself instead
 * of serving a stale thumbnail.
 *
 * Eviction is size-based (oldest `lastModified` first) rather than entry-count
 * based, matching the 200 MB budget in the requirements. `touch()` on a hit keeps
 * LRU ordering honest without rewriting file contents.
 */
class ThumbnailCache(
    rootDir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val dir = File(rootDir, "thumbnails")

    /** In-flight downloads keyed by cache file, so a grid of 60 tiles makes 60 calls. */
    private val inFlight = ConcurrentHashMap<String, Any>()

    fun fileFor(assetId: String, size: ThumbnailSize, thumbhash: String?): File {
        val hash = thumbhash?.take(12) ?: "nohash"
        return File(dir, "${assetId}_${size.wireName}_$hash.img")
    }

    fun get(assetId: String, size: ThumbnailSize, thumbhash: String?): File? {
        val file = fileFor(assetId, size, thumbhash)
        if (!file.isFile || file.length() == 0L) return null
        // Refresh mtime so eviction treats this as recently used.
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    fun put(assetId: String, size: ThumbnailSize, thumbhash: String?, source: File): File? {
        val target = fileFor(assetId, size, thumbhash)
        return runCatching {
            target.parentFile?.mkdirs()
            if (source.absolutePath != target.absolutePath) {
                source.copyTo(target, overwrite = true)
            }
            target.takeIf { it.length() > 0 }
        }.onFailure { Log.w(TAG, "failed to cache thumbnail for $assetId", it) }
            .getOrNull()
    }

    /** Guards against duplicate concurrent downloads of the same thumbnail. */
    fun <T> withLock(key: String, block: () -> T): T {
        val lock = inFlight.computeIfAbsent(key) { Any() }
        return synchronized(lock) { block() }
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun entryCount(): Int = dir.listFiles()?.size ?: 0

    /** Evicts least-recently-used entries until the cache is under [maxBytes]. */
    fun trim() {
        val files = dir.listFiles() ?: return
        val total = files.sumOf { it.length() }
        if (total <= maxBytes) return

        var current = total
        val target = (maxBytes * 0.8).toLong() // trim to 80% to avoid thrashing
        val oldestFirst = files.sortedBy { it.lastModified() }
        for (file in oldestFirst) {
            if (current <= target) break
            val length = file.length()
            if (file.delete()) current -= length
        }
        Log.d(TAG, "trimmed thumbnail cache: ${total / 1024} KiB -> ${current / 1024} KiB")
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    companion object {
        private const val TAG = "ThumbnailCache"
        const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024
    }
}

/**
 * Immich serves `thumbnail` (about 250 px) and `preview` (about 1080 px). The
 * picker asks for one or the other via `sizeHint`, so both are modelled.
 */
enum class ThumbnailSize(val wireName: String) {
    THUMBNAIL("thumbnail"),
    PREVIEW("preview"),
    ;

    companion object {
        /** DocumentsUI typically requests 96–256 px tiles. */
        fun forHint(widthPx: Int, heightPx: Int): ThumbnailSize =
            if (maxOf(widthPx, heightPx) <= 320) THUMBNAIL else PREVIEW
    }
}
