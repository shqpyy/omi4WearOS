package com.omi4wos.mobile.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.omi4wos.mobile.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omi4wos.mobile.omi.OmiConfig
import com.omi4wos.mobile.service.BatteryOptimizationHelper
import com.omi4wos.mobile.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // SAF 选目录启动器
    val pickDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // 保持持久授权
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            viewModel.updatePhoneWatchTreeUri(uri.toString())
        }
    }

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

        // === 1. 存储方案选择 ===
        StorageMethodCard(
            uiState = uiState,
            onMethodSelected = viewModel::updateStorageMethod
        )

        Spacer(modifier = Modifier.height(12.dp))

        // === 2. 对应方案的配置项 ===
        when (uiState.storageMethod) {
            OmiConfig.StorageMethod.LOCAL_FILE -> LocalFileConfigCard(
                uiState = uiState,
                onOutputDirChange = viewModel::updateLocalOutputDir
            )
            OmiConfig.StorageMethod.HTTP -> HttpConfigCard(
                uiState = uiState,
                onUrlChange = viewModel::updateHttpUploadUrl,
                onKeyChange = viewModel::updateHttpApiKey
            )
            OmiConfig.StorageMethod.S3 -> S3ConfigCard(
                uiState = uiState,
                onEndpointChange = viewModel::updateS3Endpoint,
                onBucketChange = viewModel::updateS3Bucket,
                onAccessKeyChange = viewModel::updateS3AccessKey,
                onSecretKeyChange = viewModel::updateS3SecretKey,
                onRegionChange = viewModel::updateS3Region
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // === 3. Test / Save 按钮 ===
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    viewModel.testConnection()
                    scope.launch {
                        val result = uiState.testResult
                        if (result != null) snackbarHostState.showSnackbar(result)
                    }
                },
                enabled = !uiState.isTesting,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (uiState.isTesting) context.getString(R.string.testing) else context.getString(R.string.test_connection))
            }
            Button(
                onClick = {
                    viewModel.saveSettings()
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (uiState.saveSuccess == true) context.getString(R.string.saved) else context.getString(R.string.saving)
                        )
                    }
                },
                enabled = !uiState.isSaving,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (uiState.isSaving) context.getString(R.string.saving) else context.getString(R.string.save))
            }
        }

        uiState.testResult?.let { result ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = result,
                style = MaterialTheme.typography.bodySmall,
                color = if (result.startsWith("OK")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // === 4. 通话录音监听 ===
        PhoneWatcherCard(
            uiState = uiState,
            onEnabledChange = viewModel::updatePhoneWatcherEnabled,
            onDirChange = viewModel::updatePhoneWatchDir,
            onPickDir = { pickDirLauncher.launch(null) },
            onClearTreeUri = { viewModel.updatePhoneWatchTreeUri("") },
            onPatternsChange = viewModel::updatePhoneWatchPatterns,
            onIntervalChange = viewModel::updatePhoneWatchInterval
        )

        Spacer(modifier = Modifier.height(12.dp))

        // === 5. 保活引导 ===
        KeepAliveCard(
            context = context,
            snackbarHostState = snackbarHostState
        )

        Spacer(modifier = Modifier.height(16.dp))

        // === 6. About ===
        AboutCard()

        Spacer(modifier = Modifier.height(16.dp))

        SnackbarHost(hostState = snackbarHostState)
    }
}

