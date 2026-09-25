package com.omi4wos.mobile.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 输入文本事件的本地存储。
 *
 * 落盘结构（应用私有目录，系统不自动清理）：
 *   filesDir/input_text/input_text_events_YYYY-MM-DD.jsonl   — 待上传
 *   filesDir/input_text/uploaded/...                         — 已上传留档
 *
 * 与音频上传保持同一策略：
 *   - 上传成功才从待传目录移除（move 到 uploaded/ 留档，不直接删）
 *   - 上传失败保留，等待 [InputTextUploadWorker] 周期重试
 *   - 不做超时清理
 */
class InputTextRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "InputTextRepository"
        const val INPUT_TEXT_DIR = "input_text"
        private const val UPLOADED_SUBDIR = "uploaded"

        @Volatile
        private var instance: InputTextRepository? = null

        fun getInstance(context: Context): InputTextRepository {
            return instance ?: synchronized(this) {
                instance ?: InputTextRepository(context.applicationContext).also { instance = it }
            }
        }

        /** 待上传目录，[InputTextUploadWorker] / 设置页共用 */
        fun getInputTextDir(context: Context): File {
            val dir = File(context.filesDir, INPUT_TEXT_DIR)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        /** 待上传积压字节数，供设置页展示 */
        fun getPendingBytes(context: Context): Long {
            return getInputTextDir(context)
                .listFiles()
                ?.filter { it.isFile && it.extension == "jsonl" }
                ?.sumOf { it.length() }
                ?: 0L
        }
    }

    private val dir = getInputTextDir(context)
    private val uploadedDir = File(dir, UPLOADED_SUBDIR).apply { if (!exists()) mkdirs() }
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** 当天待上传文件 */
    fun todayFile(timestampMs: Long = System.currentTimeMillis()): File =
        File(dir, "${filePrefix()}${dayFmt.format(Date(timestampMs))}.jsonl")

    private fun filePrefix() = "input_text_events_"

    /**
     * 追加一条事件到当天 JSONL。IO 线程执行。
     * @return 写入成功 true
     */
    suspend fun append(event: InputTextEvent): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = todayFile(event.timestampMs)
            synchronized(file) {
                FileOutputStream(file, true).use { out ->
                    out.write(event.toJsonLine().toByteArray(Charsets.UTF_8))
                    out.write('\n'.code)
                }
            }
            Log.d(TAG, "Appended input text (${event.textLength} chars) from ${event.packageName}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append input text event", e)
            false
        }
    }

    /** 所有待上传的 JSONL 文件（按文件名排序，即按日期） */
    fun pendingFiles(): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(filePrefix()) && it.extension == "jsonl" }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** 读取一个待上传文件的所有行（跳过空行） */
    fun readLines(file: File): List<String> =
        runCatching {
            file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        }.getOrElse {
            Log.e(TAG, "Failed to read ${file.name}", it)
            emptyList()
        }

    /**
     * 上传成功后处理：把文件移到 uploaded/ 留档。
     * 目标文件已存在时追加合并，避免同一日期多次上传互相覆盖。
     */
    suspend fun markUploaded(file: File) = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext
            val dest = File(uploadedDir, file.name)
            if (dest.exists()) {
                file.readBytes().let { bytes ->
                    FileOutputStream(dest, true).use { it.write(bytes) }
                }
                if (!file.delete()) Log.w(TAG, "Cannot delete uploaded file: ${file.name}")
            } else {
                if (!file.renameTo(dest)) {
                    dest.writeBytes(file.readBytes())
                    file.delete()
                }
            }
            Log.i(TAG, "Archived uploaded file: ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive ${file.name}", e)
        }
    }
}
