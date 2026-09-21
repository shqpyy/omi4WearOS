package com.omi4wos.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.omi4wos.mobile.omi.OmiConfig
import com.omi4wos.mobile.service.AppLog
import com.omi4wos.mobile.service.CrashLogger
import com.omi4wos.mobile.service.UploadRetryWorker
import com.omi4wos.mobile.service.WatchReceiverService
import com.omi4wos.mobile.ui.MobileApp
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val LOCATION_PERMISSION_CODE = 4101
        private const val PHONE_STATE_PERMISSION_CODE = 4102
    }

    /**
     * SettingsViewModel.updateLanguage 调用 AppCompatDelegate.setApplicationLocales 后，
     * API 33+ 会自动 recreate；API < 33 下次启动时这里会应用新语言。
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        // 注意：这里绝不能 runBlocking 阻塞主线程读 DataStore。
        // attachBaseContext 是 Activity 生命周期最早阶段（早于 onCreate），
        // 一旦 DataStore 有残留文件锁，主线程就会死锁 → ANR / 连续崩溃，
        // 而且此时 showFatalError 兜底还没机会安装，用户只能看到闪退。
        // 因此改为顺序读先前落盘的轻量镜像文件（同步 I/O，微秒级，无挂起风险）。
        val lang = try {
            OmiConfig.readLanguageSync(newBase)
        } catch (t: Throwable) { OmiConfig.DEFAULT_LANGUAGE }

        val wrapped = if (lang != "system") {
            val locale = Locale.forLanguageTag(lang)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            newBase.createConfigurationContext(config)
        } else {
            newBase
        }
        super.attachBaseContext(wrapped)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
        } catch (e: Throwable) {
            // 恢复态崩兜底：先装日志，记一步，再抛，保证下次能进来看日志
            runCatching { AppLog.install(this) }
            runCatching { CrashLogger.install(this) }
            AppLog.e("MainActivity", "super.onCreate failed", e)
            CrashLogger.markStep(this, "MainActivity.onCreate restore crash")
            showFatalError(e)
            return
        }

        // 最早时机安装日志 + 崩溃记录器
        AppLog.install(this)
        CrashLogger.install(this)

        try {
            // Start the persistent foreground service that receives watch messages
            ContextCompat.startForegroundService(
                this, Intent(this, WatchReceiverService::class.java)
            )
            scheduleUploadRetry()
            requestBatteryOptimizationExemption()
            maybeRequestLocationPermission()
            maybeRequestPhoneStatePermission()
            setContent {
                MobileApp()
            }
        } catch (e: Throwable) {
            // 初始化阶段兜底：任何后续步骤崩，都先记日志，然后显示错误页而不是直接闪退
            AppLog.e("MainActivity", "init failed", e)
            CrashLogger.markStep(this, "MainActivity.init crash")
            showFatalError(e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        val label = if (requestCode == LOCATION_PERMISSION_CODE) {"定位"} else if (requestCode == PHONE_STATE_PERMISSION_CODE) {"电话"} else {"其他"}
        AppLog.i("MainActivity", "权限回调: $label 授权=$granted")
        // 授权后显式刷新一次状态，避免隐式重建导致的状态不一致
        if (granted) {
            AppLog.i("MainActivity", "权限已授予，刷新相关状态")
        }
    }

    /**
     * 显示纯文本错误页，保证用户即使 UI 崩了也能进来看日志导出。
     *
     * 关键：这个页面**不用 Compose**，只用原生 View —— 因为崩溃很可能就发生在
     * Compose 初始化阶段，用 Compose 兜底等于没兜。
     *
     * 按钮作用：
     * - 「导出错误信息」：把堆栈发出来（闪退时进不去设置页，只能从这里拿）
     * - 「清空配置并重试」：一键复位 DataStore，避免坏配置导致永远进不去
     */
    private fun showFatalError(e: Throwable) {
        val detail = buildString {
            append("App 启动失败\n\n")
            append("${e.javaClass.name}: ${e.message ?: ""}\n\n")
            e.stackTrace.take(20).forEach { append("  at $it\n") }
            append("\n最近一步: ${runCatching { CrashLogger.lastStep(this@MainActivity) }.getOrNull() ?: "?"}\n")
            append("\n如果反复出现，请点「清空配置并重试」。")
        }

        val scroll = android.widget.ScrollView(this).apply {
            setPadding(48, 48, 48, 48)
            addView(android.widget.TextView(this@MainActivity).apply {
                text = detail
                textSize = 14f
                setTextColor(0xFFB71C1C.toInt())
                setTextIsSelectable(true)
            })
        }

        val exportBtn = android.widget.Button(this).apply {
            text = "导出错误信息"
            setOnClickListener {
                runCatching { AppLog.shareAsText(this@MainActivity, "omi4wOS 启动失败", detail) }
            }
        }
        val resetBtn = android.widget.Button(this).apply {
            text = "清空配置并重试"
            setOnClickListener {
                runCatching {
                    kotlinx.coroutines.runBlocking { OmiConfig(this@MainActivity).clearConfig() }
                }
                android.widget.Toast.makeText(
                    this@MainActivity, "配置已清空，请重新打开 App", android.widget.Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(scroll, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(android.widget.LinearLayout(this@MainActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                addView(exportBtn)
                addView(resetBtn)
            })
        }
        setContentView(layout)
    }

    /**
     * 首次启动时请求定位权限（用于周期位置上传）。只请求一次；
     * 若用户拒绝, LocationUploader 会自动跳过采样, 后续可在系统设置重新授权。
     */
    private fun maybeRequestLocationPermission() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            Log.i(TAG, "Requesting location permission for periodic upload")
            AppLog.i(TAG, "请求定位权限: ${needed.joinToString()}")
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), LOCATION_PERMISSION_CODE)
        }
    }

    /**
     * 首次启动时请求 READ_PHONE_STATE（用于通话时暂停手表录音）。只请求一次；
     * 拒绝后该功能静默不生效, 可在系统设置重新授权。
     */
    private fun maybeRequestPhoneStatePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Requesting READ_PHONE_STATE permission for call pause")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_PHONE_STATE),
                PHONE_STATE_PERMISSION_CODE
            )
        }
    }

    private fun scheduleUploadRetry() {
        val request = PeriodicWorkRequestBuilder<UploadRetryWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            UploadRetryWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Log.i(TAG, "Upload retry worker scheduled (15 min, network-constrained)")
        AppLog.i(TAG, "启动完成: 前台服务已拉起, 重试 Worker 已排程")
    }

    /**
     * Request battery optimization exemption so Samsung's FreecessHandler
     * doesn't freeze this process and block GMS from delivering watch messages.
     */
    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.i(TAG, "Requesting battery optimization exemption")
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not show battery optimization dialog, opening settings", e)
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    Log.e(TAG, "Could not open battery settings", e2)
                }
            }
        } else {
            Log.i(TAG, "Already exempt from battery optimization")
        }
    }
}
