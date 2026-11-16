package dev.immich.picker.util

/**
 * MIME rules for the picker, kept free of Android types so they are unit-testable.
 *
 * The picker is registered for *every* file request, not only media ones, because a
 * generic "attach a file" button in chat apps asks for a document type and would
 * otherwise never offer Immich as a source. An Immich library only holds photos and
 * videos, so the same rules decide two things: which tiles may be picked, and whether
 * the request is one the library can satisfy at all (used to warn instead of showing a
 * grid where every tile is disabled).
 */
object MimeFilters {

    /** The universal MIME wildcard, assembled so it never closes a block comment. */
    const val ANY_TYPE: String = "*" + "/" + "*"

    /**
     * Normalises what a calling intent asked for.
     *
     * `EXTRA_MIME_TYPES` wins when present: apps that send it set the intent type to
     * the universal wildcard and put the real list in the extra. An empty result means
     * "no restriction".
     */
    fun parse(type: String?, extraTypes: Array<String>?): List<String> {
        val extra = extraTypes.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (extra.isNotEmpty()) return extra

        val single = type?.trim().orEmpty()
        if (single.isEmpty() || single == ANY_TYPE) return emptyList()
        return listOf(single)
    }

    /** True when an asset of [mimeType] may be picked under [filters]. */
    fun matches(filters: List<String>, mimeType: String): Boolean {
        if (filters.isEmpty()) return true
        val mime = mimeType.lowercase()
        val family = mime.substringBefore('/')
        return filters.any { raw ->
            val filter = raw.lowercase()
            filter == ANY_TYPE ||
                filter == mime ||
                (filter.endsWith("/*") && filter.substringBefore('/') == family)
        }
    }

    /**
     * True when at least one requested type can be served by a photo or a video.
     *
     * A request for a document type can never be satisfied from an Immich library, so
     * the picker says so rather than presenting an unselectable grid.
     */
    fun allowsMedia(filters: List<String>): Boolean {
        if (filters.isEmpty()) return true
        return filters.any { raw ->
            val filter = raw.lowercase()
            filter == ANY_TYPE || filter.startsWith("image/") || filter.startsWith("video/")
        }
    }

    /** Human-readable summary of what the caller asked for. */
    fun describe(filters: List<String>): String =
        if (filters.isEmpty()) "any file" else filters.joinToString(", ")
}
