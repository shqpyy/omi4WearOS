package com.omi4wos.mobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.omi4wos.mobile.omi.OmiConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机自启：监听 BOOT_COMPLETED。
 *
 * 读取 [OmiConfig.Config.phoneWatcher.enabled]：
 *   - true  → 启动 [PhoneRecordingWatcherService]
 *   - false → 不启动
 *
 * 同时注册 WorkManager 周期任务作为兜底（任何情况都注册, 但 worker
 * 内部会判断是否需要启动服务）。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON" &&
            intent.action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        Log.i(TAG, "Boot completed received")

        // WorkManager 兜底任务始终注册（内部会判断 enabled 状态）
        RecordingWatcherWorker.schedule(context)

        // 启动前台服务需要异步读配置（不能在 BroadcastReceiver 主线程做 IO）
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val config = OmiConfig(context).getConfig()
                if (config.phoneWatcher.enabled) {
                    Log.i(TAG, "Phone watcher enabled — starting service on boot")
                    PhoneRecordingWatcherService.start(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start watcher on boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
