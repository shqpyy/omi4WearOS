package com.omi4wos.mobile.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.res.painterResource
import com.omi4wos.mobile.R
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omi4wos.mobile.data.SyncSummary
import com.omi4wos.mobile.omi.OmiConfig
import com.omi4wos.mobile.service.WatcherStatus
import com.omi4wos.mobile.viewmodel.HomeViewModel
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val watcherStatus by WatcherStatus.status.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.omi4wearos_logo_title),
            contentDescription = "omi4wearOS",
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Watch control card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Watch Recording Control",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (uiState.isReceivingAudio) {
                        Text(
                            text = "Receiving audio…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
                Button(
                    onClick = {
                        if (uiState.watchRecordingEnabled) viewModel.stopWatchRecording()
                        else viewModel.startWatchRecording()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.watchRecordingEnabled)
                            Color(0xFFB71C1C) else Color(0xFF1B5E20)
                    )
                ) {
                    Icon(
                        imageVector = if (uiState.watchRecordingEnabled) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (uiState.watchRecordingEnabled) "Stop" else "Start")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // [通话录音监听诊断] 无需 adb, 直接看最近一次扫描结果
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.home_watcher_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (!watcherStatus.enabled) {
                    Text(
                        text = stringResource(R.string.home_watcher_off),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB71C1C)
                    )
                } else {
                    val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                    Text(
                        text = stringResource(
                            R.string.home_watcher_summary,
                            watcherStatus.scannedToday,
                            watcherStatus.uploadedOk,
                            watcherStatus.uploadFailed
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = stringResource(
                            R.string.home_watcher_last,
                            if (watcherStatus.lastScanTime > 0L)
                                timeFmt.format(Date(watcherStatus.lastScanTime))
                            else "-"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    watcherStatus.lastError?.let { err ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.home_watcher_error, err),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB71C1C)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LocationCard(viewModel = viewModel, uiState = uiState)

        uiState.lastCrash?.let { crash ->
            Spacer(modifier = Modifier.height(8.dp))
            CrashCard(crash = crash, onClear = { viewModel.clearCrash() })
        }

        Spacer(modifier = Modifier.height(8.dp))

        // [未上传成功的提示] 有积压时显示醒目警示卡片
        val hasPending = uiState.uploadFailures > 0 || uiState.pendingBytes > 0L
        if (hasPending) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.home_upload_pending_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.home_upload_pending_desc,
                            uiState.uploadFailures,
                            formatSize(uiState.pendingBytes)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.home_upload_pending_keep),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.retryPendingUploads() },
                        enabled = !uiState.isRetrying,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        if (uiState.isRetrying) {
                            Text("Retrying...")
                        } else {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.home_upload_retry))
                        }
                    }

                    uiState.retryResult?.let { result ->
                        Spacer(modifier = Modifier.height(6.dp))
                        val isOk = result.startsWith("Succeeded") || result.startsWith("Partial")
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isOk) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.home_upload_all_done),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF4CAF50)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.recentSyncs.isEmpty()) {
            Text(
                text = "No syncs yet. Uploads will appear here after the watch syncs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn {
                items(uiState.recentSyncs) { sync ->
                    SyncCard(sync, uiState.storageMethod)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun LocationCard(viewModel: HomeViewModel, uiState: com.omi4wos.mobile.viewmodel.HomeUiState) {
    val context = LocalContext.current
    val loc = uiState.location
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val badColor = Color(0xFFB71C1C)
    val okColor = Color(0xFF4CAF50)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.home_location_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))

            when {
                !loc.hasPermission -> {
                    Text(
                        text = stringResource(R.string.home_location_off),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = badColor
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.home_location_off_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                !loc.hasBackgroundPermission -> {
                    Text(
                        text = stringResource(R.string.home_location_bg_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFA000)
                    )
                }

                !loc.endpointConfigured -> {
                    Text(
                        text = stringResource(R.string.home_location_not_configured),
                        style = MaterialTheme.typography.bodySmall,
                        color = badColor
                    )
                }

                else -> {
                    val sourceLabel = loc.lastSource.ifBlank { "-" }
                    Text(
                        text = if (loc.lastLat != null && loc.lastLon != null) {
                            stringResource(
                                R.string.home_location_summary,
                                sourceLabel,
                                "${loc.lastAccuracyM.roundToInt()}m",
                                loc.uploadedOk,
                                loc.uploadFailed
                            )
                        } else {
                            stringResource(
                                R.string.home_location_summary,
                                "-", "-", loc.uploadedOk, loc.uploadFailed
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    if (loc.lastLat != null && loc.lastLon != null) {
                        Text(
                            text = stringResource(
                                R.string.home_location_coords,
                                loc.lastLat, loc.lastLon, sourceLabel
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.home_location_last_ok,
                            if (loc.lastSuccessAt > 0L)
                                timeFmt.format(Date(loc.lastSuccessAt))
                            else stringResource(R.string.home_location_never)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = okColor
                    )
                }
            }

            loc.lastError?.let { err ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.home_location_error, err),
                    style = MaterialTheme.typography.bodySmall,
                    color = badColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!loc.hasPermission || !loc.hasBackgroundPermission) {
                    Button(onClick = {
                        if (!loc.hasPermission) {
                            // 前台权限还没给 —— 直接弹系统权限框
                            val activity = context as? android.app.Activity
                            if (activity != null) {
                                androidx.core.app.ActivityCompat.requestPermissions(
                                    activity,
                                    arrayOf(
                                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                                    ),
                                    4102
                                )
                            }
                        } else {
                            // 后台定位（「始终允许」）只能在系统设置里选，Android 11+ 无独立弹窗
                            try {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            } catch (_: Exception) {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                )
                            }
                        }
                    }) {
                        Text(stringResource(R.string.home_location_grant))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
        }
    }
}

@Composable
private fun CrashCard(crash: String, onClear: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.home_crash_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Button(onClick = onClear) {
                    Text(stringResource(R.string.home_crash_clear))
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.home_crash_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = crash,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun SyncCard(sync: SyncSummary, storageMethod: OmiConfig.StorageMethod) {
    val dateFmt = SimpleDateFormat("MM/dd/yy HH:mm", Locale.getDefault())
    val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    val allUploaded = sync.failedCount == 0
    val statusText = if (allUploaded) "✓ Uploaded" else "⏳ ${sync.failedCount} failed"
    val statusColor = if (allUploaded) Color(0xFF4CAF50) else Color(0xFFFFA000)

    val batteryStr = if (sync.batteryLevel >= 0) "${sync.batteryLevel}%" else "?%"
    val sizeStr = formatSize(sync.totalBytes)
    val segStr = if (sync.segmentCount == 1) "1 segment" else "${sync.segmentCount} segments"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${timeFmt.format(Date(sync.syncTime))}  |  Watch Battery: $batteryStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when (storageMethod) {
                    OmiConfig.StorageMethod.HTTP ->
                        stringResource(R.string.home_storage_target_http, sizeStr, segStr)
                    OmiConfig.StorageMethod.S3 ->
                        stringResource(R.string.home_storage_target_s3, sizeStr, segStr)
                    OmiConfig.StorageMethod.LOCAL_FILE ->
                        stringResource(R.string.home_storage_target_local, sizeStr, segStr)
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Spanning ${dateFmt.format(Date(sync.earliestMs))} to ${dateFmt.format(Date(sync.latestMs))}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024L      -> "$bytes B"
    bytes < 1_048_576L -> "${"%.1f".format(bytes / 1024.0)} KB"
    else               -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
}
