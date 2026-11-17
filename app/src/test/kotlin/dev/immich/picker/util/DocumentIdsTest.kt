package dev.immich.picker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentIdsTest {

    // ----------------------------------------------------------------- forAsset

    @Test
    fun `asset ids are prefixed so they cannot collide with a folder`() {
        val id = DocumentIds.forAsset("0f3c1b2a")
        assertEquals("asset:0f3c1b2a", id)
        assertTrue(DocumentIds.isAsset(id))
        assertFalse(DocumentIds.isDirectory(id))
        assertFalse(DocumentIds.listsAssets(id))
    }

    @Test
    fun `round trip through forAsset and assetIdOf preserves the asset id`() {
        val assetId = "0f3c1b2a-77aa-4c2b-9f0e-1a2b3c4d5e6f"
        assertEquals(assetId, DocumentIds.assetIdOf(DocumentIds.forAsset(assetId)))
    }

    @Test
    fun `an empty asset suffix is rejected`() {
        // `asset:` alone would otherwise become a network lookup for an empty id.
        assertNull(DocumentIds.assetIdOf("asset:"))
    }

    @Test
    fun `an unrelated document id is rejected`() {
        assertNull(DocumentIds.assetIdOf("some/other/document"))
        assertNull(DocumentIds.assetIdOf(""))
    }

    @Test
    fun `the prefix is only stripped once`() {
        assertEquals("asset:inner", DocumentIds.assetIdOf("asset:asset:inner"))
    }

    @Test
    fun `asset ids containing colons survive parsing`() {
        assertEquals("a:b:c", DocumentIds.assetIdOf("asset:a:b:c"))
    }

    // ----------------------------------------------------------------- forAlbum

    @Test
    fun `round trip through forAlbum and albumIdOf preserves the album id`() {
        val albumId = "aa11bb22-cc33-dd44-ee55-ff6677889900"
        assertEquals(albumId, DocumentIds.albumIdOf(DocumentIds.forAlbum(albumId)))
    }

    @Test
    fun `album ids are directories that list assets`() {
        val id = DocumentIds.forAlbum("album-1")
        assertTrue(DocumentIds.isAlbum(id))
        assertTrue(DocumentIds.isDirectory(id))
        assertTrue(DocumentIds.listsAssets(id))
        assertFalse(DocumentIds.isAsset(id))
    }

    @Test
    fun `an empty album suffix is rejected`() {
        assertNull(DocumentIds.albumIdOf("album:"))
    }

    @Test
    fun `an asset is not mistaken for an album`() {
        val id = DocumentIds.forAsset("abc")
        assertNull(DocumentIds.albumIdOf(id))
        assertFalse(DocumentIds.isAlbum(id))
    }

    // ------------------------------------------------------------- fixed folders

    @Test
    fun `the root lists assets because the timeline lives there`() {
        assertTrue(DocumentIds.isRoot(DocumentIds.ROOT_DOCUMENT_ID))
        assertTrue(DocumentIds.isDirectory(DocumentIds.ROOT_DOCUMENT_ID))
        assertTrue(DocumentIds.listsAssets(DocumentIds.ROOT_DOCUMENT_ID))
    }

    @Test
    fun `the albums folder lists folders rather than assets`() {
        assertTrue(DocumentIds.isAlbums(DocumentIds.ALBUMS_DOCUMENT_ID))
        assertTrue(DocumentIds.isDirectory(DocumentIds.ALBUMS_DOCUMENT_ID))
        assertFalse(DocumentIds.listsAssets(DocumentIds.ALBUMS_DOCUMENT_ID))
    }

    @Test
    fun `the favourites folder lists assets`() {
        assertTrue(DocumentIds.isFavorites(DocumentIds.FAVORITES_DOCUMENT_ID))
        assertTrue(DocumentIds.isDirectory(DocumentIds.FAVORITES_DOCUMENT_ID))
        assertTrue(DocumentIds.listsAssets(DocumentIds.FAVORITES_DOCUMENT_ID))
        assertFalse(DocumentIds.isAsset(DocumentIds.FAVORITES_DOCUMENT_ID))
    }

    @Test
    fun `the fixed folders are distinguishable`() {
        assertFalse(DocumentIds.isRoot(DocumentIds.ALBUMS_DOCUMENT_ID))
        assertFalse(DocumentIds.isAlbums(DocumentIds.ROOT_DOCUMENT_ID))
        assertFalse(DocumentIds.isFavorites(DocumentIds.ALBUMS_DOCUMENT_ID))
        assertFalse(DocumentIds.isAlbums(DocumentIds.FAVORITES_DOCUMENT_ID))
        assertFalse(DocumentIds.isRoot(DocumentIds.FAVORITES_DOCUMENT_ID))
    }

    @Test
    fun `the fixed folder ids are distinct strings`() {
        // A collision would make the timeline render the album list, or vice versa.
        val ids = setOf(
            DocumentIds.ROOT_DOCUMENT_ID,
            DocumentIds.ALBUMS_DOCUMENT_ID,
            DocumentIds.FAVORITES_DOCUMENT_ID,
        )
        assertEquals(3, ids.size)
    }

    @Test
    fun `folder ids are not confused with asset or album documents`() {
        listOf(
            DocumentIds.ROOT_DOCUMENT_ID,
            DocumentIds.ALBUMS_DOCUMENT_ID,
            DocumentIds.FAVORITES_DOCUMENT_ID,
        ).forEach { id ->
            assertFalse("$id is not an asset", DocumentIds.isAsset(id))
            // "albums" and "favorites" (no prefix) must not parse as album documents.
            assertNull(DocumentIds.albumIdOf(id))
        }
    }
}
