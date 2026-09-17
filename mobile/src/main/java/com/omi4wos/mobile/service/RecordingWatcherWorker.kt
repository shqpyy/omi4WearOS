package com.omi4wos.mobile.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.omi4wos.mobile.omi.OmiConfig
import java.util.concurrent.TimeUnit

/**
 * WorkManager 兜底 worker：当 [PhoneRecordingWatcherService] 被系统杀掉后,
 * 由 WorkManager 周期性唤起一次, 检查配置状态并尝试重启服务。
 *
 * WorkManager 周期任务最短 15 分钟, 不能完全替代前台服务, 但能在前台服务
 * 被杀后自愈。
 */
class RecordingWatcherWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "RecordingWatcherWorker"
        private const val WORK_NAME = "phone_recording_watcher_worker"

        /**
         * 注册/刷新周期任务。间隔默认 15 分钟（Android 上限）。
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RecordingWatcherWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Scheduled periodic work (15 min)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled periodic work")
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val context = applicationContext
            val config = OmiConfig(context).getConfig()

            if (config.phoneWatcher.enabled) {
                // 前台服务应该还在运行, 检查一下; 如果被杀, 重启
                Log.i(TAG, "Phone watcher enabled — ensuring foreground service is alive")
                PhoneRecordingWatcherService.start(context)
            } else {
                Log.i(TAG, "Phone watcher disabled — no action")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker failed", e)
            Result.retry()
        }
    }
}
