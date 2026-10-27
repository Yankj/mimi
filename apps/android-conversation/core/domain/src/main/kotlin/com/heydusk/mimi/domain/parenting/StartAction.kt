package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.domain.common.uuidString
import java.time.Instant
import java.util.UUID

/** 三个首页标签。（界面层有自己的私有标签枚举；这个只用于解析起手动作。） */
enum class ParentingStartTab { Today, Growth, Family }

/** 不属于账本、但会改变推荐哪个起手动作的事实。界面层把它们存进偏好再传入；解析器从不读存储或时钟。 */
data class ParentingStartContext(
    /** 家长上次完成每周复盘的时间；null 表示从未。 */
    val lastWeeklyReviewAt: Instant? = null,
    /** 提醒权限和家长的开关都已打开。 */
    val remindersEnabled: Boolean = false,
    /** 家长在启航清单的可选提醒步骤上点了"跳过"。 */
    val remindersStepSkipped: Boolean = false,
    /** 把每个标签限定在一个孩子；null 用最早创建的孩子。 */
    val childID: UUID? = null,
)

/** 「启航 n/3」。第一、二步必做；第三步可选，只提供一次。 */
data class ParentingLaunchChecklist(val done: Set<Step>, val skipped: Set<Step>) {
    enum class Step { AddChild, RecordFirstThing, EnableReminders }

    val completedCount: Int get() = done.size

    /** 按顺序下一个既没完成也没跳过的步骤。 */
    val nextStep: Step? get() = Step.entries.firstOrNull { it !in done && it !in skipped }

    /** 每一步都完成，或（仅对可选步骤）跳过时，卡片消失。 */
    val isFinished: Boolean get() = nextStep == null

    companion object { const val TOTAL = 3 }
}

/**
 * 所有成长推荐的共同起点。`resolve(.growth)`（今天页的周复盘提示）和 `ParentingGrowthStart.choose`（成长起手卡）
 * 都调它，所以阈值只在一处。
 */
sealed interface ParentingGrowthBase {
    /** 这个孩子还没有任何记录。 */
    data object NoRecords : ParentingGrowthBase

    /** 至少 `GROWTH_GOAL_OBSERVATION_THRESHOLD` 条观察而没有目标：提议起个名。 */
    data class GiveGoal(val observationCount: Int) : ParentingGrowthBase

    /** 其他情况：继续记录。 */
    data object ContinueRecording : ParentingGrowthBase
}

enum class ParentingWeeklyReviewReason { SundayEvening, SevenDaysSinceLastReview }

/**
 * 每个标签恰好一个推荐的起手动作。纯且确定：账本 + 时钟 + 日历进，动作出。
 * 它只推荐；从不写账本、不完成任何事、不发送任何东西。
 */
sealed interface ParentingStartAction {
    // 今天
    /** 还没有孩子：就地问一个昵称。 */
    data object StartWithNickname : ParentingStartAction

    /** 草稿在等家长：一键全部确认。 */
    data class ConfirmAll(val arrangementIDs: List<UUID>) : ParentingStartAction

    /** 更早日子里已确认却没标记完成的事项。由家长决定；什么都不会自动完成。 */
    data class HandleOverdue(val arrangementIDs: List<UUID>) : ParentingStartAction

    /** 今天下一项未完成的安排，带一键完成。`hasPassed` 表示它的时间今天已经过了。 */
    data class NextWithComplete(val arrangementID: UUID, val dueAt: Instant, val hasPassed: Boolean) : ParentingStartAction

    /** 18:00–21:00 且今天还没记录任何事。 */
    data class RecordTodaySmallThing(val childID: UUID) : ParentingStartAction

    /** 其他都不适用：展示一句家长可以直接说或修改的话。 */
    data class ExamplePrompt(val text: String) : ParentingStartAction

    // 成长
    data class WeeklyReview(val reason: ParentingWeeklyReviewReason) : ParentingStartAction

    /** 至少五条观察而一个目标都没有：提议起个名字。 */
    data class GiveObservationsAGoal(val childID: UUID, val observationCount: Int) : ParentingStartAction
    data class RecordObservation(val childID: UUID) : ParentingStartAction

    // 我们家
    data class LaunchChecklist(val checklist: ParentingLaunchChecklist) : ParentingStartAction

    /** 清单完成后的我们家标签：没有起手动作，只有稳定的资料。 */
    data object None : ParentingStartAction

