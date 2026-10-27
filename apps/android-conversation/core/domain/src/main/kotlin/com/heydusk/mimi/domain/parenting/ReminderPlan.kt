@file:UseSerializers(UuidSerializer::class, InstantSerializer::class)

package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.common.InstantSerializer
import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.domain.common.UuidSerializer
import com.heydusk.mimi.domain.common.uuidString
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** 在确认的安排之前多久提醒家长。 */
@Serializable
enum class ParentingReminderLead(val rawValue: String, val title: String, val interval: Duration?) {
    @SerialName("minutes15") Minutes15("minutes15", "提前 15 分钟", Duration.ofMinutes(15)),
    @SerialName("minutes30") Minutes30("minutes30", "提前 30 分钟", Duration.ofMinutes(30)),
    @SerialName("hour1") Hour1("hour1", "提前 1 小时", Duration.ofHours(1)),
    @SerialName("eveningBefore") EveningBefore("eveningBefore", "前一天晚上 8 点", null),
}

/** 家长控制的提醒偏好。按账号保存，不属于加密账本；提醒默认关闭。 */
@Serializable
data class ParentingReminderSettings(
    val enabled: Boolean = false,
    val lead: ParentingReminderLead = ParentingReminderLead.Hour1,
    /** 锁屏文字不带孩子名字和安排标题。 */
    val hideContent: Boolean = false,
) {
    companion object { val DEFAULT = ParentingReminderSettings() }
}

enum class ParentingReminderSkipReason {
    /** 提醒时间（挪出免打扰时段之后）已经过去。 */
    PastReminderTime,

    /** 当地这一天的提醒已经达到上限。 */
    DailyLimit,
}

sealed interface ParentingReminderOutcome {
    /** 通过读回系统里的待发请求确认，而不只是"已请求"。 */
    data class Scheduled(val at: Instant) : ParentingReminderOutcome
    data class Reminded(val at: Instant) : ParentingReminderOutcome
    data class Skipped(val reason: ParentingReminderSkipReason) : ParentingReminderOutcome

    /** 待发数量超过系统允许；下次打开应用时再安排。 */
    data object Deferred : ParentingReminderOutcome
}

data class ParentingPlannedReminder(
    val arrangementID: UUID,
    val childName: String,
    val title: String,
    val dueAt: Instant,
    val fireAt: Instant,
    /** 为避开免打扰时段被提前了。 */
    val movedForQuietHours: Boolean,
)

data class ParentingReminderPlan(
    val reminders: List<ParentingPlannedReminder>,
    val skipped: Map<UUID, ParentingReminderSkipReason>,
    val deferred: Set<UUID>,
    /** 本设备早先安排过、现在时间已过的提醒。 */
    val reminded: Map<UUID, Instant> = emptyMap(),
)

/**
 * 纯规划。只有未来的已确认（planned）安排才有提醒；草稿、已取消、已完成都没有。
 * 免打扰 21:00–08:00，每个当地日最多 `MAX_PER_DAY` 条。
 */
object ParentingReminderPlanner {
    const val QUIET_START_HOUR = 21
    const val QUIET_END_HOUR = 8
    const val EVENING_HOUR = 20
    const val MAX_PER_DAY = 2

    /** iOS 每个应用最多 64 条本地通知，这里沿用 48 的保守上限（Android 的上限由 M4 的闹钟实现再核对）。 */
    const val MAX_PENDING = 48

    fun isQuiet(date: Instant, calendar: MimiCalendar): Boolean {
        val hour = calendar.hour(date)
        return hour >= QUIET_START_HOUR || hour < QUIET_END_HOUR
    }

    data class Fire(val date: Instant, val moved: Boolean)

    fun fireDate(due: Instant, lead: ParentingReminderLead, calendar: MimiCalendar): Fire? {
        val interval = lead.interval
        val raw: Instant = if (interval != null) {
            due.minus(interval)
        } else {
            val startOfDue = calendar.startOfDay(due)
            val previous = calendar.addingDays(startOfDue, -1)
            calendar.date(previous, EVENING_HOUR, 0, 0) ?: return null
        }
        if (!isQuiet(raw, calendar)) return Fire(raw, false)
        // 挪到包含 raw 的免打扰窗口之前那个晚上的 20:30。
        val day = if (calendar.hour(raw) >= QUIET_START_HOUR) calendar.startOfDay(raw)
        else calendar.addingDays(calendar.startOfDay(raw), -1)
        val moved = calendar.date(day, EVENING_HOUR, 30, 0) ?: return null
        return Fire(moved, true)
    }

