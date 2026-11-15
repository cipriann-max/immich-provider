package dev.immich.picker.net

import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import dev.immich.picker.api.ApiJson
import dev.immich.picker.api.ServerVersionResponseDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Transport for the generated [dev.immich.picker.api.ImmichEndpoints].
 *
 * Responsibilities kept here so the generated code stays dumb:
 *  - base URL joining and auth header injection (session token or API key)
 *  - retry with exponential backoff on transient failures only
 *  - mapping HTTP/JSON failures onto [ApiException] subtypes
 *  - streaming downloads that honour [CancellationSignal]
 *  - optional trust of self-signed / private-CA servers
 */
class ImmichApiClient(
    baseUrl: HttpUrl,
    private val authProvider: () -> AuthHeader?,
    serverVersionProvider: () -> String? = { null },
    insecureTls: Boolean = false,
    trustedCaPem: ByteArray? = null,
    /** Overridable for tests (MockWebServer). */
    val httpClient: OkHttpClient? = null,
) {
    private val versionProvider = serverVersionProvider

    val baseUrl: HttpUrl = baseUrl

    private val client: OkHttpClient = httpClient ?: buildClient(insecureTls, trustedCaPem)

    // ------------------------------------------------------------------ public API

    suspend fun <T> execute(
        request: ApiRequest,
        serializer: KSerializer<T>,
        body: String? = null,
    ): T {
        val bytes = executeBytes(request, body)
        return decode(request, bytes, serializer)
    }

    suspend fun executeUnit(
        request: ApiRequest,
        body: String? = null,
    ) {
        executeBytes(request, body)
    }

    /**
     * Streams the response body into [sink].
     *
     * Writes to a temp file and renames on success so an aborted download never
     * leaves a truncated file that later looks like a valid cached asset.
     *
     * @return number of bytes written.
     */
    suspend fun download(
        request: ApiRequest,
        sink: File,
        signal: CancellationSignal? = null,
        onProgress: ((Long) -> Unit)? = null,
    ): Long {
        val temp = File(sink.parentFile, sink.name + ".part")
        val call = client.newCall(buildRequest(request, body = null))
        return call.awaitExecute(cancelSignal = signal, cancelMessage = "Download cancelled")
            .use { response ->
                ensureSuccess(request, response)
                val source = response.body.source()
                var written = 0L
                temp.parentFile?.mkdirs()
                temp.outputStream().use { out ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER)
                    while (true) {
                        signal?.throwIfCanceled()
                        val read = source.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        written += read
                        onProgress?.invoke(written)
                    }
                }
                if (sink.exists() && !sink.delete()) {
                    Log.w(TAG, "could not delete stale cache file ${sink.name}")
                }
                if (!temp.renameTo(sink)) {
                    temp.copyTo(sink, overwrite = true)
                    temp.delete()
                }
                written
            }
    }

    /** Cheap liveness/version probe used before saving credentials. */
    suspend fun probeServerVersion(): ServerVersionResponseDto =
        execute(
            ApiRequest("GET", "/server/version"),
            ServerVersionResponseDto.serializer(),
        )

    // ------------------------------------------------------------- request plumbing

    private suspend fun executeBytes(request: ApiRequest, body: String?): ByteArray {
        var attempt = 0
        var lastError: ApiException.Network? = null
        while (attempt <= MAX_RETRIES) {
            if (attempt > 0) {
                val backoff = BACKOFF_BASE_MS shl (attempt - 1)
                delay(backoff)
            }
            try {
                val call = client.newCall(buildRequest(request, body))
                call.awaitExecute().use { response ->
                    ensureSuccess(request, response)
                    return response.body.bytes()
                }
            } catch (e: ApiException.Network) {
                lastError = e
                if (!isRetryable(e)) break
                Log.w(TAG, "retrying ${request.method} ${request.path} after ${e.message}")
            }
            attempt++
        }
        throw lastError ?: ApiException.Network("Unknown network failure", null)
    }

    private fun isRetryable(error: ApiException.Network): Boolean {
        val cause = error.cause
        if (cause is InterruptedIOException && cause !is java.net.SocketTimeoutException) {
            // Coroutine cancellation, not a network problem.
            return false
        }
        return true
    }

    private fun buildRequest(request: ApiRequest, body: String?): Request {
        val urlBuilder = baseUrl.newBuilder()
            .addPathSegments("api")
            .addEncodedPathSegments(request.path.trimStart('/'))

        for ((key, value) in request.query) {
            // Immich validates typed query params (UUIDs, dates, enums) *before*
            // reading them, so a bare `&userId` (which is what a null value becomes)
            // is rejected with HTTP 400 "Invalid UUID" rather than treated as absent.
            // Nulls must therefore be omitted entirely, not sent empty.
            if (!value.isNullOrEmpty()) urlBuilder.addQueryParameter(key, value)
        }

        val builder = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", if (request.binary) "*/*" else "application/json")

        authProvider()?.let { auth ->
            when (auth) {
                is AuthHeader.Bearer -> builder.header("Authorization", "Bearer ${auth.token}")
                is AuthHeader.ApiKey -> builder.header("x-api-key", auth.key)
            }
        }

        val method = request.method.uppercase()
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "HEAD" -> builder.head()
            else -> builder.method(method, (body ?: "").toRequestBody(JSON_MEDIA_TYPE))
        }
        return builder.build()
    }

    /**
     * Encodes a DTO for a request body. Called from generated endpoints so the
     * compiler enforces the DTO/serializer pairing.
     */
    fun <T> encodeBody(serializer: KSerializer<T>, value: T): String =
        ApiJson.encodeToString(serializer, value)

    private fun ensureSuccess(request: ApiRequest, response: Response) {
        if (response.isSuccessful) return
        val serverMessage = runCatching {
            response.peekBody(2_048).string().let { text ->
                runCatching {
                    val obj = ApiJson.parseToJsonElement(text).jsonObject
                    (obj["message"] as? JsonPrimitive)?.content
                        ?: (obj["error"] as? JsonPrimitive)?.content
                }.getOrNull()
            }
        }.getOrNull()

        throw when (response.code) {
            401, 403 -> ApiException.Unauthorized(
                serverMessage ?: "Not authorised (HTTP ${response.code})",
            )
            else -> ApiException.Http(response.code, "${request.method} ${request.path}", serverMessage)
        }
    }

    private fun <T> decode(request: ApiRequest, bytes: ByteArray, serializer: KSerializer<T>): T {
        val text = bytes.decodeToString()
        return try {
            ApiJson.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            // A parse failure is nearly always a version mismatch. Surface it with
            // the endpoint, the cached server version and a payload snippet so the
            // user gets an actionable "incompatible server version" error.
            throw ApiException.IncompatibleSchema(
                endpoint = "${request.method} ${request.path}",
                serverVersion = versionProvider(),
                detail = e.message ?: e::class.java.simpleName,
                payloadSnippet = text.take(512),
            )
        } catch (e: IllegalArgumentException) {
            throw ApiException.IncompatibleSchema(
                endpoint = "${request.method} ${request.path}",
                serverVersion = versionProvider(),
                detail = e.message ?: "invalid payload",
                payloadSnippet = text.take(512),
            )
        }
    }

    // ------------------------------------------------------------------ HTTP plumbing

    private fun buildClient(insecureTls: Boolean, trustedCaPem: ByteArray?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)

        val trust = TlsTrust.build(insecureTls, trustedCaPem)
        if (trust != null) {
            builder.sslSocketFactory(trust.sslContext.socketFactory, trust.trustManager)
            builder.hostnameVerifier { _, _ -> true }
            // Allow cleartext HTTP to home-network servers; Immich ships http by default.
            builder.connectionSpecs(listOf(ConnectionSpec.CLEARTEXT, ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        }
        return builder.build()
    }

    private suspend fun Call.awaitExecute(
        cancelSignal: CancellationSignal? = null,
        cancelMessage: String = "Cancelled",
    ): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        cancelSignal?.setOnCancelListener { cancel() }
        enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                cancelSignal?.setOnCancelListener(null)
                if (continuation.isCancelled || call.isCanceled()) {
                    continuation.resumeWithException(ApiException.Cancelled())
                } else {
                    continuation.resumeWithException(ApiException.Network(e.message ?: "network error", e))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                cancelSignal?.setOnCancelListener(null)
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }
        })
        cancelSignal?.setOnCancelListener { cancel() }
    }

    companion object {
        private const val TAG = "ImmichApiClient"
        private const val MAX_RETRIES = 2
        private const val BACKOFF_BASE_MS = 400L
        private const val CONNECT_TIMEOUT_S = 12L
        private const val READ_TIMEOUT_S = 30L
        private const val WRITE_TIMEOUT_S = 30L
        private const val CALL_TIMEOUT_S = 45L
        private const val DOWNLOAD_BUFFER = 64 * 1024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** How the client authenticates. Immich accepts either a JWT session or an API key. */
sealed class AuthHeader {
    data class Bearer(val token: String) : AuthHeader()
    data class ApiKey(val key: String) : AuthHeader()
}

/** Self-signed / private CA support for home-network Immich servers. */
internal object TlsTrust {
    class Bundle(val sslContext: SSLContext, val trustManager: X509TrustManager)

    fun build(insecure: Boolean, caPem: ByteArray?): Bundle? {
        if (!insecure && caPem == null) return null

        val context = SSLContext.getInstance("TLS")
        if (insecure) {
            val permissive = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            }
            context.init(null, arrayOf<TrustManager>(permissive), null)
            return Bundle(context, permissive)
        }

        // Build a KeyStore containing the system CAs *plus* the user-supplied ones,
        // then trust exactly that set. Using a real KeyStore rather than hand-rolled
        // PKIX validation keeps chain building, expiry and hostname checks correct.
        val anchors = CertificateLoader.load(caPem!!)
        if (anchors.isEmpty()) {
            throw IllegalArgumentException("No certificates could be parsed from the provided CA file")
        }

        val keyStore = java.security.KeyStore.getInstance(
            java.security.KeyStore.getDefaultType(),
        ).apply {
            load(null, null)
            // Seed with the platform trust store.
            val systemFactory = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm(),
            )
            systemFactory.init(null as java.security.KeyStore?)
            systemFactory.trustManagers
                .filterIsInstance<X509TrustManager>()
                .first()
                .acceptedIssuers
                .forEachIndexed { index, cert ->
                    runCatching { setCertificateEntry("system-$index", cert) }
                }

            anchors.forEachIndexed { index, cert ->
                setCertificateEntry("user-$index", cert)
            }
        }

        val factory = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm(),
        )
        factory.init(keyStore)
        val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        context.init(null, arrayOf<TrustManager>(manager), null)
        return Bundle(context, manager)
    }
}

internal object CertificateLoader {
    /**
     * Parses one or more PEM certificates. Immich users typically export a single
     * self-signed cert, but a private CA bundle works too.
     */
    fun load(pem: ByteArray): List<java.security.cert.X509Certificate> {
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        return factory.generateCertificates(pem.inputStream())
            .filterIsInstance<java.security.cert.X509Certificate>()
    }
}
