package com.heydusk.mimi.feature.parenting.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.designsystem.MimiTintedButton
import com.heydusk.mimi.domain.parenting.ParentingGrowthInsights
import com.heydusk.mimi.domain.parenting.ParentingGrowthDay
import com.heydusk.mimi.domain.parenting.ParentingGrowthJourney
import com.heydusk.mimi.domain.parenting.ParentingWeekDensity
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import com.heydusk.mimi.i18n.ui
import java.util.UUID

data class GrowthUi(
    val workspace: ParentingWorkspace,
    val week: ParentingWeekDensity,
    val journey: ParentingGrowthJourney,
    val childID: UUID?,
)

@Composable
fun GrowthTabContent(
    uiState: GrowthUi,
    onAddGoal: (UUID) -> Unit,
    onAddObservation: (UUID) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimiColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = ui("成长"),
            style = MaterialTheme.typography.headlineLarge,
            color = colors.ink,
            modifier = Modifier.semantics { testTag = "parenting.title" },
        )
        if (uiState.workspace.children.isEmpty()) {
            Text(ui("先添加孩子的昵称"), color = colors.ink2, modifier = Modifier.semantics { testTag = "parenting.growth.empty" })
            return
        }
        Card(colors = CardDefaults.cardColors(containerColor = colors.card), modifier = Modifier.fillMaxWidth().semantics { testTag = "parenting.growth.week" }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ui("这一周的轨迹"), style = MaterialTheme.typography.titleMedium, color = colors.ink)
                Text(
                    "${uiState.week.activeDays}/${ParentingGrowthInsights.WEEK_LENGTH} · ${uiState.week.total}",
                    color = colors.ink2,
                )
            }
        }
        val goals = uiState.workspace.goals.filter { uiState.childID == null || it.childID == uiState.childID }
        if (goals.isEmpty()) {
            Text(ui("还没有成长目标。也可以先记观察，之后再建立目标。"), color = colors.ink2,
                modifier = Modifier.semantics { testTag = "parenting.growth.noGoals" })
        } else {
            goals.forEach { goal ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    modifier = Modifier.fillMaxWidth().semantics { testTag = "parenting.goal.${goal.id}" },
                ) {
                    Text(goal.title, Modifier.padding(12.dp), color = colors.ink)
                }
            }
        }
        uiState.childID?.let { childID ->
            MimiTintedButton(onClick = { onAddGoal(childID) }, modifier = Modifier.semantics { testTag = "parenting.growth.addGoal" }) {
                Text(ui("添加目标"))
            }
            MimiTintedButton(onClick = { onAddObservation(childID) }, modifier = Modifier.semantics { testTag = "parenting.growth.addObservation" }) {
                Text(ui("记下观察"))
            }
        }
        if (uiState.journey.days.isEmpty()) {
            Text(ui("这里还没有记录。观察可以直接保存；安排完成后会生成独立的完成记录。"), color = colors.ink2,
                modifier = Modifier.semantics { testTag = "parenting.growth.noRecords" })
        } else {
            uiState.journey.days.take(7).forEach { day ->
                day.items.forEach { item ->
                    if (item is ParentingGrowthDay.Item.Record) {
                        Text(
                            item.record.text,
                            color = colors.ink,
                            modifier = Modifier.semantics { testTag = "parenting.record.body.${item.record.id}" },
                        )
                    }
                }
            }
        }
    }
}
