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
import com.omi4wos.mobile.service.AudioReceiverService
import com.omi4wos.mobile.service.AudioUploadService
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
    val watchConnected: Boolean = false,
    val watchBatteryLevel: Int = -1,
    val totalUploads: Int = 0,
    val uploadFailures: Int = 0,
    val pendingBytes: Long = 0,
    val recentSyncs: List<SyncSummary> = emptyList(),
    val storageMethod: String = "LOCAL_FILE"
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    companion object { private const val TAG = "HomeViewModel" }

    private val repository = UploadRepository.getInstance(application)
    private val messageClient = Wearable.getMessageClient(application)
    private val nodeClient = Wearable.getNodeClient(application)

    // Direct listener — catches messages when WearableListenerService is not triggered (Samsung)
    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        Log.d(TAG, "Direct message received: ${event.path} size=${event.data.size}")
        AudioReceiverService.processMessage(getApplication(), event.path, event.data)
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    init {
        messageClient.addListener(messageListener)
        observeState()
        queryWatchRecordingState()
        retryPendingUploads()
        viewModelScope.launch {
            val cfg = OmiConfig(getApplication()).getConfig()
            _uiState.value = _uiState.value.copy(storageMethod = cfg.storageMethod.name)
        }
        // 定期检查手表连接状态 (每 5 秒)
        viewModelScope.launch {
            while (true) {
                val connected = runCatching {
                    nodeClient.connectedNodes.await().isNotEmpty()
                }.getOrDefault(false)
                _uiState.value = _uiState.value.copy(watchConnected = connected)
                delay(5_000)
            }
        }
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
            runUploadRetry(getApplication())
        }
    }

    fun startWatchRecording() {
        // 先检查手表是否连接, 没连就不更新 UI 也不发命令
        viewModelScope.launch {
            val nodes = runCatching { nodeClient.connectedNodes.await() }.getOrDefault(emptyList())
            if (nodes.isEmpty()) {
                Log.w(TAG, "No watch connected, cannot start recording")
                return@launch
            }
            _uiState.value = _uiState.value.copy(watchRecordingEnabled = true)
            runCatching {
                messageClient.sendMessage(
                    nodes.first().id,
                    DataLayerPaths.AUDIO_CONTROL_PATH,
                    DataLayerPaths.CMD_START_RECORDING.toByteArray(Charsets.UTF_8)
                ).await()
            }.onFailure { Log.e(TAG, "Failed to send CMD_START_RECORDING", it) }
        }
    }

    fun stopWatchRecording() {
        viewModelScope.launch {
            val nodes = runCatching { nodeClient.connectedNodes.await() }.getOrDefault(emptyList())
            if (nodes.isEmpty()) {
                Log.w(TAG, "No watch connected, cannot stop recording")
                return@launch
            }
            _uiState.value = _uiState.value.copy(watchRecordingEnabled = false)
            runCatching {
                messageClient.sendMessage(
                    nodes.first().id,
                    DataLayerPaths.AUDIO_CONTROL_PATH,
                    DataLayerPaths.CMD_STOP_RECORDING.toByteArray(Charsets.UTF_8)
                ).await()
            }.onFailure { Log.e(TAG, "Failed to send CMD_STOP_RECORDING", it) }
        }
    }

    private fun queryWatchRecordingState() {
        sendWatchCommand(DataLayerPaths.CMD_STATUS_REQUEST)
    }

    /** HomeScreen 每次进入 (resume) 时调用, 主动向手表查询最新录音状态,
     *  避免 StateFlow 初始值 false 导致按钮状态假跳。 */
    fun refreshWatchState() {
        queryWatchRecordingState()
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