    companion object {
        const val EVENING_START_HOUR = 18
        const val EVENING_END_HOUR = 21 // 不含

        const val GROWTH_GOAL_OBSERVATION_THRESHOLD = 5

        /** 这么多个日历日没有记录，成长起手卡就请家长写一条新观察。 */
        const val OBSERVATION_GAP_DAYS = 2
        const val WEEKLY_REVIEW_INTERVAL_DAYS = 7

        val examplePrompts: List<String> = listOf(
            "明天早上8点带宝宝打疫苗",
            "今天下午宝宝第一次自己拿勺子吃饭",
            "周六上午10点带宝宝去公园",
            "晚上8点半给宝宝读绘本",
            "宝宝今天自己扶着沙发走了几步",
        )

        fun resolve(
            tab: ParentingStartTab, workspace: ParentingWorkspace, now: Instant, calendar: MimiCalendar,
            context: ParentingStartContext = ParentingStartContext(),
        ): ParentingStartAction = when (tab) {
            ParentingStartTab.Today -> resolveToday(workspace, now, calendar, context)
            ParentingStartTab.Growth -> resolveGrowth(workspace, now, calendar, context)
            ParentingStartTab.Family -> resolveFamily(workspace, context)
        }

        /** 每个标签限定的那个孩子：请求的那个（若存在），否则最早创建的。 */
        fun activeChild(workspace: ParentingWorkspace, preferred: UUID?): ParentingChild? {
            if (preferred != null) workspace.children.firstOrNull { it.id == preferred }?.let { return it }
            return workspace.children.minWithOrNull(compareBy<ParentingChild> { it.createdAt }.thenBy { it.id.uuidString })
        }

        private fun resolveToday(workspace: ParentingWorkspace, now: Instant, calendar: MimiCalendar, context: ParentingStartContext): ParentingStartAction {
            val child = activeChild(workspace, context.childID) ?: return StartWithNickname
            val timeline = ParentingTodayTimeline.build(workspace, child.id, now, calendar)
            if (timeline.pending.isNotEmpty()) return ConfirmAll(timeline.pending.map { it.id })
            if (timeline.overdue.isNotEmpty()) return HandleOverdue(timeline.overdue.map { it.id })
            val planned = timeline.entries.mapNotNull { entry ->
                (entry.content as? ParentingTodayTimeline.Content.Arrangement)?.item?.takeIf { it.state == ParentingArrangementState.Planned }
            }
            // 条目按时间排序。优先今天还没到的；否则取已经过去的最近一项。
            planned.firstOrNull { it.dueAt > now }?.let { return NextWithComplete(it.id, it.dueAt, false) }
            planned.lastOrNull()?.let { return NextWithComplete(it.id, it.dueAt, true) }
            val hour = calendar.hour(now)
            val wroteToday = timeline.entries.any { it.content is ParentingTodayTimeline.Content.Observation }
            if (hour in EVENING_START_HOUR until EVENING_END_HOUR && !wroteToday) return RecordTodaySmallThing(child.id)
            return ExamplePrompt(examplePrompt(now, calendar))
        }

        /** 按天轮换，所以同一天内句子稳定而不是随机。 */
        fun examplePrompt(now: Instant, calendar: MimiCalendar): String {
            val day = calendar.dayOrdinalInEra(now)
            val n = examplePrompts.size
            return examplePrompts[((day % n) + n) % n]
        }

        private fun resolveGrowth(workspace: ParentingWorkspace, now: Instant, calendar: MimiCalendar, context: ParentingStartContext): ParentingStartAction {
            val child = activeChild(workspace, context.childID) ?: return ExamplePrompt(examplePrompt(now, calendar))
            val records = workspace.records.filter { it.childID == child.id }
            val base = growthBase(records, workspace.goals.any { it.childID == child.id })
            if (base == ParentingGrowthBase.NoRecords) return ExamplePrompt(examplePrompt(now, calendar))
            weeklyReviewReason(records, now, calendar, context.lastWeeklyReviewAt)?.let { return WeeklyReview(it) }
            if (base is ParentingGrowthBase.GiveGoal) return GiveObservationsAGoal(child.id, base.observationCount)
            return RecordObservation(child.id)
        }

        /** `records` 是这一个孩子的记录；`hasGoal` 是这个孩子有没有任何目标。 */
        fun growthBase(records: List<ParentingRecord>, hasGoal: Boolean): ParentingGrowthBase {
            if (records.isEmpty()) return ParentingGrowthBase.NoRecords
            val observations = records.count { it.kind == ParentingRecordKind.Observation }
            if (observations >= GROWTH_GOAL_OBSERVATION_THRESHOLD && !hasGoal) return ParentingGrowthBase.GiveGoal(observations)
            return ParentingGrowthBase.ContinueRecording
        }

        /** 周日 18:00 起（今天还没复盘），或距上次复盘至少七天。没有复盘记录时从孩子最早的记录起算。复盘需要最近 7 天里有东西可看。 */
        internal fun weeklyReviewReason(
            records: List<ParentingRecord>, now: Instant, calendar: MimiCalendar, lastReviewAt: Instant?,
        ): ParentingWeeklyReviewReason? {
            val weekAgo = calendar.addingDays(now, -WEEKLY_REVIEW_INTERVAL_DAYS)
            if (records.none { it.createdAt > weekAgo && it.createdAt <= now }) return null
            val reviewedToday = lastReviewAt?.let { calendar.isSameDay(it, now) } ?: false
            val isSunday = calendar.weekday(now) == 1
            if (isSunday && calendar.hour(now) >= EVENING_START_HOUR && !reviewedToday) return ParentingWeeklyReviewReason.SundayEvening
            val baseline = lastReviewAt ?: records.minOfOrNull { it.createdAt }
            if (baseline != null && baseline <= weekAgo) return ParentingWeeklyReviewReason.SevenDaysSinceLastReview
            return null
        }

        fun launchChecklist(workspace: ParentingWorkspace, context: ParentingStartContext = ParentingStartContext()): ParentingLaunchChecklist {
            val done = mutableSetOf<ParentingLaunchChecklist.Step>()
            val skipped = mutableSetOf<ParentingLaunchChecklist.Step>()
            if (workspace.children.isNotEmpty()) done.add(ParentingLaunchChecklist.Step.AddChild)
            if (workspace.arrangements.isNotEmpty() || workspace.records.isNotEmpty()) done.add(ParentingLaunchChecklist.Step.RecordFirstThing)
            if (context.remindersEnabled) done.add(ParentingLaunchChecklist.Step.EnableReminders)
            else if (context.remindersStepSkipped) skipped.add(ParentingLaunchChecklist.Step.EnableReminders)
            return ParentingLaunchChecklist(done, skipped)
        }

        private fun resolveFamily(workspace: ParentingWorkspace, context: ParentingStartContext): ParentingStartAction {
            val checklist = launchChecklist(workspace, context)
            return if (checklist.isFinished) None else LaunchChecklist(checklist)
        }
    }
}
