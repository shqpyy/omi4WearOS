package com.omi4wos.mobile.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.omi4wos.mobile.omi.OmiConfig
import com.omi4wos.mobile.storage.StorageUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 周期定位上传器（网络定位为主，低功耗）。
 *
 * 挂在 [WatchReceiverService]（永久前台服务）下，每 [INTERVAL_MS] 采样一次：
 *   1. `getLastKnownLocation` 读系统缓存，不主动唤醒 GPS 芯片 → 零额外耗电
 *   2. 精度差或源陈旧时，尝试基站/WiFi 定位（也走缓存，一般已由系统更新）
 *   3. 有权限 + 拿到了新于上一次的位置 → POST 到服务器 /location
 *
 * 无 [Manifest.permission.ACCESS_FINE_LOCATION] 或 [OmiConfig] 未配置 HTTP 时静默跳过。
 */
class LocationUploader(private val context: Context) {

    companion object {
        private const val TAG = "LocationUploader"

        /** 采样间隔: 15 分钟。 */
        const val INTERVAL_MS = 15 * 60 * 1000L

        /** 精度阈值: 超过该值(米)认为位置太粗糙, 不上传。 */
        private const val MAX_ACCURACY_M = 3000f

        /** 位置匹配有效期: 超过此时长的缓存位置视为过期, 需拿更新的。 */
        private const val MAX_LOCATION_AGE_MS = 60 * 60 * 1000L

        private const val GRACE_MS = 30 * 1000L // 启动 30s 后再采样, 等系统 location 可用

        /** 判断是否有定位权限。 */
        fun hasLocationPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    /** 上一次成功采样并上传的时间, 避免同一条位置重复传。 */
    @Volatile private var lastSampledAt = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            sampleAndUpload()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    /** 启动 15 分钟周期采样；无定位权限时只记录日志, 不崩溃。 */
    fun start() {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, GRACE_MS)
        Log.i(TAG, "Location uploader started (every ${INTERVAL_MS / 60000} min)")
    }

    fun stop() {
        handler.removeCallbacks(tickRunnable)
        scope.cancel()
        Log.i(TAG, "Location uploader stopped")
    }

    private fun sampleAndUpload() {
        if (!hasLocationPermission(context)) {
            Log.d(TAG, "No location permission — skip sampling")
            return
        }
        scope.launch {
            try {
                val location = bestLocation() ?: run {
                    Log.d(TAG, "No usable location — skip this interval")
                    return@launch
                }
                val now = System.currentTimeMillis()
                // 跳过与上一次相同时间戳的重复位置
                if (location.time in (lastSampledAt - GRACE_MS)..(lastSampledAt + GRACE_MS)) {
                    Log.d(TAG, "Same location as last sample — skip")
                    return@launch
                }
                lastSampledAt = location.time

                val config = OmiConfig(context).getConfig()
                val uploader = StorageUploader.create(context)
                if (uploader is com.omi4wos.mobile.storage.HttpUploader) {
                    val ok = uploader.uploadLocation(
                        lat = location.latitude,
                        lon = location.longitude,
                        accuracyM = location.accuracy,
                        source = when (location.provider?.lowercase()) {
                            "gps" -> "gps"
                            "network" -> "network"
                            "fused" -> "fused"
                            else -> location.provider ?: "unknown"
                        },
                        sampledAtMs = now
                    )
                    Log.i(TAG, "Location sample ${location.latitude},${location.longitude} " +
                            "acc=${location.accuracy}m uploaded=$ok")
                } else {
                    Log.d(TAG, "Storage method $config.storageMethod is not HTTP — location upload skipped")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Location sample error", e)
            }
        }
    }

    /** 从缓存拿最新的可用位置：优先 GPS, 其次网络。 */
    private fun bestLocation(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val now = System.currentTimeMillis()
        // 选一个最新且精度可接受的位置
        val candidates = buildList {
            tryRun { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { add(it) } }
            tryRun { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { add(it) } }
            tryRun { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)?.let { add(it) } }
        }
        return candidates
            .filter { it.time > 0 && (now - it.time) < MAX_LOCATION_AGE_MS }
            .filter { it.accuracy <= MAX_ACCURACY_M || it.accuracy <= 0f } // 精度达标或未知
            .maxByOrNull { it.time } // 取最新的
    }

    private inline fun tryRun(block: () -> Unit) {
        try {
            block()
        } catch (_: SecurityException) {
            // 无定位权限时某些 provider 会抛异常, 忽略
        } catch (_: Exception) {
            // provider 不可用(SecurityException 之外), 忽略
        }
    }
}