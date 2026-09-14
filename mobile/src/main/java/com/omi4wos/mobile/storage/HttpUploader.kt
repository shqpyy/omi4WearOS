package com.omi4wos.mobile.storage

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
 * 上传格式：
 *   - Method: POST
 *   - Content-Type: multipart/form-data
 *   - 字段名: file
 *   - 文件名: 调用方传入的 uploadName
 *   - Header: X-API-Key: <uploadApiKey>（为空时不发）
 *
 * 测试连接: 发 HEAD 请求到 uploadUrl。
 * 常见 FastAPI / Flask 等上传接口只声明 POST, HEAD 会返回 405。
 * 405/404 表示服务器活着、接口存在, 只是测试方法不被接受, 算"可达"。
 * 真正的上传走 POST, 不受影响。
 */
class HttpUploader(
    private val config: OmiConfig.HttpConfig
) : StorageUploader {

    companion object {
        private const val TAG = "HttpUploader"
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

        try {
            val cachePath = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "omi4wos_upload")
            if (!cachePath.exists()) cachePath.mkdirs()
            val binFile = File(cachePath, uploadName)
            java.io.FileOutputStream(binFile).use { it.write(audioData) }

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
            binFile.delete()

            if (success) {
                Log.i(TAG, "Upload accepted ($statusCode): $body")
                true
            } else {
                Log.w(TAG, "Upload failed ($statusCode): $body")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception", e)
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
                // 2xx/3xx: 接口接受 HEAD, 完全 OK
                code in 200..399 ->
                    Pair(true, "Connection OK (HTTP $code)")
                // 405/404: 服务器活着, 只是上传接口不接受 HEAD/GET
                // FastAPI 上传接口只声明 POST 时, HEAD 会返回 405
                code == 405 || code == 404 ->
                    Pair(true, "Server reachable (HTTP $code — upload endpoint exists, POST required)")
                // 其他: 真正的失败
                else ->
                    Pair(false, "Server reachable but HTTP $code")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            Pair(false, "Error: ${e.message}")
        }
    }
}
