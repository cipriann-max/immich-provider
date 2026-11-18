package dev.immich.picker.util

/**
 * Prefixes a listing position onto a display name so that DocumentsUI's fixed
 * "File name (A to Z)" sort reproduces our timeline order.
 *
 * ## Why this exists
 *
 * DocumentsUI applies its own sort to whatever a provider returns and defaults to name
 * A–Z; the order of rows in the cursor is ignored. An Immich library therefore rendered
 * oldest-first whenever filenames start with a date, and no provider-side ordering could
 * change that.
 *
 * The one lever a provider has is the name itself, because that is the key DocumentsUI
 * sorts on. Numbering the rows `0001`, `0002`, … makes A–Z identical to "newest first",
 * so the default view is correct with no tap and nothing to configure.
 *
 * ## Scope: display only
 *
 * This is applied **only** by `queryChildDocuments`, which is what fills the grid. The
 * name returned for a single document, which is what a receiving app reads when it opens a
 * picked file, is left untouched, so the attached file keeps its real name. The bytes
 * were never affected either way.
 */
object RankedNames {

    /** Minimum digits, so `1` renders as `0001` and the order reads naturally. */
    const val MIN_WIDTH: Int = 4

    /**
     * Digits needed to number [count] rows without the width changing mid-list.
     *
     * Wider lists get wider prefixes so that `10000` does not sort before `9999`, but the
     * width is fixed for a whole render so every name in one listing shares it.
     */
    fun widthFor(count: Int): Int = maxOf(MIN_WIDTH, count.toString().length)

    /**
     * Builds the display name for [position] (1-based) out of [count] rows.
     *
     * The separator is `_` rather than a space: it keeps the result a single token that
     * survives the filename handling of the apps that eventually receive it.
     */
    fun apply(position: Int, count: Int, displayName: String): String {
        require(position >= 1) { "position is 1-based, was $position" }
        val rank = position.toString().padStart(widthFor(count), '0')
        return "${rank}_$displayName"
    }
}
