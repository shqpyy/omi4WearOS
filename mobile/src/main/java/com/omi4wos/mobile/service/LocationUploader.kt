package com.omi4wos.mobile.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
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
import kotlinx.coroutines.withContext
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

    fun update(t: (LocationUploadStatus) -> LocationUploadStatus) {
        runCatching { _flow.value = t(_flow.value) }
    }
}

/**
 * 周期定位上传器（以系统定位缓存为主，低功耗）。
 *
 * 挂在 [WatchReceiverService]（永久前台服务）下，每 [INTERVAL_MS] 采样一次；
 * Home 页的「立即上报」会额外允许一次主动定位。
 *
 * 设计原则：**绝不因为定位失败而让 App 崩溃**。所有分支都会写 [LocationStatus]，
 * 异常一律降级成界面可见的错误文案。
 */
class LocationUploader(private val context: Context) {

    companion object {
        private const val TAG = "LocationUploader"

        /** 周期采样间隔: 15 分钟。 */
        const val INTERVAL_MS = 15 * 60 * 1000L

        /** 精度阈值: 超过该值(米)认为位置太粗糙, 不上传。 */
        private const val MAX_ACCURACY_M = 3000f

        /** 周期采样允许的缓存位置最大年龄。 */
        private const val CACHE_MAX_AGE_MS = 60 * 60 * 1000L

        /** 手动上报允许的缓存位置最大年龄（放宽, 便于验证链路）。 */
        private const val MANUAL_CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1000L

        private const val GRACE_MS = 30 * 1000L

        /** 主动定位超时。 */
        private const val FRESH_FIX_TIMEOUT_MS = 12_000L

        /** 仅允许应用 Context，禁止持有 Activity，除非显式调用 [stop]。 */
        @Volatile private var instance: LocationUploader? = null

        /** 服务进程内的单例（幂等启动，避免 START_STICKY 多次回调堆叠采样循环）。 */
        @JvmStatic
        fun get(context: Context): LocationUploader = instance ?: synchronized(this) {
            instance ?: LocationUploader(context.applicationContext).also { instance = it }
        }

        /** 前台定位权限（FINE/COARSE 任一即可）。 */
        fun hasLocationPermission(context: Context): Boolean = runCatching {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            (fine == PackageManager.PERMISSION_GRANTED) || (coarse == PackageManager.PERMISSION_GRANTED)
        }.getOrDefault(false)

        /**
         * 后台定位权限。Android 10 及以下没有独立权限（前台权限即可）；
         * Android 11+ 必须显式授予 ACCESS_BACKGROUND_LOCATION，否则退到后台读位置会抛
         * SecurityException。
         */
        fun hasBackgroundLocationPermission(context: Context): Boolean = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) hasLocationPermission(context)
            else ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

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
         * 立即采样并上报一次（Home 页「立即上报」）。任何异常都转成可见错误文案，不抛出。
         */
        suspend fun uploadNow(context: Context): Boolean {
            return try {
                val app = context.applicationContext
                refreshStatus(app)
                performSample(app, maxAgeMs = MANUAL_CACHE_MAX_AGE_MS, allowFreshFix = true)
            } catch (t: Throwable) {
                Log.e(TAG, "Manual location upload failed", t)
                recordFailure("手动上报异常: ${describe(t)}")
                false
            }
        }

        /** 一次完整的采样→上传流程，所有结果写入 [LocationStatus]。 */
        private suspend fun performSample(
            rawContext: Context,
            maxAgeMs: Long,
            allowFreshFix: Boolean
        ): Boolean {
            // 强制使用应用 Context：绝不能持有 Activity/View 引用，否则活动销毁后会泄漏甚至崩溃
            val context = rawContext.applicationContext
            LocationStatus.update { it.copy(lastAttemptAt = System.currentTimeMillis()) }

            if (!hasLocationPermission(context)) {
                recordFailure("未授予定位权限，请在系统设置中允许")
                return false
            }

            // 1) 缓存命中 → 转后台线程，避免占用协程主调度器
            lastKnownBest(context, maxAgeMs)?.let { cached ->
                return withContext(Dispatchers.IO) { runCatchingUpload(context, cached) }
            }

            if (!allowFreshFix) {
                recordFailure("无可用位置（系统定位缓存为空或已过期）")
                return false
            }

            // 2) 缓存为空 → 尝试一次主动定位，全程兜底
            val fresh = runCatching { freshLocation(context, FRESH_FIX_TIMEOUT_MS) }
                .onFailure {
                    Log.e(TAG, "freshLocation failed", it)
                    recordFailure("主动定位异常: ${describe(it)}")
                }
                .getOrNull()

            if (fresh == null) {
                recordFailure(
                    if (hasBackgroundLocationPermission(context)) {
                        "无可用位置：系统缓存为空且主动定位未返回，请稍后重试"
                    } else {
                        "无可用位置：后台定位权限未授予，请在设置中选择「始终允许」"
                    }
                )
                return false
            }
            return withContext(Dispatchers.IO) { runCatchingUpload(context, fresh) }
        }

