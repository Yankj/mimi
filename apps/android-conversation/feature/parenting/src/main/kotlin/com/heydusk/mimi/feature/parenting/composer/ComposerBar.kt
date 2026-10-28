package com.heydusk.mimi.feature.parenting.composer

import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.heydusk.mimi.designsystem.LocalMimiColors
import com.heydusk.mimi.designsystem.LocalReduceTransparency
import com.heydusk.mimi.designsystem.MimiIcons
import com.heydusk.mimi.designsystem.mimiBarBlurStyle
import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.domain.parenting.ParentingIntentKind
import com.heydusk.mimi.domain.parenting.ParentingNaturalContext
import com.heydusk.mimi.feature.parenting.ui.formatWhen
import com.heydusk.mimi.i18n.ui
import com.heydusk.mimi.voice.DictationController
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay

private class Starter(val id: String, val label: String, val text: String, val sendsNow: Boolean)

private val STARTERS = listOf(
    Starter("reschedule", "调课", "游泳改到", false),
    Starter("note", "记一笔", "记一下，", false),
    Starter("bring", "带什么", "周一要带", false),
    Starter("today", "今天有什么", "今天有什么安排", true),
)

/**
 * 今天 / 成长底部的输入卡：一个输入框，家长说想让小咪记的事，归到 录入 / 规划 / 复盘 在后台完成。
 * 空闲时只有一行：+ · 按住说话 · 键盘；说话中变成电平与计时；话在可编辑的转写框里；键盘一步之遥。
 * 这里**从不写账本**：交给卡片的只是输入框里的字。
 */
@Composable
fun ParentingComposerBar(
    input: NaturalInputModel,
    voice: ParentingVoiceState,
    context: ParentingNaturalContext,
    now: Instant,
    calendar: MimiCalendar,
    zone: ZoneId,
    busy: Boolean,
    hazeState: HazeState,
    onSubmit: () -> Unit,
    onManualArrangement: () -> Unit,
    onManualObservation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalMimiColors.current
    val controller = voice.controller
    val dictating by controller.dictating.collectAsState()
    val focusManager = LocalFocusManager.current
    val fieldFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val largeText = LocalDensity.current.fontScale >= 1.6f
    val currentBusy by rememberUpdatedState(busy)
    val keyboardActive = voice.mode == ParentingInputMode.Keyboard
    val expanded = keyboardActive && (focused || input.draft.isNotEmpty()) && !largeText
    val presentation = voice.presentation(input.draft.isEmpty(), dictating)

    fun handOver() {
        controller.stopNow()
        focusManager.clearFocus()
        onSubmit()
    }
    val handOverNow by rememberUpdatedState(::handOver)

    LaunchedEffect(controller, input) {
        controller.attach(input)
        controller.onSend = { if (input.canSubmit && !currentBusy) handOverNow() }
    }
    // 只有真的退到后台才停；离开页面也停。
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.stopNow() }
    DisposableEffect(controller) { onDispose { controller.stopNow() } }
    // 超大字号时胶囊放不下：只留键盘。
    LaunchedEffect(largeText) { if (largeText) voice.switchMode(ParentingInputMode.Keyboard) }

    val shape = RoundedCornerShape(28.dp)
    val glass = if (LocalReduceTransparency.current) {
        Modifier.background(colors.card, shape)
    } else {
        Modifier
            .hazeBlur(input = HazeInput.Sources(hazeState), style = mimiBarBlurStyle(colors))
            .background(colors.card.copy(alpha = 0.85f), shape)
    }
    Column(
        modifier = modifier
            .clip(shape)
            .then(glass)
            .border(1.dp, colors.hairline, shape)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics { testTag = "parenting.natural.bar" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (expanded) {
            StarterChips(busy = busy) { starter ->
                input.editDraft(starter.text)
                if (starter.sendsNow) handOver() else { voice.switchMode(ParentingInputMode.Keyboard) }
            }
            Suggestion(input, context, now, calendar, zone, busy, onClick = ::handOver)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlusMenu(
                busy = busy,
                onStarter = { starter ->
                    input.editDraft(starter.text)
                    if (starter.sendsNow) handOver() else voice.switchMode(ParentingInputMode.Keyboard)
                },
                onManualArrangement = onManualArrangement,
                onManualObservation = onManualObservation,
            )
            VoiceInputRow(input, voice, busy, fieldFocus, presentation, dictating, onFocusChange = { focused = it }, onSend = ::handOver)
        }
    }
}

