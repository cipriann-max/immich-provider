package dev.immich.picker.api

import kotlinx.serialization.json.Json

/**
 * Single Json instance used for every Immich payload.
 *
 * `ignoreUnknownKeys` is what makes the generated models forward compatible: a
 * newer Immich server that adds fields will not break parsing, which is essential
 * for the "keep working on a newer server" behaviour described in the README.
 *
 * `explicitNulls = false` keeps request bodies small and avoids sending explicit
 * nulls for optional filters, which the Immich validation layer treats as absent.
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = false
    isLenient = false
}
