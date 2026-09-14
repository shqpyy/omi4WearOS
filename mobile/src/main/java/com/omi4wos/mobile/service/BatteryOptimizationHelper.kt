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
