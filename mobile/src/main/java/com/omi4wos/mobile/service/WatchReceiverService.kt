package com.omi4wos.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.omi4wos.shared.Constants

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

    /** 周期刷新通知时间戳, 让状态栏显示相对时间(刚刚/几分钟前), 而非停滞的绝对时刻 */
    private val handler = Handler(Looper.getMainLooper())
    private val REFRESH_INTERVAL_MS = 30_000L // 30 秒刷新一次
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshNotification()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        Log.d(TAG, "Message received: ${event.path} size=${event.data.size}")
        AudioReceiverService.processMessage(applicationContext, event.path, event.data)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        messageClient = Wearable.getMessageClient(this)
        messageClient.addListener(messageListener)
        Log.i(TAG, "Watch message listener registered")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 定位采样: 只在服务进程中初始化一次 (START_STICKY 可能多次回调 onStartCommand)
        if (locationUploader == null) {
            locationUploader = LocationUploader(applicationContext).also { it.start() }
        }
        // Android 14+ requires the service type to be passed to startForeground()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
        return START_STICKY
    }

    /** 重建并重发通知, 时间戳随之刷新为当前时间 */
    private fun refreshNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
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
        messageClient.removeListener(messageListener)
        Log.i(TAG, "Watch message listener unregistered")
        super.onDestroy()
    }
}
