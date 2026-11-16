package dev.immich.picker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeFiltersTest {

    // ------------------------------------------------------------------- parse

    @Test
    fun `null type and no extras means no restriction`() {
        assertTrue(MimeFilters.parse(null, null).isEmpty())
    }

    @Test
    fun `blank type means no restriction`() {
        assertTrue(MimeFilters.parse("  ", arrayOf("", "  ")).isEmpty())
    }

    @Test
    fun `universal wildcard means no restriction`() {
        assertTrue(MimeFilters.parse(MimeFilters.ANY_TYPE, null).isEmpty())
    }

    @Test
    fun `single concrete type is kept`() {
        assertEquals(listOf("image/*"), MimeFilters.parse("image/*", null))
    }

    @Test
    fun `extra types override a wildcard intent type`() {
        // A document picker sends type */* together with the real list in the extra.
        val parsed = MimeFilters.parse(
            MimeFilters.ANY_TYPE,
            arrayOf("image/*", "application/pdf"),
        )
        assertEquals(listOf("image/*", "application/pdf"), parsed)
    }

    @Test
    fun `extra types are trimmed and de-duplicated`() {
        val parsed = MimeFilters.parse(null, arrayOf(" image/* ", "image/*", "video/mp4"))
        assertEquals(listOf("image/*", "video/mp4"), parsed)
    }

    // ----------------------------------------------------------------- matches

    @Test
    fun `empty filter accepts every asset`() {
        assertTrue(MimeFilters.matches(emptyList(), "application/pdf"))
        assertTrue(MimeFilters.matches(emptyList(), "image/jpeg"))
    }

    @Test
    fun `family wildcard accepts members of that family only`() {
        val filters = listOf("image/*")
        assertTrue(MimeFilters.matches(filters, "image/jpeg"))
        assertTrue(MimeFilters.matches(filters, "image/heic"))
        assertFalse(MimeFilters.matches(filters, "video/mp4"))
    }

    @Test
    fun `concrete filter needs an exact type`() {
        val filters = listOf("video/mp4")
        assertTrue(MimeFilters.matches(filters, "video/mp4"))
        assertFalse(MimeFilters.matches(filters, "video/webm"))
    }

    @Test
    fun `universal wildcard accepts every asset`() {
        assertTrue(MimeFilters.matches(listOf(MimeFilters.ANY_TYPE), "video/quicktime"))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(MimeFilters.matches(listOf("IMAGE/*"), "image/png"))
    }

    @Test
    fun `any matching filter is enough`() {
        val filters = listOf("application/pdf", "image/*")
        assertTrue(MimeFilters.matches(filters, "image/jpeg"))
    }

    // -------------------------------------------------------------- allowsMedia

    @Test
    fun `no restriction allows media`() {
        assertTrue(MimeFilters.allowsMedia(emptyList()))
    }

    @Test
    fun `image and video filters allow media`() {
        assertTrue(MimeFilters.allowsMedia(listOf("image/*")))
        assertTrue(MimeFilters.allowsMedia(listOf("video/mp4")))
    }

    @Test
    fun `mixed request still allows media`() {
        assertTrue(MimeFilters.allowsMedia(listOf("application/pdf", "image/*")))
    }

    @Test
    fun `document-only request cannot be served`() {
        assertFalse(MimeFilters.allowsMedia(listOf("application/pdf")))
        assertFalse(MimeFilters.allowsMedia(listOf("text/plain", "application/zip")))
    }

    // ---------------------------------------------------------------- describe

    @Test
    fun `describe names the request`() {
        assertEquals("any file", MimeFilters.describe(emptyList()))
        assertEquals("application/pdf", MimeFilters.describe(listOf("application/pdf")))
    }
}
