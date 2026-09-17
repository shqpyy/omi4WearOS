package com.omi4wos.mobile.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.omi4wos.mobile.data.SyncSummary
import com.omi4wos.mobile.data.UploadRepository
import com.omi4wos.mobile.omi.OmiConfig
import com.omi4wos.mobile.service.AppLog
import com.omi4wos.mobile.service.AudioReceiverService
import com.omi4wos.mobile.service.AudioUploadService
import com.omi4wos.mobile.service.CrashLogger
import com.omi4wos.mobile.service.LocationStatus
import com.omi4wos.mobile.service.LocationUploadStatus
import com.omi4wos.mobile.service.LocationUploader
import com.omi4wos.mobile.service.runUploadRetry
import com.omi4wos.mobile.storage.HttpUploader
import com.omi4wos.shared.DataLayerPaths
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class HomeUiState(
    val isReceivingAudio: Boolean = false,
    val watchRecordingEnabled: Boolean = false,
    val watchBatteryLevel: Int = -1,
    val totalUploads: Int = 0,
    val uploadFailures: Int = 0,
    val pendingBytes: Long = 0,
    val recentSyncs: List<SyncSummary> = emptyList(),
    val storageMethod: OmiConfig.StorageMethod = OmiConfig.StorageMethod.LOCAL_FILE,
    val location: LocationUploadStatus = LocationUploadStatus(),
    val lastCrash: String? = null,
    val logSize: String = "-",

    /** 重试状态 */
    val isRetrying: Boolean = false,
    val retryResult: String? = null
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    companion object { private const val TAG = "HomeViewModel" }

    private val repository = UploadRepository.getInstance(application)
    private val messageClient = Wearable.getMessageClient(application)
    private val nodeClient = Wearable.getNodeClient(application)

    // Direct listener — catches messages when WearableListenerService is not triggered (Samsung)
    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        Log.d(TAG, "Direct message received: ${event.path} size=${event.data.size}")
        // 监听器回调里未捕获的异常会直接杀死进程（用户没点任何按钮也会闪退）
        runCatching {
            AudioReceiverService.processMessage(getApplication(), event.path, event.data)
        }.onFailure {
            Log.e(TAG, "processMessage failed", it)
            AppLog.e(TAG, "处理手表消息失败(界面监听): ${event.path}", it)
        }
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    init {
        messageClient.addListener(messageListener)
        observeState()
        queryWatchRecordingState()
        retryPendingUploads()
        // 读取存储方式, 用于首页"已上传到…"的动态文案
        viewModelScope.launch {
            val method = OmiConfig(getApplication()).getConfig().storageMethod
            _uiState.value = _uiState.value.copy(storageMethod = method)
        }
        // 定位上报状态 (权限/最近成败/坐标)
        viewModelScope.launch {
            LocationStatus.status.collect { status ->
                _uiState.value = _uiState.value.copy(location = status)
            }
        }
        viewModelScope.launch { LocationUploader.refreshStatus(getApplication()) }
        // 上次崩溃的堆栈(如果有), 直接摆在首页便于远程排错
        _uiState.value = _uiState.value.copy(lastCrash = CrashLogger.last(getApplication()))
        refreshLogInfo()
    }

    /** 刷新日志体积，展示在首页「应用日志」卡片。 */
    private fun refreshLogInfo() {
        val app = getApplication<Application>()
        _uiState.value = _uiState.value.copy(logSize = formatKb(AppLog.totalBytes(app)))
    }

    /** 导出日志（系统分享）。无日志时内部会提示，返回 false。 */
    fun exportLog() {
        val app = getApplication<Application>()
        CrashLogger.markStep(app, "点击 导出日志")
        AppLog.share(app)
        refreshLogInfo()
    }

    /** 清空日志文件。 */
    fun clearLog() {
        val app = getApplication<Application>()
        AppLog.clear(app)
        refreshLogInfo()
    }

    private fun formatKb(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1048576L -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
    }

    override fun onCleared() {
        messageClient.removeListener(messageListener)
        super.onCleared()
    }

    private fun observeState() {
        viewModelScope.launch {
            AudioReceiverService.isReceivingAudio.collect { receiving ->
                _uiState.value = _uiState.value.copy(isReceivingAudio = receiving)
            }
        }
        viewModelScope.launch {
            AudioReceiverService.watchBatteryLevel.collect { level ->
                _uiState.value = _uiState.value.copy(watchBatteryLevel = level)
            }
        }
        viewModelScope.launch {
            AudioReceiverService.watchRecordingEnabled.collect { enabled ->
                _uiState.value = _uiState.value.copy(watchRecordingEnabled = enabled)
            }
        }

        repository.getRecentSyncSummaries(20)
            .onEach { syncs ->
                _uiState.value = _uiState.value.copy(recentSyncs = syncs)
            }
            .launchIn(viewModelScope)

        combine(
            repository.getTotalCount(),
            repository.getUploadedCount()
        ) { total, uploaded ->
            Pair(total, (total - uploaded).coerceAtLeast(0))
        }.onEach { (total, failures) ->
            _uiState.value = _uiState.value.copy(
                totalUploads = total,
                uploadFailures = failures
            )
        }.launchIn(viewModelScope)

        // 周期性扫描待上传音频的占用空间 (轻量目录扫描, 5s 一次)
        viewModelScope.launch {
            while (true) {
                val bytes = HttpUploader.getPendingBytes(getApplication())
                if (_uiState.value.pendingBytes != bytes) {
                    _uiState.value = _uiState.value.copy(pendingBytes = bytes)
                }
                delay(5_000)
            }
        }
    }

    fun retryPendingUploads() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRetrying = true, retryResult = null)
            try {
                val result = runUploadRetry(getApplication()) { msg ->
                    _uiState.value = _uiState.value.copy(retryResult = msg)
                }
                _uiState.value = _uiState.value.copy(
                    isRetrying = false,
                    retryResult = result.message
                )
                // 成功后延迟清掉提示，让用户看到"Succeeded"
                if (result.anySucceeded) {
                    delay(2_000)
                    _uiState.value = _uiState.value.copy(retryResult = null)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isRetrying = false,
                    retryResult = "Retry failed: ${e.message}"
                )
            }
        }
    }

    /** 清除已展示的崩溃日志。 */
    fun clearCrash() {
        CrashLogger.clear(getApplication())
        _uiState.value = _uiState.value.copy(lastCrash = null)
    }

    fun startWatchRecording() {
        _uiState.value = _uiState.value.copy(watchRecordingEnabled = true)
        sendWatchCommand(DataLayerPaths.CMD_START_RECORDING)
    }

    fun stopWatchRecording() {
        _uiState.value = _uiState.value.copy(watchRecordingEnabled = false)
        sendWatchCommand(DataLayerPaths.CMD_STOP_RECORDING)
    }

    private fun queryWatchRecordingState() {
        sendWatchCommand(DataLayerPaths.CMD_STATUS_REQUEST)
    }

    private fun sendWatchCommand(command: String) {
        viewModelScope.launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                val watch = nodes.firstOrNull()
                    ?: run { Log.w(TAG, "No watch connected, cannot send: $command"); return@launch }
                messageClient.sendMessage(
                    watch.id,
                    DataLayerPaths.AUDIO_CONTROL_PATH,
                    command.toByteArray(Charsets.UTF_8)
                ).await()
                Log.i(TAG, "Sent '$command' to ${watch.displayName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send '$command'", e)
            }
        }
    }
}
