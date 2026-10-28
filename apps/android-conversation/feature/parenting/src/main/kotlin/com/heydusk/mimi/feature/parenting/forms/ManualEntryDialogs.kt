package com.heydusk.mimi.feature.parenting.forms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.domain.parenting.ParentingArrangement
import com.heydusk.mimi.domain.parenting.ParentingGoal
import com.heydusk.mimi.feature.parenting.ui.formatWhen
import com.heydusk.mimi.i18n.ui
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** 所有表单共用的外部状态：保存中禁用「保存」；账本没写成功时，错误显示在表单里、输入保持原样。 */
data class FormStatus(val saving: Boolean = false, val error: String? = null)

@Composable
fun ChildEntryDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    status: FormStatus = FormStatus(),
    title: String = ui("添加孩子的昵称"),
    initial: String = "",
) {
    var draft by remember { mutableStateOf(ChildDraft(initial)) }
    EntryDialog(
        title = title,
        confirmEnabled = draft.name.trim().isNotEmpty(),
        confirmTag = "parenting.name.save",
        status = status,
        onConfirm = { onConfirm(draft.name.trim()) },
        onDismiss = onDismiss,
    ) {
        LabeledField(
            label = ui("孩子的昵称"),
            value = draft.name,
            onValueChange = { if (it.length <= 40) draft = draft.copy(name = it) },
            tag = "parenting.child.name",
            singleLine = true,
        )
    }
}

@Composable
fun GoalEntryDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit, status: FormStatus = FormStatus()) {
    var draft by remember { mutableStateOf(GoalDraft()) }
    EntryDialog(
        title = ui("添加目标"),
        confirmEnabled = draft.title.trim().isNotEmpty(),
        confirmTag = "parenting.goal.save",
        status = status,
        onConfirm = { onConfirm(draft.title.trim()) },
        onDismiss = onDismiss,
    ) {
        LabeledField(
            label = ui("目标"),
            value = draft.title,
            onValueChange = { if (it.length <= 120) draft = draft.copy(title = it) },
            tag = "parenting.goal.title",
            singleLine = true,
        )
    }
}

/**
 * 记一件安排 / 调整一件安排。
 * - 新建：标题、可选目标、**必须明确选时间**、原话；保存按钮在没选时间前保持禁用。
 * - 调整：预填当前标题 / 目标 / 时间，说明默认留空（不会悄悄重发原话）；改动了才能保存；
 *   同一分钟已有别的已安排事项时要先勾选「我已知道」。
 * 取消：有改动先弹确认，放弃才关闭。
 */
