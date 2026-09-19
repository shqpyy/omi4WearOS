package com.omi4wos.mobile

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.omi4wos.mobile.service.AppLog
import com.omi4wos.mobile.service.CrashLogger

/**
 * 【2026-09-20 新增】崩溃日志查看/导出页。
 *
 * ## 存在的理由
 *
 * 用户反馈：多任务划掉 App 后重新进入**必然闪退**，且「进不了 App 去导出日志」。
 * 之前的错误页 `MainActivity.showFatalError()` 要求 `onCreate` 能执行，
 * 而进程级崩溃根本没这个机会。本页面**故意做得极其简单**（纯原生 View、
 * 不依赖 Compose、不依赖 OmiConfig / DataStore / 网络），
 * 最大限度保证「能打开」：
 *
 * - 无 `setContentView(ComposeView)`；只用 `ScrollView + TextView`
 * - 不读 DataStore（App 启动早期死锁的元凶已在此前被移除，这里不再引入）
 * - 所有 IO 都包 `runCatching`，读不到就显示「无日志」而不是崩
 *
 * ## 三个入口
 *
 * 1. 常驻通知的「导出日志」按钮（服务发出，App 起不来也能点）
 * 2. 桌面图标长按 → 快捷方式「导出崩溃日志」
 * 3. 首页在 App 能正常打开时也可跳转（备用）
 *
 * `exportOnly=true` 时直接拉起系统分享，不需要用户再点一次。
 */
class CrashLogActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 最早时机装日志，保证本页面自身的异常也能被记录
        runCatching { CrashLogger.install(this) }
        runCatching { AppLog.install(this) }

        val exportOnly = intent?.getBooleanExtra(EXTRA_EXPORT_ONLY, false) == true

        val crash = runCatching { CrashLogger.last(this) }.getOrNull()
        val step = runCatching { CrashLogger.lastStep(this) }.getOrNull()
        val logs = runCatching { AppLog.readAll(this, 60_000) }.getOrDefault("")

        val body = buildString {
            append("=== 最近一次崩溃 ===\n")
            append(crash ?: "（无崩溃记录）")
            append("\n\n=== 最近一步操作 ===\n")
            append(step ?: "（无）")
            append("\n\n=== 运行日志（截取） ===\n")
            append(logs.ifBlank { "（无日志）" })
        }

        if (exportOnly) {
            // 从通知/快捷方式直连：用户意图就是"导出"，不再让他多点一次
            val ok = runCatching {
                AppLog.shareAsText(this@CrashLogActivity, "omi4wOS 崩溃日志", body)
            }.getOrDefault(false)
            if (!ok) {
                // 分享失败也必须让用户看到内容，不能白屏退出
                showUi(body, shareFailed = true)
            } else {
                finish()
            }
            return
        }

        showUi(body, shareFailed = false)
    }

    private fun showUi(body: String, shareFailed: Boolean) {
        if (shareFailed) {
            runCatching {
                Toast.makeText(
                    this, "分享不可用，已显示全文（可长按复制）", Toast.LENGTH_LONG
                ).show()
            }
        }

        val text = TextView(this).apply {
            this.text = body
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(40, 40, 40, 40)
        }
        val scroll = ScrollView(this).apply {
            addView(text)
        }
        setContentView(scroll)
    }

    companion object {
        const val EXTRA_EXPORT_ONLY = "export_only"

        /** 构造启动本页面的 Intent（供通知 / 快捷方式 / 首页复用）。 */
        fun intent(context: android.content.Context, exportOnly: Boolean): Intent =
            Intent(context, CrashLogActivity::class.java).apply {
                putExtra(EXTRA_EXPORT_ONLY, exportOnly)
                // 从非 Activity 上下文（服务/Application）启动时必须加
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}
