package com.omi4wos.mobile.omi

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "omi_settings")

/**
 * 上传与监听配置。支持三种存储方案独立保存配置，切换不丢失。
 *
 * - [StorageMethod.LOCAL_FILE]: 写入手机本地文件 + segments.jsonl
 * - [StorageMethod.HTTP]: POST 到自建 HTTP 服务器（multipart + X-API-Key）
 * - [StorageMethod.S3]: 上传到 S3 兼容对象存储（腾讯云 COS / Cloudflare R2 / AWS S3 / MinIO）
 *
 * 另含通话录音监听配置：[PhoneWatcherConfig]
 */
class OmiConfig(private val context: Context) {

    enum class StorageMethod {
        LOCAL_FILE,
        HTTP,
        S3
    }

    /** 本地文件方案配置 */
    data class LocalFileConfig(
        val outputDir: String = DEFAULT_LOCAL_OUTPUT_DIR
    )

    /** HTTP 服务器方案配置 */
    data class HttpConfig(
        val uploadUrl: String = "",
        val uploadApiKey: String = ""
    ) {
        val isConfigured: Boolean get() = uploadUrl.isNotBlank()
    }

    /** S3 兼容对象存储方案配置 */
    data class S3Config(
        val endpoint: String = "",
        val bucket: String = "",
        val accessKey: String = "",
        val secretKey: String = "",
        val region: String = "" // 可空;某些服务不需要 region
    ) {
        val isConfigured: Boolean
            get() = endpoint.isNotBlank() && bucket.isNotBlank() &&
                    accessKey.isNotBlank() && secretKey.isNotBlank()
    }

    /** 通话录音监听配置 */
    data class PhoneWatcherConfig(
        val enabled: Boolean = false,
        val watchDir: String = "",                       // 手动输入路径（与 treeUri 二选一）
        val treeUri: String = "",                        // SAF 授权的目录 URI（优先）
        val filePatterns: String = DEFAULT_FILE_PATTERNS, // 分号分隔, 如 *.amr;*.m4a
        val scanIntervalSec: Int = 60                    // 扫描间隔（秒）
    )

    /** 定位上报配置 */
    data class LocationConfig(
        val enabled: Boolean = true,    // 开关, 默认开（保持现状: 一直周期上报）
        val intervalMin: Int = 15       // 采样间隔（分钟）
    )

    /** 通话时暂停手表录音配置 */
    data class CallPauseConfig(
        val enabled: Boolean = true     // 开关, 默认开
    )

    /** 顶层配置聚合 */
    data class Config(
        val storageMethod: StorageMethod = StorageMethod.LOCAL_FILE,
        val localFile: LocalFileConfig = LocalFileConfig(),
        val http: HttpConfig = HttpConfig(),
        val s3: S3Config = S3Config(),
        val phoneWatcher: PhoneWatcherConfig = PhoneWatcherConfig(),
        val location: LocationConfig = LocationConfig(),
        val callPause: CallPauseConfig = CallPauseConfig(),
        val language: String = DEFAULT_LANGUAGE
    )

    companion object {
        const val DEFAULT_LOCAL_OUTPUT_DIR = "/storage/emulated/0/omi4wos"
        const val DEFAULT_FILE_PATTERNS = "*.amr;*.m4a;*.mp3;*.aac;*.opus"
        const val DEFAULT_HTTP_UPLOAD_URL = "http://124.222.91.138:8081/upload-audio"
        const val DEFAULT_HTTP_API_KEY = "OMI_UPLOAD_KEY_2026"
        const val DEFAULT_LANGUAGE = "system"  // system / en / zh-CN

        // Storage method
        private val KEY_STORAGE_METHOD = stringPreferencesKey("storage_method")

        // Local file
        private val KEY_LOCAL_OUTPUT_DIR = stringPreferencesKey("local_output_dir")

        // HTTP
        private val KEY_HTTP_UPLOAD_URL = stringPreferencesKey("http_upload_url")
        private val KEY_HTTP_API_KEY = stringPreferencesKey("http_api_key")

        // S3
        private val KEY_S3_ENDPOINT = stringPreferencesKey("s3_endpoint")
        private val KEY_S3_BUCKET = stringPreferencesKey("s3_bucket")
        private val KEY_S3_ACCESS_KEY = stringPreferencesKey("s3_access_key")
        private val KEY_S3_SECRET_KEY = stringPreferencesKey("s3_secret_key")
        private val KEY_S3_REGION = stringPreferencesKey("s3_region")

        // Phone watcher
        private val KEY_PW_ENABLED = booleanPreferencesKey("pw_enabled")
        private val KEY_PW_DIR = stringPreferencesKey("pw_dir")
        private val KEY_PW_TREE_URI = stringPreferencesKey("pw_tree_uri")
        private val KEY_PW_PATTERNS = stringPreferencesKey("pw_patterns")
        private val KEY_PW_INTERVAL = intPreferencesKey("pw_interval")

        // Location
        private val KEY_LOC_ENABLED = booleanPreferencesKey("loc_enabled")
        private val KEY_LOC_INTERVAL = intPreferencesKey("loc_interval")

        // Call pause (通话时暂停手表录音)
        private val KEY_CALL_PAUSE_ENABLED = booleanPreferencesKey("call_pause_enabled")

        // Language
        private val KEY_LANGUAGE = stringPreferencesKey("language")
    }