@Composable
fun ArrangementEntryDialog(
    onConfirm: (ArrangementDraft, conflictIDs: List<UUID>) -> Unit,
    onDismiss: () -> Unit,
    goals: List<ParentingGoal>,
    zone: ZoneId,
    now: Instant,
    status: FormStatus = FormStatus(),
    adjusting: ParentingArrangement? = null,
    conflictsFor: (Instant) -> List<ParentingArrangement> = { emptyList() },
) {
    val initial = remember(adjusting) {
        ArrangementDraft(adjusting?.title ?: "", "", adjusting?.dueAt, adjusting?.goalID)
    }
    var draft by remember(adjusting) { mutableStateOf(initial) }
    var ack by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val title = draft.title.trim()
    val changed = adjusting != null &&
        (title != adjusting.title || draft.dueAt != adjusting.dueAt || draft.goalID != adjusting.goalID)
    val conflicts = if (adjusting != null && draft.dueAt != null) conflictsFor(draft.dueAt!!) else emptyList()
    val dirty = draft != initial
    val canSave = title.isNotEmpty() && draft.dueAt != null && (adjusting == null || (changed && (conflicts.isEmpty() || ack)))
    EntryDialog(
        title = if (adjusting == null) ui("记一件安排") else ui("调整安排"),
        confirmEnabled = canSave,
        confirmTag = "parenting.arrangement.save",
        cancelTag = "parenting.arrangement.cancel",
        status = status,
        onConfirm = {
            onConfirm(
                draft.copy(title = title, sourceText = draft.sourceText.trim()),
                conflicts.map { it.id },
            )
        },
        onDismiss = { if (dirty) confirmDiscard = true else onDismiss() },
    ) {
        LabeledField(
            label = ui("安排名称"),
            value = draft.title,
            onValueChange = { if (it.length <= 160) draft = draft.copy(title = it) },
            tag = "parenting.arrangement.title",
            singleLine = true,
        )
        GoalChooser(goals, draft.goalID) { draft = draft.copy(goalID = it) }
        TimeField(draft.dueAt, zone, now) { draft = draft.copy(dueAt = it) }
        LabeledField(
            label = if (adjusting == null) ui("你说的原话") else ui("调整说明（可选）"),
            value = draft.sourceText,
            onValueChange = { if (it.length <= 8000) draft = draft.copy(sourceText = it) },
            tag = "parenting.arrangement.source",
            singleLine = false,
        )
        if (adjusting != null && changed) {
            val colors = LocalMimiColors.current
            Column(Modifier.semantics { testTag = "parenting.adjustment.review" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ui("核对这次调整"), style = MaterialTheme.typography.labelMedium, color = colors.ink2)
                Text(
                    formatWhen(adjusting.dueAt, zone),
                    color = colors.ink2,
                    modifier = Modifier.semantics { testTag = "parenting.adjustment.candidate.originalTime" },
                )
                Text(
                    draft.dueAt?.let { formatWhen(it, zone) } ?: "",
                    color = colors.ink,
                    modifier = Modifier.semantics { testTag = "parenting.adjustment.proposedTime" },
                )
            }
            if (conflicts.isNotEmpty()) {
                Column(Modifier.semantics { testTag = "parenting.adjustment.conflicts" }) {
                    Text(
                        ui("同一时间已有：{0}", conflicts.joinToString("、") { it.title }),
                        color = colors.attention,
                    )
                    CheckRow(ui("我已知道，仍然调整"), ack, "parenting.adjustment.conflict.ack") { ack = it }
                }
            }
        }
    }
    if (confirmDiscard) {
        ConfirmOverlay(
            title = ui("放弃这份修改草稿？"),
            message = null,
            confirmLabel = ui("放弃并关闭"),
            confirmTag = "parenting.arrangement.discard",
            onConfirm = onDismiss,
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
fun ObservationEntryDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    status: FormStatus = FormStatus(),
    title: String = ui("记一笔"),
    initial: String = "",
) {
    var draft by remember { mutableStateOf(ObservationDraft(initial)) }
    EntryDialog(
        title = title,
        confirmEnabled = draft.text.trim().isNotEmpty(),
        confirmTag = "parenting.record.save",
        status = status,
        onConfirm = { onConfirm(draft.text.trim()) },
        onDismiss = onDismiss,
    ) {
        LabeledField(
            label = ui("记下观察"),
            value = draft.text,
            onValueChange = { if (it.length <= 8000) draft = draft.copy(text = it) },
            tag = "parenting.record.text",
            singleLine = false,
        )
    }
}

/** 完成一件安排并写一句备注（备注可空：空则记录用安排标题）。 */
@Composable
fun CompleteEntryDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit, status: FormStatus = FormStatus()) {
    var draft by remember { mutableStateOf(CompleteDraft()) }
    EntryDialog(
        title = ui("记录完成"),
        confirmEnabled = true,
        confirmTag = "parenting.record.save",
        status = status,
        onConfirm = { onConfirm(draft.note) },
        onDismiss = onDismiss,
    ) {
        Text(title, color = LocalMimiColors.current.ink2)
        LabeledField(
            label = ui("备注"),
            value = draft.note,
            onValueChange = { if (it.length <= 8000) draft = draft.copy(note = it) },
            tag = "parenting.record.text",
            singleLine = false,
        )
    }
}

@Composable
private fun GoalChooser(goals: List<ParentingGoal>, selected: UUID?, onSelect: (UUID?) -> Unit) {
    val colors = LocalMimiColors.current
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(ui("目标"), style = MaterialTheme.typography.labelMedium, color = colors.ink2)
        TextButton(
            onClick = { open = !open },
            modifier = Modifier.semantics { testTag = "parenting.editor.goal" },
        ) { Text(goals.firstOrNull { it.id == selected }?.title ?: ui("不关联目标")) }
        if (open) {
            TextButton(
                onClick = { onSelect(null); open = false },
                modifier = Modifier.semantics { testTag = "parenting.editor.goal.none" },
            ) { Text(ui("不关联目标")) }
            goals.forEach { goal ->
                TextButton(
                    onClick = { onSelect(goal.id); open = false },
                    modifier = Modifier.semantics { testTag = "parenting.editor.goal.option.${goal.id}" },
                ) { Text(goal.title) }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val colors = LocalMimiColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .semantics { testTag = tag },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (checked) "☑ " else "☐ ", color = colors.ink)
        Text(label, color = colors.ink)
    }
}

/** 静态光标的输入框：OutlinedTextField 的光标闪烁动画会让 Robolectric 永远等不到 idle。 */
@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    tag: String,
    singleLine: Boolean,
) {
    val colors = LocalMimiColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.ink2)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
            cursorBrush = SolidColor(colors.ink),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colors.ink2.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .padding(12.dp)
                .semantics { testTag = tag },
        )
    }
}

