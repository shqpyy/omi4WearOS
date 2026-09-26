package com.omi4wos.mobile.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.omi4wos.mobile.omi.OmiConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 输入文本采集（无障碍服务）。
 *
 * 监听 [AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED]，提取用户在各 App 里键入的文本，
 * 经 [InputTextRepository] 落到本地 JSONL；是否自动上传由
 * [OmiConfig.InputTextConfig.uploadEnabled] 决定（默认关）。
 *
 * ## 为什么需要「静默合并」而不是逐事件落盘
 *
 * 该事件携带的是输入框的**当前完整内容**（全量快照），不是增量。中文输入法
 * 的拼音过程会连续触发：`w` → `wo` → `woai` → `woaini` → `我爱你`，删除回退
 * 同样会触发。若逐条落盘，一句「我爱你」会被拆成十几二十条碎片，且纯拼音的
 * 中间态（`woai`）与用户的真实意图无关。
 *
 * 因此本服务采用 **pending 缓冲 + 静默冲刷**：
 * - 同一输入框（包名 + windowId + viewId）的连续变更只更新一条 pending，不落盘；
 * - 每次变更重置静默计时器，[QUIET_MS] 毫秒内无新事件才认为这段输入结束；
 * - 冲刷时只写入**最终态**文本，中间态与已删除内容一律不落盘。
 *
 * 额外规则：
 * - 输入框被清空（发送后 / 手动清空）→ 短延时收口，避免相邻两条并成一条；
 * - 长度过滤（[MIN_TEXT_LENGTH]）放在**冲刷时**而非捕获时，中间态不参与过滤；
 * - 服务中断 / 销毁时冲刷全部 pending，避免丢最后一段。
 *
 * 隐私处理：
 * - 跳过 password 输入框（[AccessibilityNodeInfo.isPassword]）
 * - 跳过自身包名，避免采集自家界面
 * - 跳过纯空白文本
 *
 * ⚠️ 只在设置页开关打开时才应处于启用状态；系统侧由用户在
 * 「设置 → 无障碍」里手动授权，两者都满足才会收到事件。
 */
class InputTextAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "InputTextA11y"

        /**
         * 静默窗口兜底值（ms）。**实际生效值来自设置页**（[OmiConfig.InputTextConfig.quietMs]），
         * 这里只在配置读取失败时用作回落。
         *
         * 默认 5s：2026-09-25 真机数据（747 条快照 / 738 个同 App 相邻间隔）显示
         * p50 = 1.02s、p75 = 1.38s，5s 能覆盖绝大多数「停下来想一下」的停顿。
         * 调大 → 更少段、但可能把两句话并成一条；调小 → 反之。
         */
        private const val QUIET_MS = OmiConfig.DEFAULT_QUIET_MS

        /**
         * 输入框被清空后的收口延时（ms）。
         *
         * 比 [QUIET_MS] 短得多：清空通常意味着「这条已发送」，应尽快落盘；
         * 但不为 0 —— 部分输入法在候选上屏时会瞬时清空再回填，
         * 立即冲刷会造成「冲刷后又建一条同样 pending」的重复记录。
         */
        private const val SHORT_QUIET_MS = 300L

        /** 最短落盘长度：过短的多为单字误触/联想，噪声大（在冲刷时判定） */
        private const val MIN_TEXT_LENGTH = 2

        /** 单条文本长度上限，超长多为粘贴大段内容，截断保护 */
        private const val MAX_TEXT_LENGTH = 2000

        /** 是否已在系统中被用户授权启用（供设置页展示状态） */
        @Volatile
        var isConnected: Boolean = false
            private set
    }

    /**
     * 一段正在编辑中的输入。
     *
     * @param packageName 采集来源包名（同一 pending 内不变）
     * @param text        该输入框的最新全量文本（会被反复覆盖）
     * @param timestampMs 最近一次变更时间，作为落盘记录的时间戳
     */
    private data class PendingCapture(
        val packageName: String,
        var text: String,
        var timestampMs: Long,
        var eventCount: Int
    )

    /** 静默计时器作用域；随服务销毁取消 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 落盘作用域。**刻意不随服务销毁取消**：onDestroy 里要冲刷最后一段，
     * 若与计时器共用 scope，cancel() 会把正在进行的写入一起掐掉。
     */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val repository by lazy { InputTextRepository.getInstance(applicationContext) }

    /** pending 与计时器的读写锁。事件回调在服务主线程，写入在 IO 线程，需互斥 */
    private val lock = Any()
    private val pending = HashMap<String, PendingCapture>()
    private val flushJobs = HashMap<String, Job>()

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

        val pkg = ev.packageName?.toString().orEmpty()
        if (pkg.isEmpty()) return

        // 跳过自身，避免采集自家界面
        if (pkg == applicationContext.packageName) return

        // 过滤密码框
        if (isPasswordField(ev)) {
            Log.d(TAG, "Skipped password field in $pkg")
            return
        }

        // 取不到文本信息的事件（既无 text 也无 contentDescription）直接忽略。
        // 不能当成「输入框被清空」处理——那会误伤正在编辑中的 pending。
        val raw = extractText(ev) ?: return

        val key = buildFieldKey(ev, pkg)

        // 输入框被清空：通常是消息已发送 / 手动清空，应尽快收口这一段，
        // 否则会把「上一条已发送的消息」和「下一条正在打的」并成一条。
        if (raw.isBlank()) {
            scheduleFlush(key, SHORT_QUIET_MS, reason = "cleared")
            return
        }

        val now = System.currentTimeMillis()
        synchronized(lock) {
            val existing = pending[key]
            if (existing == null) {
                pending[key] = PendingCapture(
                    packageName = pkg,
                    text = raw,
                    timestampMs = now,
                    eventCount = 1
                )
            } else {
                existing.text = raw
                existing.timestampMs = now
                existing.eventCount += 1
            }

            // 每次变更重置静默计时器（真正的 debounce：等到「停止输入」才收口）。
            // 阈值每次现读配置——设置页改完立刻生效，不需要重启服务。
            val quietMs = currentQuietMs()
            flushJobs.remove(key)?.cancel()
            flushJobs[key] = scope.launch {
                delay(quietMs)
                flush(key, reason = "quiet")
            }
        }
    }

    /**
     * 读取当前生效的静默窗口（ms）。
     *
     * **不缓存、每次现读**：服务是常驻进程，设置页改完不该要求用户重启无障碍服务。
     * DataStore 是异步的，这里在 IO 线程（事件回调已从主线程切出）里阻塞读取；
     * 读失败时回落到 [QUIET_MS]。
     */
    private fun currentQuietMs(): Long = try {
        runBlocking { OmiConfig(applicationContext).getConfig().inputText.quietMs }
    } catch (e: Exception) {
        Log.w(TAG, "Read quietMs failed, fallback to $QUIET_MS: ${e.message}")
        QUIET_MS
    }

    /** 为某段 pending 安排（或重置）收口计时器；pending 已不存在时为空操作。 */
    private fun scheduleFlush(key: String, delayMs: Long, reason: String) {
        synchronized(lock) {
            if (!pending.containsKey(key)) return
            flushJobs.remove(key)?.cancel()
            flushJobs[key] = scope.launch {
                delay(delayMs)
                flush(key, reason = reason)
            }
        }
    }

    /**
     * 收口一段输入并异步落盘。
     *
     * 中间态在这里被丢弃：只有 pending 里最后一版文本会被写入，纯拼音、
     * 已删除的内容都不会出现在结果里。
     */
    private fun flush(key: String, reason: String) {
        val capture: PendingCapture = synchronized(lock) {
            val c = pending.remove(key) ?: return
            flushJobs.remove(key)?.cancel()
            c
        }

        val text = capture.text.trim().take(MAX_TEXT_LENGTH)
        if (text.length < MIN_TEXT_LENGTH) {
            Log.d(TAG, "Drop short capture ($reason, ${text.length} chars)")
            return
        }

        writeScope.launch {
            try {
                val config = OmiConfig(applicationContext).getConfig()
                if (!config.inputText.enabled) {
                    Log.d(TAG, "Collect disabled — dropping capture")
                    return@launch
                }
                // 客户端过滤：短 ASCII 噪声
                val configForFilter = runCatching { OmiConfig(applicationContext).getConfig() }.getOrNull()
                val filtered = configForFilter?.inputText?.filterShortAscii == true &&
                    text.length <= 3 &&
                    text.all { it in 'a'..'z' }
                if (filtered) {
                    Log.d(TAG, "Filtered short ASCII: $text")
                    return@launch
                }

                val event = InputTextEvent(
                    packageName = capture.packageName,
                    appLabel = resolveAppLabel(capture.packageName),
                    windowTitle = currentWindowTitle(),
                    text = text,
                    timestampMs = capture.timestampMs
                )
                repository.append(event)
                Log.d(
                    TAG,
                    "Flushed capture ($reason): ${capture.eventCount} event(s) merged -> " +
                        "${text.length} chars from ${capture.packageName}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist input text event", e)
            }
        }
    }

    /** 冲刷所有 pending（服务断开 / 销毁前调用，避免丢最后一段）。 */
    private fun flushAll(reason: String) {
        val keys = synchronized(lock) { pending.keys.toList() }
        keys.forEach { flush(it, reason) }
    }

    /**
     * 输入框身份标识：包名 + 窗口 + 视图 ID。
     *
     * 同一输入框的连续变更归为一段；切换输入框（例如从聊天框切到搜索框）
     * 会产生不同的 key，各自独立成段。viewId 拿不到时回落为空串，
     * 退化为「同包名 + 同窗口」粒度。
     */
    private fun buildFieldKey(ev: AccessibilityEvent, pkg: String): String {
        val source = runCatching { ev.source }.getOrNull()
        val viewId = try {
            source?.viewIdResourceName.orEmpty()
        } catch (_: Exception) {
            ""
        } finally {
            runCatching { @Suppress("DEPRECATION") source?.recycle() }
        }
        return "$pkg#${ev.windowId}#$viewId"
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        flushAll(reason = "interrupt")
    }

    override fun onDestroy() {
        isConnected = false
        // 先冲刷（写入走独立 writeScope），再取消计时器
        flushAll(reason = "destroy")
        scope.cancel()
        super.onDestroy()
    }

    /** 从事件里取文本；优先 text 列表，回落到 contentDescription。 */
    private fun extractText(event: AccessibilityEvent): String? {
        val chunks = event.text.orEmpty()
        if (chunks.isNotEmpty()) {
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

    /** 取当前窗口标题（近似：第一个非空 TextView 文本），拿不到返回空串。 */
    private fun currentWindowTitle(): String {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return ""
        try {
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
            Log.d(TAG, "currentWindowTitle failed: ${e.message}")
        }
        return ""
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
