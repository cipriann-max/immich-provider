package dev.immich.picker.net

/** A single Immich HTTP call, as emitted by the generator. */
data class ApiRequest(
    val method: String,
    val path: String,
    val query: Map<String, String?> = emptyMap(),
    /** True for endpoints that stream bytes rather than JSON. */
    val binary: Boolean = false,
)

/**
 * Every failure mode of the API layer, deliberately explicit so that the UI and
 * the DocumentsProvider can tell "wrong password" apart from "server is a newer
 * major version that does not speak this schema any more".
 */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Server answered 401/403. The token or API key is no longer valid. */
    class Unauthorized(message: String) : ApiException(message)

    /** Server answered with a non-2xx status that is not auth related. */
    class Http(
        val status: Int,
        val endpoint: String,
        val serverMessage: String?,
    ) : ApiException("HTTP $status on $endpoint${serverMessage?.let { ": $it" } ?: ""}")

    /** Could not reach the server at all (DNS, timeout, refused, TLS). */
    class Network(message: String, cause: Throwable?) : ApiException(message, cause)

    /**
     * The server answered, but the payload did not match the generated models.
     * Almost always means the server's major version is newer than the version
     * this build was generated from. Never swallowed silently.
     */
    class IncompatibleSchema(
        val endpoint: String,
        val serverVersion: String?,
        val detail: String,
        val payloadSnippet: String,
    ) : ApiException(
        "Unexpected response schema from $endpoint (server ${serverVersion ?: "unknown"}): $detail",
    )

    /** The call was cancelled by the caller (DocumentsUI closing the picker). */
    class Cancelled : ApiException("Cancelled")
}
