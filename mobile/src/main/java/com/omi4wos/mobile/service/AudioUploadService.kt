package com.omi4wos.mobile.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.omi4wos.mobile.data.UploadRecord
import com.omi4wos.mobile.data.UploadRepository
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 接收 [AudioReceiverService] 组装好的 Opus 音频片段, 委托 [StorageUploader]
 * 上传/写入到用户配置的存储方案（本地文件/HTTP/S3）。
 *
 * Segments 按 syncId 缓存, 当收到 CMD_SYNC_END (ACTION_FLUSH_SYNC) 时, 同一 syncId
 * 下所有片段按时间戳排序, 按 5min 间隔切分成多个 session, 每个 session 拼接成
 * 一个连续 opus 流, 一次性上传/写入。
 */
class AudioUploadService : Service() {

    companion object {
        private const val TAG = "AudioUploadService"

        const val ACTION_UPLOAD      = "com.omi4wos.mobile.ACTION_UPLOAD"
        const val ACTION_FLUSH_SYNC  = "com.omi4wos.mobile.ACTION_FLUSH_SYNC"
        const val EXTRA_SEGMENT_ID   = "segment_id"
        const val EXTRA_SYNC_ID      = "sync_id"
        const val EXTRA_AUDIO_DATA   = "audio_data"
        const val EXTRA_START_TIME   = "start_time"
        const val EXTRA_END_TIME     = "end_time"
        const val EXTRA_CONFIDENCE   = "confidence"
        const val EXTRA_BATTERY_LEVEL    = "battery_level"
        const val EXTRA_AUDIO_SIZE_BYTES = "audio_size_bytes"

        private const val SESSION_GAP_MS = 5 * 60 * 1000L
        private const val PENDING_SYNC_KEY = "__pending__"

        private val _isUploading = MutableStateFlow(false)
        val isUploading: StateFlow<Boolean> = _isUploading

        private val pendingBatchSegments =
            ConcurrentHashMap<String, MutableList<PendingSegment>>()

        private data class PendingSegment(
            val segmentId: String,
            val syncId: String,
            val audioData: ByteArray,
            val startTime: Long,
            val endTime: Long,
            val confidence: Float,
            val batteryLevel: Int,
            val audioSizeBytes: Long
        )

        private fun formatSize(bytes: Long): String = when {
            bytes < 1024L      -> "$bytes B"
            bytes < 1_048_576L -> "${"%.1f".format(bytes / 1024.0)} KB"
            else               -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: UploadRepository
    private lateinit var omiConfig: OmiConfig

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        repository = UploadRepository.getInstance(applicationContext)
        omiConfig = OmiConfig(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPLOAD -> {
                val segmentId    = intent.getStringExtra(EXTRA_SEGMENT_ID) ?: ""
                val syncId       = intent.getStringExtra(EXTRA_SYNC_ID) ?: ""
                val audioData    = intent.getByteArrayExtra(EXTRA_AUDIO_DATA)
                val startTime    = intent.getLongExtra(EXTRA_START_TIME, 0)
                val endTime      = intent.getLongExtra(EXTRA_END_TIME, 0)
                val confidence   = intent.getFloatExtra(EXTRA_CONFIDENCE, 0f)
                val batteryLevel = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                val audioSizeBytes = intent.getLongExtra(EXTRA_AUDIO_SIZE_BYTES, 0L)

                if (audioData != null && audioData.isNotEmpty()) {
                    val effectiveSyncId = syncId.ifEmpty { PENDING_SYNC_KEY }
                    bufferSegment(PendingSegment(segmentId, effectiveSyncId, audioData, startTime, endTime, confidence, batteryLevel, audioSizeBytes))
                }
            }
            ACTION_FLUSH_SYNC -> {
                val syncId = intent.getStringExtra(EXTRA_SYNC_ID) ?: ""
                if (syncId.isNotEmpty()) {
                    serviceScope.launch { flushBatch(syncId) }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun bufferSegment(segment: PendingSegment) {
        pendingBatchSegments
            .getOrPut(segment.syncId) { Collections.synchronizedList(mutableListOf()) }
            .add(segment)
        Log.d(TAG, "Buffered segment ${segment.segmentId} for batch syncId=${segment.syncId} " +
                   "(total=${pendingBatchSegments[segment.syncId]?.size})")
    }

    private suspend fun flushBatch(syncId: String) {
        delay(2_000)

        val racing = pendingBatchSegments.remove(PENDING_SYNC_KEY)
        if (!racing.isNullOrEmpty()) {
            Log.i(TAG, "Absorbing ${racing.size} pre-SYNC_START segment(s) into syncId=$syncId")
            pendingBatchSegments
                .getOrPut(syncId) { Collections.synchronizedList(mutableListOf()) }
                .addAll(racing)
        }

        val segments = pendingBatchSegments.remove(syncId)
        val orphaned = mutableListOf<PendingSegment>()
        for (key in pendingBatchSegments.keys.toList()) {
            pendingBatchSegments.remove(key)?.let { orphaned.addAll(it) }
        }
        if (orphaned.isNotEmpty()) {
            Log.w(TAG, "Absorbing ${orphaned.size} orphaned segment(s) into flush for $syncId")
        }

        val allSegments = (segments ?: emptyList()) + orphaned
        if (allSegments.isEmpty()) {
            Log.w(TAG, "Flush requested for syncId=$syncId but no buffered segments found")
            return
        }

        val sorted   = allSegments.sortedBy { it.startTime }
        val sessions = groupIntoSessions(sorted)
        val totalBytes = sorted.sumOf { it.audioSizeBytes }
        Log.i(TAG, "Flushing batch syncId=$syncId: ${sorted.size} segment(s) → " +
                   "${sessions.size} session(s), ${formatSize(totalBytes)} total")

        _isUploading.value = true
        try {
            val uploader = StorageUploader.create(applicationContext)
            for ((idx, session) in sessions.withIndex()) {
                uploadSession(uploader, session, syncId, sessionNum = idx + 1, totalSessions = sessions.size)
            }
        } finally {
            _isUploading.value = false
        }
    }

    private fun groupIntoSessions(sorted: List<PendingSegment>): List<List<PendingSegment>> {
        if (sorted.isEmpty()) return emptyList()
        val sessions = mutableListOf<MutableList<PendingSegment>>()
        var current  = mutableListOf(sorted[0])
        var prevEnd  = sorted[0].endTime

        for (seg in sorted.drop(1)) {
            if (seg.startTime - prevEnd > SESSION_GAP_MS) {
                sessions.add(current)
                current = mutableListOf()
            }
            current.add(seg)
            prevEnd = maxOf(prevEnd, seg.endTime)
        }
        if (current.isNotEmpty()) sessions.add(current)
        return sessions
    }

    private suspend fun uploadSession(
        uploader: StorageUploader,
        segments: List<PendingSegment>,
        syncId: String,
        sessionNum: Int,
        totalSessions: Int
    ) {
        val label = if (totalSessions > 1) " (session $sessionNum/$totalSessions)" else ""
        val sorted = segments.sortedBy { it.startTime }

        val totalAudioSize = sorted.sumOf { it.audioData.size }
        val combined = ByteArray(totalAudioSize)
        var offset = 0
        for (seg in sorted) {
            System.arraycopy(seg.audioData, 0, combined, offset, seg.audioData.size)
            offset += seg.audioData.size
        }

        val uploadName = "recording_fs320_${sorted.first().startTime / 1000}.bin"
        val totalBytes = sorted.sumOf { it.audioSizeBytes }
        Log.i(TAG, "Session upload$label: ${sorted.size} segment(s) → 1 file, ${formatSize(totalBytes)}")

        try {
            val ok = uploader.upload(
                audioData = combined,
                uploadName = uploadName,
                segmentId = sorted.first().segmentId,
                syncId = syncId,
                startTimeMs = sorted.first().startTime,
                endTimeMs = sorted.last().endTime,
                confidence = sorted.map { it.confidence }.average().toFloat(),
                batteryLevel = sorted.map { it.batteryLevel }.filter { it >= 0 }
                    .let { if (it.isNotEmpty()) it.average().toInt() else -1 },
                source = "watch"
            )

            if (ok) {
                val timeFmt   = SimpleDateFormat("hh:mma", Locale.getDefault())
                val dateFmt   = SimpleDateFormat("MM/dd/yy hh:mma", Locale.getDefault())
                val avgBattery = sorted.map { it.batteryLevel }.filter { it >= 0 }
                    .let { if (it.isNotEmpty()) it.average().toInt() else -1 }
                val batteryStr = if (avgBattery >= 0) "$avgBattery%" else "?%"

                for (seg in sorted) {
                    val text = "${timeFmt.format(Date())}  |  Watch Battery: $batteryStr\n" +
                               "Uploaded (${omiConfig.getConfig().storageMethod.name}): ${formatSize(seg.audioSizeBytes)}\n" +
                               "Spanning ${dateFmt.format(Date(seg.startTime))} to ${dateFmt.format(Date(seg.endTime))}"
                    saveRecord(seg.segmentId, syncId, text, seg.startTime, seg.endTime,
                        seg.confidence, seg.audioSizeBytes, seg.batteryLevel, uploaded = true)
                }
            } else {
                for (seg in sorted) {
                    saveRecord(seg.segmentId, syncId, "[Upload Failed]",
                        seg.startTime, seg.endTime, seg.confidence, seg.audioSizeBytes, seg.batteryLevel, uploaded = false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Session upload failed$label", e)
            for (seg in sorted) {
                saveRecord(seg.segmentId, syncId, "[Upload error: ${e.message}]",
                    seg.startTime, seg.endTime, seg.confidence, seg.audioSizeBytes, seg.batteryLevel, uploaded = false)
            }
        }
    }

    private suspend fun saveRecord(
        segmentId: String,
        syncId: String,
        text: String,
        startTime: Long,
        endTime: Long,
        confidence: Float,
        audioSizeBytes: Long,
        batteryLevel: Int,
        uploaded: Boolean
    ) {
        try {
            repository.insert(
                UploadRecord(
                    segmentId = segmentId,
                    syncId = syncId,
                    text = text,
                    timestamp = startTime,
                    endTimestamp = endTime,
                    speechConfidence = confidence,
                    uploadedToOmi = uploaded,
                    audioSizeBytes = audioSizeBytes,
                    watchBatteryLevel = batteryLevel
                )
            )
            Log.d(TAG, "Record saved: $segmentId uploaded=$uploaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save record", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.MOBILE_NOTIFICATION_CHANNEL_ID,
            "Audio Upload Service",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
