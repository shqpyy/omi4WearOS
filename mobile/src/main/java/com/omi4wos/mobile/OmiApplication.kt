package com.omi4wos.mobile

import android.app.Application
import com.omi4wos.mobile.service.AppLog
import com.omi4wos.mobile.service.CrashLogger

/**
 * 【2026-09-20 新增】进程级入口。
 *
 * ## 为什么必须要有这个类
 *
 * 之前只在 `MainActivity` 里安装日志/崩溃记录器，导致一个致命盲区：
 * **「多任务划掉 App → 重新进入 → 永久闪退」这类崩溃发生在进程启动早期**，
 * `Activity.onCreate` 根本没机会执行 → 日志没装 → 堆栈没落盘 →
 * 用户既看不到错误页、也导不出日志，只能干瞪眼。
 *
 * `Application.onCreate` 比任何 `Activity` 都早，且**无论进程因何被创建
 * （点图标 / 服务被 START_STICKY 重建 / 开机广播 / WorkManager）都会执行**，
 * 因此这里是安装崩溃记录器的唯一正确位置。
 *
 * ## 顺序很重要
 *
 * `CrashLogger.install()` 会接管 `Thread.setDefaultUncaughtExceptionHandler`，
 * 并在自己的处理链里调用 `previous?.uncaughtException(...)`；
 * `AppLog.install()` 同样会串联上一层。两者都**幂等**，重复安装无副作用。
 * 先装 CrashLogger（写 SharedPreferences + filesDir），再装 AppLog（写日志文件），
 * 保证崩溃瞬间两条链路都能落地。
 */
class OmiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 全部 runCatching：Application.onCreate 抛异常 = 进程秒崩且无任何线索，
        // 这里宁可日志功能降级，也不能让 App 起不来。
        runCatching { CrashLogger.install(this) }
        runCatching { AppLog.install(this) }
        runCatching {
            AppLog.i("OmiApplication", "进程启动: ${CrashLogger.lastStep(this) ?: "无面包屑"}")
        }
    }
}
