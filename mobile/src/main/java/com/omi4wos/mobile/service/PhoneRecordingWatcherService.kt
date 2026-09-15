package com.omi4wos.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omi4wos.mobile.MainActivity
import com.omi4wos.mobile.omi.OmiConfig
import com.omi4wos.mobile.storage.StorageUploader
import com.omi4wos.shared.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 最近一次通话录音扫描的结果, 暴露给 Home 诊断面板 (无需 adb)。 */
data class WatcherScanStatus(
    val lastScanTime: Long = 0L,
    val watchDir: String = "",
    val enabled: Boolean = false,
    val scannedToday: Int = 0,
    val matchedToUpload: Int = 0,
    val uploadedOk: Int = 0,
    val uploadFailed: Int = 0,
    val lastError: String? = null
)

object WatcherStatus {
    private val _flow = MutableStateFlow(WatcherScanStatus())
    val status: StateFlow<WatcherScanStatus> = _flow.asStateFlow()
    fun update(t: (WatcherScanStatus) -> WatcherScanStatus) { _flow.value = t(_flow.value) }
}

/**
 * 通话录音/手机本地音频监听服务。
 *
 * 持久前台服务（持久通知无法消除），按配置的间隔扫描：
 *   - 优先用 SAF tree URI（用户在 Settings 页用"选择目录"按钮授权的）
 *   - 次选手动输入路径（[OmiConfig.PhoneWatcherConfig.watchDir]）
 *
 * 扫描逻辑：
 *   1. 列出目录下所有文件
 *   2. 按扩展名过滤
 *   3. 维护已处理文件名集合（内存 + cache 文件）
 *   4. 新文件 → 调 [StorageUploader.upload]
 *   5. 上传成功后写 cache 标记
 */
class PhoneRecordingWatcherService : Service() {

    companion object {
        private const val TAG = "PhoneWatcher"
        const val ACTION_START = "com.omi4wos.mobile.START_PHONE_WATCHER"
        const val ACTION_STOP  = "com.omi4wos.mobile.STOP_PHONE_WATCHER"

        // 已处理文件名记录（一行一个文件名）
        private const val PROCESSED_LIST_FILE = "phone_watcher_processed.txt"

        fun start(context: Context) {
            val intent = Intent(context, PhoneRecordingWatcherService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PhoneRecordingWatcherService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: kotlinx.coroutines.Job? = null
    private val processedFiles = mutableSetOf<String>()
    private var processedListFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        processedListFile = File(filesDir, PROCESSED_LIST_FILE)
        loadProcessedFiles()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(Constants.PHONE_WATCHER_NOTIFICATION_ID, buildNotification())
                startScanLoop()
            }
            ACTION_STOP -> {
                scanJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startScanLoop() {
        scanJob?.cancel()
        scanJob = serviceScope.launch {
            Log.i(TAG, "Phone watcher scan loop started")
            while (true) {
                try {
                    val config = OmiConfig(applicationContext).getConfig()
                    if (!config.phoneWatcher.enabled) {
                        Log.i(TAG, "Phone watcher disabled — stopping")
                        stopSelf()
                        return@launch
                    }

                    scanOnce(config)

                    val intervalSec = config.phoneWatcher.scanIntervalSec.coerceIn(15, 3600)
                    delay(intervalSec * 1000L)
                } catch (e: Exception) {
                    Log.e(TAG, "Scan loop error", e)
                    delay(60_000)
                }
            }
        }
    }

    private suspend fun scanOnce(config: OmiConfig.Config) {
        val watcher = config.phoneWatcher
        val patterns = watcher.filePatterns.split(';').mapNotNull { p ->
            p.trim().removePrefix("*").ifEmpty { null }
        }
        if (patterns.isEmpty()) {
            Log.w(TAG, "No file patterns configured")
            WatcherStatus.update { it.copy(lastScanTime = System.currentTimeMillis(), lastError = "No file patterns configured") }
            return
        }

        val todayStart = todayStartMs()
        val startTime = System.currentTimeMillis()
        val newFiles = mutableListOf<Pair<String, ByteArray>>()
        var scannedToday = 0
        var errorMsg: String? = null

        try {
            if (watcher.treeUri.isNotBlank()) {
                // SAF 路径
                val treeUri = Uri.parse(watcher.treeUri)
                val children = contentResolver.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri)
                    ),
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    ),
                    null, null, null
                )
                children?.use { c ->
                    val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    while (c.moveToNext()) {
                        val name = if (nameIdx >= 0) c.getString(nameIdx) else continue
                        if (!matchesPatterns(name, patterns)) continue
                        val lastMod = if (modIdx >= 0) c.getLong(modIdx) else 0L
                        // 只处理当天的文件, 历史录音不传; lastMod<=0 表示时间未知, 保守视为当天
                        if (lastMod > 0L && lastMod < todayStart) continue
                        scannedToday++
                        if (name !in processedFiles) {
                            val data = readFromSaf(treeUri, name)
                            if (data != null) newFiles.add(name to data)
                        }
                    }
                    if (nameIdx < 0) errorMsg = "SAF query returned no columns (permission not granted?)"
                }
            } else if (watcher.watchDir.isNotBlank()) {
                // File 路径
                val dir = File(watcher.watchDir)
                if (!dir.exists()) {
                    errorMsg = "Watch dir not found: ${dir.absolutePath}"
                    Log.w(TAG, errorMsg)
                } else {
                    dir.listFiles()?.forEach { f ->
                        if (!f.isFile || !matchesPatterns(f.name, patterns)) return@forEach
                        if (f.lastModified() < todayStart) return@forEach // 只处理当天
                        scannedToday++
                        if (f.name !in processedFiles) {
                            try { newFiles.add(f.name to f.readBytes()) }
                            catch (e: Exception) { Log.w(TAG, "Cannot read ${f.name}", e) }
                        }
                    }
                }
            } else {
                errorMsg = "No watch directory configured"
                Log.w(TAG, errorMsg)
            }
        } catch (e: Exception) {
            errorMsg = "Scan error: ${e.message}"
            Log.e(TAG, "Scan error", e)
        }

