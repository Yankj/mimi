package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.domain.common.uuidString
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * 「成长」标签各种可视化背后的只读投影：账本、时钟、日历的纯函数，不碰存储、不碰界面、不写入。
 * 它只数家长写下的东西，从不给孩子打分或做评估。
 */
object ParentingGrowthInsights {
    /** 密度条覆盖最近七个日历日（含今天）。 */
    const val WEEK_LENGTH = 7

    /** 目标轨道上的记录数刻度（关联到该目标的记录）。是条目数，不是发育量表。 */
    val goalMarks = listOf(1, 5, 10, 20)

    /** 目标超过这么多天没有关联记录就显示为"安静"。 */
    const val QUIET_AFTER_DAYS = 7

    /** 够提议起个目标的观察数。只在 `ParentingStartAction` 里定义一次。 */
    const val GOAL_SUGGESTION_THRESHOLD = ParentingStartAction.GROWTH_GOAL_OBSERVATION_THRESHOLD

    /** 这么多个日历日没有记录，起手卡就请家长写新观察。只在 `ParentingStartAction` 里定义一次。 */
    const val OBSERVATION_GAP_DAYS = ParentingStartAction.OBSERVATION_GAP_DAYS

    /** 时间线上除第一条记录之外的记录数里程碑。 */
    val recordMilestones = listOf(10, 25, 50, 100)

    /** 孩子们轮流使用这么多个强调色槽（头像环、圆点、进度环）。 */
    const val ACCENT_SLOT_COUNT = 5

    fun scopedChild(workspace: ParentingWorkspace, preferred: UUID?): ParentingChild? = ParentingStartAction.activeChild(workspace, preferred)

    /** 孩子稳定的强调色槽：按创建顺序的位置取模。nil（孩子已删除）退回槽 0。 */
    fun accentSlot(childID: UUID?, workspace: ParentingWorkspace): Int {
        if (childID == null) return 0
        val ordered = workspace.children.sortedWith(compareBy<ParentingChild> { it.createdAt }.thenBy { it.id.uuidString })
        val index = ordered.indexOfFirst { it.id == childID }
        if (index < 0) return 0
        return index % ACCENT_SLOT_COUNT
    }

    internal fun dayDistance(earlier: Instant, later: Instant, calendar: MimiCalendar): Int =
        max(0, calendar.daysBetweenStartsOfDay(calendar.startOfDay(earlier), calendar.startOfDay(later)))
}

// MARK: - 本周密度

/** 最近七天每天写下了多少：「本周」条的柱子。 */
data class ParentingWeekDensity(val days: List<Day>) {
    data class Day(val date: Instant, val observations: Int, val completions: Int, val isToday: Boolean) {
        val total: Int get() = observations + completions
    }

    val total: Int get() = days.sumOf { it.total }
    val activeDays: Int get() = days.count { it.total > 0 }
    val peak: Int get() = days.maxOfOrNull { it.total } ?: 0

    /** 柱高占最忙那天的比例。空的一天是 0；非空的一天不低于 `minimumVisible`，让一条记录也看得见。 */
    fun level(day: Day, minimumVisible: Double = 0.18): Double {
        if (day.total <= 0 || peak <= 0) return 0.0
        return min(1.0, max(minimumVisible, day.total.toDouble() / peak.toDouble()))
    }

    companion object {
        fun build(workspace: ParentingWorkspace, childID: UUID?, now: Instant, calendar: MimiCalendar): ParentingWeekDensity {
            val today = calendar.startOfDay(now)
            val counts = HashMap<Instant, IntArray>() // [观察, 完成]
            for (record in workspace.records) {
                if (childID != null && record.childID != childID) continue
                val entry = counts.getOrPut(calendar.startOfDay(record.createdAt)) { IntArray(2) }
                if (record.kind == ParentingRecordKind.Observation) entry[0]++ else entry[1]++
            }
            val days = (0 until ParentingGrowthInsights.WEEK_LENGTH).reversed().map { offset ->
                val day = calendar.startOfDay(calendar.addingDays(today, -offset))
                val entry = counts[day] ?: IntArray(2)
                Day(day, entry[0], entry[1], offset == 0)
            }
            return ParentingWeekDensity(days)
        }
    }
}

// MARK: - 旅程（按天分组的时间线与里程碑）

sealed interface ParentingGrowthMilestone {
    data object FirstRecord : ParentingGrowthMilestone
    data class RecordCount(val n: Int) : ParentingGrowthMilestone
}

/** 成长时间线上的一个日历日，最新的在前。 */
data class ParentingGrowthDay(val day: Instant, val items: List<Item>) {
    sealed interface Item {
        data class Record(val record: ParentingRecord, val milestone: ParentingGrowthMilestone?) : Item
        data class GoalStarted(val goal: ParentingGoal) : Item

