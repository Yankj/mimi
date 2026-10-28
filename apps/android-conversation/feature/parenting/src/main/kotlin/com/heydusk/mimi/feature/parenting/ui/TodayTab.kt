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
import java.time.ZoneId

data class TodayUi(
    val home: ParentingTodayHome,
    val deck: ParentingTodayDeck,
    val workspace: ParentingWorkspace,
)

/** 今天：一张英雄卡、撤销条、待确认草稿、今天的时间线，以及进入「全部安排」的入口。 */
@Composable
fun TodayTabContent(
    uiState: TodayUi,
    handlers: PageHandlers,
    undoSeconds: Int?,
    onUndo: () -> Unit,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimiColors.current
    TitledPage(ui("今天"), modifier) {
        Text(uiState.home.subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.ink2,
            modifier = Modifier.semantics { testTag = "parenting.today.subtitle" })

        HeroCard(uiState.deck, handlers)

        com.heydusk.mimi.feature.parenting.reminders.ReminderPromptCard()

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
                    whenLabel = formatWhen(a.dueAt, zone),
                    rowTag = "parenting.row.${a.id}",
                    actionTag = "parenting.confirm.${a.id}",
                    actionLabel = ui("确认安排"),
                    onAction = { handlers.onConfirmArrangement(a.id) },
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
                    whenLabel = formatWhen(a.dueAt, zone),
                    rowTag = "parenting.row.${a.id}",
                    actionTag = "parenting.row.complete.${a.id}",
                    actionLabel = if (canComplete) ui("完成") else null,
                    onAction = if (canComplete) ({ handlers.onCompleteOneTap(a.id) }) else null,
                    footnote = { com.heydusk.mimi.feature.parenting.reminders.ReminderLine(a, zone) },
                )
            }
        }

        if (uiState.deck.childID != null) {
            MimiTintedButton(
                onClick = { handlers.onOpen(ParentingRoute.TodayAll) },
                modifier = Modifier.semantics { testTag = "parenting.today.allArrangements" },
            ) { Text(ui("全部安排")) }
        }
    }
}

@Composable
private fun HeroCard(deck: ParentingTodayDeck, handlers: PageHandlers) {
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
                    MimiTintedButton(onClick = handlers.onAddChild, modifier = Modifier.semantics { testTag = "parenting.hero.addChild" }) {
                        Text(ui("添加孩子的昵称"))
                    }
                }
                is ParentingStartAction.ConfirmAll -> {
                    Text(ui("你有待确认的事。可以先核对孩子、时间和内容，再决定是否安排。"), color = colors.ink)
                    MimiTintedButton(
                        onClick = { hero.arrangementIDs.firstOrNull()?.let(handlers.onConfirmArrangement) },
                        modifier = Modifier.semantics { testTag = "parenting.hero.confirmAll" },
                    ) { Text(ui("确认安排")) }
                }
                is ParentingStartAction.NextWithComplete -> {
                    val title = deck.heroArrangement?.title ?: ""
                    Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                    MimiTintedButton(
                        onClick = { handlers.onCompleteOneTap(hero.arrangementID) },
                        modifier = Modifier.semantics { testTag = "parenting.complete.${hero.arrangementID}" },
                    ) { Text(ui("完成")) }
                }
                is ParentingStartAction.HandleOverdue -> {
                    Text(ui("有安排的时间已经过去。可以按实际情况调整、取消或记录完成。"), color = colors.ink)
                    MimiTintedButton(
                        onClick = { hero.arrangementIDs.firstOrNull()?.let(handlers.onCompleteOneTap) },
                        modifier = Modifier.semantics { testTag = "parenting.hero.overdue" },
                    ) { Text(ui("完成")) }
                }
                is ParentingStartAction.RecordTodaySmallThing -> {
                    Text(ui("记下今天的第一条观察"), color = colors.ink)
                    MimiTintedButton(
                        onClick = { handlers.onAddObservation(hero.childID, null) },
                        modifier = Modifier.semantics { testTag = "parenting.hero.recordObservation" },
                    ) { Text(ui("记下观察")) }
                }
                is ParentingStartAction.ExamplePrompt -> {
                    Text(hero.text, color = colors.ink2)
                    deck.childID?.let { id ->
                        MimiTintedButton(onClick = { handlers.onAddArrangement(id) }, modifier = Modifier.semantics { testTag = "parenting.hero.addArrangement" }) { Text(ui("记一件安排")) }
                    }
                }
                else -> Text(ui("今天暂无安排"), color = colors.ink2)
            }
        }
    }
}

@Composable
private fun ArrangementRow(
    title: String,
    whenLabel: String,
    rowTag: String,
    actionTag: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    footnote: (@Composable () -> Unit)? = null,
) {
    val colors = LocalMimiColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        modifier = Modifier.fillMaxWidth().semantics { testTag = rowTag },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.ink)
                Text(whenLabel, color = colors.ink2, style = MaterialTheme.typography.labelMedium)
                footnote?.invoke()
            }
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.semantics { testTag = actionTag }) { Text(actionLabel) }
            }
        }
    }
}
