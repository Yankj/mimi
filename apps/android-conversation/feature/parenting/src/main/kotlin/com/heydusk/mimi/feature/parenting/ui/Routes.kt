package com.heydusk.mimi.feature.parenting.ui

import java.util.UUID

/** 三个 Tab 之上的二级页（对应 iOS 的 push）。返回键 / 左上角返回弹出一级。 */
sealed interface ParentingRoute {
    /** 今天 › 全部安排 */
    data object TodayAll : ParentingRoute
    /** 成长 › 时间线；[highlight] 是从完成卡片跳来的那条记录 */
    data class GrowthTimeline(val highlight: UUID? = null) : ParentingRoute
    /** 成长 › 目标 */
    data object GrowthGoals : ParentingRoute
    /** 成长 › 目标回顾 */
    data class GoalReview(val goalID: UUID) : ParentingRoute
    /** 我们家 › 孩子 */
    data class ChildDetail(val childID: UUID) : ParentingRoute
    /** 我们家 › 提醒 */
    data object Reminders : ParentingRoute
}
