package dev.immich.picker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.immich.picker.BuildConfig
import dev.immich.picker.data.AuthMode
import dev.immich.picker.data.ConnectionInfo
import dev.immich.picker.data.ConnectionOptions
import dev.immich.picker.data.ImmichCredentials
import dev.immich.picker.data.ImmichRepository
import dev.immich.picker.data.ImmichService
import dev.immich.picker.data.VersionAssessment
import dev.immich.picker.data.VersionGate
import dev.immich.picker.data.loginWithPassword
import dev.immich.picker.net.ApiException
import dev.immich.picker.net.AuthHeader
import dev.immich.picker.net.ImmichApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Everything the settings/login screen can be in. */
data class UiState(
    val serverUrl: String = "",
    val email: String = "",
    val authMode: AuthMode = AuthMode.PASSWORD,
    val loggingIn: Boolean = false,
    val testing: Boolean = false,
    val loggedIn: Boolean = false,
    val connection: ConnectionInfo? = null,
    val versionAssessment: VersionAssessment? = null,
    val error: String? = null,
    val info: String? = null,
    val insecureTls: Boolean = false,
    val customCaPem: String = "",
    val cacheSummary: String = "",
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val service: ImmichService get() = ImmichService

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        ImmichService.initialize(app)
        reloadFromStorage()
        refreshCacheSummary()
        if (service.isLoggedIn()) {
            verifyExistingSession()
        }
    }

    // ------------------------------------------------------------------ form

    fun onServerUrlChange(value: String) {
        _state.update { it.copy(serverUrl = value, error = null) }
    }

    fun onEmailChange(value: String) {
        _state.update { it.copy(email = value, error = null) }
    }

    fun onAuthModeChange(mode: AuthMode) {
        _state.update { it.copy(authMode = mode, error = null) }
    }

    fun onInsecureTlsChange(enabled: Boolean) {
        _state.update { it.copy(insecureTls = enabled) }
    }

    fun onCustomCaChange(pem: String) {
        _state.update { it.copy(customCaPem = pem) }
    }

    // ------------------------------------------------------------------ login

    /**
     * Validates the server URL, probes `/api/server/version`, then authenticates.
     *
     * Ordering matters: the version probe is unauthenticated, so a wrong URL or an
     * unreachable host is reported as such instead of as a bad password.
     */
    fun login(password: String, apiKey: String) {
        val current = _state.value
        val rawUrl = current.serverUrl.trim()
        if (rawUrl.isBlank()) {
            _state.update { it.copy(error = "Enter your Immich server URL") }
            return
        }

        val isApiKey = current.authMode == AuthMode.API_KEY
        if (isApiKey && apiKey.isBlank()) {
            _state.update { it.copy(error = "Enter an API key") }
            return
        }
        if (!isApiKey && (current.email.isBlank() || password.isBlank())) {
            _state.update { it.copy(error = "Enter your email and password") }
            return
        }

        _state.update { it.copy(loggingIn = true, error = null, info = "Checking server…") }

        viewModelScope.launch {
            try {
                val baseUrl = ImmichService.normalizeBaseUrl(rawUrl)
                val options = ConnectionOptions(
                    allowInsecureTls = current.insecureTls,
                    customCaPem = current.customCaPem.takeIf { it.isNotBlank() }?.toByteArray(),
                )

                val parsed = baseUrl.toHttpUrlOrNull()
                    ?: throw IllegalArgumentException("That is not a valid URL: $rawUrl")

                // 1. Unauthenticated version probe: proves reachability + correct URL.
                val probeClient = ImmichApiClient(
                    baseUrl = parsed,
                    authProvider = { null },
                    insecureTls = options.allowInsecureTls,
                    trustedCaPem = options.customCaPem,
                )
                val serverVersion = withContext(Dispatchers.IO) { probeClient.probeServerVersion() }
                val versionString = VersionGate.format(
                    serverVersion.major.toInt(),
                    serverVersion.minor.toInt(),
                    serverVersion.patch.toInt(),
                    serverVersion.prerelease?.toInt(),
                )
                _state.update { it.copy(info = "Server $versionString found. Signing in…") }

                // 2. Authenticate.
                val secret: String
                if (isApiKey) {
                    secret = apiKey.trim()
                } else {
                    val loginClient = ImmichApiClient(
                        baseUrl = parsed,
                        authProvider = { null },
                        insecureTls = options.allowInsecureTls,
                        trustedCaPem = options.customCaPem,
                    )
                    secret = withContext(Dispatchers.IO) {
                        loginClient.loginWithPassword(current.email.trim(), password)
                    }
                }

                // 3. Validate the credential actually works and read the user.
                val auth = if (isApiKey) AuthHeader.ApiKey(secret) else AuthHeader.Bearer(secret)
                val authedClient = ImmichApiClient(
                    baseUrl = parsed,
                    authProvider = { auth },
                    serverVersionProvider = { versionString },
                    insecureTls = options.allowInsecureTls,
                    trustedCaPem = options.customCaPem,
                )
                val repository = ImmichRepository(authedClient, placeholderCredentials(baseUrl, secret, isApiKey, versionString))
                // `user.read` is required, consistently with "Test connection" and app
                // start: the profile call is the one validation path for all three.
                val (name, email) = withContext(Dispatchers.IO) { repository.me() }

                // 4. Persist.
                val label = listOfNotNull(name?.takeIf { it.isNotBlank() }, email).joinToString(" · ")
                val credentials = ImmichCredentials(
                    serverUrl = baseUrl,
                    authMode = if (isApiKey) AuthMode.API_KEY else AuthMode.PASSWORD,
                    secret = secret,
                    userEmail = email,
                    userName = name,
                    serverVersion = versionString,
                    accountLabel = label.ifBlank { null },
                )
                service.credentialStore.save(credentials)
                service.credentialStore.saveOptions(options)
                service.credentialStore.markLoggedIn()
                service.clearAuthFailure()
                service.invalidate()
                PickerNotifications.cancelAll(getApplication())

                val assessment = VersionGate.assess(serverVersion)
                if (assessment.isWarning) {
                    PickerNotifications.notifyVersionWarning(getApplication(), assessment)
                }

                _state.update {
                    it.copy(
                        loggingIn = false,
                        loggedIn = true,
                        serverUrl = baseUrl,
                        email = email ?: it.email,
                        error = null,
                        info = "Connected.",
                        connection = ConnectionInfo(
                            serverUrl = baseUrl,
                            serverVersion = versionString,
                            userEmail = email,
                            userName = name,
                            versionAssessment = assessment,
                        ),
                        versionAssessment = assessment,
                    )
                }
                refreshCacheSummary()
            } catch (e: ApiException.Unauthorized) {
                _state.update {
                    it.copy(loggingIn = false, error = "Sign-in rejected: ${e.message}")
                }
            } catch (e: ApiException.Forbidden) {
                // A valid credential that lacks a permission. Signing in again would not
                // help, so say exactly what to change instead.
                _state.update {
                    it.copy(
                        loggingIn = false,
                        error = if (isApiKey) {
                            "This API key is missing a required permission. Grant all five: " +
                                "$REQUIRED_PERMISSIONS. (${e.message})"
                        } else {
                            "This account is not permitted to read its own profile: ${e.message}"
                        },
                    )
                }
            } catch (e: ApiException.Http) {
                _state.update {
                    it.copy(
                        loggingIn = false,
                        error = "Server returned HTTP ${e.status}: ${e.serverMessage ?: "unexpected response"}",
                    )
                }
            } catch (e: ApiException.Network) {
                _state.update {
                    it.copy(loggingIn = false, error = "Cannot reach the server: ${e.message}")
                }
            } catch (e: ApiException.IncompatibleSchema) {
                _state.update {
                    it.copy(
                        loggingIn = false,
                        error = "This does not look like an Immich ${
                            BuildConfig.TESTED_IMMICH_VERSION
                        } server (${e.endpoint} returned unexpected data).",
                    )
                }
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(loggingIn = false, error = e.message) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loggingIn = false, error = "Sign-in failed: ${e.message}")
                }
            }
        }
    }

    private fun placeholderCredentials(
        serverUrl: String,
        secret: String,
        isApiKey: Boolean,
        version: String,
    ) = ImmichCredentials(
        serverUrl = serverUrl,
        authMode = if (isApiKey) AuthMode.API_KEY else AuthMode.PASSWORD,
        secret = secret,
        userEmail = null,
        userName = null,
        serverVersion = version,
        accountLabel = null,
    )

    // ------------------------------------------------------------------ verify

    fun testConnection() {
        val session = service.session()
        if (session == null) {
            _state.update { it.copy(error = "Not signed in") }
            return
        }
        _state.update { it.copy(testing = true, error = null, info = null) }

        viewModelScope.launch {
            try {
                val version = withContext(Dispatchers.IO) { session.repository.serverVersion() }
                val versionString = VersionGate.format(
                    version.major.toInt(),
                    version.minor.toInt(),
                    version.patch.toInt(),
                    version.prerelease?.toInt(),
                )
                val (name, email) = withContext(Dispatchers.IO) { session.repository.me() }
                service.updateStoredVersion(versionString)
                service.clearError()

                val assessment = VersionGate.assess(version)
                if (assessment.isWarning) {
                    PickerNotifications.notifyVersionWarning(getApplication(), assessment)
                }

                _state.update {
                    it.copy(
                        testing = false,
                        loggedIn = true,
                        info = "Connected to Immich $versionString.",
                        connection = ConnectionInfo(
                            serverUrl = session.credentials.serverUrl,
                            serverVersion = versionString,
                            userEmail = email ?: session.credentials.userEmail,
                            userName = name ?: session.credentials.userName,
                            versionAssessment = assessment,
                        ),
                        versionAssessment = assessment,
                    )
                }
            } catch (e: ApiException.Unauthorized) {
                _state.update {
                    it.copy(testing = false, error = "Credential rejected: ${e.message}")
                }
            } catch (e: ApiException.Forbidden) {
                _state.update { it.copy(testing = false, error = e.message) }
            } catch (e: ApiException.Network) {
                _state.update {
                    it.copy(testing = false, error = "Cannot reach the server: ${e.message}")
                }
            } catch (e: Exception) {
                _state.update { it.copy(testing = false, error = e.message) }
            }
        }
    }

    private fun verifyExistingSession() {
        val session = service.session() ?: return
        _state.update { it.copy(loggedIn = true) }
        viewModelScope.launch {
            try {
                val version = withContext(Dispatchers.IO) { session.repository.serverVersion() }
                val versionString = VersionGate.format(
                    version.major.toInt(),
                    version.minor.toInt(),
                    version.patch.toInt(),
                    version.prerelease?.toInt(),
                )
                val assessment = VersionGate.assess(version)
                service.updateStoredVersion(versionString)
                // Same validation path as login and "Test connection", so all three agree
                // on which permissions a credential needs.
                val (name, email) = withContext(Dispatchers.IO) { session.repository.me() }
                _state.update {
                    it.copy(
                        loggedIn = true,
                        connection = ConnectionInfo(
                            serverUrl = session.credentials.serverUrl,
                            serverVersion = versionString,
                            userEmail = email ?: session.credentials.userEmail,
                            userName = name ?: session.credentials.userName,
                            versionAssessment = assessment,
                        ),
                        versionAssessment = assessment,
                    )
                }
            } catch (e: ApiException.Unauthorized) {
                service.onAuthFailure(e.message)
                _state.update {
                    it.copy(
                        loggedIn = false,
                        error = "Session expired. Sign in again, or switch to an API key, " +
                            "which does not expire.",
                    )
                }
            } catch (e: ApiException.Forbidden) {
                // The stored credential is valid; only the profile call is unreadable.
                // Keep the session usable and say so rather than demanding a re-login.
                _state.update { it.copy(loggedIn = true, error = e.message) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(error = "Could not reach Immich: ${e.message}")
                }
            }
        }
    }

    // ------------------------------------------------------------------ other

    fun logout() {
        service.logout()
        PickerNotifications.cancelAll(getApplication())
        _state.value = UiState(
            serverUrl = _state.value.serverUrl,
            authMode = _state.value.authMode,
            insecureTls = _state.value.insecureTls,
            info = "Signed out. Immich no longer appears in the file picker.",
        )
        refreshCacheSummary()
    }

    fun clearCache() {
        service.clearCaches()
        refreshCacheSummary()
        _state.update { it.copy(info = "Cache cleared.") }
    }

    fun refreshCacheSummary() {
        val (thumb, orig) = service.cacheUsage()
        val summary = "Thumbnails ${formatBytes(thumb)} / 200 MB · Downloads ${formatBytes(orig)} / 500 MB"
        _state.update { it.copy(cacheSummary = summary) }
    }

    /**
     * Re-reads stored credentials into the UI state.
     *
     * Public because the screen refreshes on resume: the provider can mark a credential
     * invalid at any moment, and showing "Connected" for a session that has since died is
     * exactly the stale-state problem this removes.
     */
    fun reloadFromStorage() {
        val credentials = service.credentialStore.current()
        val options = service.credentialStore.options()
        val loggedIn = service.isLoggedIn()
        _state.update {
            it.copy(
                serverUrl = credentials?.serverUrl.orEmpty(),
                email = credentials?.userEmail.orEmpty(),
                authMode = credentials?.authMode ?: AuthMode.PASSWORD,
                loggedIn = loggedIn,
                insecureTls = options.allowInsecureTls,
                customCaPem = options.customCaPem?.decodeToString().orEmpty(),
                // A dead session must not keep showing a stale "Connected" card carrying
                // the previous account's name and email.
                connection = if (loggedIn) it.connection else null,
                // ...and once it is dead, say so instead of leaving a green tick.
                error = if (loggedIn) {
                    it.error
                } else if (credentials != null) {
                    "Session expired. Sign in again, or switch to an API key."
                } else {
                    it.error
                },
            )
        }
    }

    private companion object {
        /**
         * Every permission the app actually uses, including `user.read`, which the
         * credential validation calls (`GET /users/me`) genuinely requires.
         */
        const val REQUIRED_PERMISSIONS =
            "asset.read, asset.view, asset.download, album.read, user.read"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
