package com.omi4wos.mobile.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.omi4wos.mobile.CrashLogActivity
import com.omi4wos.mobile.omi.OmiConfig
import com.omi4wos.shared.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Persistent foreground service that holds a MessageClient listener so messages
 * from the watch are received even when the app UI is in the background.
 *
 * Samsung's FreecessHandler freezes background processes, preventing
 * WearableListenerService from being started by GMS. Running as a foreground
 * service keeps the process alive and exempt from that freezing.
 */
class WatchReceiverService : Service() {

    companion object {
        private const val TAG = "WatchReceiverService"
        private const val NOTIFICATION_ID = 1003

        /** 「导出冲突日志」动作按钮的 PendingIntent requestCode。 */
        private const val REQUEST_EXPORT_LOG = 7301
    }

    private lateinit var messageClient: MessageClient
    private var locationUploader: LocationUploader? = null
    private var callStateListener: CallStateListener? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 周期刷新通知时间戳, 让状态栏显示相对时间(刚刚/几分钟前), 而非停滞的绝对时刻 */
    private val handler = Handler(Looper.getMainLooper())
    private val REFRESH_INTERVAL_MS = 30_000L // 30 秒刷新一次
    private val refreshRunnable = object : Runnable {
        override fun run() {
            runCatching { refreshNotification() }
                .onFailure { AppLog.e(TAG, "刷新通知失败", it) }
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        Log.d(TAG, "Message received: ${event.path} size=${event.data.size}")
        runCatching {
            AudioReceiverService.processMessage(applicationContext, event.path, event.data)
        }.onFailure {
            // 监听器回调里任何异常都会直接杀死进程 —— 必须就地拦截
            Log.e(TAG, "processMessage failed", it)
            AppLog.e(TAG, "处理手表消息失败: ${event.path}", it)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.ensure(applicationContext)
        AppLog.i(TAG, "onCreate: 注册手表消息监听")
        createNotificationChannel()
        messageClient = Wearable.getMessageClient(this)
        messageClient.addListener(messageListener)
        Log.i(TAG, "Watch message listener registered")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 定位采样: 只在服务进程中初始化一次 (START_STICKY 可能多次回调 onStartCommand)
        if (locationUploader == null) {
            locationUploader = LocationUploader.get(applicationContext).also { it.startOnce() }
        }
        syncCallPauseListener()
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
        startOrRefreshForeground()
        return START_STICKY
    }

    /**
     * 按配置同步通话暂停监听：开启且已授予 READ_PHONE_STATE 则注册，否则注销。
     * 默认开启（callPause.enabled 默认 true）；无权限时静默不生效。
     *
     * 【2026-09-20 修复 P0】整段包 runCatching。本方法跑在 Dispatchers.IO，
     * 而 CallStateListener 的父类构造在旧版会因缺少 Looper 抛 NPE；
     * 该 NPE 以前经协程作用域冒泡成未捕获异常 → 进程崩溃 → START_STICKY 重建 →
     * 再次进入本方法 → 再崩，形成永久闪退。父类构造器已修为传主线程 Looper，
     * 这里再加一层保险：任何构造/注册异常都不得掀翻服务进程。
     *
     * 注：用 SupervisorJob 的 scope 不保证捕获 —— launch 内的异常若未被捕获
     * 会走 CoroutineExceptionHandler → 默认处理器 → 进程崩溃。所以必须就地捕获。
     */
    private fun syncCallPauseListener() {
        serviceScope.launch {
            runCatching {
                val cfg = runCatching { OmiConfig(applicationContext).getConfig() }.getOrNull()
                val enabled = cfg?.callPause?.enabled != false
                val hasPerm = ContextCompat.checkSelfPermission(
                    applicationContext, Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
                if (enabled && hasPerm) {
                    if (callStateListener == null) {
                        callStateListener = CallStateListener(applicationContext).also { it.register() }
                    }
                } else {
                    callStateListener?.unregister()
                    callStateListener = null
                }
            }.onFailure {
                AppLog.e(TAG, "同步通话监听失败（已忽略，不影响服务）", it)
                callStateListener = null
            }
        }
    }

    /** 重建并重发通知, 时间戳随之刷新为当前时间 */
    private fun refreshNotification() {
        startOrRefreshForeground()
    }

    /**
     * 以 dataSync (+ 可选的 location) 类型进入/刷新前台。
     *
     * Android 14 起，startForeground 传入的类型必须在运行时具备对应权限，否则抛
     * SecurityException。因此只有真正拿到定位权限时才叠加 LOCATION 类型。
     */
    private fun startOrRefreshForeground() {
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                if (LocationUploader.hasLocationPermission(this)) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                try {
                    startForeground(NOTIFICATION_ID, notification, types)
                } catch (e: Exception) {
                    Log.w(TAG, "startForeground with type $types failed, retrying dataSync only", e)
                    AppLog.w(TAG, "前台服务类型 $types 启动失败，回落 dataSync", e)
                    startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // 【2026-09-20】最外层兜底：本服务由 START_STICKY 托管，一旦在此抛出异常
            // （典型：Android 14+ foregroundServiceType 对应权限缺失 → SecurityException），
            // 进程会崩溃 → 系统立刻重建 → 再崩，形成「划掉 App 后永久闪退 / 屡次停止运行」。
            // 这里宁可暂时没有前台通知，也不能让进程崩掉。
            Log.e(TAG, "startForeground failed; service continues without foreground", t)
            AppLog.e(TAG, "前台服务启动失败（已降级为普通服务，避免崩溃循环）", t)
        }
    }

    private fun createNotification(): Notification {
        // 【2026-09-20】加一个「导出冲突日志」动作按钮。
        // 原因：进程级启动崩溃时用户根本进不去 App，之前的导出入口（首页卡片、
        // 错误页按钮）全部失效。而常驻通知由**服务**发出，服务一旦活着就有点 ——
        // 用户下拉通知栏就能把日志导出来发给我。
        val exportIntent = CrashLogActivity.intent(this, exportOnly = true)
        val exportPending = PendingIntent.getActivity(
            this,
            REQUEST_EXPORT_LOG,
            exportIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Constants.MOBILE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("omi4wOS")
            .setContentText("Listening for watch audio…")
            .setWhen(System.currentTimeMillis())
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "导出冲突日志", exportPending)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.MOBILE_NOTIFICATION_CHANNEL_ID,
            "Watch Receiver",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        locationUploader?.stop()
        locationUploader = null
        callStateListener?.destroy()
        callStateListener = null
        serviceScope.cancel()
        messageClient.removeListener(messageListener)
        Log.i(TAG, "Watch message listener unregistered")
        AppLog.i(TAG, "onDestroy: 注销手表消息监听")
        super.onDestroy()
    }

    /**
     * 【2026-09-20】用户在最近任务里划掉 App 时会回调这里。
     *
     * 本服务是 START_STICKY 的常驻服务（靠它收手表音频），划掉任务后继续运行
     * 是预期行为；这里只记一笔面包屑，方便下次进来在首页看到最后一步。
     * 全部包 runCatching：onTaskRemoved 里抛异常会直接带崩整个进程。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching {
            AppLog.i(TAG, "onTaskRemoved: 用户划掉了最近任务，保持常驻服务运行")
            CrashLogger.markStep(applicationContext, "WatchReceiver: onTaskRemoved")
        }
        super.onTaskRemoved(rootIntent)
    }
}
