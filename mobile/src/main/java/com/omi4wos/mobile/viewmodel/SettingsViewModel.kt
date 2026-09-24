package com.omi4wos.mobile.viewmodel

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omi4wos.mobile.omi.OmiConfig
import com.omi4wos.mobile.service.InputTextUploadWorker
import com.omi4wos.mobile.service.LocationUploader
import com.omi4wos.mobile.service.PhoneRecordingWatcherService
import com.omi4wos.mobile.service.RecordingWatcherWorker
import com.omi4wos.mobile.storage.StorageUploader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Settings 页 UI 状态。三层结构：
 *   - storageMethod 切换
 *   - 三种存储方案各自独立配置
 *   - 通话录音监听配置
 *
 * 切换 storageMethod 时不清空其他方案的配置, 切回去能恢复。
 */
data class SettingsUiState(
    // 顶层：存储方案
    val storageMethod: OmiConfig.StorageMethod = OmiConfig.StorageMethod.LOCAL_FILE,

    // 本地文件
    val localOutputDir: String = OmiConfig.DEFAULT_LOCAL_OUTPUT_DIR,

    // HTTP
    val httpUploadUrl: String = "",
    val httpApiKey: String = "",

    // S3
    val s3Endpoint: String = "",
    val s3Bucket: String = "",
    val s3AccessKey: String = "",
    val s3SecretKey: String = "",
    val s3Region: String = "",

    // 通话录音监听
    val phoneWatcherEnabled: Boolean = false,
    val phoneWatchDir: String = "",
    val phoneWatchTreeUri: String = "",
    val phoneWatchPatterns: String = OmiConfig.DEFAULT_FILE_PATTERNS,
    val phoneWatchInterval: Int = 60,

    // 定位上报
    val locationEnabled: Boolean = true,
    val locationIntervalMin: Int = 15,

    // 通话时暂停手表录音
    val callPauseEnabled: Boolean = true,

    // 输入文本采集
    val inputTextEnabled: Boolean = false,
    val inputTextUploadEnabled: Boolean = false,

    // 语言（system / en / zh-CN）
    val language: String = OmiConfig.DEFAULT_LANGUAGE,

    // 操作状态
    val isSaving: Boolean = false,
    val saveSuccess: Boolean? = null,
    val isTesting: Boolean = false,
    val testResult: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val omiConfig = OmiConfig(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val config = omiConfig.getConfig()
            _uiState.value = _uiState.value.copy(
                storageMethod = config.storageMethod,
                localOutputDir = config.localFile.outputDir,
                httpUploadUrl = config.http.uploadUrl,
                httpApiKey = config.http.uploadApiKey,
                s3Endpoint = config.s3.endpoint,
                s3Bucket = config.s3.bucket,
                s3AccessKey = config.s3.accessKey,
                s3SecretKey = config.s3.secretKey,
                s3Region = config.s3.region,
                phoneWatcherEnabled = config.phoneWatcher.enabled,
                phoneWatchDir = config.phoneWatcher.watchDir,
                phoneWatchTreeUri = config.phoneWatcher.treeUri,
                phoneWatchPatterns = config.phoneWatcher.filePatterns,
                phoneWatchInterval = config.phoneWatcher.scanIntervalSec,
                locationEnabled = config.location.enabled,
                locationIntervalMin = config.location.intervalMin,
                callPauseEnabled = config.callPause.enabled,
                language = config.language
            )
        }
    }

    // ---- 切换存储方案 ----
    fun updateStorageMethod(method: OmiConfig.StorageMethod) {
        _uiState.value = _uiState.value.copy(storageMethod = method)
    }

    // ---- 语言切换（保存 + 立即应用，AppCompatDelegate 会触发 Activity recreate）----
    fun updateLanguage(language: String) {
        _uiState.value = _uiState.value.copy(language = language)
        viewModelScope.launch {
            val current = omiConfig.getConfig()
            omiConfig.saveConfig(current.copy(language = language))
            applyLanguage(language)
        }
    }

    private fun applyLanguage(language: String) {
        val locales = if (language == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    // ---- 本地文件 ----
    fun updateLocalOutputDir(value: String) {
        _uiState.value = _uiState.value.copy(localOutputDir = value)
    }

    // ---- HTTP ----
    fun updateHttpUploadUrl(value: String) {
        _uiState.value = _uiState.value.copy(httpUploadUrl = value)
    }
    fun updateHttpApiKey(value: String) {
        _uiState.value = _uiState.value.copy(httpApiKey = value)
    }

    // ---- S3 ----
    fun updateS3Endpoint(value: String) {
        _uiState.value = _uiState.value.copy(s3Endpoint = value)
    }
    fun updateS3Bucket(value: String) {
        _uiState.value = _uiState.value.copy(s3Bucket = value)
    }
    fun updateS3AccessKey(value: String) {
        _uiState.value = _uiState.value.copy(s3AccessKey = value)
    }
    fun updateS3SecretKey(value: String) {
        _uiState.value = _uiState.value.copy(s3SecretKey = value)
    }
    fun updateS3Region(value: String) {
        _uiState.value = _uiState.value.copy(s3Region = value)
    }

    // ---- 通话录音监听 ----
    // 开关即时生效: 拨动即保存 enabled 并启动/停止服务, 不依赖全局 Save。
    fun updatePhoneWatcherEnabled(value: Boolean) {
        _uiState.value = _uiState.value.copy(phoneWatcherEnabled = value)
        viewModelScope.launch {
            val current = omiConfig.getConfig()
            omiConfig.saveConfig(
                current.copy(phoneWatcher = current.phoneWatcher.copy(enabled = value))
            )
            val context = getApplication<Application>()
            if (value) {
                PhoneRecordingWatcherService.start(context)
                RecordingWatcherWorker.schedule(context)
            } else {
                PhoneRecordingWatcherService.stop(context)
                RecordingWatcherWorker.cancel(context)
            }
        }
    }
    fun updatePhoneWatchDir(value: String) {
        _uiState.value = _uiState.value.copy(phoneWatchDir = value)
    }
    fun updatePhoneWatchTreeUri(value: String) {
        _uiState.value = _uiState.value.copy(phoneWatchTreeUri = value)
    }
    fun updatePhoneWatchPatterns(value: String) {
        _uiState.value = _uiState.value.copy(phoneWatchPatterns = value)
    }
    fun updatePhoneWatchInterval(value: Int) {
        _uiState.value = _uiState.value.copy(phoneWatchInterval = value)
    }

    // ---- 定位上报 ----
    // 开关即时生效: 拨动即保存并启停定位采样, 不依赖全局 Save。
    fun updateLocationEnabled(value: Boolean) {
        _uiState.value = _uiState.value.copy(locationEnabled = value)
        viewModelScope.launch {
            val current = omiConfig.getConfig()
            omiConfig.saveConfig(
                current.copy(location = current.location.copy(enabled = value))
            )
            val ctx = getApplication<Application>()
            if (value) {
                LocationUploader.get(ctx).applySettings()
            } else {
                LocationUploader.get(ctx).stop()
            }
        }
    }
    fun updateLocationInterval(value: Int) {
        _uiState.value = _uiState.value.copy(locationIntervalMin = value)
        viewModelScope.launch {
            val current = omiConfig.getConfig()
            val min = value.coerceIn(1, 1440)
            omiConfig.saveConfig(
                current.copy(location = current.location.copy(intervalMin = min))
            )
            LocationUploader.get(getApplication<Application>()).applySettings()
        }
    }

    // ---- 通话时暂停手表录音 ----
    // 开关即时生效: 拨动即保存, 手表端监听通过重启 WatchReceiverService 感知
    fun updateCallPauseEnabled(value: Boolean) {
        _uiState.value = _uiState.value.copy(callPauseEnabled = value)
        viewModelScope.launch {
            val current = omiConfig.getConfig()
            omiConfig.saveConfig(
                current.copy(callPause = current.callPause.copy(enabled = value))
            )
        }
    }

    // ---- 输入文本采集 ----
    // 拨动即保存; 采集开关控制无障碍服务采集, 上传开关控制 Worker 周期上传
    fun updateInputTextEnabled(value: Boolean) {
        _uiState.value = _uiState.value.copy(inputTextEnabled = value)
        viewModelScope.launch {
            val current = omiConfig.getConfig()
            omiConfig.saveConfig(
                current.copy(inputText = current.inputText.copy(enabled = value))
            )
            val context = getApplication<Application>()
            if (value && _uiState.value.inputTextUploadEnabled) {
                InputTextUploadWorker.schedule(context)
            } else {
                InputTextUploadWorker.cancel(context)
            }
        }
    }

    fun updateInputTextUploadEnabled(value: Boolean) {
        _uiState.value = _uiState.value.copy(inputTextUploadEnabled = value)
        viewModelScope.launch {
            val current = omiConfig.getConfig()
            omiConfig.saveConfig(
                current.copy(inputText = current.inputText.copy(uploadEnabled = value))
            )
            val context = getApplication<Application>()
            if (value && _uiState.value.inputTextEnabled) {
                InputTextUploadWorker.schedule(context)
            } else {
                InputTextUploadWorker.cancel(context)
            }
        }
    }

    // ---- 保存 ----
    fun saveSettings() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            try {
                val state = _uiState.value
                omiConfig.saveConfig(
                    OmiConfig.Config(
                        storageMethod = state.storageMethod,
                        localFile = OmiConfig.LocalFileConfig(outputDir = state.localOutputDir.trim()),
                        http = OmiConfig.HttpConfig(
                            uploadUrl = state.httpUploadUrl.trim(),
                            uploadApiKey = state.httpApiKey.trim()
                        ),
                        s3 = OmiConfig.S3Config(
                            endpoint = state.s3Endpoint.trim(),
                            bucket = state.s3Bucket.trim(),
                            accessKey = state.s3AccessKey.trim(),
                            secretKey = state.s3SecretKey.trim(),
                            region = state.s3Region.trim()
                        ),
                        phoneWatcher = OmiConfig.PhoneWatcherConfig(
                            enabled = state.phoneWatcherEnabled,
                            watchDir = state.phoneWatchDir.trim(),
                            treeUri = state.phoneWatchTreeUri.trim(),
                            filePatterns = state.phoneWatchPatterns.trim(),
                            scanIntervalSec = state.phoneWatchInterval.coerceIn(15, 3600)
                        ),
                        location = OmiConfig.LocationConfig(
                            enabled = state.locationEnabled,
                            intervalMin = state.locationIntervalMin.coerceIn(1, 1440)
                        ),
                        callPause = OmiConfig.CallPauseConfig(
                            enabled = state.callPauseEnabled
                        ),
                        inputText = OmiConfig.InputTextConfig(
                            enabled = state.inputTextEnabled,
                            uploadEnabled = state.inputTextUploadEnabled
                        ),
                        language = state.language
                    )
                )
                _uiState.value = _uiState.value.copy(isSaving = false, saveSuccess = true)

                // 同步处理监听服务的启停
                val context = getApplication<Application>()
                if (state.phoneWatcherEnabled) {
                    PhoneRecordingWatcherService.start(context)
                    RecordingWatcherWorker.schedule(context)
                } else {
                    PhoneRecordingWatcherService.stop(context)
                    RecordingWatcherWorker.cancel(context)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, saveSuccess = false)
            }
        }
    }

    /**
     * 用当前 UI 状态创建一个临时 StorageUploader 实例进行测试。
     * 先把当前 UI 输入保存进 DataStore, 再 create, 拿到最新配置。
     */
    fun testConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTesting = true, testResult = null)
            try {
                val state = _uiState.value
                omiConfig.saveConfig(
                    OmiConfig.Config(
                        storageMethod = state.storageMethod,
                        localFile = OmiConfig.LocalFileConfig(outputDir = state.localOutputDir.trim()),
                        http = OmiConfig.HttpConfig(
                            uploadUrl = state.httpUploadUrl.trim(),
                            uploadApiKey = state.httpApiKey.trim()
                        ),
                        s3 = OmiConfig.S3Config(
                            endpoint = state.s3Endpoint.trim(),
                            bucket = state.s3Bucket.trim(),
                            accessKey = state.s3AccessKey.trim(),
                            secretKey = state.s3SecretKey.trim(),
                            region = state.s3Region.trim()
                        ),
                        phoneWatcher = OmiConfig.PhoneWatcherConfig(
                            enabled = state.phoneWatcherEnabled,
                            watchDir = state.phoneWatchDir.trim(),
                            treeUri = state.phoneWatchTreeUri.trim(),
                            filePatterns = state.phoneWatchPatterns.trim(),
                            scanIntervalSec = state.phoneWatchInterval.coerceIn(15, 3600)
                        ),
                        location = OmiConfig.LocationConfig(
                            enabled = state.locationEnabled,
                            intervalMin = state.locationIntervalMin.coerceIn(1, 1440)
                        ),
                        callPause = OmiConfig.CallPauseConfig(
                            enabled = state.callPauseEnabled
                        ),
                        language = state.language
                    )
                )

                val uploader = StorageUploader.create(getApplication())
                val (success, message) = uploader.testConnection()
                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    testResult = if (success) "OK: $message" else "FAIL: $message"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    testResult = "Error: ${e.message}"
                )
            }
        }
    }
}
