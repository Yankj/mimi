package com.heydusk.mimi.feature.parenting.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.designsystem.MimiTintedButton
import com.heydusk.mimi.domain.parenting.ParentingArrangement
import com.heydusk.mimi.domain.parenting.ParentingArrangementEdit
import com.heydusk.mimi.domain.parenting.ParentingArrangementState
import com.heydusk.mimi.domain.parenting.ParentingChild
import com.heydusk.mimi.domain.parenting.ParentingRecord
import com.heydusk.mimi.domain.parenting.ParentingRecordKind
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import com.heydusk.mimi.i18n.ui
import java.time.ZoneId
import java.util.UUID

/**
 * 悬浮底部（输入卡 + 底栏）占掉的高度，页面据此给滚动内容留出底部空白。默认 120dp = 只有底栏时的值；
 * Home 在有输入卡时量出实际高度提供进来。
 */
val LocalFloatingBottomInset = androidx.compose.runtime.compositionLocalOf { 120.dp }

/** 页面里所有点击的去向。Home 把它们接到表单浮层、确认浮层与账本写入。 */
class PageHandlers(
    val onConfirmArrangement: (UUID) -> Unit = {},
    val onCompleteOneTap: (UUID) -> Unit = {},
    val onCompleteWithNote: (ParentingArrangement) -> Unit = {},
    val onAdjust: (ParentingArrangement) -> Unit = {},
    val onCancelArrangement: (ParentingArrangement) -> Unit = {},
    val onDeleteArrangement: (ParentingArrangement) -> Unit = {},
    val onUndoAdjustment: (ParentingArrangementEdit) -> Unit = {},
    val onOpenRecord: (UUID) -> Unit = {},
    val onEditRecord: (ParentingRecord) -> Unit = {},
    val onDeleteRecord: (ParentingRecord) -> Unit = {},
    val onAddChild: () -> Unit = {},
    val onRenameChild: (ParentingChild) -> Unit = {},
    val onDeleteChild: (ParentingChild) -> Unit = {},
    val onAddGoal: (UUID) -> Unit = {},
    val onDeleteGoal: (UUID) -> Unit = {},
    val onAddArrangement: (UUID) -> Unit = {},
    val onAddObservation: (childID: UUID, goalID: UUID?) -> Unit = { _, _ -> },
    val onOpen: (ParentingRoute) -> Unit = {},
    val onBack: () -> Unit = {},
    val onSelectChild: (UUID) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

/**
 * 带「大标题 → 滚动后折叠成顶部小标题」的页面骨架（对应 iOS 的 large → inline navigation title）。
 * 大标题 tag `parenting.title`；折叠后顶部栏出现 `parenting.title.inline`。
 */
@Composable
fun TitledPage(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalMimiColors.current
    val scroll = rememberScrollState()
    val threshold = with(LocalDensity.current) { 56.dp.roundToPx() }
    val collapsed by remember { derivedStateOf { scroll.value > threshold } }
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = LocalFloatingBottomInset.current),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (onBack != null) {
                TextButton(onClick = onBack, modifier = Modifier.semantics { testTag = "parenting.back" }) { Text("‹ " + ui("返回")) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = colors.ink,
                    modifier = Modifier.weight(1f).semantics { testTag = "parenting.title" },
                )
                trailing?.invoke()
            }
            content()
        }
        if (collapsed) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.canvas.copy(alpha = 0.94f))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                    modifier = Modifier.semantics { testTag = "parenting.title.inline" },
                )
            }
        }
    }
}

/** 折叠 / 展开的小节（已完成、已取消、调整历史、记录来源）。头部 tag = [tag]。 */
@Composable
fun Disclosure(label: String, tag: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalMimiColors.current
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 8.dp)
                .semantics { testTag = tag },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text((if (open) "▾ " else "▸ ") + label, color = colors.ink2, style = MaterialTheme.typography.titleSmall)
        }
        if (open) content()
    }
}

class MenuItem(val tag: String, val label: String, val onClick: () -> Unit)

