package dev.immich.picker.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Everything needed to talk to one Immich server.
 *
 * @param serverUrl normalised base URL *without* the trailing `/api`, e.g.
 *   `https://photos.example.com` or `http://192.168.1.5:2283`.
 * @param authMode which credential produced [secret]
 * @param secret bearer access token or API key, depending on [authMode]
 * @param serverVersion version string reported by `/api/server/version` when the
 *   credentials were last validated
 */
data class ImmichCredentials(
    val serverUrl: String,
    val authMode: AuthMode,
    val secret: String,
    val userEmail: String?,
    val userName: String?,
    val serverVersion: String?,
    val accountLabel: String?,
) {
    val isComplete: Boolean get() = serverUrl.isNotBlank() && secret.isNotBlank()
}

enum class AuthMode { PASSWORD, API_KEY }

/**
 * Connection options that are not secret but change how TLS is handled.
 */
data class ConnectionOptions(
    val allowInsecureTls: Boolean = false,
    val customCaPem: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConnectionOptions) return false
        return allowInsecureTls == other.allowInsecureTls &&
            (customCaPem?.contentEquals(other.customCaPem) ?: (other.customCaPem == null))
    }

    override fun hashCode(): Int =
        31 * allowInsecureTls.hashCode() + (customCaPem?.contentHashCode() ?: 0)
}

/**
 * Encrypted-at-rest credential storage.
 *
 * The secret (session token / API key) is kept in `EncryptedSharedPreferences`
 * backed by a `MasterKey` in the Android Keystore, so it is unreadable even from
 * a root file copy of `/data/data/dev.immich.picker`.
 *
 * The non-secret metadata (server URL, user label, last known version) is stored
 * in the same encrypted file rather than plain preferences, because a server URL
 * on a private network is itself sensitive.
 */
class CredentialStore(private val context: Context) {

    private val prefs: SharedPreferences by lazy { open() }

    private fun open(): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Keystore can be wiped by "clear credentials" or a device restore. Rather
            // than crashing, drop the file and let the user log in again.
            Log.e(TAG, "encrypted prefs unavailable, resetting", e)
            context.deleteSharedPreferences(FILE_NAME)
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    fun current(): ImmichCredentials? {
        val serverUrl = prefs.getString(KEY_SERVER_URL, null) ?: return null
        val secret = prefs.getString(KEY_SECRET, null) ?: return null
        val mode = runCatching {
            AuthMode.valueOf(prefs.getString(KEY_AUTH_MODE, AuthMode.PASSWORD.name)!!)
        }.getOrDefault(AuthMode.PASSWORD)
        return ImmichCredentials(
            serverUrl = serverUrl,
            authMode = mode,
            secret = secret,
            userEmail = prefs.getString(KEY_USER_EMAIL, null),
            userName = prefs.getString(KEY_USER_NAME, null),
            serverVersion = prefs.getString(KEY_SERVER_VERSION, null),
            accountLabel = prefs.getString(KEY_ACCOUNT_LABEL, null),
        )
    }

    fun save(credentials: ImmichCredentials) {
        prefs.edit()
            .putString(KEY_SERVER_URL, credentials.serverUrl)
            .putString(KEY_SECRET, credentials.secret)
            .putString(KEY_AUTH_MODE, credentials.authMode.name)
            .putString(KEY_USER_EMAIL, credentials.userEmail)
            .putString(KEY_USER_NAME, credentials.userName)
            .putString(KEY_SERVER_VERSION, credentials.serverVersion)
            .putString(KEY_ACCOUNT_LABEL, credentials.accountLabel)
            .apply()
    }

    /** Updates only the version string, used after a background version probe. */
    fun updateServerVersion(version: String?) {
        prefs.edit().putString(KEY_SERVER_VERSION, version).apply()
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_SERVER_URL)
            .remove(KEY_SECRET)
            .remove(KEY_AUTH_MODE)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_NAME)
            .remove(KEY_SERVER_VERSION)
            .remove(KEY_ACCOUNT_LABEL)
            .putBoolean(KEY_LOGGED_OUT, true)
            .apply()
    }

    fun options(): ConnectionOptions {
        val pem = prefs.getString(KEY_CA_PEM, null)?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        }
        return ConnectionOptions(
            allowInsecureTls = prefs.getBoolean(KEY_INSECURE_TLS, false),
            customCaPem = pem,
        )
    }

    fun saveOptions(options: ConnectionOptions) {
        prefs.edit().apply {
            putBoolean(KEY_INSECURE_TLS, options.allowInsecureTls)
            if (options.customCaPem != null) {
                putString(KEY_CA_PEM, Base64.encodeToString(options.customCaPem, Base64.NO_WRAP))
            } else {
                remove(KEY_CA_PEM)
            }
        }.apply()
    }

    /**
     * True when the user explicitly signed out. Used to distinguish "never logged
     * in" from "logged out" so the root can disappear immediately and stay gone.
     */
    val wasLoggedOut: Boolean get() = prefs.getBoolean(KEY_LOGGED_OUT, false)

    fun markLoggedIn() {
        prefs.edit().putBoolean(KEY_LOGGED_OUT, false).apply()
    }

    private companion object {
        const val TAG = "CredentialStore"
        const val FILE_NAME = "immich_picker_credentials"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_SECRET = "secret"
        const val KEY_AUTH_MODE = "auth_mode"
        const val KEY_USER_EMAIL = "user_email"
        const val KEY_USER_NAME = "user_name"
        const val KEY_SERVER_VERSION = "server_version"
        const val KEY_ACCOUNT_LABEL = "account_label"
        const val KEY_INSECURE_TLS = "insecure_tls"
        const val KEY_CA_PEM = "ca_pem"
        const val KEY_LOGGED_OUT = "logged_out"
    }
}