@Composable
private fun StarterChips(busy: Boolean, onPick: (Starter) -> Unit) {
    val colors = LocalMimiColors.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        STARTERS.forEach { starter ->
            val label = ui(starter.label)
            Box(
                Modifier
                    .defaultMinSize(minHeight = 44.dp)
                    .clip(CircleShape)
                    .background(colors.track)
                    .tapTarget(enabled = !busy) { onPick(starter) }
                    .padding(horizontal = 14.dp)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        testTag = "parenting.natural.chip.${starter.id}"
                    },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = colors.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
private fun Suggestion(
    input: NaturalInputModel, context: ParentingNaturalContext, now: Instant, calendar: MimiCalendar, zone: ZoneId,
    busy: Boolean, onClick: () -> Unit,
) {
    val colors = LocalMimiColors.current
    val p = input.preview(context, now, calendar) ?: return
    val parts = mutableListOf(ui("小咪理解为：{0}", ui(p.kind.title)))
    p.childID?.let { id -> context.children.firstOrNull { it.id == id }?.name }?.let(parts::add)
    if (p.kind == ParentingIntentKind.NewArrangement) p.title?.let(parts::add)
    p.dueAt?.let { parts.add(formatWhen(it, zone)) }
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 36.dp)
            .tapTarget(enabled = !busy, onTap = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button; testTag = "parenting.natural.suggestion" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(MimiIcons.Sparkles, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(16.dp))
        Text(parts.joinToString(" · "), color = colors.ink2, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(ui("点这里继续"), color = colors.ink2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PlusMenu(
    busy: Boolean,
    onStarter: (Starter) -> Unit,
    onManualArrangement: () -> Unit,
    onManualObservation: () -> Unit,
) {
    val colors = LocalMimiColors.current
    var open by remember { mutableStateOf(false) }
    val label = ui("更多")
    Box {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .tapTarget(enabled = !busy) { open = true }
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = label
                    testTag = "parenting.plus.menu"
                },
            contentAlignment = Alignment.Center,
        ) { Icon(MimiIcons.Plus, contentDescription = null, tint = colors.ink, modifier = Modifier.size(24.dp)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            STARTERS.forEach { starter ->
                DropdownMenuItem(
                    text = { Text(ui(starter.label)) },
                    onClick = { open = false; onStarter(starter) },
                    modifier = Modifier.semantics { testTag = "parenting.natural.chip.${starter.id}" },
                )
            }
            // 手动表单放最后：自然输入是默认路径，表单是逃生口。
            DropdownMenuItem(
                text = { Text(ui("手动添加安排")) },
                onClick = { open = false; onManualArrangement() },
                modifier = Modifier.semantics { testTag = "parenting.plus.manualArrangement" },
            )
            DropdownMenuItem(
                text = { Text(ui("手动记一笔")) },
                onClick = { open = false; onManualObservation() },
                modifier = Modifier.semantics { testTag = "parenting.plus.manualObservation" },
            )
        }
    }
}

/** 键盘 ⇄ 语音 · （胶囊 | 文字框）· （发送 | 小麦克风）。 */
@Composable
private fun VoiceInputRow(
    input: NaturalInputModel,
    voice: ParentingVoiceState,
    busy: Boolean,
    fieldFocus: FocusRequester,
    presentation: ParentingVoicePresentation,
    dictating: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onSend: () -> Unit,
) {
    val colors = LocalMimiColors.current
    val controller = voice.controller
    val notice by controller.notice.collectAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VoiceNotice(voice, notice)
        if (voice.explaining) VoiceExplanation(voice)
        if (presentation == ParentingVoicePresentation.Listening || presentation == ParentingVoicePresentation.Preview) {
            TranscriptField(input, controller, fieldFocus, onFocusChange)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyboardToggle(voice, fieldFocus)
            when (presentation) {
                ParentingVoicePresentation.VoiceIdle, ParentingVoicePresentation.Listening, ParentingVoicePresentation.Preview -> {
                    HoldToTalkCapsule(voice, dictating, Modifier.weight(1f))
                    if (presentation == ParentingVoicePresentation.Preview && input.canSubmit) SendButton(input, busy, onSend)
                }
                ParentingVoicePresentation.Keyboard, ParentingVoicePresentation.KeyboardListening -> {
                    MessageField(input, fieldFocus, onFocusChange, busy, onSend, Modifier.weight(1f))
                    if (input.canSubmit && presentation == ParentingVoicePresentation.Keyboard) SendButton(input, busy, onSend)
                    else SmallMic(voice, dictating)
                }
            }
        }
    }
}

@Composable
private fun MessageField(
    input: NaturalInputModel, focus: FocusRequester, onFocusChange: (Boolean) -> Unit, busy: Boolean, onSend: () -> Unit, modifier: Modifier,
) {
    val colors = LocalMimiColors.current
    BasicTextField(
        value = input.draft,
        onValueChange = { input.editDraft(it) },
        textStyle = TextStyle(color = colors.ink, fontSize = 16.sp),
        cursorBrush = SolidColor(colors.ink),
        maxLines = 3,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (input.canSubmit && !busy) onSend() }),
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.track.copy(alpha = 0.7f))
            .focusRequester(focus)
            .onFocusChanged { onFocusChange(it.isFocused) }
            .semantics { testTag = "parenting.natural.field" },
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), contentAlignment = Alignment.CenterStart) {
                if (input.draft.isEmpty()) {
                    Text(ui("说说想让小咪记的事，例如：团团周五游泳改到六点了"), color = colors.ink3, fontSize = 16.sp, maxLines = 2)
                }
                inner()
            }
        },
    )
}

