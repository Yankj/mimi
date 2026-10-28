package com.heydusk.mimi.device.reminders

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** 打开系统设置的几个入口。都用 NEW_TASK，所以能从任何 Context 调用；找不到目标时回落到「应用详情」。 */
object ReminderSettingsIntents {
    fun notifications(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).newTask()

    /** 「闹钟和提醒」特殊权限页（Android 12+）；更低版本不需要，回落到应用详情。 */
    fun exactAlarms(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 31) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")).newTask()
        } else appDetails(context)

    /** 电池优化列表（用户自己决定是否放行；不申请直接豁免权限）。 */
    fun battery(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).newTask()

    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).newTask()

    /** 启动；目标不存在就回落到应用详情，仍失败就静默（界面不应因此崩溃）。 */
    fun launch(context: Context, intent: Intent, fallback: Intent = appDetails(context)) {
        runCatching { context.startActivity(intent) }.recoverCatching { context.startActivity(fallback) }
    }

    private fun Intent.newTask() = apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
}
