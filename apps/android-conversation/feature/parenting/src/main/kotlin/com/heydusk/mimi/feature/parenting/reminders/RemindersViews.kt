package com.heydusk.mimi.feature.parenting.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.designsystem.MimiTintedButton
import com.heydusk.mimi.designsystem.toColor
import com.heydusk.mimi.designsystem.tokens.Rgba
import com.heydusk.mimi.device.reminders.NotificationAuthorization
import com.heydusk.mimi.domain.parenting.ParentingArrangement
import com.heydusk.mimi.domain.parenting.ParentingArrangementState
import com.heydusk.mimi.domain.parenting.ParentingReminderLead
import com.heydusk.mimi.domain.parenting.ParentingReminderOutcome
import com.heydusk.mimi.domain.parenting.ParentingReminderSkipReason
import com.heydusk.mimi.feature.parenting.ui.PageHandlers
import com.heydusk.mimi.feature.parenting.ui.TitledPage
import com.heydusk.mimi.feature.parenting.ui.formatWhen
import com.heydusk.mimi.i18n.ui
import java.time.ZoneId
import kotlinx.coroutines.launch

/** 今天页唯一一张关于提醒的卡：没开（或权限没问过）时邀请开启；系统通知被关时说明并给设置入口。 */
@Composable
fun ReminderPromptCard(modifier: Modifier = Modifier) {
    val context = LocalReminders.current ?: return
    val colors = LocalMimiColors.current
    val scope = rememberCoroutineScope()
    val ui = context.ui
    val authorization = ui.authorization
    if (!ui.settings.enabled || authorization == NotificationAuthorization.NotDetermined) {
        Card(
            colors = CardDefaults.cardColors(containerColor = colors.card),
            modifier = modifier.fillMaxWidth().semantics { testTag = "parenting.reminders.prompt" },
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ui("让小咪在开始前提醒你"), style = MaterialTheme.typography.titleSmall, color = colors.ink)
                Text(ui("只提醒你已确认的安排；每天最多 2 条，晚 9 点到早 8 点不打扰。提醒只在本机发送。"), color = colors.ink2)
                MimiTintedButton(
                    onClick = { scope.launch { context.actions.setEnabled(true) } },
                    enabled = !ui.busy,
                    modifier = Modifier.semantics { testTag = "parenting.reminders.enable" },
                ) { Text(ui("开启提醒")) }
            }
        }
    } else if (authorization == NotificationAuthorization.Denied) {
        Card(
            colors = CardDefaults.cardColors(containerColor = colors.card),
            modifier = modifier.fillMaxWidth().semantics { testTag = "parenting.reminders.denied" },
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ui("系统通知已关闭，小咪现在无法提醒你"), style = MaterialTheme.typography.titleSmall, color = colors.danger)
                Text(ui("在系统设置里允许小咪发送通知后，回到这里会自动补上提醒。"), color = colors.ink2)
                MimiTintedButton(
                    onClick = context.actions::openNotificationSettings,
                    modifier = Modifier.semantics { testTag = "parenting.reminders.openSettings" },
                ) { Text(ui("打开系统设置")) }
            }
        }
    }
}

/** 只声称系统读回确认过的事。草稿与已结束的安排不显示提醒行。 */
@Composable
fun ReminderLine(arrangement: ParentingArrangement, zone: ZoneId, modifier: Modifier = Modifier) {
    val context = LocalReminders.current ?: return
    val status = context.ui.status ?: return
    if (arrangement.state != ParentingArrangementState.Planned || !arrangement.dueAt.isAfter(context.now)) return
    if (!status.settings.enabled || status.authorization != NotificationAuthorization.Authorized) return
    val colors = LocalMimiColors.current
    val outcome = status.outcomes[arrangement.id]
    val (text, calm) = when (outcome) {
        is ParentingReminderOutcome.Scheduled -> ui("将在 {0} 提醒", formatWhen(outcome.at, zone)) to true
        is ParentingReminderOutcome.Reminded -> ui("提醒时间已到：{0}", formatWhen(outcome.at, zone)) to true
        is ParentingReminderOutcome.Skipped -> when (outcome.reason) {
            ParentingReminderSkipReason.PastReminderTime -> ui("未提醒：提醒时间已过") to false
            ParentingReminderSkipReason.DailyLimit -> ui("未提醒：当天已有 2 条提醒") to false
        }
        ParentingReminderOutcome.Deferred -> ui("临近时打开小咪会补上提醒") to true
        null -> ui("提醒状态未确认，请稍后重新打开") to false
    }
    Text(
        text,
        color = if (calm) colors.ink2 else colors.danger,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier.semantics { testTag = "parenting.arrangement.reminder.${arrangement.id}" },
    )
}