        val id: UUID get() = when (this) { is Record -> record.id; is GoalStarted -> goal.id }
        val time: Instant get() = when (this) { is Record -> record.createdAt; is GoalStarted -> goal.createdAt }
        val childID: UUID get() = when (this) { is Record -> record.childID; is GoalStarted -> goal.childID }
    }
}

data class ParentingGrowthJourney(val days: List<ParentingGrowthDay>) {
    val isEmpty: Boolean get() = days.isEmpty()
    val recordCount: Int get() = days.sumOf { d -> d.items.count { it is ParentingGrowthDay.Item.Record } }

    companion object {
        /**
         * - childID：null 列出所有孩子；里程碑永远按孩子各自计数。
         * - goalID：只保留关联到这个目标的记录（以及目标自己的"开始"节点）。
         * - 「目标开始」节点只在未筛选的 kind 下列出，所以筛选后的列表只有记录。
         */
        fun build(
            workspace: ParentingWorkspace, childID: UUID? = null, filter: ParentingGrowthTimeline.Filter = ParentingGrowthTimeline.Filter.All,
            goalID: UUID? = null, calendar: MimiCalendar,
        ): ParentingGrowthJourney {
            val milestones = HashMap<UUID, ParentingGrowthMilestone>()
            for (child in workspace.children) {
                val ordered = workspace.records.filter { it.childID == child.id }
                    .sortedWith(compareBy<ParentingRecord> { it.createdAt }.thenBy { it.id.uuidString })
                for ((index, record) in ordered.withIndex()) {
                    val n = index + 1
                    if (n == 1) milestones[record.id] = ParentingGrowthMilestone.FirstRecord
                    else if (n in ParentingGrowthInsights.recordMilestones) milestones[record.id] = ParentingGrowthMilestone.RecordCount(n)
                }
            }
            val items = workspace.records.filter {
                (childID == null || it.childID == childID) && (goalID == null || it.goalID == goalID) &&
                    (filter == ParentingGrowthTimeline.Filter.All ||
                        (if (filter == ParentingGrowthTimeline.Filter.Observation) it.kind == ParentingRecordKind.Observation else it.kind == ParentingRecordKind.Completion))
            }.map<ParentingRecord, ParentingGrowthDay.Item> { ParentingGrowthDay.Item.Record(it, milestones[it.id]) }.toMutableList()
            if (filter == ParentingGrowthTimeline.Filter.All) {
                items += workspace.goals.filter { (childID == null || it.childID == childID) && (goalID == null || it.id == goalID) }
                    .map { ParentingGrowthDay.Item.GoalStarted(it) }
            }
            items.sortWith(compareByDescending<ParentingGrowthDay.Item> { it.time }.thenByDescending { it.id.uuidString })
            val grouped = items.groupBy { calendar.startOfDay(it.time) }
            return ParentingGrowthJourney(grouped.keys.sortedDescending().map { ParentingGrowthDay(it, grouped.getValue(it)) })
        }
    }
}

// MARK: - 目标进度

/** 针对一个目标写下了什么。圆环是"完成的安排 /（完成 + 仍未结）"；没有安排时没有圆环。 */
data class ParentingGoalProgress(
    val goal: ParentingGoal,
    val observationCount: Int,
    val completionCount: Int,
    /** 关联到目标的草稿或已确认安排。 */
    val openArrangements: Int,
    val doneArrangements: Int,
    val lastActivity: Instant?,
    val daysSinceActivity: Int?,
    /** 仍在前面的最早的已确认安排。 */
    val nextDue: Instant?,
    val marks: List<Mark>,
) {
    enum class Activity { NotStarted, Recent, Quiet }
    data class Mark(val threshold: Int, val reached: Boolean)

    val id: UUID get() = goal.id
    val recordCount: Int get() = observationCount + completionCount
    val fraction: Double?
        get() {
            val total = openArrangements + doneArrangements
            return if (total > 0) doneArrangements.toDouble() / total.toDouble() else null
        }
    val reachedMarks: Int get() = marks.count { it.reached }
    val nextMark: Int? get() = marks.firstOrNull { !it.reached }?.threshold
    val activity: Activity
        get() {
            val days = daysSinceActivity ?: return Activity.NotStarted
            return if (days > ParentingGrowthInsights.QUIET_AFTER_DAYS) Activity.Quiet else Activity.Recent
        }

    companion object {
        /** 一个孩子的目标，按创建顺序。 */
        fun build(workspace: ParentingWorkspace, childID: UUID, now: Instant, calendar: MimiCalendar): List<ParentingGoalProgress> =
            workspace.goals.filter { it.childID == childID }
                .sortedWith(compareBy<ParentingGoal> { it.createdAt }.thenBy { it.id.uuidString })
                .map { goal ->
                    val records = workspace.records.filter { it.goalID == goal.id }
                    val linked = workspace.arrangements.filter { it.goalID == goal.id }
                    val last = records.maxOfOrNull { it.createdAt }
                    val due = linked.filter { it.state == ParentingArrangementState.Planned && it.dueAt >= now }.minOfOrNull { it.dueAt }
                    ParentingGoalProgress(
                        goal,
                        records.count { it.kind == ParentingRecordKind.Observation },
                        records.count { it.kind == ParentingRecordKind.Completion },
                        linked.count { it.state.isOpen },
                        linked.count { it.state == ParentingArrangementState.Completed },
                        last,
                        last?.let { ParentingGrowthInsights.dayDistance(it, now, calendar) },
                        due,
                        ParentingGrowthInsights.goalMarks.map { Mark(it, records.size >= it) },
                    )
                }
    }
}

