package com.heydusk.mimi.feature.parenting.intake

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.domain.parenting.ParentingIntakeDraft
import com.heydusk.mimi.domain.parenting.ParentingIntakePhase
import com.heydusk.mimi.feature.parenting.ui.formatWhen
import com.heydusk.mimi.i18n.ui
import java.time.ZoneId
import java.util.UUID

/**
 * 表单里的「让小咪帮你整理」（iOS ParentingArrangementEditor.inlineIntakeSection 的 Android 版）。
 * 只在已登录云端账号时出现；结果只是候选，点「采用整理结果」才填入表单，仍需「保存」。
 * 没有转圈动画（Robolectric 等不到 idle），等待用文字 + 读屏实时播报。
 */
@Composable
fun InlineIntakeSection(
    model: InlineIntakeModel,
    sourceText: String,
    childID: UUID?,
    goalID: UUID?,
    zone: ZoneId,
    saving: Boolean,
    onAdopt: (ParentingIntakeDraft) -> Unit,
) {
    val colors = LocalMimiColors.current
    val s by model.state.collectAsState()
    var showSource by remember { mutableStateOf(false) }
    val source = sourceText.trim()
    val canRequest = source.isNotEmpty() && source.codePointCount(0, source.length) <= 3000 && childID != null &&
        !saving && !s.busy && !s.deletionPending
    val caption = MaterialTheme.typography.bodySmall

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.card, RoundedCornerShape(16.dp))
            .padding(16.dp)
            .semantics { testTag = "parenting.inline" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(ui("让小咪帮你整理"), style = MaterialTheme.typography.titleSmall, color = colors.ink)
        Text(ui("点击后，将发送选定原话及当前时间、时区到现有已选 AI 服务，不会自动附上家庭档案。需要发送许可时会先请你确认。"), style = caption, color = colors.ink2)
        Text(ui("原话与回复只在小咪服务端临时内存处理，不写入长期记忆，临时内容最多可读取 15 分钟。模型服务商的数据处理方式以发送许可中的说明为准。"), style = caption, color = colors.ink2)
        TextButton(onClick = { showSource = !showSource }, modifier = Modifier.semantics { testTag = "parenting.inline.showSource" }) {
            Text(ui("查看将发送的原话"))
        }
        if (showSource) Text(source, color = colors.ink, modifier = Modifier.semantics { testTag = "parenting.inline.source" })

        when (s.phase) {
            ParentingIntakePhase.Idle, ParentingIntakePhase.Ready, ParentingIntakePhase.Cancelled -> {
                FilledTonalButton(
                    onClick = { if (childID != null) model.request(source, childID, goalID) },
                    enabled = canRequest,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { testTag = "parenting.arrangement.aiIntake" },
                ) { Text(ui("让小咪整理")) }
                if (s.phase == ParentingIntakePhase.Cancelled) {
                    Text(ui("已停止等待，本次结果不会填入。已经发出的请求不保证在服务端停止。"), style = caption, color = colors.ink2)
                }
            }
            ParentingIntakePhase.Preparing, ParentingIntakePhase.Sending -> {
                Text(
                    ui(if (s.phase == ParentingIntakePhase.Preparing) "正在准备整理请求" else "小咪正在整理") + "…",
                    color = colors.ink,
                    modifier = Modifier.semantics { testTag = "parenting.inline.loading"; liveRegion = LiveRegionMode.Polite },
                )
                StopButton(model)
            }
            ParentingIntakePhase.AwaitingConsent -> {
                val consent = s.consent
                Text(ui("等待你确认发送许可。未允许前不会发送这次整理。"), style = caption, color = colors.ink2)
                if (consent != null) {
                    Column(
                        Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(12.dp)).padding(12.dp)
                            .semantics { testTag = "parenting.inline.consent" },
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 披露文字来自服务端（模型路由说明），原样显示。
                        Text(consent.title, style = MaterialTheme.typography.titleSmall, color = colors.ink)
                        Text(consent.body, style = caption, color = colors.ink2)
                    }
                    FilledTonalButton(
                        onClick = model::allowConsent,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { testTag = "parenting.inline.consent.allow" },
                    ) { Text(ui("允许")) }
                    TextButton(onClick = model::declineConsent, modifier = Modifier.semantics { testTag = "parenting.inline.consent.decline" }) {
                        Text(ui("暂不允许"))
                    }
                }
            }
            ParentingIntakePhase.Failed -> {
                s.error?.let { Issue(it) }
                if (s.canRetry) {
                    TextButton(onClick = model::retry, enabled = canRequest, modifier = Modifier.semantics { testTag = "parenting.inline.retry" }) {
                        Text(ui("重试本次整理"))
                    }
                } else {
                    TextButton(
                        onClick = { if (childID != null) model.request(source, childID, goalID) },
                        enabled = canRequest,
                        modifier = Modifier.semantics { testTag = "parenting.inline.freshAttempt" },
                    ) { Text(ui("重新发起整理")) }
                }
            }
            ParentingIntakePhase.Unknown -> {
                Text(ui("本次整理的结果尚未确认。请检查原请求，避免重复发送。"), color = colors.attention)
                TextButton(onClick = model::reconcile, enabled = s.canReconcile, modifier = Modifier.semantics { testTag = "parenting.inline.reconcile" }) {
                    Text(ui("检查本次结果"))
                }
                StopButton(model)
            }
        }

        val candidate = s.candidate
        if (s.phase == ParentingIntakePhase.Ready && candidate != null) {
            Column(
                Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(12.dp)).padding(12.dp)
                    .semantics { testTag = "parenting.inline.candidate" },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(ui("整理建议 · 尚未采用或保存"), style = MaterialTheme.typography.labelLarge, color = colors.ink2)
                Text(candidate.title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                val due = candidate.dueAt
                if (due != null) Text(formatWhen(due, zone), color = colors.ink2)
                else Text(ui("时间待补充，采用后仍需选择明确时间。"), color = colors.attention)
                candidate.clarification?.takeIf { it.isNotBlank() }?.let { Text(it, style = caption, color = colors.ink2) }
                Text(ui("采用后会替换下方的安排名称和时间。你可以继续修改，原话会保留。"), style = caption, color = colors.ink2)
                FilledTonalButton(
                    onClick = { model.consumeCandidate()?.let(onAdopt) },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { testTag = "parenting.inline.adopt" },
                ) { Text(ui("采用整理结果")) }
            }
        }

        if (s.recoveryAvailable && s.phase == ParentingIntakePhase.Idle) {
            TextButton(onClick = model::recover, enabled = !s.busy, modifier = Modifier.semantics { testTag = "parenting.inline.recover" }) {
                Text(ui("恢复上次整理"))
            }
            Text(ui("服务重启或 15 分钟后，服务端内容可能无法读取。恢复的本机原话可继续手动填写，不会因此在后台重复发送。"), style = caption, color = colors.ink2)
        }
        if (s.deletionPending) {
            Column(Modifier.semantics { testTag = "parenting.inline.cleanupPending" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ui("本次临时整理记录尚未完成清理"), color = colors.attention)
                s.deletionError?.let { Issue(it) }
                TextButton(onClick = model::retryDeletion, enabled = !saving, modifier = Modifier.semantics { testTag = "parenting.inline.retryCleanup" }) {
                    Text(ui("重试清理"))
                }
            }
        } else if (s.recoveryAvailable || s.candidate != null) {
            TextButton(onClick = model::deleteRecord, enabled = !saving, modifier = Modifier.semantics { testTag = "parenting.inline.deleteRecord" }) {
                Text(ui("删除本次整理记录"), color = colors.danger)
            }
        }
        Text(ui("整理不会保存或确认安排，也不会创建日历或提醒。离开或切到后台会停止本页等待并尝试清理临时副本，不影响表单原话和已保存安排。"), style = caption, color = colors.ink2)
    }
}

@Composable
private fun StopButton(model: InlineIntakeModel) {
    TextButton(onClick = model::cancel, modifier = Modifier.semantics { testTag = "parenting.inline.cancel" }) { Text(ui("停止等待本次整理")) }
}

@Composable
private fun Issue(message: String) {
    Text(ui(message), color = LocalMimiColors.current.danger, modifier = Modifier.semantics(mergeDescendants = true) { testTag = "parenting.inline.error" })
}
