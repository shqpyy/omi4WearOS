package com.omi4wos.mobile.service

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.omi4wos.mobile.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 落盘日志（手机不能连 adb 时的唯一排错手段）。
 *
 * 为什么需要：App 闪退时 logcat 拿不到，而「不点按钮也会崩」这类问题必须知道**崩在哪一步**。
 * 这里把运行日志（关键步骤 + 心跳 + 错误堆栈 + 未捕获异常）持续写入
 * `filesDir/logs/app.log`，首页「应用日志」卡片可一键导出，通过微信/邮件发出来。
 *
 * 策略：
 * - **同步写**：崩溃瞬间异步写容易丢行，这里直接 append。
 * - **单文件滚动**：app.log 超过 512KB → app.log.1 → app.log.2，最多 3 个文件。
 * - **绝不抛异常**：日志失败不能引发二次崩溃（写日志本身导致崩溃是最蠢的 bug）。
 */
object AppLog {

    private const val TAG = "AppLog"
    private const val DIR_NAME = "logs"
    private const val FILE_NAME = "app.log"
    private const val MAX_BYTES = 512L * 1024L
    private const val MAX_BACKUPS = 2

    /** 与 AndroidManifest 中 FileProvider 的 authority 保持一致。 */
    private const val FILE_PROVIDER_AUTHORITY = "com.omi4wos.fileprovider"

    private val lock = Any()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val sessionFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Volatile private var appContext: Context? = null
    @Volatile private var installed = false

    // ---------------------------------------------------------------- 安装

    /**
     * 安装全局未捕获异常处理（幂等）。应在 Application/Activity 最早时机调用。
     * 注意调用顺序：先 CrashLogger.install() 再 AppLog.install()，两个处理链会串起来，
     * 崩溃既写日志文件、又写首页崩溃卡片。
     */
    fun install(context: Context) {
        ensure(context)
        if (installed) return
        installed = true
        val app = context.applicationContext
        i(TAG, "===== session start: ${deviceInfo(app)} =====")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                append("FATAL", "Crash", "未捕获异常 (线程=${thread.name})", throwable)
            }
            // 继续交给上一层（CrashLogger → 系统默认），保持原有闪退行为
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 只准备上下文与目录，不接管异常处理。服务被系统单独拉起时也要能写日志。 */
    fun ensure(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    // ---------------------------------------------------------------- 写日志

    fun i(tag: String, msg: String) = append("I", tag, msg, null)

    fun w(tag: String, msg: String, t: Throwable? = null) = append("W", tag, msg, t)

    fun e(tag: String, msg: String, t: Throwable? = null) = append("E", tag, msg, t)

    private fun append(level: String, tag: String, msg: String, t: Throwable?) {
        val ctx = appContext ?: return
        synchronized(lock) {
            runCatching {
                val file = logFile(ctx)
                if (file.length() > MAX_BYTES) rotate(ctx)
                file.appendText("${timeFmt.format(Date())} $level/$tag: $msg\n")
                if (t != null) file.appendText(describe(t))
            }
            when (level) {
                "E", "FATAL" -> Log.e(tag, msg, t)
                "W" -> Log.w(tag, msg, t)
                else -> Log.i(tag, msg)
            }
        }
    }

    private fun describe(t: Throwable): String = buildString {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 3) {
            append(if (depth == 0) "    ${cur.javaClass.name}: ${cur.message}\n"
            else "    Caused by: ${cur.javaClass.name}: ${cur.message}\n")
            cur.stackTrace.take(25).forEach { append("      at $it\n") }
            cur = cur.cause
            depth++
        }
    }

    private fun deviceInfo(context: Context): String {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        return "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT}) / app $version"
    }

    // ---------------------------------------------------------------- 文件

    private fun logsDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun logFile(context: Context): File = File(logsDir(context), FILE_NAME)

    private fun rotate(context: Context) {
        val dir = logsDir(context)
        runCatching { File(dir, "$FILE_NAME.$MAX_BACKUPS").delete() }
        for (idx in (MAX_BACKUPS - 1) downTo 1) {
            runCatching {
                val src = File(dir, "$FILE_NAME.$idx")
                if (src.exists()) src.renameTo(File(dir, "$FILE_NAME.${idx + 1}"))
            }
        }
        runCatching { logFile(context).renameTo(File(dir, "$FILE_NAME.1")) }
    }

    /** 现存日志文件（旧→新）。 */
    fun logFiles(context: Context): List<File> {
        val dir = logsDir(context)
        val all = (MAX_BACKUPS downTo 1).map { File(dir, "$FILE_NAME.$it") } +
                File(dir, FILE_NAME)
        return all.filter { it.exists() && it.length() > 0L }
    }

    fun totalBytes(context: Context): Long = logFiles(context).sumOf { it.length() }

    fun readAll(context: Context, maxChars: Int = 400_000): String = runCatching {
        val sb = StringBuilder()
        logFiles(context).forEach { f ->
            if (sb.length >= maxChars) return@forEach
            sb.append("----- ${f.name} (${f.length()} bytes) -----\n")
            sb.append(f.readText())
        }
        sb.toString().take(maxChars)
    }.getOrDefault("")

    fun clear(context: Context) {
        logFiles(context).forEach { runCatching { it.delete() } }
        i(TAG, "log cleared by user")
    }

    /**
     * 导出日志：优先走系统分享（微信/邮件/文件管理器都能接），
     * 没有可用 App 时退化成「复制到剪贴板 + 提示」，保证一定能拿到内容。
     */
    fun share(context: Context): Boolean {
        val files = logFiles(context)
        if (files.isEmpty()) {
            toast(context, context.getString(R.string.home_log_empty))
            return false
        }
        return try {
            val uris = files.mapNotNull { f ->
                runCatching {
                    FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, f)
                }.getOrNull()
            }
            if (uris.isEmpty()) throw IllegalStateException("no content uri")
            val subject = context.getString(R.string.home_log_share_subject)
            val send = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "text/plain"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            }
            send.putExtra(Intent.EXTRA_SUBJECT, subject)
            // 同时附上文本正文：即使接收方不接受文件流，也能看到最近日志片段
            send.putExtra(Intent.EXTRA_TEXT, readAll(context, 2000))
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(send, context.getString(R.string.home_log_export))
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (t: Throwable) {
            e(TAG, "share failed, fallback to clipboard", t)
            val text = readAll(context, 4000)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("omi4wOS log", text))
            toast(context, context.getString(R.string.home_log_copied))
            false
        }
    }

    /**
     * 把日志转成一个「一定打得开」的纯文本内容，供错误页分享。
     *
     * 为什么不用 [share]：闪退场景下 Activity 常常处于异常状态，
     * 直接起系统分享可能二次失败；这里退化成最原始的 ACTION_SEND，
     * 任何有文本处理能力的 App 都能接。
     */
    fun shareAsText(context: Context, subject: String, body: String): Boolean {
        return try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            val chooser = Intent.createChooser(send, subject)
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (t: Throwable) {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText(subject, body))
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    private fun toast(context: Context, msg: String) {
        runCatching {
            Handler(Looper.getMainLooper()).post {
                runCatching { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
            }
        }
    }
}