    /**
     * `remembered`：本设备上次为某安排安排的提醒时间。已经响过的提醒会被报告为"已提醒"，并占用当天的额度，而不是"错过"。
     */
    fun plan(
        workspace: ParentingWorkspace, settings: ParentingReminderSettings, now: Instant, calendar: MimiCalendar,
        remembered: Map<UUID, Instant> = emptyMap(),
    ): ParentingReminderPlan {
        if (!settings.enabled) return ParentingReminderPlan(emptyList(), emptyMap(), emptySet(), emptyMap())
        val names = workspace.children.associate { it.id to it.name }
        val candidates = mutableListOf<ParentingPlannedReminder>()
        val elapsed = mutableListOf<ParentingPlannedReminder>()
        val skipped = linkedMapOf<UUID, ParentingReminderSkipReason>()
        val ordered = workspace.arrangements.sortedWith(compareBy<ParentingArrangement> { it.dueAt }.thenBy { it.id.uuidString })
        for (item in ordered) {
            if (item.state != ParentingArrangementState.Planned || item.dueAt <= now) continue
            val name = names[item.childID]
            val fire = if (name != null) fireDate(item.dueAt, settings.lead, calendar) else null
            if (name == null || fire == null) { skipped[item.id] = ParentingReminderSkipReason.PastReminderTime; continue }
            val planned = ParentingPlannedReminder(item.id, name, item.title, item.dueAt, fire.date, fire.moved)
            if (fire.date <= now.plusSeconds(5)) {
                val earlier = remembered[item.id]
                if (earlier != null && abs(Duration.between(fire.date, earlier).toMillis()) < 1000) elapsed.add(planned)
                else skipped[item.id] = ParentingReminderSkipReason.PastReminderTime
                continue
            }
            candidates.add(planned)
        }
        candidates.sortWith(compareBy<ParentingPlannedReminder> { it.fireAt }.thenBy { it.arrangementID.uuidString })
        val perDay = HashMap<Instant, Int>()
        // 已经响过的提醒先用掉那一天的额度。
        for (done in elapsed) perDay.merge(calendar.startOfDay(done.fireAt), 1, Int::plus)
        val kept = mutableListOf<ParentingPlannedReminder>()
        for (candidate in candidates) {
            val day = calendar.startOfDay(candidate.fireAt)
            if ((perDay[day] ?: 0) >= MAX_PER_DAY) { skipped[candidate.arrangementID] = ParentingReminderSkipReason.DailyLimit; continue }
            perDay.merge(day, 1, Int::plus)
            kept.add(candidate)
        }
        val deferred = kept.drop(MAX_PENDING).map { it.arrangementID }.toSet()
        val reminded = LinkedHashMap<UUID, Instant>()
        for (e in elapsed) reminded.putIfAbsent(e.arrangementID, e.fireAt)
        return ParentingReminderPlan(kept.take(MAX_PENDING), skipped, deferred, reminded)
    }
}

/** 点按或前台提醒的路由。提醒属于安排它的那个账号。键名与 iOS 相同；Android 端把它们放进通知的 extras。 */
object ParentingReminderPayload {
    const val MARKER = "mimi_parenting_reminder"
    const val SUBJECT_KEY = "mimi_parenting_subject"
    const val ARRANGEMENT_KEY = "mimi_parenting_arrangement"
    const val SIGNATURE_KEY = "mimi_parenting_signature"
    const val IDENTIFIER_PREFIX = "mimi.parenting.v1."

    fun isParenting(info: Map<String, Any?>): Boolean = info[MARKER] as? String == "1"

    /** 只有安排它的同一个账号仍在登录时才展示或打开。 */
    fun belongsToCurrent(info: Map<String, Any?>, currentAccount: String?): Boolean {
        if (!isParenting(info) || currentAccount == null) return false
        val subject = info[SUBJECT_KEY] as? String ?: return false
        return subject.equals(currentAccount, ignoreCase = true)
    }

    fun identifier(subject: UUID, arrangement: UUID): String =
        IDENTIFIER_PREFIX + subject.toString().lowercase(Locale.ROOT) + "." + arrangement.toString().lowercase(Locale.ROOT)

    fun subjectPrefix(subject: UUID): String = IDENTIFIER_PREFIX + subject.toString().lowercase(Locale.ROOT) + "."
}
