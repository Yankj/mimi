package com.heydusk.mimi.feature.parenting.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.designsystem.MimiTintedButton
import com.heydusk.mimi.domain.parenting.FamilyLaunchChecklist
import com.heydusk.mimi.domain.parenting.ParentingFamilyChildSummary
import com.heydusk.mimi.i18n.ui

data class FamilyUi(
    val children: List<ParentingFamilyChildSummary>,
    val checklist: FamilyLaunchChecklist,
)

@Composable
fun FamilyTabContent(
    uiState: FamilyUi,
    onAddChild: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimiColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = ui("我们家"),
                style = MaterialTheme.typography.headlineLarge,
                color = colors.ink,
                modifier = Modifier.semantics { testTag = "parenting.title" },
            )
            Text(
                ui("设置"),
                color = colors.ink2,
                modifier = Modifier
                    .clickable(onClick = onOpenSettings)
                    .semantics { testTag = "parenting.family.settings" }
                    .padding(8.dp),
            )
        }

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

        if (uiState.children.isEmpty()) {
            Text(ui("先添加孩子的昵称"), color = colors.ink2, modifier = Modifier.semantics { testTag = "parenting.family.empty" })
            MimiTintedButton(onClick = onAddChild, modifier = Modifier.semantics { testTag = "parenting.family.addChild" }) {
                Text(ui("添加孩子的昵称"))
            }
        } else {
            uiState.children.forEach { child ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    modifier = Modifier.fillMaxWidth().semantics { testTag = "parenting.family.child.${child.id}" },
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
            MimiTintedButton(onClick = onAddChild, modifier = Modifier.semantics { testTag = "parenting.family.addChild" }) {
                Text(ui("添加孩子的昵称"))
            }
        }
    }
}
