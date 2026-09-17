package com.omi4wos.mobile.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
     */
    private fun syncCallPauseListener() {
        serviceScope.launch {
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
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, Constants.MOBILE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("omi4wOS")
            .setContentText("Listening for watch audio…")
            .setWhen(System.currentTimeMillis())
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
}