        /** 上传一层额外兜底：即使 uploadSample 本身抛异常，也只记录不崩。 */
        private suspend fun runCatchingUpload(context: Context, location: Location): Boolean =
            try {
                uploadSample(context, location)
            } catch (t: Throwable) {
                Log.e(TAG, "Location upload failed", t)
                recordFailure("上报异常: ${describe(t)}")
                false
            }

        /** 把一条位置 POST 到 /location，并把结果写入状态。 */
        private suspend fun uploadSample(context: Context, location: Location): Boolean {
            val uploader = runCatching { StorageUploader.create(context) }.getOrNull()
            if (uploader !is HttpUploader) {
                recordFailure("当前存储方式不是 HTTP，无法上报定位")
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
                if (ok) recordSuccess(location, source)
                else recordFailure("上报失败：服务器未接受（检查上传地址与 Key）")
                Log.i(TAG, "Location ${location.latitude},${location.longitude} acc=${location.accuracy}m ok=$ok")
                ok
            } catch (t: Throwable) {
                Log.e(TAG, "Location upload failed", t)
                recordFailure("上报异常: ${describe(t)}")
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
            LocationStatus.update { it.copy(uploadFailed = it.uploadFailed + 1, lastError = message) }
        }

        /** 异常摘要：类名 + 消息 + 第一个业务栈帧（便于远程排错）。 */
        private fun describe(t: Throwable): String {
            // stackTrace 在某些 JVM 异常（1.4 及以下/部分厂商实现）可能为 null
            val frames = t.stackTrace ?: emptyArray()
            val frame = frames.firstOrNull { it.className.startsWith("com.omi4wos") }
                ?: frames.firstOrNull()
            val where = frame?.let { "${it.fileName}:${it.lineNumber}" } ?: "-"
            return "${t.javaClass.simpleName}: ${t.message ?: ""} @$where"
        }

        /**
         * 主动请求一次单次定位（网络源优先，省电）。超时或失败返回 null。
         *
         * API 30+ 走 `getCurrentLocation`（无监听器回调，最稳）；API 28/29 才用
         * 传统的 `requestLocationUpdates` + 延迟注销。所有分支均不抛异常。
         */
        private suspend fun freshLocation(context: Context, timeoutMs: Long): Location? =
            withTimeoutOrNull(timeoutMs) {
                val app = context.applicationContext
                val lm = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    ?: return@withTimeoutOrNull null
                val provider = listOf(
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.GPS_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                ).firstOrNull { p ->
                    runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)
                } ?: return@withTimeoutOrNull null

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    currentLocationOnce(lm, provider, app)
                } else {
                    legacySingleFix(lm, provider, timeoutMs)
                }
            }

        /** API 30+：`getCurrentLocation` 单次定位，无监听器重入风险。 */
        @RequiresApi(Build.VERSION_CODES.R)
        private suspend fun currentLocationOnce(
            lm: LocationManager,
            provider: String,
            context: Context
        ): Location? = suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { runCatching { signal.cancel() } }
            try {
                lm.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    runCatching { if (cont.isActive) cont.resume(location) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "getCurrentLocation failed for $provider: ${t.message}")
                runCatching { if (cont.isActive) cont.resume(null) }
            }
        }

        /**
         * API 28/29：传统回调式单次定位。
         *
         * ⚠️ 关键：必须在 [timeoutMs] 内向框架线程延迟注销监听，否则 GPS 永不回调时
         * 监听器会永久驻留（耗电 + 每次手动上报叠加一个）。这里自行兜底，不依赖
         * 外层 withTimeoutOrNull 的取消。
         */
        private suspend fun legacySingleFix(
            lm: LocationManager,
            provider: String,
            timeoutMs: Long
        ): Location? = suspendCancellableCoroutine { cont ->
            val mainHandler = Handler(Looper.getMainLooper())
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)

            // listener 作为参数传入，避免局部函数前向引用（Kotlin 不允许）
            fun finish(target: LocationListener?, location: Location?) {
                if (!finished.compareAndSet(false, true)) return
                if (target != null) {
                    runCatching { mainHandler.post { runCatching { lm.removeUpdates(target) } } }
                }
                runCatching { if (cont.isActive) cont.resume(location) }
            }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) = finish(this, location)

                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) = finish(this, null)

                @Deprecated("Deprecated in API 29")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            try {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            } catch (t: Throwable) {
                Log.w(TAG, "requestLocationUpdates failed for $provider: ${t.message}")
                finish(listener, null)
                return@suspendCancellableCoroutine
            }
            // 兜底注销：超时后主动摘掉监听器
            mainHandler.postDelayed({ finish(listener, null) }, timeoutMs + 500L)
            cont.invokeOnCancellation { finish(listener, null) }
        }

        /** 从系统缓存拿最新的可用位置：优先 GPS, 其次网络/被动。 */
        private fun lastKnownBest(context: Context, maxAgeMs: Long): Location? = runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val now = System.currentTimeMillis()
            val candidates = buildList {
                tryRun { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { add(it) } }
                tryRun { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { add(it) } }
                tryRun { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)?.let { add(it) } }
            }
            candidates
                .filter { it.time > 0 && (now - it.time) < maxAgeMs }
                .filter { it.accuracy <= MAX_ACCURACY_M || it.accuracy <= 0f }
                .maxByOrNull { it.time }
        }.onFailure { Log.d(TAG, "lastKnownBest failed: ${it.message}") }.getOrNull()

        private inline fun tryRun(block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                Log.d(TAG, "Provider read failed: ${t.message}")
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    /** 上一次成功采样并上传的位置时间戳, 避免同一条位置重复传。 */
    @Volatile private var lastSampledAt = 0L
    @Volatile private var started = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            sampleAndUpload()
            handler.postDelayed(this, currentIntervalMs())
        }
    }

    /** 启动周期采样：幂等，重复调用不会叠加采样循环。 */
    fun startOnce() {
        if (started) return
        started = true
        start()
    }

    /** 启动周期采样；无权限时只记录状态, 不崩溃。间隔/开关从配置读取。 */
    fun start() {
        val config = runCatching { OmiConfig(context).getConfig() }.getOrNull()
        if (config != null && !config.location.enabled) {
            Log.i(TAG, "Location uploader disabled by settings, skip start")
            return
        }
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, GRACE_MS)
        scope.launch { runCatching { refreshStatus(context.applicationContext) } }
        Log.i(TAG, "Location uploader started (every ${currentIntervalMs() / 60000} min)")
        AppLog.i(TAG, "定位上传器已启动（每 ${currentIntervalMs() / 60000} 分钟采样一次，首次 ${GRACE_MS / 1000}s 后）")
    }

    /** 读取用户配置的采样间隔（分钟→毫秒），异常回退默认 15 分钟。 */
    private fun currentIntervalMs(): Long {
        val min = runCatching { OmiConfig(context).getConfig() }.getOrNull()
            ?.location?.intervalMin?.coerceIn(1, 1440) ?: (INTERVAL_MS / 60_000L).toInt()
        return min * 60_000L
    }

    /** 设置变更（开关/间隔）后重排下一次采样。 */
    fun applySettings() {
        val config = runCatching { OmiConfig(context).getConfig() }.getOrNull()
        if (config != null && !config.location.enabled) {
            stop()
            return
        }
        if (!started) { started = true }
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, GRACE_MS)
        scope.launch { runCatching { refreshStatus(context.applicationContext) } }
        val m = currentIntervalMs() / 60_000L
        Log.i(TAG, "Location uploader settings applied (every $m min)")
        AppLog.i(TAG, "定位上报设置已生效（每 $m 分钟）")
    }

    fun stop() {
        handler.removeCallbacks(tickRunnable)
        scope.cancel()
        started = false
        instance = null
        Log.i(TAG, "Location uploader stopped")
        AppLog.i(TAG, "定位上传器已停止")
    }

    /** 周期采样：只读缓存，不主动唤醒 GPS。 */
    private fun sampleAndUpload() {
        val ctx = context.applicationContext
        if (!hasLocationPermission(ctx)) {
            LocationStatus.update {
                it.copy(
                    lastAttemptAt = System.currentTimeMillis(),
                    hasPermission = false,
                    lastError = "未授予定位权限，请在系统设置中允许"
                )
            }
            return
        }
        scope.launch {
            try {
                val location = lastKnownBest(ctx, CACHE_MAX_AGE_MS)
                if (location == null) {
                    recordFailure(
                        if (hasBackgroundLocationPermission(ctx)) {
                            "无可用位置（系统定位缓存为空或已过期）"
                        } else {
                            "无可用位置：后台定位权限未授予，请在设置中选择「始终允许」"
                        }
                    )
                    return@launch
                }
                // 跳过与上一次相同时间戳的重复位置
                if (location.time in (lastSampledAt - GRACE_MS)..(lastSampledAt + GRACE_MS)) return@launch
                lastSampledAt = location.time

                uploadSample(ctx, location)
            } catch (t: Throwable) {
                Log.e(TAG, "Location sample error", t)
                recordFailure("采样异常: ${describe(t)}")
            }
        }
    }
}
