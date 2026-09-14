package com.omi4wos.mobile.service

import android.content.Context
import android.util.Log
import com.omi4wos.mobile.data.UploadRepository
import com.omi4wos.mobile.storage.StorageUploader
import java.io.File

/**
 * 失败上传重试逻辑。
 *
 * 把失败记录按 syncId 分组（batch 共享一个 bin 文件），从 cacheDir 读回
 * bin 文件，再委托 [StorageUploader] 重传。
 *
 * 记录对应的 bin 文件不存在（缓存被清）则标记为已上传,避免一直挂账。
 *
 * 返回 true 表示至少有一条重传成功。
 */
suspend fun runUploadRetry(context: Context): Boolean {
    val tag = "UploadRetryRunner"
    val repository = UploadRepository.getInstance(context)
    val cacheDir = File(context.cacheDir, "speech_audio")

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
        val binFile = File(cacheDir, uploadName)

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
                    binFile.delete()
                    anySucceeded = true
                    Log.i(tag, "Retry succeeded for ${records.size} record(s) syncId=${earliest.syncId}")
                } else {
                    Log.w(tag, "Retry upload returned false for $uploadName")
                }
            } catch (e: Exception) {
                Log.e(tag, "Retry failed for syncId=${earliest.syncId}", e)
            }
        } else {
            Log.w(tag, "Cache file missing: $uploadName — dismissing ${records.size} record(s)")
            for (record in records) repository.markUploaded(record.id)
        }
    }

    return anySucceeded
}
