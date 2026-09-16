package com.omi4wos.mobile.service

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简崩溃记录器。
 *
 * 目的：手机上没法随时连 adb，出问题时把堆栈落到文件 + SharedPreferences，
 * 下次打开 App 直接在首页「位置上报」卡片附近显示，便于远程定位。
 *
 * 记录完仍交给系统默认处理器（App 照常闪退），不改变原有行为。
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val PREFS = "crash_log"
    private const val KEY_LAST = "last_crash"
    private const val KEY_STEP = "last_step"
    private const val FILE_NAME = "last_crash.txt"

    @Volatile private var installed = false

    /** 在 Application/Activity 最早时机调用一次即可。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val step = lastStep(appContext)
                val summary = buildSummary(thread, throwable, step)
                Log.e(TAG, summary)
                // commit() 同步落盘：崩溃瞬间 apply() 的异步写可能丢
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LAST, summary).commit()
                runCatching { File(appContext.filesDir, FILE_NAME).writeText(summary) }
            } catch (_: Throwable) {
                // 记录失败也不能再抛，否则递归崩溃
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 最近一次崩溃摘要；无记录返回 null。 */
    fun last(context: Context): String? =
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST, null)
        }.getOrNull()

    /**
     * 记录「最近一步操作」面包屑。万一崩溃没留下异常（原生崩溃/OOM/被系统杀），
     * 也能知道用户当时卡在哪一步。同步写盘，保证崩溃前已落地。
     */
    fun markStep(context: Context, step: String) {
        runCatching {
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_STEP, "$stamp  $step").commit()
        }
    }

    /** 最近一步操作；无记录返回 null。 */
    fun lastStep(context: Context): String? =
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_STEP, null)
        }.getOrNull()

    /** 清除崩溃记录与面包屑。 */
    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_LAST).remove(KEY_STEP).commit()
            File(context.filesDir, FILE_NAME).delete()
        }
    }

    private fun buildSummary(thread: Thread, t: Throwable, step: String?): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("时间: ").append(time).append('\n')
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        sb.append(" / Android ").append(Build.VERSION.RELEASE)
        sb.append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("线程: ").append(thread.name).append('\n')
        if (!step.isNullOrBlank()) sb.append("最近操作: ").append(step).append('\n')
        sb.append("异常: ").append(t.javaClass.name).append(": ").append(t.message ?: "").append('\n')
        sb.append("堆栈:\n")
        t.stackTrace.take(30).forEach { sb.append("  at ").append(it.toString()).append('\n') }
        var cause = t.cause
        var depth = 0
        while (cause != null && depth < 3) {
            sb.append("Caused by: ").append(cause.javaClass.name)
                .append(": ").append(cause.message ?: "").append('\n')
            cause.stackTrace.take(15).forEach { sb.append("  at ").append(it.toString()).append('\n') }
            cause = cause.cause
            depth++
        }
        return sb.toString()
    }
}
