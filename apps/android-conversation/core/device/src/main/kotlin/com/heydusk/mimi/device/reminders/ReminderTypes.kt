package com.heydusk.mimi.device.reminders

import com.heydusk.mimi.domain.parenting.ParentingReminderOutcome
import com.heydusk.mimi.domain.parenting.ParentingReminderSettings
import java.time.Instant
import java.util.UUID

/**
 * 系统通知权限的三种状态（与 iOS `ParentingNotificationAuthorization` 对齐）。
 * Android 13+ 的运行时权限没有「从未问过」这个系统状态，所以 [NotDetermined] 来自我们自己记的「问过了」标记。
 */
enum class NotificationAuthorization { NotDetermined, Denied, Authorized }

data class PendingNotification(val id: String, val signature: String?, val fireAt: Instant?)

data class NotificationRequest(
    val id: String,
    val title: String,
    val body: String,
    val fireAt: Instant,
    /** 通知附带的键值（键名与 iOS 相同）。 */
    val extras: Map<String, String>,
)

/**
 * 调度器唯一需要的系统通知面。测试用假实现；生产是 [AlarmNotificationCenter]（AlarmManager + NotificationManager）。
 * 只涉及本功能创建的请求（标识符前缀 `ParentingReminderPayload.IDENTIFIER_PREFIX`）。
 */
interface ReminderNotificationCenter {
    suspend fun authorization(): NotificationAuthorization

    /** 只能由家长的明确操作触发。返回是否已授权。 */
    suspend fun requestAuthorization(): Boolean

    /** 读回系统里真正还在排队的请求。 */
    suspend fun pending(): List<PendingNotification>

    /** 失败要抛异常；调度器会据此如实报告。 */
    suspend fun add(request: NotificationRequest)
    suspend fun remove(ids: List<String>)

    /** 到点是否精确（Android 12+ 需要「闹钟和提醒」特殊权限）。不精确时提醒可能晚几分钟，界面要如实说明。 */
    fun exactAlarms(): Boolean = true
}

/** 提醒文案。`ui` 取共享文案目录；`formatTime` 按用户的语言与时区格式化开始时间。 */
class ReminderCopy(
    val ui: (key: String, arguments: List<String>) -> String,
    val formatTime: (Instant) -> String,
) {
    companion object {
        /** 测试与预览：按 {n} 替换，时间用 ISO。 */
        val PLAIN = ReminderCopy(
            ui = { key, arguments -> arguments.foldIndexed(key) { i, text, a -> text.replace("{$i}", a) } },
            formatTime = { it.toString() },
        )
    }
}

/** 记住「本设备上次为某个安排排的提醒时间」，这样已经响过的提醒不会被误报为错过。不属于加密账本。 */
interface ReminderMemory {
    fun load(): Map<UUID, Instant>
    fun save(value: Map<UUID, Instant>)
}

/** 提醒偏好按账号保存在账本旁边（不是账本里）。 */
interface ReminderSettingsStore {
    fun load(): ParentingReminderSettings
    fun save(settings: ParentingReminderSettings)
    fun erase()
}

/** 今天页能声称什么。`Scheduled` 只对系统读回确认过的请求成立。 */
data class ParentingReminderStatus(
    val settings: ParentingReminderSettings,
    val authorization: NotificationAuthorization,
    val outcomes: Map<UUID, ParentingReminderOutcome> = emptyMap(),
    /** 上次同步中有系统调用失败；没有读回确认的项目什么都不声称。 */
    val systemRefused: Boolean = false,
    val checkedAt: Instant,
    /** 到点是否精确（见 [ReminderNotificationCenter.exactAlarms]）。 */
    val exactAlarms: Boolean = true,
) {
    val scheduledCount: Int get() = outcomes.values.count { it is ParentingReminderOutcome.Scheduled }
}
