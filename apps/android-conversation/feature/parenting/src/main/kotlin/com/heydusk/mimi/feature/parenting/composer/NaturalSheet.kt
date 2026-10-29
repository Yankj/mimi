package com.heydusk.mimi.feature.parenting.composer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.accessibility.AccessibilityManager
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.designsystem.MascotPose
import com.heydusk.mimi.designsystem.MimiIcons
import com.heydusk.mimi.designsystem.MimiMascot
import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.domain.parenting.ParentingConfirmCard
import com.heydusk.mimi.domain.parenting.ParentingFollowUp
import com.heydusk.mimi.domain.parenting.ParentingIntentKind
import com.heydusk.mimi.domain.parenting.ParentingMissingField
import com.heydusk.mimi.domain.parenting.ParentingNaturalContext
import com.heydusk.mimi.domain.parenting.ParentingNaturalInput
import com.heydusk.mimi.domain.parenting.ParentingNaturalProposal
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import com.heydusk.mimi.feature.parenting.actions.ParentingActions
import com.heydusk.mimi.feature.parenting.ui.formatWhen
import com.heydusk.mimi.i18n.ui
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * 「小咪来整理」：一次问一项，然后是确认卡。与表单浮层一样是同一窗口里的遮罩 + 底部面板（不用 ModalBottomSheet 窗口，
 * Robolectric 里窗口内的输入框会让 Compose 永远等不到 idle）。
 * 只有「确认」会写账本，并且逐步等账本确认；关掉 = 丢弃，零写入。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParentingNaturalSheet(
    input: NaturalInputModel,
    workspace: ParentingWorkspace,
    actions: ParentingActions,
    context: ParentingNaturalContext,
    now: Instant,
    calendar: MimiCalendar,
    zone: ZoneId,
    busy: Boolean,
    remindersEnabled: Boolean?,
    receiptMillis: Long,
    onClose: () -> Unit,
    onNavigate: (ParentingIntentKind) -> Unit,
    onManual: (ParentingNaturalProposal) -> Unit,
    onAddChild: () -> Unit,
) {
    val colors = LocalMimiColors.current
    val scope = rememberCoroutineScope()
    val latestWorkspace by rememberUpdatedState(workspace)
    val proposal = input.proposal
    val phase = input.phase
    val saving = phase == NaturalInputModel.Phase.Saving
    val savedReceipt = (phase as? NaturalInputModel.Phase.Saved)?.receipt
    val touchExploration = (LocalContext.current.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)
        ?.isTouchExplorationEnabled == true

    BackHandler(enabled = !saving, onBack = onClose)
    // 已保存：回执短暂显示后自己收起（读屏时不收，测试里可关）。
    LaunchedEffect(savedReceipt) {
        if (savedReceipt != null && receiptMillis > 0 && !touchExploration) {
            kotlinx.coroutines.delay(receiptMillis)
            onClose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .pointerInput(saving) { detectTapGestures { if (!saving) onClose() } }
            .semantics { testTag = "parenting.natural.scrim" },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(max = 640.dp)
                .imePadding()
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { testTag = "parenting.natural.sheet" },
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ui("小咪来整理"),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.ink,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    Box(
                        Modifier
                            .defaultMinSize(minHeight = 48.dp, minWidth = 48.dp)
                            .tapTarget(onTap = onClose)
                            .semantics(mergeDescendants = true) { role = Role.Button; testTag = "parenting.natural.close" },
                        contentAlignment = Alignment.Center,
                    ) { Text(ui("关闭"), color = colors.ink2) }
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (proposal != null) {
                        Understood(proposal)
                        if (input.canSwitchType && (phase != NaturalInputModel.Phase.Asking || proposal.kind != ParentingIntentKind.Unknown)) {
                            TypeSwitch(proposal.kind, enabled = !saving) { input.switchType(it, context, now, calendar) }
                        }
                        when (phase) {
                            NaturalInputModel.Phase.Asking -> Asking(input, proposal, context, now, calendar, onAddChild = { onClose(); onAddChild() })
                            NaturalInputModel.Phase.Confirming, NaturalInputModel.Phase.Saving, is NaturalInputModel.Phase.Failed -> {
                                val card = input.card(workspace, remindersEnabled, now)
                                if (card != null) {
                                    ConfirmCardView(card, zone)
                                    if (phase is NaturalInputModel.Phase.Failed) {
                                        Text(
                                            ui(phase.message),
                                            color = colors.danger,
                                            modifier = Modifier.semantics(mergeDescendants = true) { testTag = "parenting.natural.failure" },
                                        )
                                    }
                                }
                            }
                            is NaturalInputModel.Phase.Saved -> Saved(phase.receipt) { onClose() }
                            NaturalInputModel.Phase.Idle -> Unit
                        }
                    }
                }
                if (proposal != null && (phase == NaturalInputModel.Phase.Confirming || saving || phase is NaturalInputModel.Phase.Failed)) {
                    val card = input.card(workspace, remindersEnabled, now)
                    if (card != null) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars)
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (saving) Text(ui("正在保存…"), color = colors.ink2, modifier = Modifier.semantics(mergeDescendants = true) { testTag = "parenting.natural.saving" })
                            PrimaryButton(
                                label = ui(card.confirmTitle),
                                tag = "parenting.natural.confirm",
                                enabled = !saving && !busy,
                            ) {
                                if (card.writes) {
                                    scope.launch { input.confirm(actions) { latestWorkspace } }
                                } else {
                                    val kind = proposal.kind
                                    input.reset()
                                    onClose()
                                    onNavigate(kind)
                                }
                            }
                            if (card.writes) {
                                SecondaryButton(ui("手动调整"), "parenting.natural.manual", enabled = !saving) { onManual(proposal) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Understood(p: ParentingNaturalProposal) {
    val colors = LocalMimiColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            p.sourceText,
            color = colors.ink,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colors.track)
                .padding(14.dp)
                .semantics(mergeDescendants = true) { testTag = "parenting.natural.source" },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) { testTag = "parenting.natural.understood" },
        ) {
            p.mode?.let { mode ->
                Text(
                    ui(mode.title),
                    color = colors.ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).background(colors.accent.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Text(ui(if (p.kind == ParentingIntentKind.Unknown) (if (p.ambiguousIntent) "这句话有两种理解" else "小咪还没听明白") else p.kind.title), color = colors.ink2, fontSize = 12.sp)
        }
    }
}

/** 「记一笔 | 加安排」：同一句话按另一种意思重读。 */
@Composable
private fun TypeSwitch(kind: ParentingIntentKind, enabled: Boolean, onPick: (ParentingIntentKind) -> Unit) {
    val colors = LocalMimiColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { testTag = "parenting.natural.typeSwitch" }) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.track).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            listOf(ParentingIntentKind.Observation, ParentingIntentKind.NewArrangement).forEach { k ->
                val selected = k == kind
                Box(
                    Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (selected) colors.card else Color.Transparent)
                        .tapTarget { if (enabled && !selected) onPick(k) }
                        .semantics(mergeDescendants = true) { role = Role.Tab; this.selected = selected; testTag = "parenting.natural.switch.${k.rawValue}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ui(k.title), color = if (selected) colors.ink else colors.ink2, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, fontSize = 14.sp)
                }
            }
        }
        Text(
            ui(if (kind == ParentingIntentKind.NewArrangement) "到点提醒你。只想记下来就点“记一笔”。" else "只记录，不提醒。要提醒就点“加安排”。"),
            color = colors.ink2,
            fontSize = 12.sp,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Asking(
    input: NaturalInputModel, p: ParentingNaturalProposal, context: ParentingNaturalContext, now: Instant, calendar: MimiCalendar, onAddChild: () -> Unit,
) {
    val colors = LocalMimiColors.current
    val zone = calendar.zone
    val ask = ParentingNaturalInput.followUp(p, context, calendar) ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.card)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(20.dp))
            .padding(16.dp)
            .semantics { testTag = "parenting.natural.asking" },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            ui(ask.question),
            color = colors.ink,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics(mergeDescendants = true) { testTag = "parenting.natural.question" },
        )
        if (ask.field == ParentingMissingField.Intent && ask.options.size == 2) {
            // 冲突才问：两个大按钮并排，选完即锁定，不在这里追问钟点。
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                ask.options.forEach { option ->
                    Column(
                        Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 72.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surface)
                            .border(0.5.dp, colors.hairline, RoundedCornerShape(16.dp))
                            .tapTarget { input.answer(option.answer, context, now, calendar) }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .semantics(mergeDescendants = true) { role = Role.Button; testTag = "parenting.natural.${option.id}" },
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        Text(optionLabel(option.label, zone), color = colors.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        option.subtitle?.let { Text(ui(it), color = colors.ink2, fontSize = 12.sp) }
                    }
                }
            }
        } else if (ask.options.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ask.options.forEach { option ->
                    Column(
                        Modifier
                            .defaultMinSize(minWidth = 130.dp, minHeight = 48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface)
                            .border(0.5.dp, colors.hairline, RoundedCornerShape(14.dp))
                            .tapTarget { input.answer(option.answer, context, now, calendar) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .semantics(mergeDescendants = true) { role = Role.Button; testTag = "parenting.natural.${option.id}" },
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(optionLabel(option.label, zone), color = colors.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        option.detail?.let { Text(formatWhen(it, zone), color = colors.ink2, fontSize = 12.sp) }
                    }
                }
            }
        }
        if (ask.field == ParentingMissingField.Child && ask.options.isEmpty()) {
            PrimaryButton(ui("添加孩子"), "parenting.natural.addChild", enabled = true, onClick = onAddChild)
        }
        if (ask.field == ParentingMissingField.Target && ask.options.isEmpty()) {
            SecondaryButton(ui("改为记一件安排"), "parenting.natural.toArrangement", enabled = true) {
                input.answer(com.heydusk.mimi.domain.parenting.ParentingNaturalAnswer.Intent(ParentingIntentKind.NewArrangement), context, now, calendar)
            }
        }
        if (ask.allowsText) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = input.reply,
                    onValueChange = { input.reply = it },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.ink, fontSize = 16.sp),
                    cursorBrush = SolidColor(colors.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { input.answerWithReply(context, now, calendar) }),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(0.5.dp, colors.hairline, RoundedCornerShape(14.dp))
                        .semantics { testTag = "parenting.natural.reply" },
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            if (input.reply.isEmpty()) Text(ui(ask.textHint ?: "补充一句"), color = colors.ink3, fontSize = 16.sp)
                            inner()
                        }
                    },
                )
                PrimaryButton(ui("好"), "parenting.natural.replySend", enabled = input.reply.isNotBlank()) { input.answerWithReply(context, now, calendar) }
            }
        }
        Text(ui("只问还缺的这一项；其余已经听明白的不会再问。"), color = colors.ink2, fontSize = 12.sp)
    }
}

