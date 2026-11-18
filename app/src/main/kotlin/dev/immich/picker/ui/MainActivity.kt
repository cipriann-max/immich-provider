package dev.immich.picker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.immich.picker.data.ImmichService

/**
 * Settings and sign-in.
 *
 * Also the target of DocumentsUI's per-root settings arrow: the provider advertises
 * [android.provider.DocumentsContract.ACTION_DOCUMENT_ROOT_SETTINGS] and this activity
 * answers it, so a signed-out user can reach sign-in directly from the file picker rather
 * than having to find the launcher icon.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Deliberately NOT enableEdgeToEdge(): Scaffold's TopAppBar does not apply
        // statusBarsPadding() here, so drawing under the status bar hid the title behind
        // the clock and notification icons. Letting the system own the insets keeps the
        // header readable in gesture and 3-button navigation alike.

        ImmichService.initialize(this)

        setContent {
            MaterialTheme {
                Surface {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    SettingsScreen(
                        state = state,
                        onServerUrlChange = viewModel::onServerUrlChange,
                        onEmailChange = viewModel::onEmailChange,
                        onAuthModeChange = viewModel::onAuthModeChange,
                        onInsecureTlsChange = viewModel::onInsecureTlsChange,
                        onCustomCaChange = viewModel::onCustomCaChange,
                        onLogin = { password, apiKey -> viewModel.login(password, apiKey) },
                        onTest = { viewModel.testConnection() },
                        onLogout = { viewModel.logout() },
                        onClearCache = { viewModel.clearCache() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Settings is the screen where credentials are fixed, so it must never present a
        // stale picture of them. A `401` handled in the provider marks the credential
        // invalid in ImmichService; without this, the screen would still show "Connected".
        viewModel.reloadFromStorage()
    }
}
