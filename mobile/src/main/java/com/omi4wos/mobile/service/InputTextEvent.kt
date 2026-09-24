package com.omi4wos.mobile.service

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一条输入文本采集事件。
 *
 * 由 [InputTextAccessibilityService] 在捕获到 TYPE_VIEW_TEXT_CHANGED 时构造，
 * 先经 [InputTextRepository] 落到本地 JSONL，再由 [InputTextUploadWorker] 上传。
 *
 * JSONL 行格式与音频段元数据风格保持一致（snake_case 字段）。
 */
data class InputTextEvent(
    /** 包名，如 com.tencent.mm */
    val packageName: String,
    /** 应用可读名（拿不到时回落为包名） */
    val appLabel: String,
    /** 当前窗口标题（可能为空） */
    val windowTitle: String,
    /** 采集到的文本 */
    val text: String,
    /** 事件时间 epoch ms */
    val timestampMs: Long,
    /** 文本长度（冗余字段，便于服务端统计） */
    val textLength: Int = text.length
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("package_name", packageName)
        put("app_label", appLabel)
        put("window_title", windowTitle)
        put("text", text)
        put("text_length", textLength)
        put("timestamp", isoFmt.format(Date(timestampMs)))
        put("timestamp_ms", timestampMs)
        put("source", SOURCE)
    }

    fun toJsonLine(): String = toJson().toString()

    companion object {
        /** 与音频上传的 source 字段语义一致，服务端据此区分数据来源 */
        const val SOURCE = "phone_input_text"

        private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    }
}
