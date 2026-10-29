package com.heydusk.mimi.feature.parenting.composer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.domain.common.graphemeCount
import com.heydusk.mimi.domain.common.prefixGraphemes
import com.heydusk.mimi.domain.common.trimSwift
import com.heydusk.mimi.domain.parenting.ParentingConfirmCard
import com.heydusk.mimi.domain.parenting.ParentingIntentKind
import com.heydusk.mimi.domain.parenting.ParentingNaturalAnswer
import com.heydusk.mimi.domain.parenting.ParentingNaturalContext
import com.heydusk.mimi.domain.parenting.ParentingNaturalInput
import com.heydusk.mimi.domain.parenting.ParentingNaturalProposal
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import com.heydusk.mimi.feature.parenting.actions.ParentingActions
import com.heydusk.mimi.voice.DictationDraftSink
import java.time.Instant
import java.util.UUID

/**
 * 一次自然输入的状态：话 → 理解 → （一次问一项）→ 确认卡 → 已保存。
 * **确认之前零写入**：这里只有内存状态；只有 [confirm] 把命令交给账本，并且每一步都要账本确认后才算数。
 * 听写出来的字只进 [draft]（输入框里的字），从不直接写账本。
 */
class NaturalInputModel : DictationDraftSink {
    sealed interface Phase {
        data object Idle : Phase
        data object Asking : Phase
        data object Confirming : Phase
        data object Saving : Phase
        /** 回执的文案键。只在账本报告已保存之后才会出现。 */
        data class Saved(val receipt: String) : Phase
        data class Failed(val message: String) : Phase
    }

    override var draft by mutableStateOf("")
        private set
    var reply by mutableStateOf("")
    var proposal by mutableStateOf<ParentingNaturalProposal?>(null)
        private set
    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set

    private var recordID = UUID.randomUUID()
    private var operationIDs = listOf(UUID.randomUUID(), UUID.randomUUID())
    private var completedSteps = 0

    override fun editDraft(text: String) {
        draft = if (text.graphemeCount() > ParentingNaturalInput.MAX_LENGTH) text.prefixGraphemes(ParentingNaturalInput.MAX_LENGTH) else text
    }

    val trimmedDraft: String get() = draft.trimSwift()
    val canSubmit: Boolean get() = trimmedDraft.isNotEmpty() && trimmedDraft.graphemeCount() <= ParentingNaturalInput.MAX_LENGTH

    /** 对草稿的廉价实时读法：输入框下面灰色的「小咪理解为…」。从不保存、不发往任何地方。 */
    fun preview(context: ParentingNaturalContext, now: Instant, calendar: MimiCalendar): ParentingNaturalProposal? {
        if (!canSubmit) return null
        val p = ParentingNaturalInput.classify(trimmedDraft, context, now, calendar)
        return if (p.kind == ParentingIntentKind.Unknown) null else p
    }

    fun submit(context: ParentingNaturalContext, now: Instant, calendar: MimiCalendar) {
        if (!canSubmit) return
        startOver()
        adopt(ParentingNaturalInput.classify(trimmedDraft, context, now, calendar))
    }

    fun answer(answer: ParentingNaturalAnswer, context: ParentingNaturalContext, now: Instant, calendar: MimiCalendar) {
        val current = proposal ?: return
        if (phase != Phase.Asking) return
        reply = ""
        adopt(ParentingNaturalInput.applying(answer, current, context, now, calendar))
    }

    fun answerWithReply(context: ParentingNaturalContext, now: Instant, calendar: MimiCalendar) {
        val text = reply.trimSwift()
        if (text.isEmpty()) return
        answer(ParentingNaturalAnswer.Text(text), context, now, calendar)
    }

    /** 卡片上「记一笔 | 加安排」切换：只在还没写入任何一步、且是记录/安排之一时可用。 */
    val canSwitchType: Boolean get() {
        val p = proposal ?: return false
        val kindOk = p.kind == ParentingIntentKind.Observation || p.kind == ParentingIntentKind.NewArrangement
        val phaseOk = phase == Phase.Asking || phase == Phase.Confirming || phase is Phase.Failed
        return kindOk && phaseOk && completedSteps == 0
    }

    /** 同一句话按另一种意思重读（iOS #143）。 */
    fun switchType(kind: ParentingIntentKind, context: ParentingNaturalContext, now: Instant, calendar: MimiCalendar) {
        val current = proposal ?: return
        if (!canSwitchType || current.kind == kind) return
        if (kind != ParentingIntentKind.Observation && kind != ParentingIntentKind.NewArrangement) return
        reply = ""
        adopt(ParentingNaturalInput.applying(ParentingNaturalAnswer.Intent(kind), current, context, now, calendar))
    }

    /** 回到家长上次看到的问题或卡片（例如保存失败之后）。 */
    fun dismissFailure() { if (phase is Phase.Failed) phase = Phase.Confirming }

    /** 清空：丢掉提案和草稿；已经保存的不受影响。 */
    fun reset() { startOver(); draft = "" }

    /** 只清提案（保留输入框里的字）。 */
    fun clearProposal() = startOver()

    fun card(workspace: ParentingWorkspace, remindersEnabled: Boolean?, now: Instant): ParentingConfirmCard? =
        proposal?.let { ParentingConfirmCard.make(it, workspace, remindersEnabled, now) }

    /**
     * 执行确认过的卡片。已经保存过的步骤重试时不会重复（创建成功、确认失败后重试只补确认）。
     * @return true = 全部步骤账本都确认了。
     */
    suspend fun confirm(actions: ParentingActions, workspace: () -> ParentingWorkspace): Boolean {
        val p = proposal ?: return false
        val failed = phase is Phase.Failed
        if (!p.isReadyToConfirm || !p.kind.writes || !(phase == Phase.Confirming || failed)) return false
        phase = Phase.Saving
        val commands = ParentingNaturalInput.commands(p, workspace(), ParentingNaturalInput.WriteIDs(recordID))
        if (commands.isEmpty()) {
            phase = Phase.Failed("这张卡已经过期，请重新说一次。")
            return false
        }
        var index = completedSteps
        // 部分保存之后第一条（创建）已经在账本里，绝不能再跑一次。
        for (command in commands.drop(index)) {
            val ok = actions.applyCommand(command, operationIDs[minOf(index, operationIDs.size - 1)])
            if (!ok) {
                phase = Phase.Failed(
                    if (index > 0) "已先存为待确认，还没有进入安排。请在今天页确认，或重试。"
                    else actions.lastError() ?: "没有保存成功。已填写的内容保留，可以重试。",
                )
                return false
            }
            index += 1
            completedSteps = index
        }
        phase = when (p.kind) {
            ParentingIntentKind.NewArrangement -> Phase.Saved("已安排好了")
            ParentingIntentKind.Reschedule -> Phase.Saved("已改期")
            else -> Phase.Saved("已保存这条观察")
        }
        return true
    }

    private fun adopt(p: ParentingNaturalProposal) {
        proposal = p
        phase = if (p.missing.isEmpty() && p.kind != ParentingIntentKind.Unknown) Phase.Confirming else Phase.Asking
    }

    private fun startOver() {
        proposal = null
        phase = Phase.Idle
        reply = ""
        recordID = UUID.randomUUID()
        operationIDs = listOf(UUID.randomUUID(), UUID.randomUUID())
        completedSteps = 0
    }
}
