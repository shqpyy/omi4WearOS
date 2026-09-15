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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omi4wos.mobile.data.SyncSummary
import com.omi4wos.mobile.service.WatcherStatus
import com.omi4wos.mobile.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                    if (!uiState.watchConnected) {
                        Text(
                            text = "Watch not connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB71C1C)
                        )
                    } else if (uiState.isReceivingAudio) {
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
                    enabled = uiState.watchConnected,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.watchRecordingEnabled)
                            Color(0xFFB71C1C) else Color(0xFF1B5E20),
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
                            watcherStatus.watchDir.ifBlank { "-" },
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

        // [未上传成功的提示] 有积压时显示醒目警示卡片, 0 积压则不显示
        val hasPending = uiState.uploadFailures > 0 || uiState.pendingBytes > 0L
        if (hasPending) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                // 改用 MaterialTheme 语义色, 深色模式自动适配, 不再扎眼
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
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.home_upload_retry))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.recentSyncs.isEmpty()) {
            Text(
                text = stringResource(R.string.home_no_syncs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn {
                items(uiState.recentSyncs) { sync ->
                    val storageLabel = when (uiState.storageMethod) {
                        "HTTP" -> "your HTTP server"
                        "S3" -> "S3 storage"
                        else -> "local storage"
                    }
                    SyncCard(sync, storageLabel)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SyncCard(sync: SyncSummary, storageLabel: String) {
    val dateFmt = SimpleDateFormat("MM/dd/yy hh:mma", Locale.getDefault())
    val timeFmt = SimpleDateFormat("hh:mma", Locale.getDefault())

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
                text = "Uploaded to $storageLabel: $sizeStr  ($segStr)",
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
