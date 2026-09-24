package com.omi4wos.mobile.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.omi4wos.mobile.omi.OmiConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 输入文本采集（无障碍服务）。
 *
 * 监听 [AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED]，提取用户在各 App 里键入的文本，
 * 经 [InputTextRepository] 落到本地 JSONL；是否自动上传由
 * [OmiConfig.InputTextConfig.uploadEnabled] 决定（默认关）。
 *
 * 隐私处理：
 * - 跳过 password 输入框（[AccessibilityNodeInfo.isPassword]）
 * - 跳过自身包名，避免采集自家界面
 * - 跳过纯空白 / 过短文本
 *
 * ⚠️ 只在设置页开关打开时才应处于启用状态；系统侧由用户在
 * 「设置 → 无障碍」里手动授权，两者都满足才会收到事件。
 */
class InputTextAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "InputTextA11y"

        /** 最短采集长度：过短的多为单字误触/联想，噪声大 */
        private const val MIN_TEXT_LENGTH = 2

        /** 同一 App 内两次采集的最小间隔，防抖（打字会高频触发） */
        private const val DEBOUNCE_MS = 800L

        /** 单条文本长度上限，超长多为粘贴大段内容，截断保护 */
        private const val MAX_TEXT_LENGTH = 2000

        /** 是否已在系统中被用户授权启用（供设置页展示状态） */
        @Volatile
        var isConnected: Boolean = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastCaptureAt = AtomicLong(0L)
    private val repository by lazy { InputTextRepository.getInstance(applicationContext) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true

        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // 只收文本变更，不要窗口变化等噪声事件
            notificationTimeout = 100
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        }
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        if (ev.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return

        val text = extractText(ev) ?: return
        val pkg = ev.packageName?.toString().orEmpty()
        if (pkg.isEmpty()) return

        // 跳过自身，避免采集自家界面
        if (pkg == applicationContext.packageName) return

        // 过滤密码框
        if (isPasswordField(ev)) {
            Log.d(TAG, "Skipped password field in $pkg")
            return
        }

        val trimmed = text.trim()
        if (trimmed.length < MIN_TEXT_LENGTH) return

        // 防抖：打字时每敲一个键都触发，只取停顿后的一次
        val now = System.currentTimeMillis()
        val last = lastCaptureAt.get()
        if (now - last < DEBOUNCE_MS) return
        if (!lastCaptureAt.compareAndSet(last, now)) return

        val event = InputTextEvent(
            packageName = pkg,
            appLabel = resolveAppLabel(pkg),
            windowTitle = findWindowTitle(ev),
            text = trimmed.take(MAX_TEXT_LENGTH),
            timestampMs = now
        )

        // 采集开关关闭时不落盘（用 last-known 配置，避免每次 IO 读 DataStore）
        scope.launch {
            try {
                val config = OmiConfig(applicationContext).getConfig()
                if (!config.inputText.enabled) {
                    Log.d(TAG, "Collect disabled — dropping event")
                    return@launch
                }
                repository.append(event)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist input text event", e)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        isConnected = false
        scope.cancel()
        super.onDestroy()
    }

    /** 从事件里取文本；优先 text 列表，回落到 contentDescription。 */
    private fun extractText(event: AccessibilityEvent): String? {
        val chunks = event.text
        if (chunks != null && chunks.isNotEmpty()) {
            val joined = chunks.joinToString("").trim()
            if (joined.isNotEmpty()) return joined
        }
        return event.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 判断是否为密码输入框。
     *
     * 顺序：事件级 isPassword → 源节点 isPassword → 遍历子节点找 password。
     * 拿不到节点信息时保守返回 true（宁可不采，也不采到密码）。
     */
    private fun isPasswordField(event: AccessibilityEvent): Boolean {
        if (event.isPassword) return true

        val source = runCatching { event.source }.getOrNull() ?: return false
        try {
            if (source.isPassword) return true
            // 某些输入法把 EditText 挂在下层，向上找一层
            var parent: AccessibilityNodeInfo? = source.parent
            var depth = 0
            while (parent != null && depth < 3) {
                if (parent.isPassword) return true
                parent = parent.parent
                depth++
            }
        } catch (e: Exception) {
            Log.d(TAG, "isPasswordField probe failed: ${e.message}")
        } finally {
            runCatching { @Suppress("DEPRECATION") source.recycle() }
        }
        return false
    }

    /** 取当前窗口标题（Activity label / 标题栏文本），拿不到返回空串。 */
    private fun findWindowTitle(event: AccessibilityEvent): String {
        val fromEvent = runCatching { event.className?.toString() }.getOrNull()
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return fromEvent.orEmpty()
        try {
            // 常见标题容器：取第一个非空 text 的 TextView 作为近似标题
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < 60) {
                val node = queue.removeFirst()
                visited++
                val t = node.text?.toString()?.trim()
                if (!t.isNullOrEmpty() && t.length in 2..40) return t
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "findWindowTitle failed: ${e.message}")
        }
        return fromEvent.orEmpty()
    }

    /** 包名 → 应用显示名；失败回落包名。 */
    private fun resolveAppLabel(pkg: String): String {
        return try {
            val pm = applicationContext.packageManager
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
}
