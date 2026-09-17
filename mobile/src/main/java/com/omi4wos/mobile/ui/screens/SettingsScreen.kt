package com.omi4wos.mobile.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.omi4wos.mobile.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

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

        // === 存储测试 / 全量保存（放在页面顶部，避免被误认为 HTTP 专用）===
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

        Text(
            text = context.getString(R.string.settings_save_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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

        // （Test / Save 已移至页面顶部，作用于全部设置）
        Spacer(modifier = Modifier.height(24.dp))

        // === 4. 通话录音监听 ===
        PhoneWatcherCard(
            uiState = uiState,
            onEnabledChange = viewModel::updatePhoneWatcherEnabled,
            onPickDir = {
                // 若已选过目录, 打开 picker 时直达该文件夹而非根目录, 减少全量枚举导致的延迟
                pickDirLauncher.launch(initialTreeDocumentUri(uiState.phoneWatchTreeUri))
            },
            onClearTreeUri = { viewModel.updatePhoneWatchTreeUri("") },
            onPatternsChange = viewModel::updatePhoneWatchPatterns,
            onIntervalChange = viewModel::updatePhoneWatchInterval
        )

        Spacer(modifier = Modifier.height(12.dp))

        // === 5. 定位上报 ===
        LocationSettingsCard(
            uiState = uiState,
            onEnabledChange = viewModel::updateLocationEnabled,
            onIntervalChange = viewModel::updateLocationInterval
        )

        Spacer(modifier = Modifier.height(12.dp))

        // === 6. 保活引导 ===
        KeepAliveCard(
            context = context,
            snackbarHostState = snackbarHostState
        )

        Spacer(modifier = Modifier.height(16.dp))

        // === 7. About ===
        AboutCard()

        Spacer(modifier = Modifier.height(16.dp))

        // === 8. 语言切换（紧凑，底部） ===
        LanguageCard(
            uiState = uiState,
            onLanguageSelected = viewModel::updateLanguage
        )

        Spacer(modifier = Modifier.height(16.dp))

        SnackbarHost(hostState = snackbarHostState)
    }
}

@Composable
private fun LanguageCard(
    uiState: com.omi4wos.mobile.viewmodel.SettingsUiState,
    onLanguageSelected: (String) -> Unit
) {
    val context = LocalContext.current
    var pendingRestart by remember { mutableStateOf(false) }

    // 仅提供英文 / 中文。其他语言未翻译, 不提供"跟随系统"。
    val languages = listOf(
        "en" to context.getString(R.string.language_english),
        "zh-CN" to context.getString(R.string.language_chinese)
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = context.getString(R.string.language_title) + ":",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(12.dp))
                languages.forEach { (code, label) ->
                    val selected = uiState.language == code
                    FilterChip(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                onLanguageSelected(code)
                                pendingRestart = true
                            }
                        },
                        label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
        }
    }

    // 选中语言后弹框确认重启
    if (pendingRestart) {
        AlertDialog(
            onDismissRequest = { pendingRestart = false },
            title = { Text(context.getString(R.string.language_restart_title)) },
            text = { Text(context.getString(R.string.language_restart_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestart = false
                    restartApp(context)
                }) { Text(context.getString(R.string.language_restart_now)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestart = false }) {
                    Text(context.getString(R.string.language_restart_later))
                }
            }
        )
    }
}

/** 重启应用以让语言切换生效 */
private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

/**
 * 把 SAF tree URI 转成可读目录路径。
 * content://.../tree/primary%3ARecord%2FCallRecord → /Record/CallRecord
 * URI 为空 → 返回 [noneText]。
 * 识别失败 → 原样返回 URI。
 */
private fun treeUriToDisplay(uri: String, noneText: String): String {
    if (uri.isBlank()) return noneText
    return try {
        val seg = Uri.parse(uri).lastPathSegment ?: return uri
        val decoded = java.net.URLDecoder.decode(seg, "UTF-8")
        val rel = decoded.substringAfter(":", decoded)
        "/" + rel.trim('/')
    } catch (_: Exception) {
        uri
    }
}

/**
 * 从已保存的 tree URI 构造一个可直达该文件夹的 document URI，
 * 作为 OpenDocumentTree 的初始位置。无法解析时返回 null（picker 回退到默认位置）。
 */
private fun initialTreeDocumentUri(treeUri: String): Uri? {
    if (treeUri.isBlank()) return null
    return try {
        val uri = Uri.parse(treeUri)
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        DocumentsContract.buildDocumentUriUsingTree(uri, treeId)
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun StorageMethodCard(
    uiState: com.omi4wos.mobile.viewmodel.SettingsUiState,
    onMethodSelected: (OmiConfig.StorageMethod) -> Unit
) {
            val context = LocalContext.current
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
            val context = LocalContext.current
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
            val context = LocalContext.current
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
        var showHttpKey by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = uiState.httpApiKey,
            onValueChange = onKeyChange,
            label = { Text(context.getString(R.string.http_api_key)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showHttpKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showHttpKey = !showHttpKey }) {
                    Icon(
                        imageVector = if (showHttpKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showHttpKey) "Hidden" else "Visible"
                    )
                }
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "multipart/form-data POST, field name \"file\".",
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
            val context = LocalContext.current
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
    onPickDir: () -> Unit,
    onClearTreeUri: () -> Unit,
    onPatternsChange: (String) -> Unit,
    onIntervalChange: (Int) -> Unit
) {
            val context = LocalContext.current
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

                Spacer(modifier = Modifier.height(12.dp))

                // 只读展示所选目录（SAF 授权路径, 只看不可改）
                Text(
                    text = context.getString(R.string.phone_watcher_selected) + ":",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = treeUriToDisplay(
                        uiState.phoneWatchTreeUri,
                        context.getString(R.string.phone_watcher_none)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (uiState.phoneWatchTreeUri.isNotEmpty())
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
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
private fun LocationSettingsCard(
    uiState: com.omi4wos.mobile.viewmodel.SettingsUiState,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalChange: (Int) -> Unit
) {
    val context = LocalContext.current
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
                    text = context.getString(R.string.location_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = uiState.locationEnabled,
                    onCheckedChange = onEnabledChange
                )
            }
            if (uiState.locationEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = context.getString(R.string.location_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = uiState.locationIntervalMin.toString(),
                    onValueChange = { v ->
                        v.toIntOrNull()?.let { onIntervalChange(it) }
                    },
                    label = { Text(context.getString(R.string.location_interval)) },
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
            val context = LocalContext.current
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

            // 2. 厂商自启动设置（点击时实时解析多候选 Intent，绕过 remember 缓存）
            OutlinedButton(
                onClick = {
                    val opened = BatteryOptimizationHelper.openAutoStartSettings(context)
                    if (!opened) {
                        // 厂商自启动页打不开 → 明确提示手动路径, 避免和"应用信息"按钮混淆
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.keepalive_autostart_unavailable_manual)
                            )
                        }
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
            val context = LocalContext.current
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
            val context = LocalContext.current
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






