package dev.immich.picker.data

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import dev.immich.picker.BuildConfig
import dev.immich.picker.cache.OriginalCache
import dev.immich.picker.cache.ThumbnailCache
import dev.immich.picker.net.ApiException
import dev.immich.picker.net.AuthHeader
import dev.immich.picker.net.ImmichApiClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide wiring: credentials, HTTP client, repository, caches.
 *
 * Lives in the application process, which is shared by [dev.immich.picker.ui.MainActivity]
 * and [dev.immich.picker.provider.ImmichDocumentsProvider], so the provider sees a
 * login performed in the UI without any IPC.
 */
object ImmichService {

    private const val TAG = "ImmichService"

    @Volatile
    private var appContext: Context? = null

    /**
     * Context is only stored as [Context.getApplicationContext], which lives for the
     * process lifetime anyway, so holding it in this singleton leaks nothing. Lint cannot
     * tell the difference from the field type alone.
     */
    @SuppressLint("StaticFieldLeak")
    lateinit var credentialStore: CredentialStore
        private set

    val thumbnails: ThumbnailCache by lazy {
        ThumbnailCache(requireContext().cacheDir)
    }

    val originals: OriginalCache by lazy {
        OriginalCache(requireContext().cacheDir)
    }

    /** Scratch directory for in-flight thumbnail downloads before they are committed. */
    val thumbnailsDirTemp: File by lazy {
        File(requireContext().cacheDir, "tmp").apply { mkdirs() }
    }

    // ------------------------------------------------------------------ health

    /**
     * Last transport/version problem, surfaced by the UI and by the provider's
     * notifications. Null when the last operation succeeded.
     */
    private val lastError = AtomicReference<ServiceError?>(null)

    fun lastError(): ServiceError? = lastError.get()

    fun clearError() {
        lastError.set(null)
    }

    /**
     * Called by the provider when the server rejects the stored credential.
     *
     * The credential itself is kept so the user can see which account failed, but
     * [isLoggedIn] starts returning false because the provider's root must vanish
     * and the UI must show the login screen.
     */
    fun onAuthFailure(message: String?) {
        lastError.set(ServiceError.AuthExpired(message ?: "Immich session expired"))
        authFailed.set(true)
        invalidate()
        notifyListeners()
    }

    fun onNetworkFailure(message: String?) {
        lastError.set(ServiceError.Network(message ?: "Immich server unreachable"))
        notifyListeners()
    }

    fun onIncompatibleServer(error: ApiException.IncompatibleSchema) {
        lastError.set(
            ServiceError.Incompatible(
                endpoint = error.endpoint,
                serverVersion = error.serverVersion,
                detail = error.detail,
            ),
        )
        notifyListeners()
    }

    private val authFailed = AtomicBoolean(false)

    fun isAuthFailed(): Boolean = authFailed.get()

    // -------------------------------------------------------- provider bridge

