package com.omi4wos.mobile.viewmodel

import android.app.Application
import android.util.Log
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
    /** 停止打字阈值（ms）；设置页以「秒」展示，范围 MIN_QUIET_MS..MAX_QUIET_MS */
    val inputTextQuietMs: Long = OmiConfig.DEFAULT_QUIET_MS,
    val inputTextFilterShortAscii: Boolean = false,
    val inputTextFilterShortAsciiMaxLength: Int = 3,
    val inputTextFilterShortAscii: Boolean = false,

    // 语言（system / en / zh-CN）
    val language: String = OmiConfig.DEFAULT_LANGUAGE,

    // 操作状态
    val isSaving: Boolean = false,
    val saveSuccess: Boolean? = null,
    val isTesting: Boolean = false,
    val testResult: String? = null,

    // 模块级保存反馈（保存按钮已从全局下沉到各模块）
    val lastSavedModule: String? = null
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "SettingsVM"
    }

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
                inputTextEnabled = config.inputText.enabled,
                inputTextUploadEnabled = config.inputText.uploadEnabled,
                inputTextQuietMs = config.inputText.quietMs,
                inputTextFilterShortAscii = config.inputText.filterShortAscii,
                language = config.language
            )
        }
    }

    // ---- 切换存储方案 ----
    // 单选即生效：切方案不涉及文本框的半成品输入，立即落盘。
    fun updateStorageMethod(method: OmiConfig.StorageMethod) {
        _uiState.value = _uiState.value.copy(storageMethod = method)
        viewModelScope.launch { persist() }
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
    // 注意：这里的副作用（启停前台服务）必须留在开关里 —— 拆配置时别把它一起搬走。
    fun updatePhoneWatcherEnabled(value: Boolean) {
        _uiState.value = _uiState.value.copy(phoneWatcherEnabled = value)
        viewModelScope.launch {
            // 用完整配置落盘（buildConfig 已含 phoneWatcher.enabled），
            // 避免旧写法「只覆盖 phoneWatcher」与模块化保存出现两套写入路径。
            persist()
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
        // 目录来自系统文件选择器 —— 选完即完成，属于「选择器」而非「文本框」，
        // 因此立即保存，用户不需要再去找保存按钮。
        viewModelScope.launch { persist() }
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
            persist()
            val ctx = getApplication<Application>()
            if (value) {
                LocationUploader.get(ctx).applySettings()
            } else {
                LocationUploader.get(ctx).stop()
            }
        }
    }
    fun updateLocationInterval(value: Int) {
        val min = value.coerceIn(1, 1440)
        _uiState.value = _uiState.value.copy(locationIntervalMin = min)
        viewModelScope.launch {
            persist()
            LocationUploader.get(getApplication<Application>()).applySettings()
        }
    }

    // ---- 通话时暂停手表录音 ----
    // 开关即时生效: 拨动即保存, 手表端监听通过重启 WatchReceiverService 感知
    fun updateCallPauseEnabled(value: Boolean) {
        _uiState.value = _uiState.value.copy(callPauseEnabled = value)
        viewModelScope.launch { persist() }
    }

    // ---- 输入文本采集 ----
    // 拨动即保存; 采集开关控制无障碍服务采集, 上传开关控制 Worker 周期上传
    fun updateInputTextEnabled(value: Boolean) {
        _uiState.value = _uiState.value.copy(inputTextEnabled = value)
        viewModelScope.launch {
            persist()
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
            persist()
            val context = getApplication<Application>()
            if (value && _uiState.value.inputTextEnabled) {
                InputTextUploadWorker.schedule(context)
            } else {
                InputTextUploadWorker.cancel(context)
            }
        }
    }

    /**
     * 更新停止打字阈值（ms）。拨动即保存，无需点「保存」——
     * 无障碍服务每次事件都现读配置，所以改完立刻生效，不必重启服务。
     */
    fun updateInputTextQuietMs(valueMs: Long) {
        val clamped = valueMs.coerceIn(OmiConfig.MIN_QUIET_MS, OmiConfig.MAX_QUIET_MS)
        _uiState.value = _uiState.value.copy(inputTextQuietMs = clamped)
        viewModelScope.launch { persist() }
    }

    fun updateInputTextFilterShortAscii(value: Boolean) {
        _uiState.value = _uiState.value.copy(inputTextFilterShortAscii = value)
        viewModelScope.launch { persist() }
    }

    fun updateInputTextFilterShortAsciiMaxLength(value: Int) {
        _uiState.value = _uiState.value.copy(inputTextFilterShortAsciiMaxLength = value)
        viewModelScope.launch { persist() }
    }

    // ---- 保存 ----
    /**
     * 把当前 UI 状态组装成一份完整配置。
     *
     * 抽成函数的原因：保存已从「一个全局按钮」拆成「每个模块各保存各的」，
     * 若每处各拼一遍 Config，一旦新增字段就很容易漏掉某个模块 ——
     * 那种「界面上改了但没存进去」的 bug 最难查（重启后静默还原）。
     * 所有模块共用这一份组装逻辑，保证字段不遗漏。
     */
    private fun buildConfig(state: SettingsUiState): OmiConfig.Config =
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
                uploadEnabled = state.inputTextUploadEnabled,
                quietMs = state.inputTextQuietMs.coerceIn(
                    OmiConfig.MIN_QUIET_MS, OmiConfig.MAX_QUIET_MS
                ),
                filterShortAscii = state.inputTextFilterShortAscii,
                filterShortAsciiMaxLength = state.inputTextFilterShortAsciiMaxLength.coerceIn(1, 20)
            ),
            language = state.language
        )

    /** 把 buildConfig 的结果落盘（模块保存共用），异常不抛出，只记日志。 */
    private suspend fun persist() {
        try {
            omiConfig.saveConfig(buildConfig(_uiState.value))
        } catch (e: Exception) {
            Log.e(TAG, "persist failed", e)
        }
    }

    /**
     * 模块级保存：文本类字段的显式保存入口。
     *
     * 为什么文本框不能「边打字边存」：打到一半切走会存下半个 URL / 半个密钥，
     * 下次启动就是一个坏配置。所以文本框保留显式保存动作，但按钮**下沉到模块内部**，
     * 不再要求用户滚到页面顶部。
     *
     * @param module 模块名，仅用于回显与日志（如 "HTTP"）
     */
    fun saveModule(module: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val ok = try {
                omiConfig.saveConfig(buildConfig(_uiState.value))
                true
            } catch (e: Exception) {
                Log.e(TAG, "saveModule($module) failed", e)
                false
            }
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                saveSuccess = ok,
                lastSavedModule = module
            )
        }
    }

    /** 用户看到提示后清掉「已保存」回显，避免下次进页面还显示上一次的结果。 */
    fun consumeSaveFeedback() {
        _uiState.value = _uiState.value.copy(saveSuccess = null, lastSavedModule = null)
    }

    /**
     * 用当前 UI 状态创建一个临时 StorageUploader 实例进行测试。
     * 先把当前 UI 输入保存进 DataStore, 再 create, 拿到最新配置。
     */
    fun testConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTesting = true, testResult = null)
            try {
                // 用与保存完全相同的组装逻辑落盘，避免「测试用的配置」和
                // 「实际保存的配置」出现字段漂移（比如漏了 inputText）。
                omiConfig.saveConfig(buildConfig(_uiState.value))

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
