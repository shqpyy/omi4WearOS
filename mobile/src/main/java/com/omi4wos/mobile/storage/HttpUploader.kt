package com.omi4wos.mobile.storage

import android.content.Context
import android.util.Log
import com.omi4wos.mobile.omi.OmiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HTTP 服务器上传实现。
 *
 * 上传格式:
 *   - Method: POST
 *   - Content-Type: multipart/form-data
 *   - 字段名: file
 *   - 文件名: 调用方传入的 uploadName
 *   - Header: X-API-Key: <uploadApiKey>（为空时不发）
 *
 * 中转文件持久化策略 (服务器宕机不丢数据):
 *   - 写入路径: context.filesDir/speech_audio/xxx.bin  (应用私有, 系统不自动清理)
 *   - 上传成功: 立即删除 bin 文件
 *   - 上传失败: 保留 bin 文件, 等待 UploadRetryWorker (15 min 周期) 重试
 *   - 重试成功: 删除 bin 文件 + 标记 uploaded=true
 *   - 未上传成功的音频永久保留, 不做超时清理. 只有上传成功才删.
 *     (服务器可能宕机数天, 音频必须留存到成功上传为止)
 *
 * 测试连接: 发 HEAD 请求, 405/404 算可达 (FastAPI 上传接口只声明 POST)
 */
class HttpUploader(
    private val config: OmiConfig.HttpConfig,
    private val context: Context
) : StorageUploader {

    companion object {
        private const val TAG = "HttpUploader"
        const val SPEECH_AUDIO_DIR = "speech_audio"

        /**
         * 统一中转文件目录, UploadRetryRunner / HomeViewModel 共用。
         */
        fun getSpeechAudioDir(context: Context): File {
            val dir = File(context.filesDir, SPEECH_AUDIO_DIR)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        /**
         * 积压占用字节数: speech_audio 目录下所有待上传 bin 文件大小之和。
         * 供 Home 页展示"未上传成功"的提示。
         */
        fun getPendingBytes(context: Context): Long {
            return getSpeechAudioDir(context)
                .listFiles()
                ?.filter { it.isFile }
                ?.sumOf { it.length() }
                ?: 0L
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .build()

    override suspend fun upload(
        audioData: ByteArray,
        uploadName: String,
        segmentId: String,
        syncId: String,
        startTimeMs: Long,
        endTimeMs: Long,
        confidence: Float,
        batteryLevel: Int,
        source: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            Log.w(TAG, "uploadUrl not configured — skip upload")
            return@withContext false
        }

        val audioDir = getSpeechAudioDir(context)
        val binFile = File(audioDir, uploadName)

        // 1. 把音频字节持久化到 filesDir/speech_audio/ (先落盘, 再上传, 上传失败也不丢)
        try {
            java.io.FileOutputStream(binFile).use { it.write(audioData) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist audio to $binFile", e)
            return@withContext false
        }

        // 2. 上传
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    uploadName,
                    binFile.asRequestBody("application/octet-stream".toMediaType())
                )
                .build()

            val requestBuilder = Request.Builder()
                .url(config.uploadUrl)
                .post(requestBody)
            if (config.uploadApiKey.isNotBlank()) {
                requestBuilder.header("X-API-Key", config.uploadApiKey)
            }
            val request = requestBuilder.build()

            Log.d(TAG, "Uploading to ${config.uploadUrl}: $uploadName (${audioData.size} bytes)")

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            val body = response.body?.string()
            val statusCode = response.code
            response.close()

            if (success) {
                binFile.delete()
                Log.i(TAG, "Upload accepted ($statusCode): $body — bin deleted")
                true
            } else {
                Log.w(TAG, "Upload failed ($statusCode): $body — bin kept for retry: $binFile")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception — bin kept for retry: $binFile", e)
            false
        }
    }

    override suspend fun testConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Pair(false, "Upload URL not configured")
        }

        try {
            val requestBuilder = Request.Builder()
                .url(config.uploadUrl)
                .head()
            if (config.uploadApiKey.isNotBlank()) {
                requestBuilder.header("X-API-Key", config.uploadApiKey)
            }
            val request = requestBuilder.build()

            val response = client.newCall(request).execute()
            val code = response.code
            response.close()

            when {
                code in 200..399 ->
                    Pair(true, "Connection OK (HTTP $code)")
                code == 405 || code == 404 ->
                    Pair(true, "Server reachable (HTTP $code — upload endpoint exists, POST required)")
                else ->
                    Pair(false, "Server reachable but HTTP $code")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            Pair(false, "Error: ${e.message}")
        }
    }

    /**
     * 上传一条定位点（网络定位为主）。POST JSON 到 uploadUrl 派生出的 /location 端点,
     * 例如 uploadUrl = http://host:8081/upload → http://host:8081/location。
     *
     * @param lat 纬度, @param lon 经度, @param accuracyM 精度米(<=0 表示未知),
     * @param source 定位来源 "network"/"gps"/"fused"
     * @param sampledAtMs 采样的设备本地时间戳(epoch ms), 供服务端与录音时间对齐
     * @return 成功是否
     */
    suspend fun uploadLocation(
        lat: Double, lon: Double, accuracyM: Float,
        source: String, sampledAtMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            Log.w(TAG, "uploadUrl not configured — skip location upload")
            return@withContext false
        }
        val url = locationEndpoint() ?: run {
            Log.w(TAG, "Cannot derive /location endpoint from ${config.uploadUrl}")
            return@withContext false
        }
        try {
            val json = JSONObject()
                .put("lat", lat)
                .put("lon", lon)
                .put("accuracy_m", accuracyM)
                .put("source", source)
                .put("timestamp", sampledAtMs)
            val body = json.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val requestBuilder = Request.Builder()
                .url(url)
                .post(body)
            if (config.uploadApiKey.isNotBlank()) {
                requestBuilder.header("X-API-Key", config.uploadApiKey)
            }
            val request = requestBuilder.build()

            val response = client.newCall(request).execute()
            val status = response.code
            val respBody = response.body?.string()
            response.close()
            if (status in 200..299) {
                Log.i(TAG, "Location uploaded ($status): $lat,$lon acc=${accuracyM}m")
                true
            } else {
                Log.w(TAG, "Location upload failed ($status): $respBody")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location upload exception", e)
            false
        }
    }

    /** 从 uploadUrl 派生 /location 端点；无法解析时返回 null。 */
    private fun locationEndpoint(): String? {
        return try {
            val base = android.net.Uri.parse(config.uploadUrl)
            if (base?.host.isNullOrBlank()) return null
            val port = if (base.port > 0) ":${base.port}" else ""
            "${base.scheme}://${base.host}$port/location"
        } catch (_: Exception) {
            null
        }
    }
}
