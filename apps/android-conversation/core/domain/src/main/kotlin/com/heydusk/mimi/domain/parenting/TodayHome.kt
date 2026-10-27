package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.common.MimiCalendar
import java.time.Instant
import java.util.UUID
import kotlin.math.max

/**
 * 已确认的安排与时钟的关系。标记只是呈现：这里（以及整个起手逻辑）从不完成、取消或移动安排，只有家长自己的命令能。
 */
enum class ParentingOverdueMark {
    /** 没过期：已完成、已取消、草稿，或还在前面。 */
    None,

    /** 计划在今天更早的时候，还没标记完成。 */
    PassedToday,

    /** 计划在更早的日子，没标记完成。 */
    PreviousDay,
    ;

    companion object {
        fun mark(arrangement: ParentingArrangement, now: Instant, calendar: MimiCalendar): ParentingOverdueMark {
            if (arrangement.state != ParentingArrangementState.Planned || arrangement.dueAt >= now) return None
            return if (calendar.isSameDay(arrangement.dueAt, now)) PassedToday else PreviousDay
        }
    }
}

/** 新「今天」页的数据，只读地从账本推导。行数有上限让首屏保持简短；其余都能从"全部安排"到达。 */
data class ParentingTodayHome(
    /** 例如 "3 件·已完成 1"；今天没有安排也没有完成时是 "今天暂无安排"。 */
    val subtitle: String,
    /** 最多 `rowCap` 行，按时间排序。 */
    val rows: List<Row>,
    /** "现在"线画在这个行下标之前（0...rows.size）。 */
    val nowIndex: Int,
    /** 今天放不进 `rows` 的条目数。 */
    val hiddenTodayCount: Int,
    val upcomingCount: Int,
    val overdueCount: Int,
    val pendingCount: Int,
) {
    data class Row(val id: UUID, val time: Instant, val content: ParentingTodayTimeline.Content, val mark: ParentingOverdueMark, val sameTimeConflict: Boolean)

    /** "全部安排"里有首页没展示的东西。 */
    val hasMoreInAllArrangements: Boolean get() = hiddenTodayCount > 0 || upcomingCount > 0 || overdueCount > 0

    companion object {
        const val DEFAULT_ROW_CAP = 5

        fun subtitle(planned: Int, done: Int): String {
            val total = planned + done
            return if (total == 0) "今天暂无安排" else "$total 件·已完成 $done"
        }

        fun build(
            workspace: ParentingWorkspace, childID: UUID? = null, now: Instant, calendar: MimiCalendar, rowCap: Int = DEFAULT_ROW_CAP,
        ): ParentingTodayHome {
            val timeline = ParentingTodayTimeline.build(workspace, childID, now, calendar)
            val cap = max(rowCap, 0)
            val entries = timeline.entries
            // 选择展示什么：还在前面的先选（家长要处理的），再选最近已过去的。
            val ahead = entries.filter { it.time > now }
            val past = entries.filter { it.time <= now }
            val chosenAhead = ahead.take(cap)
            val chosenPast = past.takeLast(max(cap - chosenAhead.size, 0))
            val chosenIDs = (chosenAhead + chosenPast).map { it.id }.toSet()
            val visible = entries.filter { it.id in chosenIDs } // 已经按时间排序
            val rows = visible.map { entry ->
                val item = (entry.content as? ParentingTodayTimeline.Content.Arrangement)?.item
                val mark = if (item != null) ParentingOverdueMark.mark(item, now, calendar) else ParentingOverdueMark.None
                Row(entry.id, entry.time, entry.content, mark, entry.sameTimeConflict)
            }
            return ParentingTodayHome(
                subtitle(timeline.summary.today, timeline.summary.done), rows, insertionIndex(now, rows.map { it.time }),
                entries.size - rows.size, timeline.upcoming.size, timeline.overdue.size, timeline.pending.size,
            )
        }

        /** "现在"线在按时间排序的列表里的位置：第一个晚于 `now` 的条目之前。 */
        fun insertionIndex(now: Instant, times: List<Instant>): Int =
            times.indexOfFirst { it > now }.let { if (it < 0) times.size else it }
    }
}