/** 我们家 › 提醒。开关、提前量、锁屏隐藏、系统通知被关的引导，以及精确闹钟与电池优化的如实说明。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemindersPage(handlers: PageHandlers, modifier: Modifier = Modifier) {
    val colors = LocalMimiColors.current
    val context = LocalReminders.current
    val scope = rememberCoroutineScope()
    TitledPage(ui("提醒"), modifier.semantics { testTag = "parenting.family.reminders.page" }, onBack = handlers.onBack) {
        if (context == null) {
            Text(
                ui("这台设备暂不发送提醒。安排只保存在本机，不会添加系统日历。"),
                color = colors.ink2,
                modifier = Modifier.semantics { testTag = "parenting.family.reminders.unavailable" },
            )
            return@TitledPage
        }
        val state = context.ui
        val settings = state.settings
        val actions = context.actions
        val busy = state.busy || !state.loaded
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(ui("到点提醒"), color = colors.ink, style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = settings.enabled,
                onCheckedChange = { value -> scope.launch { actions.setEnabled(value) } },
                enabled = !busy,
                modifier = Modifier.semantics { testTag = "parenting.reminders.toggle" },
            )
        }
        if (settings.enabled) {
            Text(ui("提醒时间"), color = colors.ink2, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { testTag = "parenting.reminders.lead" }) {
                ParentingReminderLead.entries.forEach { lead ->
                    FilterChip(
                        selected = settings.lead == lead,
                        onClick = { scope.launch { actions.setLead(lead) } },
                        enabled = !busy,
                        label = { Text(ui(lead.title)) },
                        modifier = Modifier.semantics { testTag = "parenting.reminders.lead.${lead.rawValue}" },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = colors.card,
                            labelColor = colors.ink,
                            selectedContainerColor = colors.set.tabSelectedFill.composited(Rgba(colors.set.card)).toColor(),
                            selectedLabelColor = colors.ink,
                        ),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(ui("锁屏上隐藏孩子和事项"), color = colors.ink, modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.hideContent,
                    onCheckedChange = { value -> scope.launch { actions.setHideContent(value) } },
                    enabled = !busy,
                    modifier = Modifier.semantics { testTag = "parenting.reminders.hideContent" },
                )
            }
            val status = state.status
            when {
                state.authorization == NotificationAuthorization.Denied -> {
                    Text(ui("系统通知已关闭，小咪现在无法提醒你"), color = colors.danger, modifier = Modifier.semantics { testTag = "parenting.reminders.denied" })
                    TextButton(onClick = actions::openNotificationSettings, modifier = Modifier.semantics { testTag = "parenting.reminders.openSettings" }) {
                        Text(ui("打开系统设置"))
                    }
                }
                status != null && state.authorization == NotificationAuthorization.Authorized -> {
                    Text(
                        ui("已为 {0} 件安排在本机排好提醒", status.scheduledCount.toString()),
                        color = colors.ink2,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.semantics { testTag = "parenting.reminders.count" },
                    )
                    if (status.systemRefused) {
                        Text(ui("有提醒未能确认已排好，请重新打开小咪后再看。"), color = colors.danger, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.semantics { testTag = "parenting.reminders.refused" })
                    }
                    if (!status.exactAlarms) {
                        Text(ui("没有「闹钟和提醒」权限，提醒可能晚几分钟才到。"), color = colors.ink2, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.semantics { testTag = "parenting.reminders.exactAlarm" })
                        TextButton(onClick = actions::openExactAlarmSettings, modifier = Modifier.semantics { testTag = "parenting.reminders.exactAlarm.open" }) {
                            Text(ui("打开闹钟和提醒设置"))
                        }
                    }
                }
            }
            Text(
                ui("部分手机的省电设置会让后台提醒延迟或丢失。如果提醒不准，请把小咪加入电池优化白名单。"),
                color = colors.ink2,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.semantics { testTag = "parenting.reminders.battery" },
            )
            TextButton(onClick = actions::openBatterySettings, modifier = Modifier.semantics { testTag = "parenting.reminders.battery.open" }) {
                Text(ui("打开电池优化设置"))
            }
        }
        Text(
            ui("只提醒已确认的安排；每天最多 2 条，晚 9 点到早 8 点不提醒（会改在前一晚 8:30）。提醒只在本机发送，不经过服务器，也不会添加系统日历。"),
            color = colors.ink2,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
