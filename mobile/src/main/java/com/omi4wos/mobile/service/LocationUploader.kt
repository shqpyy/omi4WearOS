package com.omi4wos.mobile.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.omi4wos.mobile.omi.OmiConfig
import com.omi4wos.mobile.storage.HttpUploader
import com.omi4wos.mobile.storage.StorageUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** 定位上传状态，供 Home 页展示与排错。 */
data class LocationUploadStatus(
    val lastAttemptAt: Long = 0L,
    val lastSuccessAt: Long = 0L,
    val uploadedOk: Int = 0,
    val uploadFailed: Int = 0,
    val lastLat: Double? = null,
    val lastLon: Double? = null,
    val lastAccuracyM: Float = 0f,
    val lastSource: String = "",
    val lastError: String? = null,
    /** 前台定位权限（FINE/COARSE）是否已授权。 */
    val hasPermission: Boolean = false,
    /** 后台定位权限（ACCESS_BACKGROUND_LOCATION）是否已授权。 */
    val hasBackgroundPermission: Boolean = false,
    /** 上传地址是否已配置（HTTP 方式）。 */
    val endpointConfigured: Boolean = false
)

object LocationStatus {
    private val _flow = MutableStateFlow(LocationUploadStatus())
    val status: StateFlow<LocationUploadStatus> = _flow.asStateFlow()
    fun update(t: (LocationUploadStatus) -> LocationUploadStatus) { _flow.value = t(_flow.value) }
}

/**
 * 周期定位上传器（网络定位为主，低功耗）。
 *
 * 挂在 [WatchReceiverService]（永久前台服务）下，每 [INTERVAL_MS] 采样一次：
 *   1. 优先读系统缓存 `getLastKnownLocation`，不主动唤醒 GPS 芯片 → 零额外耗电
 *   2. 手动上报时先请求一次单次定位，拿不到再回落到缓存
 *   3. 有权限 + 拿到可用位置 → POST 到服务器 /location
 *
 * 所有分支都会写 [LocationStatus]，不再静默吞异常。
 */
class LocationUploader(private val context: Context) {