/**
 * 表单浮层：与内容同一窗口的遮罩 + 卡片（不用 Dialog 窗口——Robolectric 里窗口内的输入框会让 Compose 永远等不到 idle）。
 * 点遮罩 / 返回键 = 放弃，不写账本；只有「保存」会回调 [onConfirm]。保存中禁用保存键；失败时错误显示在表单里，输入保留。
 */
@Composable
private fun EntryDialog(
    title: String,
    confirmEnabled: Boolean,
    confirmTag: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    status: FormStatus = FormStatus(),
    confirmLabel: String = ui("保存"),
    cancelTag: String = "parenting.form.discard",
    content: @Composable () -> Unit,
) {
    val colors = LocalMimiColors.current
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
            .semantics { testTag = "parenting.form.scrim" },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .imePadding()
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { testTag = "parenting.form" },
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = colors.ink)
                content()
                if (status.error != null) {
                    Text(
                        status.error,
                        color = colors.danger,
                        modifier = Modifier.semantics(mergeDescendants = true) { testTag = "parenting.form.error" },
                    )
                }
                if (status.saving) {
                    Text(ui("正在保存…"), color = colors.ink2, modifier = Modifier.semantics(mergeDescendants = true) { testTag = "parenting.saving" })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.semantics { testTag = cancelTag },
                    ) { Text(ui("取消")) }
                    TextButton(
                        onClick = onConfirm,
                        enabled = confirmEnabled && !status.saving,
                        modifier = Modifier.semantics { testTag = confirmTag },
                    ) { Text(confirmLabel) }
                }
            }
        }
    }
}

/** 二次确认（取消安排 / 删除 / 撤销调整 / 放弃草稿）。同样是同窗口浮层。 */
@Composable
fun ConfirmOverlay(
    title: String,
    message: String?,
    confirmLabel: String,
    confirmTag: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalMimiColors.current
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { testTag = "parenting.confirm.dialog" },
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                if (message != null) Text(message, color = colors.ink2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.semantics { testTag = "parenting.confirm.dialog.cancel" }) {
                        Text(ui("取消"))
                    }
                    TextButton(onClick = onConfirm, modifier = Modifier.semantics { testTag = confirmTag }) { Text(confirmLabel) }
                }
            }
        }
    }
}
