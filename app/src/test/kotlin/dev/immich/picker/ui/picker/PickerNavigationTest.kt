package dev.immich.picker.ui.picker

import dev.immich.picker.data.PickerAlbum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the back-button rule.
 *
 * The bug these guard against: leaving the album list used to navigate to Timeline instead
 * of exiting, so escaping the album tab took two presses and landed the user on a tab they
 * had not been on.
 */
class PickerNavigationTest {

    private val album = PickerAlbum(
        id = "a1",
        name = "Holiday",
        assetCount = 3,
        updatedAtMillis = 0L,
        coverAssetId = null,
    )

    // ------------------------------------------------------------- inside an album

    @Test
    fun `back from an album goes to the album list`() {
        assertEquals(
            PickerLocation.Albums,
            PickerNavigation.parentOf(PickerLocation.Album(album)),
        )
    }

    @Test
    fun `an album has a level up`() {
        assertTrue(PickerNavigation.canGoUp(PickerLocation.Album(album)))
    }

    // ---------------------------------------------------------------- top level

    @Test
    fun `back from the album list exits the picker`() {
        // The regression: this previously returned Recents, which is not a parent.
        assertNull(PickerNavigation.parentOf(PickerLocation.Albums))
    }

    @Test
    fun `back from timeline exits the picker`() {
        assertNull(PickerNavigation.parentOf(PickerLocation.Recents))
    }

    @Test
    fun `back from favourites exits the picker`() {
        assertNull(PickerNavigation.parentOf(PickerLocation.Favorites))
    }

    @Test
    fun `timeline and album list are siblings not parent and child`() {
        // Neither is an ancestor of the other, so back must not move between them.
        assertNull(PickerNavigation.parentOf(PickerLocation.Recents))
        assertNull(PickerNavigation.parentOf(PickerLocation.Albums))
    }

    // ------------------------------------------------------------- icon agreement

    @Test
    fun `the up affordance agrees with the back rule`() {
        val locations = listOf(
            PickerLocation.Recents,
            PickerLocation.Albums,
            PickerLocation.Favorites,
            PickerLocation.Album(album),
        )
        locations.forEach { location ->
            assertEquals(
                "canGoUp must be true exactly when a parent exists for $location",
                PickerNavigation.parentOf(location) != null,
                PickerNavigation.canGoUp(location),
            )
        }
    }

    @Test
    fun `only an album shows the up affordance`() {
        assertFalse(PickerNavigation.canGoUp(PickerLocation.Recents))
        assertFalse(PickerNavigation.canGoUp(PickerLocation.Albums))
        assertFalse(PickerNavigation.canGoUp(PickerLocation.Favorites))
        assertTrue(PickerNavigation.canGoUp(PickerLocation.Album(album)))
    }
}
