package dev.immich.picker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.immich.picker.BuildConfig
import dev.immich.picker.data.AuthMode
import dev.immich.picker.data.VersionSeverity

/**
 * Single-screen Material 3 UI: credentials at the top, live status below, then
 * cache controls and the version warning. Deliberately plain — this app exists to
 * make the picker work, not to be a gallery.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onServerUrlChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onAuthModeChange: (AuthMode) -> Unit,
    onInsecureTlsChange: (Boolean) -> Unit,
    onCustomCaChange: (String) -> Unit,
    onLogin: (password: String, apiKey: String) -> Unit,
    onTest: () -> Unit,
    onLogout: () -> Unit,
    onClearCache: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Immich File Picker") },
                actions = {
                    if (state.loggedIn) {
                        TextButton(onClick = onLogout) { Text("Sign out") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            Text(
                "Immich appears in the Android file picker once you sign in. " +
                    "The app only reads from your server.",
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text("Server URL") },
                placeholder = { Text("https://photos.example.com") },
                singleLine = true,
                enabled = !state.loggingIn,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.authMode == AuthMode.PASSWORD,
                    onClick = { onAuthModeChange(AuthMode.PASSWORD) },
                    label = { Text("Email & password") },
                    enabled = !state.loggingIn,
                )
                FilterChip(
                    selected = state.authMode == AuthMode.API_KEY,
                    onClick = { onAuthModeChange(AuthMode.API_KEY) },
                    label = { Text("API key") },
                    enabled = !state.loggingIn,
                )
            }

            if (state.authMode == AuthMode.PASSWORD) {
                OutlinedTextField(
                    value = state.email,
                    onValueChange = onEmailChange,
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !state.loggingIn,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !state.loggingIn,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(if (passwordVisible) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    singleLine = true,
                    enabled = !state.loggingIn,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                            Text(if (passwordVisible) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The key needs all five permissions: user.read, asset.read, asset.view, " +
                        "asset.download, album.read. Create it in Immich → Account Settings → " +
                        "API Keys. user.read is required because the app reads your profile to " +
                        "validate the key; without it sign-in is rejected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onLogin(password, apiKey) },
                    enabled = !state.loggingIn,
                ) {
                    if (state.loggingIn) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (state.loggedIn) "Sign in again" else "Sign in")
                }
                if (state.loggedIn) {
                    OutlinedButton(onClick = onTest, enabled = !state.testing) {
                        Text(if (state.testing) "Testing…" else "Test connection")
                    }
                }
            }

            state.error?.let { message ->
                MessageCard(
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
                    text = message,
                )
            }

            state.info?.takeIf { state.error == null }?.let { message ->
                MessageCard(
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    text = message,
                )
            }

            state.connection?.let { connection ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text("Connected", style = MaterialTheme.typography.titleMedium)
                        }
                        StatusRow("Server", connection.serverUrl)
                        StatusRow(
                            "Immich version",
                            "${connection.serverVersion} (app built for ${BuildConfig.TESTED_IMMICH_VERSION})",
                        )
                        connection.userName?.let { StatusRow("User", it) }
                        connection.userEmail?.let { StatusRow("Email", it) }
                        StatusRow("Auth", if (state.authMode == AuthMode.API_KEY) "API key" else "Session token")
                    }
                }
            }

            state.versionAssessment?.takeIf { it.isWarning }?.let { assessment ->
                val isMajor = assessment.severity == VersionSeverity.SERVER_NEWER_MAJOR ||
                    assessment.severity == VersionSeverity.SERVER_OLDER
                MessageCard(
                    container = if (isMajor) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    content = if (isMajor) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
                    text = assessment.message.orEmpty(),
                )
            }

            HorizontalDivider()

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Cache", style = MaterialTheme.typography.titleMedium)
                    Text(state.cacheSummary, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onClearCache) { Text("Clear cache") }
                }
            }

            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "Hide certificate options" else "Certificate options")
            }

            if (showAdvanced) {
                Text(
                    "Only for servers with self-signed certificates on your home network. " +
                        "Prefer installing your CA on the device when possible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Allow untrusted certificates", Modifier.weight(1f))
                    Switch(
                        checked = state.insecureTls,
                        onCheckedChange = onInsecureTlsChange,
                    )
                }
                OutlinedTextField(
                    value = state.customCaPem,
                    onValueChange = onCustomCaChange,
                    label = { Text("Custom CA (PEM, optional)") },
                    minLines = 3,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Changes apply the next time you sign in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Text(
                "How to find Immich in the picker",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Chrome and other apps often open the system photo picker first. " +
                    "Tap the menu (⋮) or \"Browse…\", then choose Immich under " +
                    "\"Other locations\" / the drawer. Files → Immich works too, and " +
                    "Immich stays available there for any app that opens the file picker.",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun MessageCard(
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    icon: @Composable () -> Unit,
    text: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon()
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
