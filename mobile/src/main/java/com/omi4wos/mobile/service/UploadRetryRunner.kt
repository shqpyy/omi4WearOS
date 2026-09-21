package com.omi4wos.mobile.service

import android.content.Context
import android.util.Log
import com.omi4wos.mobile.data.UploadRepository
import com.omi4wos.mobile.storage.HttpUploader
import com.omi4wos.mobile.storage.StorageUploader
import java.io.File

/**
 * 失败上传重试逻辑。
 *
 * 把失败记录按 syncId 分组（batch 共享一个 bin 文件），从 filesDir/speech_audio/
 * 读回 bin 文件, 再委托 [StorageUploader] 重传。
 *
 * 未上传成功的音频永久保留, 不做超时清理。
 *  - 重传成功: 删除 bin 文件 + 标记 uploaded=true
 *  - bin 文件不存在: 标记 uploaded=true (异常情况, 避免一直挂账)
 *
 * 返回 true 表示至少有一条重传成功。
 */
suspend fun runUploadRetry(context: Context, onProgress: ((String) -> Unit)? = null): RetryResult {
    val tag = "UploadRetryRunner"
    val repository = UploadRepository.getInstance(context)
    val audioDir = HttpUploader.getSpeechAudioDir(context)

    var pending = repository.getPendingUploads()
    val orphaned = mutableListOf<File>()

    // 兜底：扫描 orphan bin 文件（有文件但数据库无记录）
    val binFiles = audioDir.listFiles()?.filter { it.isFile && it.extension == "bin" } ?: emptyList()
    val knownBinNames = pending.map { "recording_fs320_${it.timestamp / 1000}.bin" }.toSet()
    for (bin in binFiles) {
        if (bin.name !in knownBinNames) {
            orphaned.add(bin)
        }
    }

    val totalWork = pending.size + orphaned.size
    if (totalWork == 0) {
        onProgress?.invoke("Nothing to retry")
        return RetryResult(false, 0, 0, "Nothing to retry")
    }

    Log.i(tag, "Retrying ${pending.size} failed + ${orphaned.size} orphan bin(s)")
    onProgress?.invoke("Retrying ${pending.size} failed + ${orphaned.size} orphan(s)...")

    val uploader = StorageUploader.create(context)
    var succeeded = 0
    var failed = 0
    val errors = mutableListOf<String>()

    // 先处理数据库里的 pending 记录
    val grouped = pending.groupBy { record ->
        if (record.syncId.isEmpty()) "solo_${record.id}" else record.syncId
    }

    for ((_, records) in grouped) {
        val earliest = records.minByOrNull { it.timestamp } ?: continue
        val uploadName = "recording_fs320_${earliest.timestamp / 1000}.bin"
        val binFile = File(audioDir, uploadName)

        if (binFile.exists()) {
            try {
                val audioData = binFile.readBytes()
                val ok = uploader.upload(
                    audioData = audioData,
                    uploadName = uploadName,
                    segmentId = earliest.segmentId,
                    syncId = earliest.syncId,
                    startTimeMs = earliest.timestamp,
                    endTimeMs = earliest.endTimestamp,
                    confidence = earliest.speechConfidence,
                    batteryLevel = earliest.watchBatteryLevel,
                    source = "watch_retry"
                )
                if (ok) {
                    for (record in records) repository.markUploaded(record.id)
                    if (binFile.exists()) binFile.delete()
                    succeeded++
                    Log.i(tag, "Retry succeeded for ${records.size} record(s) syncId=${earliest.syncId}")
                } else {
                    failed++
                    errors.add("$uploadName: upload returned false")
                    Log.w(tag, "Retry upload returned false for $uploadName")
                }
            } catch (e: Exception) {
                failed++
                errors.add("$uploadName: ${e.message}")
                Log.e(tag, "Retry failed for syncId=${earliest.syncId}", e)
            }
        } else {
            Log.w(tag, "Cache file missing: $uploadName — dismissing ${records.size} record(s)")
            for (record in records) repository.markUploaded(record.id)
        }
    }

    // 再处理 orphan bin 文件：补录到 DB 后重传
    for (bin in orphaned) {
        try {
            val audioData = bin.readBytes()
            val ts = bin.nameWithoutExtension.removePrefix("recording_fs320_").toLongOrNull() ?: System.currentTimeMillis()
            val ok = uploader.upload(
                audioData = audioData,
                uploadName = bin.name,
                segmentId = bin.name,
                syncId = "",
                startTimeMs = ts * 1000,
                endTimeMs = ts * 1000 + 1000,
                confidence = 0f,
                batteryLevel = -1,
                source = "watch_retry_orphan"
            )
            if (ok) {
                if (bin.exists()) bin.delete()
                succeeded++
                Log.i(tag, "Orphan bin retried: ${bin.name}")
            } else {
                failed++
                errors.add("${bin.name}: upload returned false")
                Log.w(tag, "Orphan bin retry failed: ${bin.name}")
            }
        } catch (e: Exception) {
            failed++
            errors.add("${bin.name}: ${e.message}")
            Log.e(tag, "Orphan bin retry error: ${bin.name}", e)
        }
    }

    val msg = when {
        succeeded == 0 && failed > 0 -> "Failed: ${errors.first()}"
        succeeded > 0 && failed == 0 -> "Succeeded: $succeeded uploaded"
        succeeded > 0 && failed > 0 -> "Partial: $succeeded ok, $failed failed"
        else -> "Nothing to retry"
    }
    onProgress?.invoke(msg)
    return RetryResult(succeeded > 0, succeeded, failed, msg)
}

/** 重试结果 */
data class RetryResult(
    val anySucceeded: Boolean,
    val succeeded: Int,
    val failed: Int,
    val message: String
)