@Composable
private fun optionLabel(label: ParentingFollowUp.Label, zone: ZoneId): String = when (label) {
    is ParentingFollowUp.Label.Key -> ui(label.key)
    is ParentingFollowUp.Label.Verbatim -> label.text
    is ParentingFollowUp.Label.DateTime -> formatWhen(label.date, zone)
}

@Composable
private fun Saved(receipt: String, onDone: () -> Unit) {
    val colors = LocalMimiColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.card)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MimiMascot(MascotPose.Note, size = 38.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.semantics(mergeDescendants = true) { testTag = "parenting.natural.receipt" },
            ) {
                Icon(MimiIcons.Check, contentDescription = null, tint = colors.success, modifier = Modifier.size(22.dp))
                Text(ui(receipt), color = colors.ink, style = MaterialTheme.typography.titleMedium)
            }
        }
        Text(ui("已保存在这台设备上；今天页和成长页会同步显示。"), color = colors.ink2)
        PrimaryButton(ui("好的"), "parenting.natural.done", enabled = true, onClick = onDone)
    }
}

@Composable
private fun PrimaryButton(label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalMimiColors.current
    Box(
        Modifier
            .defaultMinSize(minHeight = 48.dp, minWidth = 48.dp)
            .clip(CircleShape)
            .background(if (enabled) colors.accent else colors.track)
            .tapTarget(enabled = enabled, onTap = onClick)
            .padding(horizontal = 20.dp)
            .semantics(mergeDescendants = true) { role = Role.Button; testTag = tag },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) colors.accentOn else colors.ink3, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SecondaryButton(label: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalMimiColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clip(CircleShape)
            .border(1.dp, colors.hairline, CircleShape)
            .tapTarget(enabled = enabled, onTap = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button; testTag = tag },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) colors.ink else colors.ink3) }
}

