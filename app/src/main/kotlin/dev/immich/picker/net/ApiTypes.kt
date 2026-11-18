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

    /**
     * Server answered 401: the token or API key is unknown, deleted or expired.
     *
     * This is the only status that means "sign in again". Immich deletes a session
     * after 90 days without use, so a stored token really can become invalid.
     */
    class Unauthorized(message: String) : ApiException(message)

    /**
     * Server answered 403: the credential is valid but lacks a permission.
     *
     * Deliberately *not* an [Unauthorized]. Conflating the two made an API key that
     * was missing one permission look like an expired session, which cannot be fixed
     * by signing in again. It needs the key's permissions changed.
     */
    class Forbidden(val permission: String?) : ApiException(
        permission?.let { "This credential is missing the Immich permission: $it" }
            ?: "Immich refused the request: not permitted",
    )

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

/**
 * Turns an HTTP status into the right [ApiException], kept out of the transport so the
 * distinction between "sign in again" and "this credential lacks a permission" is
 * unit-testable rather than only observable against a live server.
 */
object ApiFailures {

    /** Matches an Immich permission string such as `asset.view` inside a message. */
    private val PERMISSION = Regex("""\b([a-z][A-Za-z]*\.[a-z][A-Za-z]*)\b""")

    fun forStatus(
        method: String,
        path: String,
        code: Int,
        serverMessage: String?,
    ): ApiException = when (code) {
        // 401: the token or key is unknown, revoked or expired. Signing in fixes it.
        401 -> ApiException.Unauthorized(
            serverMessage ?: "Not authorised (HTTP 401)",
        )
        // 403: the credential is fine, the permission is not. Signing in does not fix it.
        403 -> ApiException.Forbidden(permissionFrom(serverMessage))
        else -> ApiException.Http(code, "$method $path", serverMessage)
    }

    /**
     * Immich answers a permission failure with `Missing required permission: asset.view`,
     * so the missing permission can be named instead of guessed at.
     */
    fun permissionFrom(serverMessage: String?): String? {
        val message = serverMessage ?: return null
        if (!message.contains("permission", ignoreCase = true)) return null
        return PERMISSION.find(message)?.groupValues?.get(1)
    }
}