@Composable
fun OverflowMenu(tag: String, items: List<MenuItem>) {
    val colors = LocalMimiColors.current
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.semantics { testTag = tag }) { Text("⋯", color = colors.ink2) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
                    onClick = { open = false; item.onClick() },
                    modifier = Modifier.semantics { testTag = item.tag },
                )
            }
        }
    }
}

@Composable
fun ChildFilter(children: List<ParentingChild>, selected: UUID?, onSelect: (UUID) -> Unit) {
    if (children.isEmpty()) return
    val colors = LocalMimiColors.current
    Row(
        Modifier.fillMaxWidth().semantics { testTag = "parenting.childFilter" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        children.forEach { child ->
            val isSelected = child.id == selected
            TextButton(
                onClick = { onSelect(child.id) },
                modifier = Modifier.semantics { testTag = "parenting.childFilter.${child.id}" },
            ) {
                Text(
                    child.name,
                    color = if (isSelected) colors.ink else colors.ink2,
                    style = if (isSelected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun StateLabel(text: String, tag: String? = null) {
    val colors = LocalMimiColors.current
    Text(
        text,
        color = colors.ink2,
        style = MaterialTheme.typography.labelMedium,
        modifier = if (tag != null) Modifier.semantics { testTag = tag } else Modifier,
    )
}

/** 一件安排。状态决定可用的动作：草稿 → 确认 / 调整；已安排 → 完成 / 调整；已完成 / 已取消 → 只读（完成的带记录链接）。 */
@Composable
fun ArrangementCard(
    arrangement: ParentingArrangement,
    workspace: ParentingWorkspace,
    zone: ZoneId,
    handlers: PageHandlers,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimiColors.current
    val a = arrangement
    val goal = workspace.goals.firstOrNull { it.id == a.goalID }
    val edits = workspace.editHistory.filter { it.after.id == a.id }.sortedBy { it.createdAt }
    val undoable = edits.lastOrNull { it.canUndo(workspace) }
    val completion = workspace.records.firstOrNull { it.arrangementID == a.id && it.kind == ParentingRecordKind.Completion }
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        modifier = modifier.fillMaxWidth().semantics { testTag = "parenting.arrangement.${a.id}" },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(a.title, style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.weight(1f))
                val items = buildList {
                    if (a.state.isOpen) add(MenuItem("parenting.arrangement.action.cancel.${a.id}", ui("取消这个安排")) { handlers.onCancelArrangement(a) })
                    if (undoable != null) add(MenuItem("parenting.undoAdjustment.${a.id}", ui("撤销上次调整")) { handlers.onUndoAdjustment(undoable) })
                    add(MenuItem("parenting.arrangement.action.delete.${a.id}", ui("删除安排")) { handlers.onDeleteArrangement(a) })
                }
                OverflowMenu("parenting.arrangement.menu.${a.id}", items)
            }
            Text(
                formatWhen(a.dueAt, zone),
                color = colors.ink2,
                modifier = Modifier.semantics { testTag = "parenting.arrangement.time.${a.id}" },
            )
            if (goal != null) {
                Text(
                    ui("目标：{0}", goal.title),
                    color = colors.ink2,
                    modifier = Modifier.semantics { testTag = "parenting.arrangement.goal.${a.id}" },
                )
            }
            when (a.state) {
                ParentingArrangementState.Draft -> StateLabel(ui("待确认"))
                ParentingArrangementState.Planned -> StateLabel(ui("已安排"))
                ParentingArrangementState.Completed -> StateLabel(ui("已完成"), "parenting.arrangement.completed.${a.id}")
                ParentingArrangementState.Cancelled -> StateLabel(ui("已取消"), "parenting.arrangement.cancelled.${a.id}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (a.state) {
                    ParentingArrangementState.Draft -> {
                        MimiTintedButton(onClick = { handlers.onConfirmArrangement(a.id) }, modifier = Modifier.semantics { testTag = "parenting.confirm.${a.id}" }) { Text(ui("确认安排")) }
                        TextButton(onClick = { handlers.onAdjust(a) }, modifier = Modifier.semantics { testTag = "parenting.adjust.${a.id}" }) { Text(ui("调整")) }
                    }
                    ParentingArrangementState.Planned -> {
                        MimiTintedButton(onClick = { handlers.onCompleteWithNote(a) }, modifier = Modifier.semantics { testTag = "parenting.complete.${a.id}" }) { Text(ui("完成")) }
                        TextButton(onClick = { handlers.onAdjust(a) }, modifier = Modifier.semantics { testTag = "parenting.adjust.${a.id}" }) { Text(ui("调整")) }
                    }
                    else -> Unit
                }
            }
            if (a.state == ParentingArrangementState.Completed && completion != null) {
                TextButton(
                    onClick = { handlers.onOpenRecord(completion.id) },
                    modifier = Modifier.semantics { testTag = "parenting.completionRecord.${completion.id}" },
                ) { Text(ui("查看完成记录")) }
            }
            if (edits.isNotEmpty()) {
                Disclosure(ui("调整历史"), "parenting.adjustmentHistory.${a.id}") {
                    edits.forEach { edit ->
                        Column(Modifier.semantics { testTag = "parenting.adjustment.${edit.id}" }) {
                            Text(
                                formatWhen(edit.before.dueAt, zone) + " → " + formatWhen(edit.after.dueAt, zone),
                                color = colors.ink,
                            )
                            if (edit.sourceText.isNotBlank()) {
                                Text(edit.sourceText, color = colors.ink2, modifier = Modifier.semantics { testTag = "parenting.adjustment.source.${edit.id}" })
                            }
                            if (edit.revertedAt != null) {
                                StateLabel(ui("已撤销"), "parenting.adjustment.reverted.${edit.id}")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一条记录（观察或完成记录）：正文、目标、操作菜单、来源与改动时间（折叠）。 */
@Composable
fun RecordCard(
    record: ParentingRecord,
    workspace: ParentingWorkspace,
    zone: ZoneId,
    handlers: PageHandlers,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimiColors.current
    val goal = workspace.goals.firstOrNull { it.id == record.goalID }
    Card(
        colors = CardDefaults.cardColors(containerColor = if (highlighted) colors.surface else colors.card),
        modifier = modifier.fillMaxWidth().semantics { testTag = "parenting.record.${record.id}" },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                StateLabel(if (record.kind == ParentingRecordKind.Completion) ui("完成记录") else ui("观察"))
                OverflowMenu(
                    "parenting.record.menu.${record.id}",
                    listOf(
                        MenuItem("parenting.record.action.edit.${record.id}", ui("修改记录")) { handlers.onEditRecord(record) },
                        MenuItem("parenting.record.action.delete.${record.id}", ui("删除记录")) { handlers.onDeleteRecord(record) },
                    ),
                )
            }
            Text(
                record.text,
                color = colors.ink,
                modifier = Modifier.semantics { testTag = "parenting.record.body.${record.id}" },
            )
            if (goal != null) {
                Text(
                    ui("目标：{0}", goal.title),
                    color = colors.ink2,
                    modifier = Modifier.semantics { testTag = "parenting.record.goal.${record.id}" },
                )
            }
            Text(formatWhen(record.createdAt, zone), color = colors.ink2, style = MaterialTheme.typography.labelMedium)
            Disclosure(ui("记录来源"), "parenting.record.provenance.${record.id}") {
                Text(
                    record.sourceText.ifBlank { ui("没有原话") },
                    color = colors.ink2,
                    modifier = Modifier.semantics { testTag = "parenting.record.source.${record.id}" },
                )
                if (record.updatedAt != record.createdAt) {
                    Text(
                        ui("修改于 {0}", formatWhen(record.updatedAt, zone)),
                        color = colors.ink2,
                        modifier = Modifier.semantics { testTag = "parenting.record.correctedAt.${record.id}" },
                    )
                }
            }
        }
    }
}
