package com.heydusk.mimi

import android.content.Context
import com.heydusk.mimi.device.reminders.ReminderSettingsIntents

/** 界面请求打开的系统设置页。接口让 ViewModel 的测试不需要 Android 上下文。 */
interface SystemSettingsOpener {
    fun notifications()
    fun exactAlarms()
    fun battery()
}

/** 用应用上下文启动（NEW_TASK）；目标页不存在时回落到应用详情，仍失败就静默。 */
class AndroidSystemSettingsOpener(private val context: Context) : SystemSettingsOpener {
    override fun notifications() = ReminderSettingsIntents.launch(context, ReminderSettingsIntents.notifications(context))
    override fun exactAlarms() = ReminderSettingsIntents.launch(context, ReminderSettingsIntents.exactAlarms(context))
    override fun battery() = ReminderSettingsIntents.launch(context, ReminderSettingsIntents.battery())
}
