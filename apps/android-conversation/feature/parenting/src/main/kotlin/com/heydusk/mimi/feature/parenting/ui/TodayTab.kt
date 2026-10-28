package com.heydusk.mimi.feature.parenting.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.designsystem.MimiTintedButton
import com.heydusk.mimi.domain.parenting.ParentingArrangementState
import com.heydusk.mimi.domain.parenting.ParentingStartAction
import com.heydusk.mimi.domain.parenting.ParentingTodayDeck
import com.heydusk.mimi.domain.parenting.ParentingTodayHome
import com.heydusk.mimi.domain.parenting.ParentingTodayTimeline
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import com.heydusk.mimi.i18n.ui
import java.util.UUID

data class TodayUi(
    val home: ParentingTodayHome,
    val deck: ParentingTodayDeck,
    val workspace: ParentingWorkspace,
)

@Composable
fun TodayTabContent(
    uiState: TodayUi,
    onAddChild: () -> Unit,
    onAddArrangement: (UUID) -> Unit,
    onConfirmArrangement: (UUID) -> Unit,
    onCompleteArrangement: (UUID) -> Unit,
    onUndo: () -> Unit,
    undoSeconds: Int?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimiColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = ui("今天"),
            style = MaterialTheme.typography.headlineLarge,
            color = colors.ink,
            modifier = Modifier.semantics { testTag = "parenting.title" },
        )
        Text(uiState.home.subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.ink2,
            modifier = Modifier.semantics { testTag = "parenting.today.subtitle" })

        HeroCard(uiState.deck, onAddChild, onAddArrangement, onConfirmArrangement, onCompleteArrangement)

        if (undoSeconds != null && undoSeconds > 0) {
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.card),
                modifier = Modifier.fillMaxWidth().semantics { testTag = "parenting.undo.bar" },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ui("已完成") + " · ${undoSeconds}s", color = colors.ink)
                    TextButton(onClick = onUndo, modifier = Modifier.semantics { testTag = "parenting.undo" }) {
                        Text(ui("撤销"))
                    }
                }
            }
        }

        val drafts = uiState.workspace.arrangements.filter { it.state == ParentingArrangementState.Draft }
        if (drafts.isNotEmpty()) {
            Text(ui("确认安排"), style = MaterialTheme.typography.titleMedium, color = colors.ink2)
            drafts.forEach { a ->
                ArrangementRow(
                    title = a.title,
                    tag = "parenting.confirm.${a.id}",
                    actionLabel = ui("确认安排"),
                    onAction = { onConfirmArrangement(a.id) },
                )
            }
        }

        if (uiState.home.rows.isEmpty() && drafts.isEmpty() && uiState.workspace.children.isNotEmpty()) {
            Text(ui("今天暂无安排"), color = colors.ink2, modifier = Modifier.semantics { testTag = "parenting.today.empty" })
        }

        uiState.home.rows.forEach { row ->
            val content = row.content
            if (content is ParentingTodayTimeline.Content.Arrangement) {
                val a = content.item
                val canComplete = a.state == ParentingArrangementState.Planned
                ArrangementRow(
                    title = a.title,
                    tag = "parenting.row.${a.id}",
                    actionLabel = if (canComplete) ui("完成") else null,
                    onAction = if (canComplete) ({ onCompleteArrangement(a.id) }) else null,
                )
            }
        }

        uiState.deck.childID?.let { childID ->
            MimiTintedButton(
                onClick = { onAddArrangement(childID) },
                modifier = Modifier.semantics { testTag = "parenting.today.addArrangement" },
            ) { Text(ui("记一件安排")) }
        }
    }
}

@Composable
private fun HeroCard(
    deck: ParentingTodayDeck,
    onAddChild: () -> Unit,
    onAddArrangement: (UUID) -> Unit,
    onConfirmArrangement: (UUID) -> Unit,
    onCompleteArrangement: (UUID) -> Unit,
) {
    val colors = LocalMimiColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        modifier = Modifier.fillMaxWidth().semantics { testTag = "parenting.today.hero" },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (val hero = deck.hero) {
                ParentingStartAction.StartWithNickname -> {
                    Text(ui("从孩子的昵称开始"), style = MaterialTheme.typography.titleMedium, color = colors.ink)
                    Text(ui("不用填写生日和健康信息。添加昵称后，就能记录安排、观察与成长目标。"), color = colors.ink2)
                    MimiTintedButton(onClick = onAddChild, modifier = Modifier.semantics { testTag = "parenting.hero.addChild" }) {
                        Text(ui("添加孩子的昵称"))
                    }
                }
                is ParentingStartAction.ConfirmAll -> {
                    Text(ui("你有待确认的事。可以先核对孩子、时间和内容，再决定是否安排。"), color = colors.ink)
                    MimiTintedButton(
                        onClick = { hero.arrangementIDs.firstOrNull()?.let(onConfirmArrangement) },
                        modifier = Modifier.semantics { testTag = "parenting.hero.confirmAll" },
                    ) { Text(ui("确认安排")) }
                }
                is ParentingStartAction.NextWithComplete -> {
                    val title = deck.heroArrangement?.title ?: ""
                    Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                    MimiTintedButton(
                        onClick = { onCompleteArrangement(hero.arrangementID) },
                        modifier = Modifier.semantics { testTag = "parenting.complete.${hero.arrangementID}" },
                    ) { Text(ui("完成")) }
                }
                is ParentingStartAction.HandleOverdue -> {
                    Text(ui("有安排的时间已经过去。可以按实际情况调整、取消或记录完成。"), color = colors.ink)
                    MimiTintedButton(
                        onClick = { hero.arrangementIDs.firstOrNull()?.let(onCompleteArrangement) },
                        modifier = Modifier.semantics { testTag = "parenting.hero.overdue" },
                    ) { Text(ui("完成")) }
                }
                is ParentingStartAction.RecordTodaySmallThing -> {
                    Text(ui("记下今天的第一条观察"), color = colors.ink)
                }
                is ParentingStartAction.ExamplePrompt -> {
                    Text(hero.text, color = colors.ink2)
                    deck.childID?.let { id ->
                        MimiTintedButton(onClick = { onAddArrangement(id) }) { Text(ui("记一件安排")) }
                    }
                }
                else -> Text(ui("今天暂无安排"), color = colors.ink2)
            }
        }
    }
}

@Composable
private fun ArrangementRow(title: String, tag: String, actionLabel: String?, onAction: (() -> Unit)?) {
    val colors = LocalMimiColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        modifier = Modifier.fillMaxWidth().semantics { testTag = tag },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = colors.ink, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
