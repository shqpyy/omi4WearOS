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
import okhttp3.logging.HttpLoggingInterceptor
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
 *   - 超过 7 天的失败记录: 强制清理 bin 文件 (避免无限堆积)
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
        const val MAX_PENDING_DAYS = 7L

        /**
         * 统一中转文件目录, UploadRetryRunner / AudioUploadService 共用。
         */
        fun getSpeechAudioDir(context: Context): File {
            val dir = File(context.filesDir, SPEECH_AUDIO_DIR)
            if (!dir.exists()) dir.mkdirs()
            return dir
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
}
