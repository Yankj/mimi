package com.heydusk.mimi.feature.parenting.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.designsystem.MimiTintedButton
import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.domain.parenting.ParentingGrowthDay
import com.heydusk.mimi.domain.parenting.ParentingGrowthInsights
import com.heydusk.mimi.domain.parenting.ParentingGrowthJourney
import com.heydusk.mimi.domain.parenting.ParentingWeekDensity
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import com.heydusk.mimi.i18n.ui
import java.time.ZoneId
import java.util.UUID

data class GrowthUi(
    val workspace: ParentingWorkspace,
    val week: ParentingWeekDensity,
    val journey: ParentingGrowthJourney,
    val childID: UUID?,
)

/** 成长首页：本周轨迹 + 两扇门（时间线 / 目标）+ 记观察。列表本身在二级页里。 */
@Composable
fun GrowthTabContent(
    uiState: GrowthUi,
    handlers: PageHandlers,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimiColors.current
    TitledPage(ui("成长"), modifier) {
        if (uiState.workspace.children.isEmpty()) {
            Text(ui("先添加孩子的昵称"), color = colors.ink2, modifier = Modifier.semantics { testTag = "parenting.growth.empty" })
            MimiTintedButton(onClick = handlers.onAddChild, modifier = Modifier.semantics { testTag = "parenting.firstChild" }) {
                Text(ui("添加孩子的昵称"))
            }
            return@TitledPage
        }
        ChildFilter(uiState.workspace.children, uiState.childID, handlers.onSelectChild)
        Card(colors = CardDefaults.cardColors(containerColor = colors.card), modifier = Modifier.fillMaxWidth().semantics { testTag = "parenting.growth.week" }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ui("这一周的轨迹"), style = MaterialTheme.typography.titleMedium, color = colors.ink)
                Text(
                    "${uiState.week.activeDays}/${ParentingGrowthInsights.WEEK_LENGTH} · ${uiState.week.total}",
                    color = colors.ink2,
                )
            }
        }
        MimiTintedButton(
            onClick = { handlers.onOpen(ParentingRoute.GrowthTimeline()) },
            modifier = Modifier.fillMaxWidth().semantics { testTag = "parenting.growth.mode.timeline" },
        ) { Text(ui("成长时间线")) }
        MimiTintedButton(
            onClick = { handlers.onOpen(ParentingRoute.GrowthGoals) },
            modifier = Modifier.fillMaxWidth().semantics { testTag = "parenting.growth.mode.goals" },
        ) { Text(ui("成长目标")) }
        uiState.childID?.let { childID ->
            MimiTintedButton(onClick = { handlers.onAddObservation(childID, null) }, modifier = Modifier.semantics { testTag = "parenting.growth.addObservation" }) {
                Text(ui("记下观察"))
            }
        }
    }
}

@Composable
fun GrowthTimelinePage(
    workspace: ParentingWorkspace,
    childID: UUID?,
    highlight: UUID?,
    zone: ZoneId,
    handlers: PageHandlers,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimiColors.current
    val journey = ParentingGrowthJourney.build(workspace, childID, calendar = MimiCalendar(zone))
    TitledPage(ui("成长时间线"), modifier, onBack = handlers.onBack) {
        ChildFilter(workspace.children, childID, handlers.onSelectChild)
        if (journey.isEmpty) {
            Text(ui("这里还没有记录。观察可以直接保存；安排完成后会生成独立的完成记录。"), color = colors.ink2,
                modifier = Modifier.semantics { testTag = "parenting.growth.noRecords" })
        }
        journey.days.forEach { day ->
            day.items.forEach { item ->
                when (item) {
                    is ParentingGrowthDay.Item.Record -> RecordCard(item.record, workspace, zone, handlers, highlighted = item.record.id == highlight)
                    is ParentingGrowthDay.Item.GoalStarted -> Text(
                        ui("开始目标：{0}", item.goal.title),
                        color = colors.ink2,
                        modifier = Modifier.semantics { testTag = "parenting.growth.goalStarted.${item.goal.id}" },
                    )
                    else -> Unit
                }
            }
        }
    }
}

@Composable
fun GrowthGoalsPage(
    workspace: ParentingWorkspace,
    childID: UUID?,
    handlers: PageHandlers,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimiColors.current
    TitledPage(ui("成长目标"), modifier, onBack = handlers.onBack) {
        ChildFilter(workspace.children, childID, handlers.onSelectChild)
        val goals = workspace.goals.filter { childID == null || it.childID == childID }
        if (goals.isEmpty()) {
            Text(ui("还没有成长目标。也可以先记观察，之后再建立目标。"), color = colors.ink2,
                modifier = Modifier.semantics { testTag = "parenting.growth.noGoals" })
        }
        goals.forEach { goal ->
            val count = workspace.records.count { it.goalID == goal.id }
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.card),
                modifier = Modifier.fillMaxWidth().semantics { testTag = "parenting.goal.${goal.id}" },
            ) {
                TextButton(onClick = { handlers.onOpen(ParentingRoute.GoalReview(goal.id)) }) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(goal.title, color = colors.ink, style = MaterialTheme.typography.titleMedium)
                        Text(ui("{0} 条记录", count.toString()), color = colors.ink2)
                    }
                }
            }
        }
        if (childID != null) {
            MimiTintedButton(onClick = { handlers.onAddGoal(childID) }, modifier = Modifier.semantics { testTag = "parenting.addGoal" }) {
                Text(ui("添加目标"))
            }
        }
    }
}

@Composable
fun GoalReviewPage(
    workspace: ParentingWorkspace,
    goalID: UUID,
    zone: ZoneId,
    handlers: PageHandlers,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimiColors.current
    val goal = workspace.goals.firstOrNull { it.id == goalID }
    TitledPage(ui("回顾这个目标"), modifier, onBack = handlers.onBack) {
        if (goal == null) {
            Text(ui("这个目标已经不存在。"), color = colors.ink2)
            return@TitledPage
        }
        Text(goal.title, style = MaterialTheme.typography.titleLarge, color = colors.ink, modifier = Modifier.semantics { testTag = "parenting.goal.review.title" })
        val records = ParentingGrowthJourney.build(workspace, goal.childID, goalID = goalID, calendar = MimiCalendar(zone))
        if (records.recordCount == 0) {
            Text(ui("这个目标下还没有记录。"), color = colors.ink2, modifier = Modifier.semantics { testTag = "parenting.goal.review.empty" })
        }
        records.days.forEach { day ->
            day.items.forEach { item ->
                if (item is ParentingGrowthDay.Item.Record) RecordCard(item.record, workspace, zone, handlers)
            }
        }
        MimiTintedButton(
            onClick = { handlers.onAddObservation(goal.childID, goalID) },
            modifier = Modifier.semantics { testTag = "parenting.addObservation.forGoal" },
        ) { Text(ui("为这个目标记一笔")) }
        TextButton(onClick = { handlers.onDeleteGoal(goalID) }, modifier = Modifier.semantics { testTag = "parenting.goal.delete" }) {
            Text(ui("删除目标"), color = colors.danger)
        }
    }
}
