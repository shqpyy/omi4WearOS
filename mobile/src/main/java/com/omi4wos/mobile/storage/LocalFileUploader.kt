package com.omi4wos.mobile.storage

import android.content.Context
import android.util.Log
import com.omi4wos.mobile.omi.OmiConfig
import com.omi4wos.shared.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地文件存储实现。
 *
 * 输出结构：
 *   <outputDir>/audio/YYYYMMDD/segment_<NNNN>.<ext>
 *   <outputDir>/segments.jsonl   (每行一条 JSON 元数据, 追加写入)
 *
 * 文件名按 uploadName 保留扩展名，序号在 process 内递增。
 */
class LocalFileUploader(
    private val context: Context,
    private val config: OmiConfig.LocalFileConfig
) : StorageUploader {

    companion object {
        private const val TAG = "LocalFileUploader"
    }

    private val outputDir = File(config.outputDir)
    private val jsonlFile: File by lazy { File(outputDir, "segments.jsonl") }
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    private val dateDirFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val sequence = AtomicInteger(scanMaxSequence())

    init {
        if (!outputDir.exists()) outputDir.mkdirs()
    }

    override suspend fun upload(
        audioData: ByteArray,
        uploadName: String,
        segmentId: String,
        syncId: String,
        startTimeMs: Long,
        endTimeMs: Long,
        confidence: Float,
        batteryLevel: Int,
        source: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                Log.e(TAG, "Cannot create output dir: ${outputDir.absolutePath}")
                return@withContext false
            }

            val dateDir = File(File(outputDir, "audio"), dateDirFmt.format(Date(startTimeMs)))
            if (!dateDir.exists()) dateDir.mkdirs()

            val ext = uploadName.substringAfterLast('.', "bin")
            val seq = sequence.incrementAndGet()
            val audioFileName = "segment_%04d.$ext".format(seq)
            val audioFile = File(dateDir, audioFileName)

            FileOutputStream(audioFile).use { it.write(audioData) }

            val relPath = "audio/${dateDirFmt.format(Date(startTimeMs))}/$audioFileName"
            val meta = JSONObject().apply {
                put("id", audioFileName.removeSuffix(".$ext"))
                put("original_name", uploadName)
                put("segment_id", segmentId)
                put("sync_id", syncId)
                put("start_time", isoFmt.format(Date(startTimeMs)))
                put("end_time", isoFmt.format(Date(endTimeMs)))
                put("duration_ms", endTimeMs - startTimeMs)
                put("audio_path", relPath)
                put("audio_size_bytes", audioData.size)
                put("battery_level", batteryLevel)
                put("speech_confidence", confidence.toDouble())  // 平台 org.json 无 put(String,float)
                put("source", source)
                put("is_final", true)
                put("exported_at", isoFmt.format(Date()))
            }

            synchronized(jsonlFile) {
                FileOutputStream(jsonlFile, true).use { out ->
                    out.write(meta.toString().toByteArray(Charsets.UTF_8))
                    out.write('\n'.code)
                }
            }

            Log.i(TAG, "Wrote segment: $relPath (${audioData.size} bytes, ${endTimeMs - startTimeMs} ms) source=$source")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write segment", e)
            false
        }
    }

    override suspend fun testConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                return@withContext Pair(false, "Cannot create dir: ${outputDir.absolutePath}")
            }
            val probe = File(outputDir, ".probe")
            probe.writeText("ok")
            val content = probe.readText()
            probe.delete()
            if (content == "ok") Pair(true, "Directory writable: ${outputDir.absolutePath}")
            else Pair(false, "Read/write mismatch")
        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }

    /**
     * 输入文本「上传」= 追加写入 <outputDir>/input_text_events.jsonl。
     *
     * LOCAL_FILE 后端不上网，仅本地归档，因此永远返回 true（写盘失败除外）。
     */
    override suspend fun uploadInputText(eventsJson: List<String>, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            if (eventsJson.isEmpty()) return@withContext true
            try {
                if (!outputDir.exists() && !outputDir.mkdirs()) {
                    Log.e(TAG, "Cannot create output dir: ${outputDir.absolutePath}")
                    return@withContext false
                }
                val target = File(outputDir, "input_text_events.jsonl")
                synchronized(target) {
                    FileOutputStream(target, true).use { out ->
                        for (jsonLine in eventsJson) {
                            out.write(jsonLine.toByteArray(Charsets.UTF_8))
                            out.write('\n'.code)
                        }
                    }
                }
                Log.i(TAG, "Appended input text to ${target.absolutePath} (from $fileName)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write input text", e)
                false
            }
        }

    /**
     * 扫描 audio/ 目录下所有 segment_XXXX.* 文件，取最大序号。
     * 没有文件返回 0。
     */
    private fun scanMaxSequence(): Int {
        val audioRoot = File(outputDir, "audio")
        if (!audioRoot.exists()) return 0
        var max = 0
        audioRoot.walkTopDown().forEach { f ->
            val name = f.name
            if (name.startsWith("segment_")) {
                val n = name.removePrefix("segment_")
                    .substringBefore('.')
                    .toIntOrNull() ?: 0
                if (n > max) max = n
            }
        }
        return max
    }
}
