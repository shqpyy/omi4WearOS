package com.omi4wos.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omi4wos.mobile.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Omi API Configuration
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Omi Cloud Configuration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Configure your direct-sync credentials to upload audio securely.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Omi Firebase ID configuration (v2/sync-local-files requires this instead of standard keys)
                Text(
                    text = "Omi Web Session Authentication (Required)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "The audio upload API requires tokens from your browser session on app.omi.me.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "How to obtain Firebase Token:\n" +
                            "1. Open app.omi.me in Chrome and sign in (from a computer is best).\n" +
                            "2. Open DevTools (`F12` or `Cmd+Option+I`) → Network tab\n" +
                            "3. Click any request in the list → Headers → copy the value after `Authorization: Bearer `\n\n" +
                            "How to obtain Refresh Token (for automatic long-term renewal):\n" +
                            "1. In DevTools → Application tab\n" +
                            "2. IndexedDB → `firebaseLocalStorageDb` → `firebaseLocalStorage`\n" +
                            "3. Expand your user entry → `stsTokenManager` → copy `refreshToken`\n\n" +
                            "How to obtain Web API Key (required for auto-refresh):\n" +
                            "1. In the Network tab, filter requests by `googleapis.com`\n" +
                            "2. Look for the API key in the request URL parameter: `key=AIza...`",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = uiState.firebaseWebApiKey,
                    onValueChange = { viewModel.updateFirebaseWebApiKey(it) },
                    label = { Text("Firebase Web API Key") },
                    placeholder = { Text("AIza...") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = uiState.firebaseToken,
                    onValueChange = { viewModel.updateFirebaseToken(it) },
                    label = { Text("Firebase Token (expires in 1h)") },
                    placeholder = { Text("eyJ...") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = uiState.firebaseRefreshToken,
                    onValueChange = { viewModel.updateFirebaseRefreshToken(it) },
                    label = { Text("Refresh Token") },
                    placeholder = { Text("AMf...") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = uiState.uploadUrl,
                    onValueChange = { viewModel.updateUploadUrl(it) },
                    label = { Text("Upload URL") },
                    placeholder = { Text("http://your-server:8080/upload-audio") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = uiState.uploadApiKey,
                    onValueChange = { viewModel.updateUploadApiKey(it) },
                    label = { Text("Upload API Key") },
                    placeholder = { Text("x-api-key value") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        viewModel.saveSettings()
                        scope.launch {
                            snackbarHostState.showSnackbar("Settings saved")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Settings")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Status info
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "About",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "omi4wOS Companion V1.0.0 (cipioh version)",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Adapted from https://github.com/neurocis/omi4wos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SnackbarHost(hostState = snackbarHostState)
    }
}
