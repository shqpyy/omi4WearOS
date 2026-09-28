package com.omi4wos.mobile.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
 *
 * ## 字段语义提醒（2026-09-28）
 *
 * - [windowId]：**Android 系统窗口编号**，不是聊天会话标识。同一窗口实例存活期间稳定，
 *   窗口销毁重建（切会话、切 App 回来、进程重启）后会变，且号码会被系统回收复用。
 *   **仅可用于「一段连续输入」的分段，不可跨时间聚合到联系人。**
 *   旧名 `chat_id` 名不副实，已改名。
 * - [chatTitle]：聊天对象名/群名。**微信因无障碍屏蔽取不到（恒为空）**，
 *   华为联系人/抖音等 App 可正常取到。
 */
data class InputTextEvent(
    /** 包名，如 com.tencent.mm */
    val packageName: String,
    /** 应用显示名，如「微信」；取不到时回落包名 */
    val appLabel: String = "",
    /** 采集到的文本 */
    val text: String,
    /** 事件时间 epoch ms */
    val timestampMs: Long,
    /** 聊天对象名/群名；拿不到时为 empty */
    val chatTitle: String = "",
    /** 系统窗口编号（窗口实例标识，非会话标识）。见类注释。 */
    val windowId: String = ""
) {

    fun toWireJson(deviceId: String): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("timestamp", isoFmt.format(Date(timestampMs)))
        put("package_name", packageName)
        put("app_label", appLabel)
        put("text", text)
        put("window_id", windowId)
        put("chat_title", chatTitle)
    }

    /** 服务端扁平格式的 JSON 行（用于 HTTP 上传的 events 数组元素） */
    fun toWireJsonLine(deviceId: String): String = toWireJson(deviceId).toString()

    fun toJson(): JSONObject = JSONObject().apply {
        put("package_name", packageName)
        put("app_label", appLabel)
        put("text", text)
        put("chat_title", chatTitle)
        put("window_id", windowId)
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
                put("app_label", local.optString("app_label"))
                put("text", local.optString("text"))
                put("window_id", local.optString("window_id"))
                put("chat_title", local.optString("chat_title"))
            }.toString()
        }

        /**
         * 包名 → 应用显示名；失败回落包名。
         *
         * 从 [InputTextAccessibilityService] 挪到这里，与事件字段定义放一起。
         */
        fun resolveAppLabel(context: Context, pkg: String): String {
            return try {
                val pm = context.packageManager
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(pkg, 0)
                }
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                pkg
            }
        }

        private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    }
}
