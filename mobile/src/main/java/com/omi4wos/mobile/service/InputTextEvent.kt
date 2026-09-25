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

    /**
     * 服务端 /input-text 的上传字段定义。
     *
     * 契约来源：audio_server.py `save_input_text`
     *   {
     *     "device_id":    str,   // 参与去重 key, 不能为空
     *     "timestamp":    str,   // ISO 8601 带毫秒, 参与去重 key
     *     "package_name": str,   // 参与去重 key
     *     "text":         str,   // 参与去重 key
     *     "app_name":     str    // 仅展示, 不参与去重
     *   }
     */
    fun toWireJson(deviceId: String): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("package_name", packageName)
        put("app_name", appLabel)
        put("text", text)
        put("timestamp", isoFmt.format(Date(timestampMs)))
    }

    /** 服务端扁平格式的 JSON 行（用于 HTTP 上传的 events 数组元素） */
    fun toWireJsonLine(deviceId: String): String = toWireJson(deviceId).toString()

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

        /**
         * 把本地 JSONL 行（[toJson] 的产物, snake_case + 本地字段）
         * 转换为服务端 /input-text 契约格式。
         *
         * 本地行示例：
         *   {"package_name":..,"app_label":..,"window_title":..,"text":..,
         *    "text_length":..,"timestamp":..,"timestamp_ms":..,"source":..}
         *
         * 服务端期望：{device_id, timestamp, package_name, text, app_name}
         * 其中 device_id 本地不落盘（避免每行冗余），上传时补上。
         *
         * @param rawLine  本地 JSONL 的一行
         * @param deviceId 设备标识（Android ID）
         * @throws org.json.JSONException 行不是合法 JSON 时抛出，由调用方跳过
         */
        fun toWireJsonLine(rawLine: String, deviceId: String): String {
            val local = JSONObject(rawLine)
            return JSONObject().apply {
                put("device_id", deviceId)
                put("package_name", local.optString("package_name"))
                put("app_name", local.optString("app_label").ifBlank {
                    local.optString("package_name")
                })
                put("text", local.optString("text"))
                put("timestamp", local.optString("timestamp"))
            }.toString()
        }

        private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    }
}