    companion object {
        private const val TAG = "LocationUploader"

        /** 采样间隔: 15 分钟。 */
        const val INTERVAL_MS = 15 * 60 * 1000L

        /** 精度阈值: 超过该值(米)的缓存位置认为太粗糙, 不上传。 */
        private const val MAX_ACCURACY_M = 3000f

        /** 缓存位置有效期: 超过此时长视为过期。 */
        private const val MAX_LOCATION_AGE_MS = 60 * 60 * 1000L

        private const val GRACE_MS = 30 * 1000L // 启动 30s 后再采样, 等系统 location 可用

        /** 主动定位的超时（手动上报时用）。 */
        private const val FRESH_FIX_TIMEOUT_MS = 15_000L

        /** 前台定位权限（FINE/COARSE 任一即可）。 */
        fun hasLocationPermission(context: Context): Boolean {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            return (fine == PackageManager.PERMISSION_GRANTED) || (coarse == PackageManager.PERMISSION_GRANTED)
        }

        /**
         * 后台定位权限。
         *
         * Android 10 及以下没有独立的后台定位权限（前台权限即可）；Android 11+ 必须显式授予
         * ACCESS_BACKGROUND_LOCATION，否则 app 退到后台后读位置会抛 SecurityException。
         */
        fun hasBackgroundLocationPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return hasLocationPermission(context)
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        /** 刷新权限/配置相关状态（Home 页进入或手动上报前调用）。 */
        suspend fun refreshStatus(context: Context) {
            val config = runCatching { OmiConfig(context).getConfig() }.getOrNull()
            val configured = config != null &&
                    config.storageMethod == OmiConfig.StorageMethod.HTTP &&
                    config.http.isConfigured
            LocationStatus.update {
                it.copy(
                    hasPermission = hasLocationPermission(context),
                    hasBackgroundPermission = hasBackgroundLocationPermission(context),
                    endpointConfigured = configured
                )
            }
        }

        /**
         * 立即采样并上报一次（Home 页「立即上报」按钮）。
         * 与周期采样不同，这里会先主动请求一次单次定位，便于验证链路是否真的通。
         */
        suspend fun uploadNow(context: Context): Boolean {
            refreshStatus(context)
            return try {
                performSample(context, fresh = true)
            } catch (e: Exception) {
                Log.e(TAG, "Manual location upload failed", e)
                recordFailure("手动上报异常: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }

        /** 一次完整的采样→上传流程，所有结果写入 [LocationStatus]。 */
        private suspend fun performSample(context: Context, fresh: Boolean): Boolean {
            LocationStatus.update { it.copy(lastAttemptAt = System.currentTimeMillis()) }

            if (!hasLocationPermission(context)) {
                recordFailure("未授予定位权限，请在系统设置中允许")
                Log.d(TAG, "No location permission — skip sampling")
                return false
            }

            val location: Location? = (if (fresh) freshLocation(context, FRESH_FIX_TIMEOUT_MS) else null)
                ?: lastKnownBest(context)

            if (location == null) {
                recordFailure(
                    if (hasBackgroundLocationPermission(context)) {
                        "无可用位置（系统定位缓存为空），稍后重试"
                    } else {
                        "无可用位置：后台定位权限未授予，请在设置中选择「始终允许」"
                    }
                )
                Log.d(TAG, "No usable location — skip this interval")
                return false
            }

            val config = OmiConfig(context).getConfig()
            val uploader = StorageUploader.create(context)
            if (uploader !is HttpUploader) {
                recordFailure("当前存储方式不是 HTTP，无法上报定位")
                Log.d(TAG, "Storage method ${config.storageMethod} is not HTTP — location upload skipped")
                return false
            }

            val source = when (location.provider?.lowercase()) {
                "gps" -> "gps"
                "network" -> "network"
                "fused" -> "fused"
                else -> location.provider ?: "unknown"
            }

            return try {
                val ok = uploader.uploadLocation(
                    lat = location.latitude,
                    lon = location.longitude,
                    accuracyM = location.accuracy,
                    source = source,
                    sampledAtMs = System.currentTimeMillis()
                )
                if (ok) {
                    recordSuccess(location, source)
                    Log.i(TAG, "Location sample ${location.latitude},${location.longitude} " +
                            "acc=${location.accuracy}m uploaded=true")
                } else {
                    recordFailure("上报失败：服务器未接受（检查上传地址与 Key）")
                    Log.w(TAG, "Location upload rejected by server")
                }
                ok
            } catch (e: Exception) {
                Log.e(TAG, "Location upload error", e)
                recordFailure("上报异常: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }

        private fun recordSuccess(location: Location, source: String) {
            LocationStatus.update {
                it.copy(
                    lastSuccessAt = System.currentTimeMillis(),
                    uploadedOk = it.uploadedOk + 1,
                    lastLat = location.latitude,
                    lastLon = location.longitude,
                    lastAccuracyM = location.accuracy,
                    lastSource = source,
                    lastError = null
                )
            }
        }

        private fun recordFailure(message: String) {
            LocationStatus.update {
                it.copy(uploadFailed = it.uploadFailed + 1, lastError = message)
            }
        }

        /**
         * 主动请求一次单次定位（网络源优先，省电）。超时或失败返回 null，
         * 由调用方回落到缓存位置。拿到首个回调后立刻注销监听。
         */
        private suspend fun freshLocation(context: Context, timeoutMs: Long): Location? =
            withTimeoutOrNull(timeoutMs) {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    ?: return@withTimeoutOrNull null
                val provider = listOf(
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.GPS_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                ).firstOrNull {
                    try { lm.isProviderEnabled(it) } catch (_: Exception) { false }
                } ?: return@withTimeoutOrNull null

                suspendCancellableCoroutine { cont ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            tryRun { lm.removeUpdates(this) }
                            if (cont.isActive) cont.resume(location)
                        }
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                        @Deprecated("Deprecated in API 29")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    }
                    try {
                        lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                    } catch (e: Exception) {
                        Log.w(TAG, "freshLocation request failed for $provider: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    cont.invokeOnCancellation { tryRun { lm.removeUpdates(listener) } }
                }
            }

        /** 从缓存拿最新的可用位置：优先 GPS, 其次网络/被动。 */
        private fun lastKnownBest(context: Context): Location? {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val now = System.currentTimeMillis()
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
            } catch (e: SecurityException) {
                Log.d(TAG, "Provider read denied: ${e.message}")
            } catch (e: Exception) {
                Log.d(TAG, "Provider read failed: ${e.message}")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    /** 上一次成功采样并上传的位置时间戳, 避免同一条位置重复传。 */
    @Volatile private var lastSampledAt = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            sampleAndUpload()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    /** 启动 15 分钟周期采样；无定位权限时只记录状态, 不崩溃。 */
    fun start() {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, GRACE_MS)
        scope.launch { refreshStatus(context) }
        Log.i(TAG, "Location uploader started (every ${INTERVAL_MS / 60000} min)")
    }

    fun stop() {
        handler.removeCallbacks(tickRunnable)
        scope.cancel()
        Log.i(TAG, "Location uploader stopped")
    }

    private fun sampleAndUpload() {
        if (!hasLocationPermission(context)) {
            LocationStatus.update {
                it.copy(
                    lastAttemptAt = System.currentTimeMillis(),
                    hasPermission = false,
                    lastError = "未授予定位权限，请在系统设置中允许"
                )
            }
            Log.d(TAG, "No location permission — skip sampling")
            return
        }
        scope.launch {
            try {
                val location = lastKnownBest(context)
                if (location == null) {
                    recordFailure(
                        if (hasBackgroundLocationPermission(context)) {
                            "无可用位置（系统定位缓存为空）"
                        } else {
                            "无可用位置：后台定位权限未授予，请在设置中选择「始终允许」"
                        }
                    )
                    return@launch
                }
                // 跳过与上一次相同时间戳的重复位置
                if (location.time in (lastSampledAt - GRACE_MS)..(lastSampledAt + GRACE_MS)) {
                    Log.d(TAG, "Same location as last sample — skip")
                    return@launch
                }
                lastSampledAt = location.time

                val config = OmiConfig(context).getConfig()
                val uploader = StorageUploader.create(context)
                if (uploader is HttpUploader) {
                    val source = when (location.provider?.lowercase()) {
                        "gps" -> "gps"
                        "network" -> "network"
                        "fused" -> "fused"
                        else -> location.provider ?: "unknown"
                    }
                    val ok = uploader.uploadLocation(
                        lat = location.latitude,
                        lon = location.longitude,
                        accuracyM = location.accuracy,
                        source = source,
                        sampledAtMs = System.currentTimeMillis()
                    )
                    if (ok) {
                        recordSuccess(location, source)
                    } else {
                        recordFailure("上报失败：服务器未接受（检查上传地址与 Key）")
                    }
                    Log.i(TAG, "Location sample ${location.latitude},${location.longitude} " +
                            "acc=${location.accuracy}m uploaded=$ok")
                } else {
                    recordFailure("当前存储方式不是 HTTP，无法上报定位")
                    Log.d(TAG, "Storage method ${config.storageMethod} is not HTTP — location upload skipped")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Location sample error", e)
                recordFailure("采样异常: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
