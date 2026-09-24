package com.omi4wos.mobile.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.omi4wos.mobile.omi.OmiConfig
import com.omi4wos.mobile.storage.StorageUploader
import java.util.concurrent.TimeUnit

/**
 * 输入文本上传 worker。
 *
 * 周期把 filesDir/input_text/ 下待上传的 JSONL 逐行 POST 到 /input-text，
 * 上传成功的文件归档到 uploaded/，失败保留等下次重试。
 *
 * 与音频侧 [UploadRetryWorker] 完全同构，区别只是数据源与端点。
 * 两条开关都必须为 true 才干活：
 *   - inputTextEnabled        启用采集
 *   - inputTextUploadEnabled  启用自动上传
 */
class InputTextUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "omi4wos_input_text_upload"
        private const val TAG = "InputTextUploadWorker"
        private const val INTERVAL_MINUTES = 15L

        /** 注册/刷新周期任务（网络可用时才跑）。 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<InputTextUploadWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Scheduled input text upload ($INTERVAL_MINUTES min, network-constrained)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled input text upload")
        }
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        return try {
            val config = OmiConfig(context).getConfig()
            if (!config.inputText.enabled || !config.inputText.uploadEnabled) {
                Log.i(TAG, "Input text collect/upload disabled — skip")
                return Result.success()
            }
            val summary = runInputTextUpload(context)
            Log.i(TAG, "Auto-upload finished: $summary")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Input text upload threw unexpectedly", e)
            Result.retry()
        }
    }
}

/**
 * 执行一轮输入文本上传。
 *
 * 逐文件、逐行上传；失败的**整文件**保留（不删部分行），避免半传状态。
 * 返回人类可读的汇总，供日志与设置页手动触发复用。
 */
suspend fun runInputTextUpload(context: Context): String {
    val tag = "InputTextUploadRunner"
    val repository = InputTextRepository.getInstance(context)
    val files = repository.pendingFiles()

    if (files.isEmpty()) {
        Log.i(tag, "Nothing to upload")
        return "Nothing to upload"
    }

    val uploader = StorageUploader.create(context)
    var uploadedFiles = 0
    var failedFiles = 0
    var totalLines = 0

    for (file in files) {
        val lines = repository.readLines(file)
        if (lines.isEmpty()) {
            // 空文件直接归档，避免永远挂账
            repository.markUploaded(file)
            continue
        }

        var allOk = true
        for (line in lines) {
            val ok = try {
                uploader.uploadInputText(line, file.name)
            } catch (e: Exception) {
                Log.e(tag, "Upload failed for ${file.name}", e)
                false
            }
            if (!ok) {
                allOk = false
                break // 单行失败即中止本文件，保持文件完整待重试
            }
            totalLines++
        }

        if (allOk) {
            repository.markUploaded(file)
            uploadedFiles++
        } else {
            failedFiles++
        }
    }

    val msg = when {
        failedFiles == 0 -> "Uploaded $totalLines line(s) from $uploadedFiles file(s)"
        uploadedFiles == 0 -> "Failed: 0 of $totalLines line(s) uploaded, $failedFiles file(s) pending"
        else -> "Partial: $totalLines line(s) from $uploadedFiles file(s), $failedFiles file(s) pending"
    }
    Log.i(tag, msg)
    return msg
}
