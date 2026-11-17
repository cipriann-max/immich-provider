package dev.immich.picker.util

/**
 * The document-id scheme shared by the picker and the DocumentsProvider.
 *
 * Kept free of Android types so the mapping can be unit-tested: a mismatch between the id
 * the provider returns and the id the picker builds would produce a URI that resolves to
 * nothing, and the receiving app would fail with a bare `FileNotFoundException`.
 *
 * The tree the SAF root exposes is deliberately shallow:
 *
 * ```
 * root                  the 50 newest assets, with more loaded as you scroll
 * ├── albums            one folder per album
 * │   └── album:<id>    that album's assets, paged the same way
 * └── favorites         assets marked as favourite, paged the same way
 * ```
 *
 * The timeline lives *in* the root rather than in a `timeline` subfolder. That saves a tap
 * for the common case — attaching a recent photo — and keeps the folder list to the one
 * thing that genuinely needs navigating. Assets are addressed as `asset:<assetId>`
 * wherever they appear, so a document id is stable regardless of the folder it was
 * reached through.
 */
object DocumentIds {

    /** Document id of the root directory, which also lists the timeline. */
    const val ROOT_DOCUMENT_ID: String = "root"

    /** Folder holding one directory per album. */
    const val ALBUMS_DOCUMENT_ID: String = "albums"

    /** Folder holding every asset marked as a favourite. */
    const val FAVORITES_DOCUMENT_ID: String = "favorites"

    /** Prefix that marks a document id as an album directory. */
    const val ALBUM_PREFIX: String = "album:"

    /** Prefix that marks a document id as an Immich asset. */
    const val ASSET_PREFIX: String = "asset:"

    /** Builds the document id for an Immich asset. */
    fun forAsset(assetId: String): String = ASSET_PREFIX + assetId

    /** Builds the document id for an album directory. */
    fun forAlbum(albumId: String): String = ALBUM_PREFIX + albumId

    /**
     * Extracts the asset id from a document id, or null when it is not an asset document.
     *
     * An empty suffix is rejected: `asset:` alone is not a valid asset and treating it as
     * one would produce a pointless network lookup for every malformed request.
     */
    fun assetIdOf(documentId: String): String? {
        if (!documentId.startsWith(ASSET_PREFIX)) return null
        return documentId.removePrefix(ASSET_PREFIX).takeIf { it.isNotEmpty() }
    }

    /** Extracts the album id from an album document id, or null when it is not one. */
    fun albumIdOf(documentId: String): String? {
        if (!documentId.startsWith(ALBUM_PREFIX)) return null
        return documentId.removePrefix(ALBUM_PREFIX).takeIf { it.isNotEmpty() }
    }

    /** True when [documentId] names the root directory (which also holds the timeline). */
    fun isRoot(documentId: String): Boolean = documentId == ROOT_DOCUMENT_ID

    /** True when [documentId] names the albums folder. */
    fun isAlbums(documentId: String): Boolean = documentId == ALBUMS_DOCUMENT_ID

    /** True when [documentId] names the favourites folder. */
    fun isFavorites(documentId: String): Boolean = documentId == FAVORITES_DOCUMENT_ID

    /** True when [documentId] names an album directory. */
    fun isAlbum(documentId: String): Boolean = albumIdOf(documentId) != null

    /** True when [documentId] names a single asset. */
    fun isAsset(documentId: String): Boolean = assetIdOf(documentId) != null

    /**
     * True when [documentId] lists assets.
     *
     * The root does, because the timeline lives there; so do favourites and any album.
     * The albums folder does not — it lists folders.
     */
    fun listsAssets(documentId: String): Boolean =
        isRoot(documentId) || isFavorites(documentId) || isAlbum(documentId)

    /** True for every directory this provider exposes. */
    fun isDirectory(documentId: String): Boolean =
        isRoot(documentId) || isAlbums(documentId) || isFavorites(documentId) ||
            isAlbum(documentId)
}