    /** Observers registered by the provider (to call notifyChange) and the UI. */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { runCatching { it() } }
    }

    /**
     * Bumped whenever credentials change. The provider uses it to invalidate its
     * per-session listing caches without holding a reference to the session.
     */
    private val generation = AtomicReference(0L)

    private val sessionRef = AtomicReference<Session?>(null)

    @Synchronized
    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            credentialStore = CredentialStore(context.applicationContext)
        }
    }

    private fun requireContext(): Context =
        appContext ?: error("ImmichService.initialize() must be called before use")

    fun currentGeneration(): Long = generation.get()

    /**
     * True when a usable credential exists *and* it has not been rejected by the
     * server during this process lifetime. Does not make a network call.
     */
    fun isLoggedIn(): Boolean =
        credentialStore.current()?.isComplete == true && !authFailed.get()

    /** Forgets a previous auth failure, e.g. after a fresh successful login. */
    fun clearAuthFailure() {
        authFailed.set(false)
        clearError()
    }

    /**
     * Returns the active session, building it on first use. Returns null when the
     * user is not logged in.
     */
    fun session(): Session? {
        val creds = credentialStore.current() ?: return null
        if (!creds.isComplete) return null

        val existing = sessionRef.get()
        if (existing != null && existing.credentials == creds) return existing

        synchronized(this) {
            val current = sessionRef.get()
            if (current != null && current.credentials == creds) return current
            val built = try {
                buildSession(creds, credentialStore.options())
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "invalid server URL: ${creds.serverUrl}", e)
                return null
            }
            sessionRef.set(built)
            return built
        }
    }

    private fun buildSession(creds: ImmichCredentials, options: ConnectionOptions): Session {
        val baseUrl = normalizeBaseUrl(creds.serverUrl).toHttpUrlOrNull()
            ?: throw IllegalArgumentException("not a valid URL: ${creds.serverUrl}")

        val auth = when (creds.authMode) {
            AuthMode.API_KEY -> AuthHeader.ApiKey(creds.secret)
            AuthMode.PASSWORD -> AuthHeader.Bearer(creds.secret)
        }

        val client = ImmichApiClient(
            baseUrl = baseUrl,
            authProvider = { auth },
            serverVersionProvider = { credentialStore.current()?.serverVersion },
            insecureTls = options.allowInsecureTls,
            trustedCaPem = options.customCaPem,
        )
        return Session(creds, client, ImmichRepository(client, creds))
    }

    /** Call after any credential or TLS option change. */
    fun invalidate() {
        sessionRef.set(null)
        generation.updateAndGet { it + 1 }
    }

    fun logout() {
        credentialStore.clear()
        invalidate()
        authFailed.set(false)
        lastError.set(null)
        runCatching {
            thumbnails.clear()
            originals.clear()
            thumbnailsDirTemp.listFiles()?.forEach { it.delete() }
        }
        notifyListeners()
    }

    /** Clears every on-disk cache without touching credentials. */
    fun clearCaches() {
        runCatching {
            thumbnails.clear()
            originals.clear()
            thumbnailsDirTemp.listFiles()?.forEach { it.delete() }
        }
        notifyListeners()
    }

    fun cacheUsage(): Pair<Long, Long> = thumbnails.sizeBytes() to originals.sizeBytes()

    /** Persists refreshed metadata after a successful validation. */
    fun updateStoredVersion(version: String?) {
        credentialStore.updateServerVersion(version)
        sessionRef.get()?.let { existing ->
            // Keep the in-memory copy in sync without rebuilding the client, so
            // version-mismatch errors report the right server version.
            sessionRef.set(existing.copy(credentials = existing.credentials.copy(serverVersion = version)))
        }
    }

    /**
     * Turns `photos.example.com`, `https://photos.example.com/`, or
     * `http://host:2283/api` into a canonical `scheme://host[:port][/path]` with no
     * trailing slash and no `/api` suffix.
     */
    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return url
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            // A bare host on a home network is almost always HTTP with Immich's default port.
            url = if (url.contains(":443")) "https://$url" else "http://$url"
        }
        url = url.trimEnd('/')
        if (url.endsWith("/api")) url = url.removeSuffix("/api")
        return url
    }

    /** Default port hint used by the login form when the user types a bare host. */
    fun suggestUrl(raw: String): String {
        val normalised = normalizeBaseUrl(raw)
        val parsed = normalised.toHttpUrlOrNull() ?: return normalised
        return if (parsed.port == 80 && parsed.scheme == "http") "$normalised" else normalised
    }

    fun testedVersion(): String = BuildConfig.TESTED_IMMICH_VERSION

    /** Everything needed to talk to one server, rebuilt whenever creds change. */
    data class Session(
        val credentials: ImmichCredentials,
        val client: ImmichApiClient,
        val repository: ImmichRepository,
    )

    /**
     * A problem the user must be told about. Kept as data so both the Compose UI
     * and the provider's notification can render the same facts.
     */
    sealed class ServiceError {
        abstract val message: String

        data class AuthExpired(override val message: String) : ServiceError()
        data class Network(override val message: String) : ServiceError()
        data class Incompatible(
            val endpoint: String,
            val serverVersion: String?,
            val detail: String,
        ) : ServiceError() {
            override val message: String =
                "Immich $endpoint returned data this app does not understand " +
                    "(server ${serverVersion ?: "unknown"}). The server version is " +
                    "probably newer than this build supports."
        }
    }
}

/** Convenience for callers that want "session or a clear error". */
fun ImmichService.requireSession(): ImmichService.Session =
    session() ?: throw ApiException.Unauthorized("Not signed in to Immich")
