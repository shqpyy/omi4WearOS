package com.omi4wos.mobile.storage

import android.content.Context
import android.util.Log
import com.omi4wos.mobile.omi.OmiConfig
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * S3 兼容对象存储上传实现。
 *
 * 适配所有 S3 协议服务：
 * - 腾讯云 COS（endpoint: cos.ap-shanghai.myqcloud.com）
 * - Cloudflare R2（endpoint: <account>.r2.cloudflarestorage.com）
 * - AWS S3（endpoint: s3.amazonaws.com，需配 region）
 * - MinIO（endpoint: <your-ip>:9000）
 * - Backblaze B2 / Wasabi / 阿里云 OSS（兼容模式）
 *
 * 用 AWS S3 SDK，对象 key 路径：
 *   audio/<YYYYMMDD>/<uploadName>
 *
 * 测试连接：调 doesBucketExist(bucket) 验证配置是否可用。
 */
class S3Uploader(
    private val context: Context,
    private val config: OmiConfig.S3Config
) : StorageUploader {

    companion object {
        private const val TAG = "S3Uploader"
        private val dateFmt = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
    }

    private val s3Client: AmazonS3Client by lazy { buildClient() }

    private fun buildClient(): AmazonS3Client {
        val creds = BasicAWSCredentials(config.accessKey, config.secretKey)
        val client = AmazonS3Client(creds)

        // 设置 endpoint（区域路径风格）
        if (config.endpoint.isNotBlank()) {
            // endpoint 形如 cos.ap-shanghai.myqcloud.com 或 <account>.r2.cloudflarestorage.com
            client.setEndpoint(config.endpoint)
        } else if (config.region.isNotBlank()) {
            // 没填 endpoint 但填了 region，用 AWS 标准 region
            client.region = com.amazonaws.services.s3.model.Region.fromValue(config.region)
        }

        return client
    }

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
            Log.w(TAG, "S3 config incomplete — skip upload")
            return@withContext false
        }

        try {
            val dateKey = dateFmt.format(java.util.Date(startTimeMs))
            val objectKey = "audio/$dateKey/$uploadName"

            val metadata = ObjectMetadata().apply {
                contentLength = audioData.size.toLong()
                contentType = "application/octet-stream"
                addUserMetadata("x-omi-segment-id", segmentId)
                addUserMetadata("x-omi-sync-id", syncId)
                addUserMetadata("x-omi-start-time", startTimeMs.toString())
                addUserMetadata("x-omi-end-time", endTimeMs.toString())
                addUserMetadata("x-omi-duration-ms", (endTimeMs - startTimeMs).toString())
                addUserMetadata("x-omi-battery", batteryLevel.toString())
                addUserMetadata("x-omi-source", source)
            }

            val stream = ByteArrayInputStream(audioData)
            val putRequest = PutObjectRequest(config.bucket, objectKey, stream, metadata)
            // 配置 bucket 后缀，避免触发默认 path-style
            s3Client.putObject(putRequest)

            Log.i(TAG, "Uploaded to s3://${config.bucket}/$objectKey (${audioData.size} bytes)")
            true
        } catch (e: AmazonS3Exception) {
            Log.e(TAG, "S3 upload failed: ${e.errorCode} - ${e.errorMessage}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception", e)
            false
        }
    }

    override suspend fun testConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Pair(false, "S3 config incomplete")
        }

        try {
            val exists = runCatching { s3Client.doesBucketExistV2(config.bucket) }
                .getOrDefault(false)
            if (exists) {
                Pair(true, "Bucket '${config.bucket}' accessible on ${config.endpoint.ifBlank { config.region }}")
            } else {
                Pair(false, "Bucket '${config.bucket}' not found or no permission")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            Pair(false, "Error: ${e.message}")
        }
    }
}
