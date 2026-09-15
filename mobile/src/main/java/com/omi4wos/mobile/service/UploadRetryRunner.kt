package com.omi4wos.mobile.service

import android.content.Context
import android.util.Log
import com.omi4wos.mobile.data.UploadRepository
import com.omi4wos.mobile.storage.HttpUploader
import com.omi4wos.mobile.storage.StorageUploader
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 失败上传重试逻辑。
 *
 * 把失败记录按 syncId 分组（batch 共享一个 bin 文件），从 filesDir/speech_audio/
 * 读回 bin 文件, 再委托 [StorageUploader] 重传。
 *
 * 重传成功: 删除 bin 文件 + 标记 uploaded=true
 * bin 文件不存在: 标记 uploaded=true (避免一直挂账)
 * 超过 7 天的失败记录: 强制清理 bin 文件 (避免无限堆积)
 *
 * 返回 true 表示至少有一条重传成功。
 */
suspend fun runUploadRetry(context: Context): Boolean {
    val tag = "UploadRetryRunner"
    val repository = UploadRepository.getInstance(context)
    val audioDir = HttpUploader.getSpeechAudioDir(context)

    // 1. 7 天兜底清理: 超过 MAX_PENDING_DAYS 的 bin 文件强制删除
    val now = System.currentTimeMillis()
    val maxPendingMs = TimeUnit.DAYS.toMillis(HttpUploader.MAX_PENDING_DAYS)
    audioDir.listFiles()?.forEach { file ->
        if (file.isFile && now - file.lastModified() > maxPendingMs) {
            Log.w(tag, "Force-deleting stale bin (> ${HttpUploader.MAX_PENDING_DAYS} days): ${file.name}")
            file.delete()
        }
    }

    val pending = repository.getPendingUploads()
    if (pending.isEmpty()) return false

    Log.i(tag, "Retrying ${pending.size} failed upload(s)")

    val uploader = StorageUploader.create(context)

    val grouped = pending.groupBy { record ->
        if (record.syncId.isEmpty()) "solo_${record.id}" else record.syncId
    }

    var anySucceeded = false

    for ((_, records) in grouped) {
        val earliest = records.minByOrNull { it.timestamp } ?: continue
        val uploadName = "recording_fs320_${earliest.timestamp / 1000}.bin"
        val binFile = File(audioDir, uploadName)

        // 超过 7 天的失败记录: 标记为 uploaded (避免一直挂账, bin 已被兜底清理)
        val recordAgeMs = now - earliest.timestamp
        if (recordAgeMs > maxPendingMs) {
            Log.w(tag, "Record ${earliest.segmentId} age=${recordAgeMs / 86_400_000} days, dismiss")
            for (record in records) repository.markUploaded(record.id)
            continue
        }

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
                    // 成功: 删除 bin 文件 + 标记 uploaded (HttpUploader 内部已经删了, 这里兜底)
                    for (record in records) repository.markUploaded(record.id)
                    if (binFile.exists()) binFile.delete()
                    anySucceeded = true
                    Log.i(tag, "Retry succeeded for ${records.size} record(s) syncId=${earliest.syncId}")
                } else {
                    Log.w(tag, "Retry upload returned false for $uploadName")
                }
            } catch (e: Exception) {
                Log.e(tag, "Retry failed for syncId=${earliest.syncId}", e)
            }
        } else {
            // bin 文件不存在 (被兜底清理了), 标记 uploaded 避免一直挂账
            Log.w(tag, "Cache file missing: $uploadName — dismissing ${records.size} record(s)")
            for (record in records) repository.markUploaded(record.id)
        }
    }

    return anySucceeded
}
