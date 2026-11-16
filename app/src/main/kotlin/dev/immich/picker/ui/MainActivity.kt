package dev.immich.picker.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.immich.picker.data.ImmichService

class MainActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Deliberately NOT enableEdgeToEdge(), for the same reason as PickerActivity:
        // Scaffold's TopAppBar does not apply statusBarsPadding() here, so drawing under
        // the status bar hid the title behind the clock and notification icons. Letting
        // the system own the insets keeps the header readable in gesture and 3-button
        // navigation alike.

        ImmichService.initialize(this)
        PickerNotifications.ensureChannels(this)
        requestNotificationPermission()

        // A notification or an auth failure in the provider deep-links here.
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) {
            PickerNotifications.cancelAll(this)
        }

        setContent {
            MaterialTheme {
                Surface {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    val context = LocalContext.current
                    SettingsScreen(
                        state = state,
                        onServerUrlChange = viewModel::onServerUrlChange,
                        onEmailChange = viewModel::onEmailChange,
                        onAuthModeChange = viewModel::onAuthModeChange,
                        onInsecureTlsChange = viewModel::onInsecureTlsChange,
                        onCustomCaChange = viewModel::onCustomCaChange,
                        onLogin = { password, apiKey -> viewModel.login(password, apiKey) },
                        onTest = {
                            viewModel.testConnection()
                            PickerNotifications.mirrorServiceError(context)
                        },
                        onLogout = { viewModel.logout() },
                        onClearCache = { viewModel.clearCache() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            PickerNotifications.cancelAll(this)
            // Re-entered from the picker's cog or a notification: the stored credential
            // may have changed elsewhere (provider auth failure, another activity), so
            // re-read it rather than trusting the state from the first launch.
            viewModel.reloadFromStorage()
        }
    }

    override fun onResume() {
        super.onResume()
        // Settings is the screen where credentials are fixed, so it must never present a
        // stale picture of them. A `401` handled in the provider marks the credential
        // invalid in ImmichService; without this, the screen would still show "Connected".
        viewModel.reloadFromStorage()
    }

    /**
     * Android 13+ requires runtime permission for notifications. The provider posts
     * them without an Activity, so asking here (the only foreground entry point) is
     * the only opportunity.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "dev.immich.picker.extra.OPEN_SETTINGS"
    }
}
