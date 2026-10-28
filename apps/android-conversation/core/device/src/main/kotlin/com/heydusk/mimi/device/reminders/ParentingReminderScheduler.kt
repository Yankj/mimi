package com.heydusk.mimi.device.reminders

import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.domain.parenting.ParentingPlannedReminder
import com.heydusk.mimi.domain.parenting.ParentingReminderOutcome
import com.heydusk.mimi.domain.parenting.ParentingReminderPayload
import com.heydusk.mimi.domain.parenting.ParentingReminderPlanner
import com.heydusk.mimi.domain.parenting.ParentingReminderSettings
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 让系统里待发的本机通知始终等于「已确认账本推出来的计划」。提醒只在本机：不走服务器、不写系统日历。
 * 每次账本变化、回到前台、设置变化、开机/时区/校时之后都重跑一遍。（iOS `ParentingReminderScheduler` 的移植。）
 *
 * [calendar] 每次现取：时区变了，免打扰与「每天最多 2 条」的"当地一天"就跟着变。
 */
class ParentingReminderScheduler(
    private val center: ReminderNotificationCenter,
    private val subjectID: UUID,
    private val calendar: () -> MimiCalendar = { MimiCalendar(ZoneId.systemDefault()) },
    private val copy: ReminderCopy = ReminderCopy.PLAIN,
    private val memory: ReminderMemory? = null,
    private val isCurrentAccount: () -> Boolean,
) {
    private val mutex = Mutex()

    suspend fun authorization(): NotificationAuthorization = center.authorization()

    /** 向系统要权限。只能从家长的明确操作调用。 */
    suspend fun requestAuthorization(): NotificationAuthorization {
        if (!isCurrentAccount()) return center.authorization()
        runCatching { center.requestAuthorization() }
        return center.authorization()
    }

    /** 移除本功能为这个账号排的全部提醒。 */
    suspend fun clear(): Unit = mutex.withLock {
        val prefix = ParentingReminderPayload.subjectPrefix(subjectID)
        val own = center.pending().map { it.id }.filter { it.startsWith(prefix) }
        if (own.isNotEmpty()) center.remove(own)
    }

    suspend fun reconcile(workspace: ParentingWorkspace, settings: ParentingReminderSettings, now: Instant): ParentingReminderStatus =
        mutex.withLock {
            val prefix = ParentingReminderPayload.subjectPrefix(subjectID)
            var authorization = center.authorization()
            var status = ParentingReminderStatus(settings, authorization, emptyMap(), false, now, center.exactAlarms())
            val pending = center.pending().filter { it.id.startsWith(prefix) }
            if (!isCurrentAccount()) return status
            if (!settings.enabled || authorization != NotificationAuthorization.Authorized) {
                // 关闭或不允许：不能留下任何排队的，也不声称任何事。
                if (pending.isNotEmpty()) center.remove(pending.map { it.id })
                return status
            }
            val remembered = memory?.load().orEmpty()
            val plan = ParentingReminderPlanner.plan(workspace, settings, now, calendar(), remembered)
            val desired = LinkedHashMap<String, Pair<NotificationRequest, ParentingPlannedReminder>>()
            for (reminder in plan.reminders) {
                val request = makeRequest(reminder, settings)
                desired[request.id] = request to reminder
            }
            val stale = pending.filter { desired[it.id] == null }.map { it.id }
            if (stale.isNotEmpty()) center.remove(stale)
            val known = pending.associateBy { it.id }
            var refused = false
            for ((id, entry) in desired.entries.sortedBy { it.value.first.fireAt }) {
                if (known[id]?.signature == entry.first.extras[ParentingReminderPayload.SIGNATURE_KEY]) continue
                // 这本账本的账号已经不在了：一律拒绝再写。
                if (!isCurrentAccount()) break
                try { center.add(entry.first) } catch (e: Exception) { refused = true }
            }
            if (!isCurrentAccount()) {
                val own = center.pending().map { it.id }.filter { it.startsWith(prefix) }
                if (own.isNotEmpty()) center.remove(own)
                return status
            }
            authorization = center.authorization()
            val readBack = center.pending().filter { it.id.startsWith(prefix) }.associateBy { it.id }
            val outcomes = LinkedHashMap<UUID, ParentingReminderOutcome>()
            for ((id, entry) in desired) {
                val seen = readBack[id]
                val fireAt = seen?.fireAt
                if (fireAt != null && seen.signature == entry.first.extras[ParentingReminderPayload.SIGNATURE_KEY]) {
                    outcomes[entry.second.arrangementID] = ParentingReminderOutcome.Scheduled(fireAt)
                } else {
                    refused = true
                }
            }
            for ((id, at) in plan.reminded) outcomes[id] = ParentingReminderOutcome.Reminded(at)
            for ((id, reason) in plan.skipped) outcomes[id] = ParentingReminderOutcome.Skipped(reason)
            for (id in plan.deferred) outcomes[id] = ParentingReminderOutcome.Deferred
            // 记住「已确认排好的」和「已经响过的」，其余都忘掉。
            val next = LinkedHashMap(plan.reminded)
            for (reminder in plan.reminders) {
                if (outcomes[reminder.arrangementID] is ParentingReminderOutcome.Scheduled) next[reminder.arrangementID] = reminder.fireAt
            }
            if (next != remembered) memory?.save(next)
            status = status.copy(authorization = authorization, outcomes = outcomes, systemRefused = refused, exactAlarms = center.exactAlarms())
            status
        }

    internal fun makeRequest(reminder: ParentingPlannedReminder, settings: ParentingReminderSettings): NotificationRequest {
        val title = copy.ui("小咪提醒", emptyList())
        val body = if (settings.hideContent) {
            copy.ui("有一件孩子的安排即将开始，打开小咪查看。", emptyList())
        } else {
            val time = copy.formatTime(reminder.dueAt)
            val why = if (reminder.movedForQuietHours) copy.ui("为避开夜间安静时段，已提前到前一晚提醒", emptyList())
            else copy.ui("按你的设置，{0}提醒", listOf(copy.ui(settings.lead.title, emptyList())))
            copy.ui("{0}：{1}", listOf(reminder.childName, reminder.title)) + "\n" +
                copy.ui("{0} 开始 · {1}", listOf(time, why))
        }
        val signature = hash("${reminder.fireAt.toEpochMilli()}|${reminder.dueAt.toEpochMilli()}|$title|$body")
        return NotificationRequest(
            id = ParentingReminderPayload.identifier(subjectID, reminder.arrangementID),
            title = title,
            body = body,
            fireAt = reminder.fireAt,
            extras = mapOf(
                ParentingReminderPayload.MARKER to "1",
                ParentingReminderPayload.SUBJECT_KEY to subjectID.toString().lowercase(),
                ParentingReminderPayload.ARRANGEMENT_KEY to reminder.arrangementID.toString().lowercase(),
                ParentingReminderPayload.SIGNATURE_KEY to signature,
            ),
        )
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}
