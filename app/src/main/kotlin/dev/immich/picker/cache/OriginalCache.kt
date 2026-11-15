package dev.immich.picker.cache

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cache for fully downloaded originals, which `openDocument()` must hand out as a
 * `ParcelFileDescriptor`.
 *
 * Two independent bounds, matching the requirements:
 *  - a hard size cap (500 MB), enforced LRU-style
 *  - a TTL (30 minutes) after which a download is considered stale and deleted,
 *    so a picked video does not linger on disk forever
 *
 * `ParcelFileDescriptor` closes do not tell us when the consumer is done, so a TTL
 * is the only reliable cleanup signal. Files still open by the receiver are
 * unlinked safely: on Linux the inode survives until the last fd closes.
 */
class OriginalCache(
    rootDir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val ttlMillis: Long = DEFAULT_TTL_MS,
) {
    private val dir = File(rootDir, "originals")

    fun fileFor(assetId: String, displayName: String): File {
        // Keep the extension: Chrome and other receivers infer type from the name.
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
        return File(dir, "${assetId}_$safeName")
    }

    fun get(assetId: String, displayName: String): File? {
        val file = fileFor(assetId, displayName)
        if (!file.isFile || file.length() == 0L) return null
        if (isExpired(file)) {
            file.delete()
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    /** Records a completed download and enforces the budget. */
    fun commit(assetId: String, displayName: String): File {
        val file = fileFor(assetId, displayName)
        file.setLastModified(System.currentTimeMillis())
        trim()
        return file
    }

    private fun isExpired(file: File): Boolean =
        System.currentTimeMillis() - file.lastModified() > ttlMillis

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun entryCount(): Int = dir.listFiles()?.size ?: 0

    fun trim() {
        val files = dir.listFiles() ?: return

        // 1. Drop expired entries unconditionally.
        val now = System.currentTimeMillis()
        var remaining = files.filterNot { now - it.lastModified() > ttlMillis && it.delete() }

        // 2. Enforce the size cap, oldest first.
        var total = remaining.sumOf { it.length() }
        if (total > maxBytes) {
            val target = (maxBytes * 0.8).toLong()
            for (file in remaining.sortedBy { it.lastModified() }) {
                if (total <= target) break
                val length = file.length()
                if (file.delete()) total -= length
            }
            Log.d(TAG, "trimmed original cache to ${total / 1024} KiB")
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    companion object {
        private const val TAG = "OriginalCache"
        const val DEFAULT_MAX_BYTES = 500L * 1024 * 1024
        val DEFAULT_TTL_MS = TimeUnit.MINUTES.toMillis(30)
    }
}
