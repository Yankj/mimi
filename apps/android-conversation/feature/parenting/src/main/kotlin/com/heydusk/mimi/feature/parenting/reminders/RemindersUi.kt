package com.heydusk.mimi.feature.parenting.reminders

import androidx.compose.runtime.compositionLocalOf
import com.heydusk.mimi.device.reminders.NotificationAuthorization
import com.heydusk.mimi.device.reminders.ParentingReminderStatus
import com.heydusk.mimi.domain.parenting.ParentingReminderLead
import com.heydusk.mimi.domain.parenting.ParentingReminderSettings
import com.heydusk.mimi.feature.parenting.model.ParentingWorkspaceModel
import java.time.Instant

/** 界面看到的提醒状态。null（没有提供）= 这台设备不发提醒。 */
data class RemindersUi(
    val settings: ParentingReminderSettings = ParentingReminderSettings.DEFAULT,
    val status: ParentingReminderStatus? = null,
    val busy: Boolean = false,
    val loaded: Boolean = true,
) {
    val authorization: NotificationAuthorization? get() = status?.authorization
}

/** 界面对提醒的全部操作。系统设置入口由平台层（Activity）实现。 */
interface RemindersActions {
    suspend fun setEnabled(enabled: Boolean)
    suspend fun setLead(lead: ParentingReminderLead)
    suspend fun setHideContent(hidden: Boolean)

    /** 回到前台 / 从系统设置回来：重读权限并补排。 */
    suspend fun refresh()
    fun openNotificationSettings() {}
    fun openExactAlarmSettings() {}
    fun openBatterySettings() {}

    object None : RemindersActions {
        override suspend fun setEnabled(enabled: Boolean) {}
        override suspend fun setLead(lead: ParentingReminderLead) {}
        override suspend fun setHideContent(hidden: Boolean) {}
        override suspend fun refresh() {}
    }
}

/** 把模型的提醒操作接给界面。 */
open class ModelRemindersActions(private val model: () -> ParentingWorkspaceModel?) : RemindersActions {
    override suspend fun setEnabled(enabled: Boolean) { model()?.setReminders(enabled) }
    override suspend fun setLead(lead: ParentingReminderLead) { model()?.setReminderLead(lead) }
    override suspend fun setHideContent(hidden: Boolean) { model()?.setReminderContentHidden(hidden) }
    override suspend fun refresh() { model()?.refreshReminders() }
}

/** 页面里的提醒文案（今天卡片、安排行、提醒页）读它。 */
class RemindersContext(val ui: RemindersUi, val actions: RemindersActions, val now: Instant)

val LocalReminders = compositionLocalOf<RemindersContext?> { null }

/** 点通知打开应用：回到「今天」。`nonce` 让同一个安排的连续两次点击都能触发。 */
data class ReminderOpenRequest(val arrangementID: String, val nonce: Long)
