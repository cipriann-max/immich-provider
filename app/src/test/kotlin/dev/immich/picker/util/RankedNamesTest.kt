package dev.immich.picker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankedNamesTest {

    // -------------------------------------------------------------------- apply

    @Test
    fun `the first row is ranked 0001`() {
        assertEquals(
            "0001_IMG_20261117_154600.jpg",
            RankedNames.apply(position = 1, count = 50, displayName = "IMG_20261117_154600.jpg"),
        )
    }

    @Test
    fun `ranks are zero padded to the minimum width`() {
        assertEquals("0002_a.jpg", RankedNames.apply(2, 50, "a.jpg"))
        assertEquals("0050_a.jpg", RankedNames.apply(50, 50, "a.jpg"))
    }

    @Test
    fun `the real name is preserved after the rank`() {
        val original = "IMG-20260919-WA0010.jpg"
        assertTrue(RankedNames.apply(7, 50, original).endsWith("_$original"))
    }

    // -------------------------------------------------------------------- width

    @Test
    fun `width grows so that 10000 does not sort before 9999`() {
        // Five-digit positions need five digits, otherwise "10000" sorts before "9999"
        // lexicographically and the last page jumps to the top of the list.
        assertEquals(5, RankedNames.widthFor(10_000))
        assertEquals("09999_a.jpg", RankedNames.apply(9_999, 10_000, "a.jpg"))
        assertEquals("10000_a.jpg", RankedNames.apply(10_000, 10_000, "a.jpg"))
    }

    @Test
    fun `small listings keep the natural four digit width`() {
        assertEquals(4, RankedNames.widthFor(50))
        assertEquals(4, RankedNames.widthFor(9_999))
    }

    @Test
    fun `a position below one is rejected`() {
        // Silently accepting 0 would produce "0000_", colliding with nothing but making
        // the off-by-one invisible.
        try {
            RankedNames.apply(position = 0, count = 10, displayName = "a.jpg")
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // pass
        }
    }

    // --------------------------------------------------------------- ordering

    @Test
    fun `sorting the ranked names reproduces timeline order`() {
        // The whole point: lexicographic order of the produced names must equal the order
        // the assets were supplied in, otherwise DocumentsUI's A-Z sort would reorder them.
        val originals = listOf(
            "IMG-20261117-WA0010.jpg",
            "IMG-20261117-WA0009.jpg",
            "IMG-20261028-WA0002.jpg",
            "IMG-20261027-WA0001.jpg",
            "IMG-20260919-WA0001.jpg",
        )

        val ranked = originals.mapIndexed { index, name ->
            RankedNames.apply(index + 1, originals.size, name)
        }

        assertEquals(ranked, ranked.sorted())
        assertTrue(ranked.first().startsWith("0001_"))
        assertTrue(ranked.last().startsWith("0005_"))
    }

    @Test
    fun `ranking is stable when the original names sort against the timeline`() {
        // Original names that sort ascending in the *opposite* direction must still come
        // out in timeline order, because the rank is compared before the name.
        val newest = "AAA_first_alphabetically.jpg"
        val oldest = "ZZZ_last_alphabetically.jpg"

        val ranked = listOf(newest, oldest).mapIndexed { index, name ->
            RankedNames.apply(index + 1, 2, name)
        }

        assertEquals(ranked, ranked.sorted())
    }
}
