package com.heydusk.mimi.feature.parenting.forms

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.i18n.ui

@Composable
fun ChildEntryDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(ChildDraft()) }
    EntryDialog(
        title = ui("添加孩子的昵称"),
        confirmEnabled = draft.name.trim().isNotEmpty(),
        confirmTag = "parenting.name.save",
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
fun GoalEntryDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(GoalDraft()) }
    EntryDialog(
        title = ui("添加目标"),
        confirmEnabled = draft.title.trim().isNotEmpty(),
        confirmTag = "parenting.goal.save",
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

@Composable
fun ArrangementEntryDialog(onConfirm: (ArrangementDraft) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(ArrangementDraft()) }
    EntryDialog(
        title = ui("记一件安排"),
        confirmEnabled = draft.title.trim().isNotEmpty(),
        confirmTag = "parenting.arrangement.save",
        onConfirm = { onConfirm(draft.copy(title = draft.title.trim(), sourceText = draft.sourceText.trim())) },
        onDismiss = onDismiss,
    ) {
        LabeledField(
            label = ui("安排名称"),
            value = draft.title,
            onValueChange = { if (it.length <= 160) draft = draft.copy(title = it) },
            tag = "parenting.arrangement.title",
            singleLine = true,
        )
        LabeledField(
            label = ui("你说的原话"),
            value = draft.sourceText,
            onValueChange = { if (it.length <= 8000) draft = draft.copy(sourceText = it) },
            tag = "parenting.arrangement.source",
            singleLine = false,
        )
    }
}

@Composable
fun ObservationEntryDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(ObservationDraft()) }
    EntryDialog(
        title = ui("记一笔"),
        confirmEnabled = draft.text.trim().isNotEmpty(),
        confirmTag = "parenting.record.save",
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

@Composable
fun CompleteEntryDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(CompleteDraft()) }
    EntryDialog(
        title = ui("已完成"),
        confirmEnabled = true,
        confirmTag = "parenting.complete.save",
        confirmLabel = ui("完成"),
        onConfirm = { onConfirm(draft.note.trim()) },
        onDismiss = onDismiss,
    ) {
        Text(title, color = LocalMimiColors.current.ink2)
        LabeledField(
            label = ui("备注"),
            value = draft.note,
            onValueChange = { if (it.length <= 8000) draft = draft.copy(note = it) },
            tag = "parenting.complete.note",
            singleLine = false,
        )
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
 * 点遮罩 / 返回键 = 放弃，不写账本；只有「保存」会回调 [onConfirm]。
 */
@Composable
private fun EntryDialog(
    title: String,
    confirmEnabled: Boolean,
    confirmTag: String,
    confirmLabel: String = ui("保存"),
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalMimiColors.current
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
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
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .semantics { testTag = "parenting.form" },
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = colors.ink)
                content()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.semantics { testTag = "parenting.form.discard" },
                    ) { Text(ui("取消")) }
                    TextButton(
                        onClick = onConfirm,
                        enabled = confirmEnabled,
                        modifier = Modifier.semantics { testTag = confirmTag },
                    ) { Text(confirmLabel) }
                }
            }
        }
    }
}