@Composable
private fun TranscriptField(input: NaturalInputModel, controller: DictationController, focus: FocusRequester, onFocusChange: (Boolean) -> Unit) {
    val colors = LocalMimiColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.card)
            .border(0.5.dp, colors.hairline, RoundedCornerShape(20.dp)),
        verticalAlignment = Alignment.Top,
    ) {
        BasicTextField(
            value = input.draft,
            onValueChange = { input.editDraft(it) },
            textStyle = TextStyle(color = colors.ink, fontSize = 16.sp),
            cursorBrush = SolidColor(colors.ink),
            maxLines = 4,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .onFocusChanged { onFocusChange(it.isFocused) }
                .semantics { testTag = "parenting.composer.transcript" },
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
                    if (input.draft.isEmpty()) Text(ui("在听…"), color = colors.ink3, fontSize = 16.sp)
                    inner()
                }
            },
        )
        if (input.draft.isNotEmpty()) {
            val clear = ui("清除")
            Box(
                Modifier
                    .size(width = 40.dp, height = 48.dp)
                    .tapTarget { controller.stopNow(); input.editDraft("") }
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = clear
                        testTag = "parenting.composer.transcript.clear"
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(MimiIcons.Close, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun KeyboardToggle(voice: ParentingVoiceState, fieldFocus: FocusRequester) {
    val colors = LocalMimiColors.current
    val focusManager = LocalFocusManager.current
    val toKeyboard = voice.mode == ParentingInputMode.Voice
    val label = ui(if (toKeyboard) "切换到键盘输入" else "切换到语音输入")
    // 切到键盘后等文字框挂上再聚焦。
    var wantFocus by remember { mutableStateOf(false) }
    LaunchedEffect(wantFocus, voice.mode) {
        if (wantFocus && voice.mode == ParentingInputMode.Keyboard) {
            runCatching { fieldFocus.requestFocus() }
            wantFocus = false
        }
    }
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(colors.track)
            .tapTarget {
                voice.toggleMode()
                if (toKeyboard) wantFocus = true else focusManager.clearFocus()
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                testTag = "parenting.composer.keyboardToggle"
            },
        contentAlignment = Alignment.Center,
    ) { Icon(if (toKeyboard) MimiIcons.Keyboard else MimiIcons.Waveform, contentDescription = null, tint = colors.ink, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun SendButton(input: NaturalInputModel, busy: Boolean, onSend: () -> Unit) {
    val colors = LocalMimiColors.current
    val enabled = input.canSubmit && !busy
    val label = ui("交给小咪")
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (enabled) colors.accent else colors.track)
            .tapTarget(enabled = enabled, onTap = onSend)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                testTag = "parenting.natural.send"
            },
        contentAlignment = Alignment.Center,
    ) { Icon(MimiIcons.ArrowUp, contentDescription = null, tint = colors.accentOn, modifier = Modifier.size(22.dp)) }
}

/** 键盘布局下输入框为空时右边的小麦克风（点 = 开关，按住 = 按住说话）；说话时变成波形。 */
@Composable
private fun SmallMic(voice: ParentingVoiceState, dictating: Boolean) {
    val colors = LocalMimiColors.current
    val controller = voice.controller
    val label = ui(if (dictating) "停止说话" else "说话")
    val cancel = ui("取消")
    val send = ui("发送")
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (dictating) colors.logoOrange else colors.track)
            .micGesture(voice)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                testTag = if (dictating) "parenting.composer.listening" else "parenting.composer.mic"
                onClick { micTap(voice); true }
                if (dictating) customActions = listOf(
                    CustomAccessibilityAction(cancel) { controller.cancel(); true },
                    CustomAccessibilityAction(send) { controller.confirmAndSend(); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) { Icon(if (dictating) MimiIcons.Waveform else MimiIcons.Mic, contentDescription = null, tint = if (dictating) colors.accentOn else colors.ink, modifier = Modifier.size(22.dp)) }
}

private fun micTap(voice: ParentingVoiceState) {
    if (voice.controller.dictating.value) voice.controller.toggle() else voice.requestStart(hold = false)
}

@Composable
private fun Modifier.micGesture(voice: ParentingVoiceState): Modifier {
    val controller = voice.controller
    return holdToTalk(
        isDictating = { controller.dictating.value },
        onTap = { micTap(voice) },
        onHoldStart = { voice.requestStart(hold = true) },
        onHoldMove = controller::updateHold,
        onHoldEnd = { dx, dy -> controller.updateHold(dx, dy); controller.endHold() },
    )
}

/** 空闲：大胶囊「按住说话」。在听：电平条、计时和滑动提示——同一个视图，所以按住不会被打断。 */
@Composable
private fun HoldToTalkCapsule(voice: ParentingVoiceState, dictating: Boolean, modifier: Modifier) {
    val colors = LocalMimiColors.current
    val controller = voice.controller
    val mode by controller.mode.collectAsState()
    val zone by controller.zone.collectAsState()
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(dictating) {
        elapsed = 0
        if (dictating) while (true) { delay(1_000); elapsed += 1 }
    }
    val timer = "%d:%02d".format(elapsed / 60, elapsed % 60)
    val background = when {
        !dictating -> colors.card
        zone == DictationController.HoldZone.Cancel -> colors.ink
        zone == DictationController.HoldZone.Send -> colors.accent
        else -> colors.logoOrange
    }
    val contentColor = if (dictating) colors.accentOn else colors.ink
    val label = ui(if (dictating) "停止说话" else "按住说话")
    val hintText = ui("双击开始说话，再双击完成")
    val cancel = ui("取消")
    val send = ui("发送")
    val hint = when {
        mode != DictationController.Mode.Hold -> ui("再点一下完成")
        zone == DictationController.HoldZone.Send -> ui("松开发送")
        zone == DictationController.HoldZone.Cancel -> ui("松开取消")
        else -> ui("上滑发送 · 左滑取消")
    }
    Box(
        modifier
            .defaultMinSize(minHeight = 52.dp)
            .clip(CircleShape)
            .background(background)
            .border(if (dictating) 0.dp else 0.5.dp, colors.hairline, CircleShape)
            .micGesture(voice)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                if (dictating) stateDescription = "$hint · $timer" else onClick(hintText) { micTap(voice); true }
                testTag = if (dictating) "parenting.composer.listening" else "parenting.composer.mic"
                if (dictating) {
                    onClick { micTap(voice); true }
                    customActions = listOf(
                        CustomAccessibilityAction(cancel) { controller.cancel(); true },
                        CustomAccessibilityAction(send) { controller.confirmAndSend(); true },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (dictating) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LevelBars(controller, contentColor)
                    Text(timer, color = contentColor, fontWeight = FontWeight.SemiBold)
                }
                Text(hint, color = contentColor.copy(alpha = 0.9f), fontSize = 12.sp)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(MimiIcons.Mic, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
                Text(ui("按住说话"), color = contentColor, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 最近约 1.4 秒的输入电平。减少动效（系统动画关闭）时是一组固定的条。 */
@Composable
private fun LevelBars(controller: DictationController, color: Color) {
    val context = LocalContext.current
    val still = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    var samples by remember { mutableStateOf(List(SAMPLE_COUNT) { 0f }) }
    LaunchedEffect(controller) { controller.level.collect { level -> samples = samples.drop(1) + level } }
    val values = if (still) listOf(0.35f, 0.6f, 0.45f, 0.8f, 0.5f, 0.65f, 0.4f) else samples
    val barWidth = if (still) 4.dp else 3.dp
    Canvas(Modifier.width(if (still) 56.dp else 112.dp).height(22.dp)) {
        val step = size.width / values.size
        val bar = barWidth.toPx()
        values.forEachIndexed { index, level ->
            val h = maxOf(bar, level * size.height)
            drawRoundRect(
                color = color.copy(alpha = 0.55f + level * 0.45f),
                topLeft = Offset(index * step + (step - bar) / 2, (size.height - h) / 2),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

private const val SAMPLE_COUNT = 28

/** 第一次说话之前的一句话（在系统权限弹窗之前）。如实说明这次可能在线识别。 */
@Composable
private fun VoiceExplanation(voice: ParentingVoiceState) {
    val colors = LocalMimiColors.current
    val text = if (voice.mayUseOnlineRecognition) {
        ui("这台手机没有本机语音识别，说话时可能经系统语音服务在线识别，小咪不保存录音。接下来系统会请你允许麦克风。")
    } else {
        ui("小咪的语音识别只在你的手机上完成，不会上传。接下来系统会请你允许麦克风。")
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.track)
            .padding(12.dp)
            .semantics { testTag = "parenting.composer.voiceExplain" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text, color = colors.ink, fontSize = 12.sp, modifier = Modifier.semantics { testTag = "parenting.composer.voiceExplain.text" })
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextAction(ui("继续"), "parenting.composer.voiceExplain.continue", strong = true) { voice.acceptExplanation() }
            TextAction(ui("改用键盘"), "parenting.composer.voiceExplain.keyboard", strong = false) { voice.declineExplanation() }
        }
    }
}

@Composable
private fun TextAction(label: String, tag: String, strong: Boolean, onClick: () -> Unit) {
    val colors = LocalMimiColors.current
    Box(
        Modifier
            .defaultMinSize(minHeight = 40.dp, minWidth = 48.dp)
            .tapTarget(onTap = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button; testTag = tag },
        contentAlignment = Alignment.CenterStart,
    ) { Text(label, color = if (strong) colors.ink else colors.ink2, fontSize = 13.sp, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal) }
}

/** 每一种非顺利状态的行内提示。从不是模态；键盘永远是出路。 */
@Composable
private fun VoiceNotice(voice: ParentingVoiceState, notice: DictationController.Notice?) {
    if (notice == null) return
    val colors = LocalMimiColors.current
    val controller = voice.controller
    val context = LocalContext.current
    val sticky = notice == DictationController.Notice.PermissionDenied || notice == DictationController.Notice.Unsupported
    val millis = voice.noticeMillis
    LaunchedEffect(notice) {
        if (!sticky && millis > 0) { delay(millis); controller.clearNotice() }
    }
    val message = when (notice) {
        DictationController.Notice.PermissionDenied -> ui("需要麦克风权限才能说话。可以在“设置”里开启，或直接打字。")
        DictationController.Notice.Unsupported -> ui("这台手机暂时没有可用的语音识别服务。可以点键盘，用系统键盘上的麦克风听写。")
        DictationController.Notice.Failed -> ui("没听清，可能是周围太吵。再说一次，或点键盘打字。")
        DictationController.Notice.Empty -> ui("没听到内容。再说一次，或点键盘打字。")
        DictationController.Notice.Interrupted -> ui("语音被打断了，已听到的内容保留在上面，可以接着改或再说一次。")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.track)
            .pointerInput(Unit) { detectTapGestures { controller.clearNotice() } }
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .semantics(mergeDescendants = true) { testTag = "parenting.composer.voiceNotice" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(message, color = colors.ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
        when (notice) {
            DictationController.Notice.PermissionDenied ->
                TextAction(ui("去设置"), "parenting.composer.voiceNotice.settings", strong = true) { voice.openAppSettings(); controller.clearNotice() }
            DictationController.Notice.Unsupported ->
                TextAction(ui("改用键盘"), "parenting.composer.voiceNotice.keyboard", strong = true) {
                    controller.clearNotice(); voice.switchMode(ParentingInputMode.Keyboard)
                }
            else -> Unit
        }
    }
}