        val dirLabel = if (watcher.treeUri.isNotBlank()) watcher.treeUri else watcher.watchDir
        WatcherStatus.update {
            it.copy(
                lastScanTime = System.currentTimeMillis(),
                watchDir = dirLabel,
                enabled = config.phoneWatcher.enabled,
                scannedToday = scannedToday,
                matchedToUpload = newFiles.size,
                lastError = errorMsg
            )
        }

        if (newFiles.isEmpty()) return

        Log.i(TAG, "Found ${newFiles.size} new today's file(s), uploading via ${config.storageMethod.name}")

        val uploader = StorageUploader.create(applicationContext)
        var okCount = 0
        var failCount = 0
        for ((name, data) in newFiles) {
            try {
                val uploadName = "phone_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_${name}"
                val ok = uploader.upload(
                    audioData = data,
                    uploadName = uploadName,
                    segmentId = "",
                    syncId = "phone_watcher",
                    startTimeMs = startTime,
                    endTimeMs = startTime,
                    confidence = 0f,
                    batteryLevel = -1,
                    source = "phone"
                )
                if (ok) {
                    processedFiles.add(name)
                    saveProcessedFiles()
                    okCount++
                    Log.i(TAG, "Uploaded $name (${data.size} bytes)")
                } else {
                    failCount++
                    Log.w(TAG, "Upload failed for $name, will retry next scan")
                }
            } catch (e: Exception) {
                failCount++
                Log.e(TAG, "Upload error for $name", e)
            }
        }
        WatcherStatus.update { it.copy(uploadedOk = okCount, uploadFailed = failCount) }
    }

    /** 今天零点 (本地时区) 对应的毫秒时间戳。 */
    private fun todayStartMs(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun matchesPatterns(name: String, exts: List<String>): Boolean {
        val lower = name.lowercase()
        return exts.any { ext -> lower.endsWith(ext.lowercase()) }
    }

    private fun readFromSaf(treeUri: Uri, fileName: String): ByteArray? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val children = contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )
            children?.use { c ->
                val docIdIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val n = if (nameIdx >= 0) c.getString(nameIdx) else continue
                    if (n == fileName && docIdIdx >= 0) {
                        val childDocId = c.getString(docIdIdx)
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                        return contentResolver.openInputStream(childUri)?.use { it.readBytes() }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read $fileName via SAF", e)
            null
        }
    }

    private fun loadProcessedFiles() {
        try {
            processedListFile?.let { f ->
                if (f.exists()) {
                    f.readLines().forEach { line -> if (line.isNotBlank()) processedFiles.add(line) }
                    Log.i(TAG, "Loaded ${processedFiles.size} processed files")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load processed files", e)
        }
    }

    private fun saveProcessedFiles() {
        try {
            processedListFile?.let { f ->
                f.writeText(processedFiles.joinToString("\n"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save processed files", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.PHONE_WATCHER_NOTIFICATION_CHANNEL_ID,
            "Phone Recording Watcher",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Continuously watches phone for new recordings"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Constants.PHONE_WATCHER_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("omi4wos")
            .setContentText("Phone recording watcher is running")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
