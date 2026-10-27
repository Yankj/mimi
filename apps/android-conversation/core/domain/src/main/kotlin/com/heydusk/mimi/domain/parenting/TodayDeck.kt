package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.domain.common.uuidString
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max

/**
 * 「今天」页展示的内容：一张主卡（推荐的起手动作）加一条简短的"接下来"。
 * 只读且确定；不写、不完成、不发送；不含面向用户的文字（界面把枚举映射成本地化文案）。
 */
data class ParentingTodayDeck(
    /** 主卡和队列限定的孩子；还没有孩子时为 null。 */
    val childID: UUID?,
    /** 唯一推荐的起手动作，恰好是 `ParentingStartAction.resolve(Today)` 的结果。 */
    val hero: ParentingStartAction,
    /** `NextWithComplete` 背后的那项安排；其他主卡为 null。 */
    val heroArrangement: ParentingArrangement?,
    /** 仍在 `now` 之后的已确认安排（今天先、之后的日子再），最早的在前，不含主卡本身。 */
    val rail: List<RailItem>,
    /** 这个孩子的未结安排（草稿或已确认）里，主卡和队列都没展示的数量。 */
    val hiddenOpenCount: Int,
) {
    data class RailItem(val id: UUID, val title: String, val dueAt: Instant, val childID: UUID, val whenLabel: When) {
        enum class When { LaterToday, AnotherDay }
    }

    companion object {
        /** 队列最多放这么多项。 */
        const val RAIL_LIMIT = 3

        fun build(
            workspace: ParentingWorkspace, now: Instant, calendar: MimiCalendar,
            context: ParentingStartContext = ParentingStartContext(), railLimit: Int = RAIL_LIMIT,
        ): ParentingTodayDeck {
            val hero = ParentingStartAction.resolve(ParentingStartTab.Today, workspace, now, calendar, context)
            val child = ParentingStartAction.activeChild(workspace, context.childID)
                ?: return ParentingTodayDeck(null, hero, null, emptyList(), 0)
            val heroArrangement = (hero as? ParentingStartAction.NextWithComplete)?.let { h -> workspace.arrangements.firstOrNull { it.id == h.arrangementID } }

            val ahead = workspace.arrangements
                .filter { it.childID == child.id && it.state == ParentingArrangementState.Planned && it.dueAt > now && it.id != heroArrangement?.id }
                .sortedWith(compareBy<ParentingArrangement> { it.dueAt }.thenBy { it.id.uuidString })
            val rail = ahead.take(max(railLimit, 0)).map { item ->
                RailItem(
                    item.id, item.title, item.dueAt, item.childID,
                    if (calendar.isSameDay(item.dueAt, now)) RailItem.When.LaterToday else RailItem.When.AnotherDay,
                )
            }
            val shown = rail.map { it.id }.toSet() + listOfNotNull(heroArrangement?.id)
            val open = workspace.arrangements.filter { it.childID == child.id && it.state.isOpen }
            return ParentingTodayDeck(child.id, hero, heroArrangement, rail, open.count { it.id !in shown })
        }
    }
}

/** 一键完成之后的短暂撤销窗口。完成已经保存（账本已确认）时才存在；撤销是第二条独立保存的命令（`ReopenArrangement`），不是回滚。 */
data class ParentingCompletionUndo(val arrangementID: UUID, val recordID: UUID, val expiresAt: Instant) {
    /** 剩余整秒数，向上取整，不为负。保存后立刻是 3，窗口过去后是 0。 */
    fun remainingSeconds(now: Instant): Int =
        max(0, ceil(Duration.between(now, expiresAt).toNanos() / 1_000_000_000.0).toInt())

    /** 计时器该等多久再撤掉撤销入口。绝对时间（`expiresAt`），离开再回来不会延长窗口。 */
    fun timeUntilExpiry(now: Instant): Duration = Duration.between(now, expiresAt).let { if (it.isNegative) Duration.ZERO else it }

    fun isActive(now: Instant): Boolean = now < expiresAt

    companion object {
        val WINDOW: Duration = Duration.ofSeconds(3)

        /** 除非这项安排当前正是被这条记录完成的，否则为 null。 */
        fun make(workspace: ParentingWorkspace, arrangementID: UUID, recordID: UUID, now: Instant): ParentingCompletionUndo? {
            if (workspace.arrangements.firstOrNull { it.id == arrangementID }?.state != ParentingArrangementState.Completed) return null
            if (workspace.records.none { it.id == recordID && it.arrangementID == arrangementID }) return null
            return ParentingCompletionUndo(arrangementID, recordID, now.plus(WINDOW))
        }
    }
}

/** 家长上次把每周复盘标记完成已经多久。 */
sealed interface ParentingWeeklyReviewStatus {
    data object Never : ParentingWeeklyReviewStatus
    data object Today : ParentingWeeklyReviewStatus
    data class DaysAgo(val days: Int) : ParentingWeeklyReviewStatus

    companion object {
        /** 存的日期在未来（改过时钟）：当作今天，永远不出现负的天数。 */
        fun make(lastAt: Instant?, now: Instant, calendar: MimiCalendar): ParentingWeeklyReviewStatus {
            if (lastAt == null) return Never
            val days = calendar.daysBetweenStartsOfDay(lastAt, now)
            return if (days <= 0) Today else DaysAgo(days)
        }
    }
}

/**
 * 「上次周复盘时间」的存储接口。iOS 把它放在加密账本旁边的 UserDefaults 里，每个登录主体一个键；
 * Android 由 :core:data 用 DataStore 实现同一个键名。它只喂给 `ParentingStartContext.lastWeeklyReviewAt`，不属于账本。
 */
interface ParentingWeeklyReviewStore {
    suspend fun load(): Instant?
    suspend fun save(date: Instant)
    suspend fun erase()

    companion object {
        const val KEY_PREFIX = "mimi.parenting.weeklyReview.lastAt.v1."
        fun key(subjectID: UUID): String = KEY_PREFIX + subjectID.toString().lowercase(Locale.ROOT)
    }
}

/**
 * Today 主卡一键「完成」的防重入保护。是个纯值，规则不用界面就能测：
 * - 同一时间只有一次完成在进行；
 * - 完成之后主卡已经显示下一项，所以紧接着 `COOLDOWN` 秒内的点击被忽略（双击只能完成一项，不能完成两项）。
 *   保存失败不启动冷却，家长可以马上重试。
 */
class ParentingOneTapGate {
    var inFlight: Boolean = false
        private set
    var lastCompletedAt: Instant? = null
        private set

    /** 这次点击可以开始完成时为 true；在 `finish` 之前把门关上。 */
    fun begin(now: Instant): Boolean {
        if (inFlight) return false
        val last = lastCompletedAt
        if (last != null && Duration.between(last, now) < COOLDOWN && now >= last) return false
        inFlight = true
        return true
    }

    /** `completedAt` 为 null 表示保存失败或被拒绝。 */
    fun finish(completedAt: Instant?) {
        inFlight = false
        if (completedAt != null) lastCompletedAt = completedAt
    }

    companion object { val COOLDOWN: Duration = Duration.ofMillis(800) }
}
