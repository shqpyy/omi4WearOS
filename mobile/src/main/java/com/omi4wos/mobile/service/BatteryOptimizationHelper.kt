package com.omi4wos.mobile.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.getSystemService

/**
 * 电池优化白名单引导。
 *
 * Android 6+ Doze 模式会暂停后台服务, 需引导用户:
 *   1. 关闭电池优化（系统弹窗直接加白）
 *   2. 各厂商自有的启动管理（跳到对应设置页）
 *
 * 厂商判定基于 Build.MANUFACTURER, 大小写不敏感。
 */
object BatteryOptimizationHelper {

    /**
     * 检查本 App 是否已在电池优化白名单中。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService<PowerManager>() ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 弹出系统电池优化白名单请求（直接加白, 用户点允许）。
     * 需在 Activity 中调用, Activity 会收到回调 onActivityResult。
     */
    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            // 某些定制 ROM 不支持这个 action, 退到电池优化总列表
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                // 退到应用详情页
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${activity.packageName}")))
            }
        }
    }

    /**
     * 直接打开厂商自启动管理页。
     *
     * 健壮策略:
     *   1. 取该厂商所有候选 Intent
     *   2. 先尝试 resolveActivity 可用的
     *   3. 再尝试直接 startActivity（绕过 resolveActivity —— 某些 ROM 不暴露但可启动）
     *   4. 全部失败返回 false（调用方退到应用详情页）
     *
     * 解决问题: HUAWEI HarmonyOS 改了 Activity 名，导致原 getManufacturerAutoStartIntent 返回 null，
     *          旧代码静默 fallback 到应用详情页，用户以为按钮没反应。
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val candidates = getAutoStartCandidates()
        // 1. 优先用 resolveActivity 可用的
        for (intent in candidates) {
            if (isIntentAvailable(context, intent)) {
                if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
                    return true
                }
            }
        }
        // 2. 绕过 resolveActivity 直接启动（HarmonyOS / MIUI 有时不暴露 resolveActivity）
        for (intent in candidates) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
                return true
            }
        }
        return false
    }

    /**
     * 各厂商自启动设置页候选 Intent 列表（按优先级排序）。
     * 同一厂商多个候选，覆盖不同 ROM 版本。
     */
    private fun getAutoStartCandidates(): List<Intent> {
        val mfr = Build.MANUFACTURER.lowercase()
        return when {
            mfr.contains("xiaomi") || mfr.contains("redmi") -> listOf(
                Intent().apply { setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity") }
            )
            mfr.contains("huawei") || mfr.contains("honor") -> listOf(
                // EMUI 旧版
                Intent().apply { setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity") },
                // HarmonyOS 新版（应用启动管理）
                Intent().apply { setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.HwFrozeAppListActivity") },
                // 省电模式 / 锁屏清理
                Intent().apply { setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity") },
                // 鸿蒙纯净模式
                Intent().apply { setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.featureviews.sceneswitch.AppScenerySwitchActivity") }
            )
            mfr.contains("oppo") || mfr.contains("realme") -> listOf(
                Intent().apply { setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity") },
                Intent().apply { setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity2") },
                Intent().apply { setClassName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity") }
            )
            mfr.contains("vivo") -> listOf(
                Intent().apply { setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity") },
                Intent().apply { setClassName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager") }
            )
            mfr.contains("samsung") -> listOf(
                Intent().apply { setClassName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity") }
            )
            mfr.contains("meizu") -> listOf(
                Intent().apply { setClassName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC") }
            )
            else -> emptyList()
        }
    }

    /**
     * 检测厂商并返回对应的自启动管理设置页 Intent。
     * 找不到匹配的厂商返回 null（调用方应退到通用电池设置）。
     */
    fun getManufacturerAutoStartIntent(context: Context): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
                Intent().apply {
                    setClassName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity")
                }
            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                Intent().apply {
                    setClassName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                }
            manufacturer.contains("oppo") ->
                Intent().apply {
                    setClassName("com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                }
            manufacturer.contains("vivo") ->
                Intent().apply {
                    setClassName("com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                }
            manufacturer.contains("samsung") ->
                Intent().apply {
                    setClassName("com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity")
                }
            manufacturer.contains("meizu") ->
                Intent().apply {
                    setClassName("com.meizu.safe",
                        "com.meizu.safe.security.SHOW_APPSEC")
                }
            else -> null
        }?.takeIf { isIntentAvailable(context, it) }
    }

    /**
     * 检测厂商并返回"省电模式/锁屏清理"等关键设置页 Intent。
     */
    fun getManufacturerPowerSettingsIntent(context: Context): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                Intent().apply {
                    setClassName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity")
                }
            manufacturer.contains("oppo") ->
                Intent().apply {
                    setClassName("com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                }
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }?.takeIf { isIntentAvailable(context, it) }
    }

    private fun isIntentAvailable(context: Context, intent: Intent): Boolean {
        return try {
            context.packageManager.resolveActivity(intent, 0) != null
        } catch (_: Exception) {
            false
        }
    }
}
