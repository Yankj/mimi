package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.domain.common.uuidString
import java.time.Instant
import java.util.UUID

private val arrangementOrder: Comparator<ParentingArrangement> =
    compareBy<ParentingArrangement> { it.dueAt }.thenBy { it.id.uuidString }

/** 「今天」页对本地账本的只读投影。它从不写入，也从不凭空造出一项。 */
data class ParentingTodayTimeline(
    /** 等家长确认的草稿（任意日期）。 */
    val pending: List<ParentingArrangement>,
    /** 今天已确认或已完成的安排，加上今天的观察，按时间排序。 */
    val entries: List<Entry>,
    /** 更早的日子里已确认但从未标记完成的安排。 */
    val overdue: List<ParentingArrangement>,
    /** 明天起已确认的安排。 */
    val upcoming: List<ParentingArrangement>,
    /** "现在"标记放在这个条目下标之前。 */
    val nowIndex: Int,
    val summary: Summary,
) {
    sealed interface Content {
        data class Arrangement(val item: ParentingArrangement) : Content
        data class Observation(val record: ParentingRecord) : Content
    }

    data class Entry(
        val id: UUID,
        val time: Instant,
        val content: Content,
        /** 同一分钟另有已确认安排开始。时长未知，所以这只是提示核对。 */
        val sameTimeConflict: Boolean,
        val isPast: Boolean,
    )

    data class Summary(val pending: Int, val today: Int, val done: Int, val next: ParentingArrangement?)

    companion object {
        fun build(workspace: ParentingWorkspace, childID: UUID? = null, now: Instant, calendar: MimiCalendar): ParentingTodayTimeline {
            val scoped = workspace.arrangements.filter { childID == null || it.childID == childID }
            val startOfToday = calendar.startOfDay(now)
            val tomorrow = calendar.addingDays(startOfToday, 1)

            val pending = scoped.filter { it.state == ParentingArrangementState.Draft }.sortedWith(arrangementOrder)
            val overdue = scoped.filter { it.state == ParentingArrangementState.Planned && it.dueAt < startOfToday }.sortedWith(arrangementOrder)
            val upcoming = scoped.filter { it.state == ParentingArrangementState.Planned && it.dueAt >= tomorrow }.sortedWith(arrangementOrder)
            val todays = scoped.filter {
                (it.state == ParentingArrangementState.Planned || it.state == ParentingArrangementState.Completed) &&
                    calendar.isSameDay(it.dueAt, now)
            }.sortedWith(arrangementOrder)

            fun minute(i: Instant) = Math.floorDiv(i.epochSecond, 60L)
            val minutes = HashMap<Long, Int>()
            for (item in todays) if (item.state == ParentingArrangementState.Planned) minutes.merge(minute(item.dueAt), 1, Int::plus)
            val entries = todays.map { item ->
                Entry(
                    item.id, item.dueAt, Content.Arrangement(item),
                    sameTimeConflict = item.state == ParentingArrangementState.Planned && (minutes[minute(item.dueAt)] ?: 0) > 1,
                    isPast = item.dueAt <= now,
                )
            }.toMutableList()
            val observations = workspace.records.filter {
                it.kind == ParentingRecordKind.Observation && (childID == null || it.childID == childID) && calendar.isSameDay(it.createdAt, now)
            }
            entries += observations.map { Entry(it.id, it.createdAt, Content.Observation(it), false, it.createdAt <= now) }
            entries.sortWith(compareBy<Entry> { it.time }.thenBy { it.id.uuidString })

            val nowIndex = entries.count { it.time <= now }
            val plannedToday = todays.filter { it.state == ParentingArrangementState.Planned }
            val next = plannedToday.firstOrNull { it.dueAt > now } ?: upcoming.firstOrNull()
            return ParentingTodayTimeline(
                pending, entries, overdue, upcoming, nowIndex,
                Summary(pending.size, plannedToday.size, todays.count { it.state == ParentingArrangementState.Completed }, next),
            )
        }
    }
}

/** 成长页时间线：观察和家长标记的完成，按写下的日子分组。 */
data class ParentingGrowthTimeline(val days: List<Day>) {
    enum class Filter { All, Observation, Completion }
    data class Day(val day: Instant, val records: List<ParentingRecord>)

    val isEmpty: Boolean get() = days.isEmpty()

    companion object {
        fun build(workspace: ParentingWorkspace, childID: UUID? = null, filter: Filter = Filter.All, calendar: MimiCalendar): ParentingGrowthTimeline {
            val records = workspace.records.filter {
                (childID == null || it.childID == childID) &&
                    (filter == Filter.All || (if (filter == Filter.Observation) it.kind == ParentingRecordKind.Observation else it.kind == ParentingRecordKind.Completion))
            }.sortedWith(compareByDescending<ParentingRecord> { it.createdAt }.thenByDescending { it.id.uuidString })
            val grouped = records.groupBy { calendar.startOfDay(it.createdAt) }
            return ParentingGrowthTimeline(grouped.keys.sortedDescending().map { Day(it, grouped.getValue(it)) })
        }
    }
}
