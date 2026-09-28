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
    /** 采集到的文本 */
    val text: String,
    /** 事件时间 epoch ms */
    val timestampMs: Long,
    /** 聊天对象名/群名；拿不到时为 empty */
    val chatTitle: String = "",
    /** 输入框会话身份：pkg#windowId#viewId 的窗口+视图部分 */
    val chatId: String = ""
) {

    fun toWireJson(deviceId: String): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("timestamp", isoFmt.format(Date(timestampMs)))
        put("package_name", packageName)
        put("text", text)
        put("chat_id", chatId)
        put("chat_title", chatTitle)
    }

    /** 服务端扁平格式的 JSON 行（用于 HTTP 上传的 events 数组元素） */
    fun toWireJsonLine(deviceId: String): String = toWireJson(deviceId).toString()

    fun toJson(): JSONObject = JSONObject().apply {
        put("package_name", packageName)
        put("text", text)
        put("chat_title", chatTitle)
        put("chat_id", chatId)
        put("timestamp", isoFmt.format(Date(timestampMs)))
        put("timestamp_ms", timestampMs)
        put("source", SOURCE)
    }

    fun toJsonLine(): String = toJson().toString()

    companion object {
        const val SOURCE = "phone_input_text"

        fun toWireJsonLine(rawLine: String, deviceId: String): String {
            val local = JSONObject(rawLine)
            return JSONObject().apply {
                put("device_id", deviceId)
                put("timestamp", local.optString("timestamp"))
                put("package_name", local.optString("package_name"))
                put("text", local.optString("text"))
                put("chat_id", local.optString("chat_id"))
                put("chat_title", local.optString("chat_title"))
            }.toString()
        }

        private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    }
}