// 确认卡：固定组件目录的渲染器（没有任何自由格式通路，卡片永远不会显示目录之外的东西）。

@Composable
fun ConfirmCardView(card: ParentingConfirmCard, zone: ZoneId) {
    val colors = LocalMimiColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.card)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(20.dp))
            .padding(16.dp)
            .semantics { testTag = "parenting.confirmCard.${card.kind.rawValue}" },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        card.blocks.forEach { block -> BlockView(block, zone) }
    }
}

@Composable
private fun BlockView(block: ParentingConfirmCard.Block, zone: ZoneId) {
    val colors = LocalMimiColors.current
    when (block) {
        is ParentingConfirmCard.Block.Headline -> Text(
            ui(block.title),
            color = colors.ink,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        is ParentingConfirmCard.Block.Fields -> Column {
            block.fields.forEachIndexed { index, field ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                        .semantics(mergeDescendants = true) { testTag = "parenting.confirmCard.field.$index" },
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(ui(field.label), color = colors.ink2, fontSize = 14.sp)
                    Text(
                        when (val v = field.value) {
                            is ParentingConfirmCard.Value.Text -> v.text
                            is ParentingConfirmCard.Value.Date -> formatWhen(v.date, zone)
                            is ParentingConfirmCard.Value.Key -> ui(v.key)
                        },
                        color = tint(field.tone, colors),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                if (index < block.fields.size - 1) Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.hairline))
            }
        }
        is ParentingConfirmCard.Block.Change -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ui(block.label), color = colors.ink2, fontSize = 14.sp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.accent.copy(alpha = 0.08f))
                    .padding(12.dp)
                    .semantics(mergeDescendants = true) { testTag = "parenting.confirmCard.change" },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatWhen(block.before, zone), color = colors.ink2, textDecoration = TextDecoration.LineThrough, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("→", color = colors.ink2)
                Text(formatWhen(block.after, zone), color = colors.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
        }
        is ParentingConfirmCard.Block.Quote -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(ui(block.label), color = colors.ink2, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.semantics(mergeDescendants = true) { testTag = "parenting.confirmCard.quote" }) {
                Box(Modifier.size(width = 3.dp, height = 20.dp).background(colors.accent.copy(alpha = 0.5f)))
                Text(block.text, color = colors.ink, fontSize = 14.sp)
            }
        }
        is ParentingConfirmCard.Block.Conflicts -> Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.attention.copy(alpha = 0.10f))
                .padding(12.dp)
                .semantics(mergeDescendants = true) { testTag = "parenting.confirmCard.conflicts" },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(ui(block.title), color = colors.attention, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            block.items.forEach { Text(it.title + " · " + formatWhen(it.at, zone), color = colors.ink, fontSize = 14.sp) }
            Text(ui("没有时长信息，暂不能判断是否重叠或能否同时进行。"), color = colors.ink2, fontSize = 12.sp)
        }
        is ParentingConfirmCard.Block.Note -> Text(
            ui(block.text),
            color = if (block.tone == ParentingConfirmCard.Tone.Warning) colors.attention else colors.ink2,
            fontSize = 12.sp,
        )
    }
}

private fun tint(tone: ParentingConfirmCard.Tone, colors: com.heydusk.mimi.designsystem.MimiColors): Color = when (tone) {
    ParentingConfirmCard.Tone.Normal -> colors.ink
    ParentingConfirmCard.Tone.Changed -> colors.orangeText
    ParentingConfirmCard.Tone.Unknown -> colors.ink2
    ParentingConfirmCard.Tone.Warning -> colors.attention
}