@Composable
private fun StorageMethodCard(
    uiState: com.omi4wos.mobile.viewmodel.SettingsUiState,
    onMethodSelected: (OmiConfig.StorageMethod) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = context.getString(R.string.storage_method),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            OmiConfig.StorageMethod.values().forEach { method ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(
                        selected = uiState.storageMethod == method,
                        onClick = { onMethodSelected(method) }
                    )
                    Text(
                        text = when (method) {
                            OmiConfig.StorageMethod.LOCAL_FILE -> context.getString(R.string.storage_local)
                            OmiConfig.StorageMethod.HTTP -> context.getString(R.string.storage_http)
                            OmiConfig.StorageMethod.S3 -> context.getString(R.string.storage_s3)
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Switch method anytime — other methods' configs are preserved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LocalFileConfigCard(
    uiState: com.omi4wos.mobile.viewmodel.SettingsUiState,
    onOutputDirChange: (String) -> Unit
) {
    ConfigCard(title = context.getString(R.string.local_config_title)) {
        OutlinedTextField(
            value = uiState.localOutputDir,
            onValueChange = onOutputDirChange,
            label = { Text(context.getString(R.string.local_output_dir)) },
            placeholder = { Text("/storage/emulated/0/omi4wos") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = context.getString(R.string.local_files_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HttpConfigCard(
    uiState: com.omi4wos.mobile.viewmodel.SettingsUiState,
    onUrlChange: (String) -> Unit,
    onKeyChange: (String) -> Unit
) {
    ConfigCard(title = context.getString(R.string.http_config_title)) {
        OutlinedTextField(
            value = uiState.httpUploadUrl,
            onValueChange = onUrlChange,
            label = { Text(context.getString(R.string.http_upload_url)) },
            placeholder = { Text(context.getString(R.string.http_upload_url_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.httpApiKey,
            onValueChange = onKeyChange,
            label = { Text(context.getString(R.string.http_api_key)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "multipart/form-data POST, field name \"files\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun S3ConfigCard(
    uiState: com.omi4wos.mobile.viewmodel.SettingsUiState,
    onEndpointChange: (String) -> Unit,
    onBucketChange: (String) -> Unit,
    onAccessKeyChange: (String) -> Unit,
    onSecretKeyChange: (String) -> Unit,
    onRegionChange: (String) -> Unit
) {
    ConfigCard(title = context.getString(R.string.s3_config_title)) {
        Text(
            text = "Works with: Tencent COS / Cloudflare R2 / AWS S3 / MinIO / Aliyun OSS / Backblaze B2.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.s3Endpoint,
            onValueChange = onEndpointChange,
            label = { Text(context.getString(R.string.s3_endpoint)) },
            placeholder = { Text(context.getString(R.string.s3_endpoint_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.s3Bucket,
            onValueChange = onBucketChange,
            label = { Text(context.getString(R.string.s3_bucket)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.s3AccessKey,
            onValueChange = onAccessKeyChange,
            label = { Text(context.getString(R.string.s3_access_key)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.s3SecretKey,
            onValueChange = onSecretKeyChange,
            label = { Text(context.getString(R.string.s3_secret_key)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = uiState.s3Region,
            onValueChange = onRegionChange,
            label = { Text(context.getString(R.string.s3_region)) },
            placeholder = { Text(context.getString(R.string.s3_region_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

@Composable
private fun PhoneWatcherCard(
    uiState: com.omi4wos.mobile.viewmodel.SettingsUiState,
    onEnabledChange: (Boolean) -> Unit,
    onDirChange: (String) -> Unit,
    onPickDir: () -> Unit,
    onClearTreeUri: () -> Unit,
    onPatternsChange: (String) -> Unit,
    onIntervalChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = context.getString(R.string.phone_watcher_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = uiState.phoneWatcherEnabled,
                    onCheckedChange = onEnabledChange
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = context.getString(R.string.phone_watcher_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (uiState.phoneWatcherEnabled) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Directory (SAF preferred — picks via system file picker, persists across reboots)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onPickDir,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (uiState.phoneWatchTreeUri.isEmpty()) context.getString(R.string.phone_watcher_pick) else "Picked ✓")
                    }
                    if (uiState.phoneWatchTreeUri.isNotEmpty()) {
                        OutlinedButton(onClick = onClearTreeUri) {
                            Text("Clear")
                        }
                    }
                }

                if (uiState.phoneWatchTreeUri.isNotEmpty()) {
                    Text(
                        text = "URI: ${uiState.phoneWatchTreeUri.take(80)}${if (uiState.phoneWatchTreeUri.length > 80) "..." else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = context.getString(R.string.phone_watcher_or_path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.phoneWatchDir,
                    onValueChange = onDirChange,
                    label = { Text(context.getString(R.string.phone_watcher_dir)) },
                    placeholder = { Text("/sdcard/Record/CallRecord/") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = uiState.phoneWatchPatterns,
                    onValueChange = onPatternsChange,
                    label = { Text(context.getString(R.string.phone_watcher_patterns)) },
                    placeholder = { Text("*.amr;*.m4a;*.mp3;*.aac;*.opus") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = uiState.phoneWatchInterval.toString(),
                    onValueChange = { v ->
                        v.toIntOrNull()?.let { onIntervalChange(it) }
                    },
                    label = { Text(context.getString(R.string.phone_watcher_interval)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        }
    }
}

@Composable
private fun KeepAliveCard(
    context: android.content.Context,
    snackbarHostState: SnackbarHostState
) {
    val scope = rememberCoroutineScope()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = context.getString(R.string.keepalive_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = context.getString(R.string.keepalive_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 1. 电池优化白名单
            val isWhitelisted = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
            Button(
                onClick = {
                    val activity = context as? Activity
                    if (activity != null) {
                        BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(activity)
                    } else {
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.phone_watcher_saf_hint)) }
                    }
                },
                enabled = !isWhitelisted,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isWhitelisted) context.getString(R.string.keepalive_whitelist_granted) else context.getString(R.string.keepalive_whitelist_grant))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 2. 厂商自启动设置
            val autoStartIntent = remember(context) {
                BatteryOptimizationHelper.getManufacturerAutoStartIntent(context)
            }
            OutlinedButton(
                onClick = {
                    val intent = autoStartIntent
                        ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:${context.packageName}"))
                    runCatching { context.startActivity(intent) }
                        .onFailure {
                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.test_fail, it.message)) }
                        }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                val mfr = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
                Text(context.getString(R.string.keepalive_autostart, mfr))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 3. 应用详情（电池/存储权限兜底）
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${context.packageName}"))
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(context.getString(R.string.keepalive_appinfo))
            }
        }
    }
}

@Composable
private fun AboutCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.about_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.about_version),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(R.string.about_adapted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConfigCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}





