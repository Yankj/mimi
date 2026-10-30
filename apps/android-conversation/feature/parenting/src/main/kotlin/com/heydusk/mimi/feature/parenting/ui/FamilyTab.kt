package com.heydusk.mimi.feature.parenting.ui

import androidx.compose.foundation.clickable
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
import com.heydusk.mimi.domain.parenting.FamilyLaunchChecklist
import com.heydusk.mimi.domain.parenting.ParentingFamilyChildSummary
import com.heydusk.mimi.domain.parenting.ParentingProfileFact
import com.heydusk.mimi.domain.parenting.ParentingProfileScope
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import com.heydusk.mimi.feature.parenting.reminders.LocalReminders
import com.heydusk.mimi.i18n.ui
import java.util.UUID

data class FamilyUi(
    val children: List<ParentingFamilyChildSummary>,
    val checklist: FamilyLaunchChecklist,
    /** 家庭范围、家长确认过的长期事实（#148）。没有就不显示这张卡。 */
    val familyFacts: List<ParentingProfileFact> = emptyList(),
)

@Composable
fun FamilyTabContent(
    uiState: FamilyUi,
    handlers: PageHandlers,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimiColors.current
    TitledPage(
        ui("我们家"),
        modifier,
        trailing = {
            TextButton(onClick = handlers.onOpenSettings, modifier = Modifier.semantics { testTag = "parenting.family.settings" }) {
                Text(ui("设置"), color = colors.ink2)
            }
        },
    ) {
        if (!uiState.checklist.isFinished) {
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.card),
                modifier = Modifier.fillMaxWidth().semantics { testTag = "parenting.family.launch" },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        ui("启航 {0}/{1}", uiState.checklist.doneCount.toString(), uiState.checklist.totalCount.toString()),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.ink,
                    )
                    uiState.checklist.steps.forEach { step ->
                        Text(
                            (if (step.isDone) "✓ " else "○ ") + ui(step.title),
                            color = if (step.isDone) colors.ink2 else colors.ink,
                            modifier = Modifier.semantics { testTag = "parenting.family.launch.${step.id}" },
                        )
                    }
                }
            }
        }

        if (uiState.familyFacts.isNotEmpty()) FactsCard(uiState.familyFacts, "parenting.family.facts")

        val reminders = LocalReminders.current
        Card(
            colors = CardDefaults.cardColors(containerColor = colors.card),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { handlers.onOpen(ParentingRoute.Reminders) }
                .semantics { testTag = "parenting.family.reminders" },
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(ui("提醒"), style = MaterialTheme.typography.titleMedium, color = colors.ink)
                Text(
                    when {
                        reminders == null -> ui("这台设备暂不发送提醒")
                        reminders.ui.settings.enabled -> ui("已开启 · 每天最多 2 条，晚 9 点到早 8 点不打扰")
                        else -> ui("未开启 · 每天最多 2 条，晚 9 点到早 8 点不打扰")
                    },
                    color = colors.ink2,
                    modifier = Modifier.semantics { testTag = "parenting.family.reminders.subtitle" },
                )
            }
        }

        if (uiState.children.isEmpty()) {
            Text(ui("先添加孩子的昵称"), color = colors.ink2, modifier = Modifier.semantics { testTag = "parenting.family.empty" })
            MimiTintedButton(onClick = handlers.onAddChild, modifier = Modifier.semantics { testTag = "parenting.family.addChild" }) {
                Text(ui("添加孩子的昵称"))
            }
        } else {
            uiState.children.forEach { child ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { handlers.onOpen(ParentingRoute.ChildDetail(child.id)) }
                        .semantics { testTag = "parenting.family.child.${child.id}" },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${child.initial}  ${child.name}", style = MaterialTheme.typography.titleMedium, color = colors.ink)
                        Text(
                            ui("{0} 个目标 · {1} 条观察", child.goalCount.toString(), child.observationCount.toString()),
                            color = colors.ink2,
                        )
                    }
                }
            }
            MimiTintedButton(onClick = handlers.onAddChild, modifier = Modifier.semantics { testTag = "parenting.family.addChild" }) {
                Text(ui("添加孩子的昵称"))
            }
        }
    }
}

@Composable
fun ChildDetailPage(
    workspace: ParentingWorkspace,
    childID: UUID,
    handlers: PageHandlers,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimiColors.current
    val child = workspace.children.firstOrNull { it.id == childID }
    TitledPage(child?.name ?: ui("孩子"), modifier, onBack = handlers.onBack) {
        if (child == null) {
            Text(ui("这个孩子已经不存在。"), color = colors.ink2)
            return@TitledPage
        }
        val goals = workspace.goals.count { it.childID == childID }
        val observations = workspace.records.count { it.childID == childID }
        val arrangements = workspace.arrangements.count { it.childID == childID }
        Text(
            ui("{0} 个目标 · {1} 条记录 · {2} 件安排", goals.toString(), observations.toString(), arrangements.toString()),
            color = colors.ink2,
            modifier = Modifier.semantics { testTag = "parenting.child.counts" },
        )
        val facts = workspace.profile.usableFacts(ParentingProfileScope.child(childID))
        if (facts.isNotEmpty()) FactsCard(facts, "parenting.child.facts")
        MimiTintedButton(onClick = { handlers.onRenameChild(child) }, modifier = Modifier.semantics { testTag = "parenting.child.rename" }) {
            Text(ui("修改昵称"))
        }
        TextButton(onClick = { handlers.onDeleteChild(child) }, modifier = Modifier.semantics { testTag = "parenting.child.delete" }) {
            Text(ui("删除孩子及其全部内容"), color = colors.danger)
        }
    }
}

/** 长期事实：只显示在确认卡上点过确认的。 */
@Composable
private fun FactsCard(facts: List<ParentingProfileFact>, tag: String) {
    val colors = LocalMimiColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        modifier = Modifier.fillMaxWidth().semantics { testTag = tag },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ui("长期事实"), style = MaterialTheme.typography.titleMedium, color = colors.ink)
            facts.forEach { fact ->
                Column(Modifier.semantics(mergeDescendants = true) { testTag = "$tag.${fact.id}" }) {
                    Text(ui(fact.kind.archiveTitle), color = colors.ink2, style = MaterialTheme.typography.labelMedium)
                    Text(fact.text, color = colors.ink)
                }
            }
            Text(ui("只显示你在确认卡上点过确认的事实。还没确认或还不确定的不会出现在这里。"), color = colors.ink2, style = MaterialTheme.typography.labelMedium)
        }
    }
}