// MARK: - 唯一的起手动作

/**
 * 成长标签顶部恰好一件推荐的事。它只推荐；家长在平常的弹层里决定并确认。
 * 共同的成长规则（没有记录 / 观察够多可以起目标）来自 `ParentingStartAction.growthBase`，
 * 和 `resolve(Growth)` 用的是同一个函数；这个解析器只额外加了按目标细化（`RecordForGoal`）和"安静天数"规则。
 */
data class ParentingGrowthStart(val kind: Kind, val childID: UUID?) {
    sealed interface Kind {
        /** 还没有孩子。 */
        data object AddChild : Kind

        /** 有孩子，没有记录也没有目标：写下第一条小观察。 */
        data object FirstObservation : Kind

        /** 某个目标还没有记录，或已经安静：针对它写点什么。 */
        data class RecordForGoal(val goalID: UUID) : Kind

        /** 至少五条观察而没有目标：提议起名。 */
        data class MakeGoal(val observationCount: Int) : Kind

        /** 最后一条记录已经两天或更久。 */
        data object RecordObservation : Kind

        /** 没有到期的事：回看这一周。 */
        data object ReviewWeek : Kind
    }

    companion object {
        /**
         * 规则顺序（先命中先用）：
         * 1. 没有孩子 → AddChild
         * 2. 没有记录 → 有目标则 RecordForGoal(最早的目标)，否则 FirstObservation
         * 3. ≥ 5 条观察且没有目标 → MakeGoal
         * 4. 今天写过东西 → ReviewWeek（刚记完不催）
         * 5. 有还没记录的目标，否则安静得最久的目标 → RecordForGoal
         * 6. 最后一条记录 ≥ 2 天前 → RecordObservation
         * 7. 其他 → ReviewWeek
         */
        fun choose(workspace: ParentingWorkspace, childID: UUID?, now: Instant, calendar: MimiCalendar): ParentingGrowthStart {
            val child = ParentingGrowthInsights.scopedChild(workspace, childID) ?: return ParentingGrowthStart(Kind.AddChild, null)
            val records = workspace.records.filter { it.childID == child.id }
            val goals = ParentingGoalProgress.build(workspace, child.id, now, calendar)
            fun start(kind: Kind) = ParentingGrowthStart(kind, child.id)

            val base = ParentingStartAction.growthBase(records, goals.isNotEmpty())
            val lastRecord = records.maxOfOrNull { it.createdAt }
            if (lastRecord == null) {
                goals.firstOrNull()?.let { return start(Kind.RecordForGoal(it.goal.id)) }
                return start(Kind.FirstObservation)
            }
            if (base is ParentingGrowthBase.GiveGoal) return start(Kind.MakeGoal(base.observationCount))
            val sinceLast = ParentingGrowthInsights.dayDistance(lastRecord, now, calendar)
            if (sinceLast == 0) return start(Kind.ReviewWeek)
            val needy = goals.filter { it.activity != ParentingGoalProgress.Activity.Recent }.minWithOrNull(
                compareBy<ParentingGoalProgress> { if (it.activity == ParentingGoalProgress.Activity.NotStarted) 0 else 1 }
                    .thenBy { -(it.daysSinceActivity ?: 0) }
                    .thenBy { it.goal.createdAt }
                    .thenBy { it.goal.id.uuidString },
            )
            if (needy != null) return start(Kind.RecordForGoal(needy.goal.id))
            if (sinceLast >= ParentingGrowthInsights.OBSERVATION_GAP_DAYS) return start(Kind.RecordObservation)
            return start(Kind.ReviewWeek)
        }
    }
}
