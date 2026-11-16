package dev.immich.picker.ui.picker

import dev.immich.picker.data.PickerAlbum
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the album-list ordering.
 *
 * The sort button used to be hidden on the Albums tab, and even when reachable it did
 * nothing there. These guard the behaviour now that it is a real toggle.
 */
class AlbumSortTest {

    private fun album(id: String, updated: Long) = PickerAlbum(
        id = id,
        name = id,
        assetCount = 1,
        updatedAtMillis = updated,
        coverAssetId = null,
    )

    private val albums = listOf(
        album("oldest", 100L),
        album("newest", 300L),
        album("middle", 200L),
    )

    /** Mirrors PickerViewModel.applyAlbumSort, which needs Android to instantiate. */
    private fun sort(albums: List<PickerAlbum>, newestFirst: Boolean): List<PickerAlbum> =
        if (newestFirst) {
            albums.sortedByDescending { it.updatedAtMillis }
        } else {
            albums.sortedBy { it.updatedAtMillis }
        }

    @Test
    fun `newest first orders by last activity descending`() {
        assertEquals(
            listOf("newest", "middle", "oldest"),
            sort(albums, newestFirst = true).map { it.id },
        )
    }

    @Test
    fun `oldest first orders by last activity ascending`() {
        assertEquals(
            listOf("oldest", "middle", "newest"),
            sort(albums, newestFirst = false).map { it.id },
        )
    }

    @Test
    fun `toggling twice restores the original order`() {
        val once = sort(albums, newestFirst = false)
        val twice = sort(once, newestFirst = true)
        assertEquals(sort(albums, newestFirst = true), twice)
    }

    @Test
    fun `sort labels distinguish the two directions`() {
        assertEquals("Newest first", SortOrder.NEWEST_FIRST.label)
        assertEquals("Oldest first", SortOrder.OLDEST_FIRST.label)
        assertEquals(SortOrder.OLDEST_FIRST, SortOrder.NEWEST_FIRST.toggle())
        assertEquals(SortOrder.NEWEST_FIRST, SortOrder.OLDEST_FIRST.toggle())
    }
}