    suspend fun getConfig(): Config {
        return context.dataStore.data.map { prefs ->
            Config(
                storageMethod = prefs[KEY_STORAGE_METHOD]
                    ?.let { runCatching { StorageMethod.valueOf(it) }.getOrNull() }
                    ?: StorageMethod.LOCAL_FILE,
                localFile = LocalFileConfig(
                    outputDir = prefs[KEY_LOCAL_OUTPUT_DIR] ?: DEFAULT_LOCAL_OUTPUT_DIR
                ),
                http = HttpConfig(
                    uploadUrl = prefs[KEY_HTTP_UPLOAD_URL] ?: DEFAULT_HTTP_UPLOAD_URL,
                    uploadApiKey = prefs[KEY_HTTP_API_KEY] ?: DEFAULT_HTTP_API_KEY
                ),
                s3 = S3Config(
                    endpoint = prefs[KEY_S3_ENDPOINT] ?: "",
                    bucket = prefs[KEY_S3_BUCKET] ?: "",
                    accessKey = prefs[KEY_S3_ACCESS_KEY] ?: "",
                    secretKey = prefs[KEY_S3_SECRET_KEY] ?: "",
                    region = prefs[KEY_S3_REGION] ?: ""
                ),
                phoneWatcher = PhoneWatcherConfig(
                    enabled = prefs[KEY_PW_ENABLED] ?: false,
                    watchDir = prefs[KEY_PW_DIR] ?: "",
                    treeUri = prefs[KEY_PW_TREE_URI] ?: "",
                    filePatterns = prefs[KEY_PW_PATTERNS] ?: DEFAULT_FILE_PATTERNS,
                    scanIntervalSec = prefs[KEY_PW_INTERVAL] ?: 60
                ),
                location = LocationConfig(
                    enabled = prefs[KEY_LOC_ENABLED] ?: true,
                    intervalMin = prefs[KEY_LOC_INTERVAL] ?: 15
                ),
                callPause = CallPauseConfig(
                    enabled = prefs[KEY_CALL_PAUSE_ENABLED] ?: true
                ),
                language = prefs[KEY_LANGUAGE] ?: DEFAULT_LANGUAGE
            )
        }.first()
    }

    suspend fun saveConfig(config: Config) {
        context.dataStore.edit { prefs ->
            prefs[KEY_STORAGE_METHOD] = config.storageMethod.name
            prefs[KEY_LOCAL_OUTPUT_DIR] = config.localFile.outputDir
            prefs[KEY_HTTP_UPLOAD_URL] = config.http.uploadUrl
            prefs[KEY_HTTP_API_KEY] = config.http.uploadApiKey
            prefs[KEY_S3_ENDPOINT] = config.s3.endpoint
            prefs[KEY_S3_BUCKET] = config.s3.bucket
            prefs[KEY_S3_ACCESS_KEY] = config.s3.accessKey
            prefs[KEY_S3_SECRET_KEY] = config.s3.secretKey
            prefs[KEY_S3_REGION] = config.s3.region
            prefs[KEY_PW_ENABLED] = config.phoneWatcher.enabled
            prefs[KEY_PW_DIR] = config.phoneWatcher.watchDir
            prefs[KEY_PW_TREE_URI] = config.phoneWatcher.treeUri
            prefs[KEY_PW_PATTERNS] = config.phoneWatcher.filePatterns
            prefs[KEY_PW_INTERVAL] = config.phoneWatcher.scanIntervalSec
            prefs[KEY_LOC_ENABLED] = config.location.enabled
            prefs[KEY_LOC_INTERVAL] = config.location.intervalMin
            prefs[KEY_CALL_PAUSE_ENABLED] = config.callPause.enabled
            prefs[KEY_LANGUAGE] = config.language
        }
    }

    fun observeConfig() = context.dataStore.data.map { prefs ->
        Config(
            storageMethod = prefs[KEY_STORAGE_METHOD]
                ?.let { runCatching { StorageMethod.valueOf(it) }.getOrNull() }
                ?: StorageMethod.LOCAL_FILE,
            localFile = LocalFileConfig(
                outputDir = prefs[KEY_LOCAL_OUTPUT_DIR] ?: DEFAULT_LOCAL_OUTPUT_DIR
            ),
            http = HttpConfig(
                uploadUrl = prefs[KEY_HTTP_UPLOAD_URL] ?: DEFAULT_HTTP_UPLOAD_URL,
                uploadApiKey = prefs[KEY_HTTP_API_KEY] ?: DEFAULT_HTTP_API_KEY
            ),
            s3 = S3Config(
                endpoint = prefs[KEY_S3_ENDPOINT] ?: "",
                bucket = prefs[KEY_S3_BUCKET] ?: "",
                accessKey = prefs[KEY_S3_ACCESS_KEY] ?: "",
                secretKey = prefs[KEY_S3_SECRET_KEY] ?: "",
                region = prefs[KEY_S3_REGION] ?: ""
            ),
            phoneWatcher = PhoneWatcherConfig(
                enabled = prefs[KEY_PW_ENABLED] ?: false,
                watchDir = prefs[KEY_PW_DIR] ?: "",
                treeUri = prefs[KEY_PW_TREE_URI] ?: "",
                filePatterns = prefs[KEY_PW_PATTERNS] ?: DEFAULT_FILE_PATTERNS,
                scanIntervalSec = prefs[KEY_PW_INTERVAL] ?: 60
            ),
            location = LocationConfig(
                enabled = prefs[KEY_LOC_ENABLED] ?: true,
                intervalMin = prefs[KEY_LOC_INTERVAL] ?: 15
            ),
            language = prefs[KEY_LANGUAGE] ?: DEFAULT_LANGUAGE
        )
    }

    suspend fun clearConfig() {
        context.dataStore.edit { it.clear() }
    }
}
