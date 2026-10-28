package com.heydusk.mimi.feature.parenting.composer

import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.domain.parenting.ParentingCommand
import com.heydusk.mimi.domain.parenting.ParentingIntentKind
import com.heydusk.mimi.domain.parenting.ParentingNaturalAnswer
import com.heydusk.mimi.domain.parenting.ParentingNaturalContext
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import com.heydusk.mimi.feature.parenting.actions.ParentingActions
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** 纯 JVM：自然输入的状态机。写入由假账本记录，所以能精确断言「确认之前零写入」「重试不重复」。 */
class NaturalInputModelTest {
    private fun blocking(block: suspend () -> Unit) = runBlocking { block() }
    private val now = Instant.parse("2026-10-26T01:00:00Z")
    private val calendar = MimiCalendar(ZoneOffset.UTC)
    private val childA = UUID.randomUUID()
    private val childB = UUID.randomUUID()
    private val ws = ParentingWorkspace.EMPTY
        .applying(ParentingCommand.AddChild(childA, "团团"), now)
    private val ws2 = ws.applying(ParentingCommand.AddChild(childB, "圆圆"), now)

    private class Ledger(var failAt: Set<Int> = emptySet(), var error: String? = null) : ParentingActions by ParentingActions.None {
        val calls = mutableListOf<Pair<ParentingCommand, UUID>>()
        var attempts = 0
        override suspend fun applyCommand(command: ParentingCommand, operationID: UUID): Boolean {
            attempts += 1
            if (attempts in failAt) return false
            calls += command to operationID
            return true
        }
        override fun lastError() = error
    }

    private fun ctx(w: ParentingWorkspace) = ParentingNaturalContext(w)
    private fun model(text: String, w: ParentingWorkspace = ws): NaturalInputModel =
        NaturalInputModel().also { it.editDraft(text); it.submit(ctx(w), now, calendar) }

    @Test fun readyTextGoesStraightToConfirmingAndWritesNothing() {
        val m = model("团团今天下午5点游泳")
        assertEquals(NaturalInputModel.Phase.Confirming, m.phase)
        val ledger = Ledger()
        assertTrue(ledger.calls.isEmpty())
        assertTrue(m.card(ws, null, now)?.writes == true)
    }

    @Test fun confirmRunsCreateThenConfirmAndSaysSaved() = blocking {
        val m = model("团团今天下午5点游泳")
        val ledger = Ledger()
        assertTrue(m.confirm(ledger) { ws })
        assertEquals(2, ledger.calls.size)
        assertIs<ParentingCommand.CreateDraft>(ledger.calls[0].first)
        assertIs<ParentingCommand.ConfirmArrangement>(ledger.calls[1].first)
        assertEquals(NaturalInputModel.Phase.Saved("已安排好了"), m.phase)
    }

    @Test fun partialSaveRetryOnlyRunsTheMissingStepWithTheSameOperationId() = blocking {
        val m = model("团团今天下午5点游泳")
        val ledger = Ledger(failAt = setOf(2))
        assertFalse(m.confirm(ledger) { ws })
        val failed = assertIs<NaturalInputModel.Phase.Failed>(m.phase)
        assertTrue(failed.message.contains("已先存为待确认"))
        assertEquals(1, ledger.calls.size)
        assertTrue(m.confirm(ledger) { ws })
        assertEquals(listOf(true, false), ledger.calls.map { it.first is ParentingCommand.CreateDraft }.let { listOf(it[0], it[1]) })
        assertEquals(2, ledger.calls.size, "创建只跑一次：重试只补确认")
        assertIs<NaturalInputModel.Phase.Saved>(m.phase)
    }

    @Test fun firstStepFailureShowsLedgerErrorAndRetryRunsBothSteps() = blocking {
        val m = model("团团今天下午5点游泳")
        val ledger = Ledger(failAt = setOf(1), error = "没有保存成功。")
        assertFalse(m.confirm(ledger) { ws })
        assertEquals("没有保存成功。", assertIs<NaturalInputModel.Phase.Failed>(m.phase).message)
        assertTrue(m.confirm(ledger) { ws })
        assertEquals(2, ledger.calls.size)
    }

    @Test fun confirmIsRefusedUnlessConfirmingOrFailed() = blocking {
        val ledger = Ledger()
        val empty = NaturalInputModel()
        assertFalse(empty.confirm(ledger) { ws })
        val asking = model("游泳", ws2) // 缺孩子、时间
        assertEquals(NaturalInputModel.Phase.Asking, asking.phase)
        assertFalse(asking.confirm(ledger) { ws2 })
        assertTrue(ledger.calls.isEmpty())
    }

    @Test fun viewIntentsNeverWriteAndAreNotConfirmable() = blocking {
        val m = model("今天有什么安排")
        assertEquals(ParentingIntentKind.ViewToday, m.proposal?.kind)
        assertFalse(m.card(ws, null, now)!!.writes)
        val ledger = Ledger()
        assertFalse(m.confirm(ledger) { ws })
        assertTrue(ledger.calls.isEmpty())
    }

    @Test fun answersAreOnlyAcceptedWhileAskingAndRereadTheOriginalWords() {
        val m = model("今天下午5点游泳", ws2)
        assertEquals(NaturalInputModel.Phase.Asking, m.phase)
        m.answer(ParentingNaturalAnswer.Child(childB), ctx(ws2), now, calendar)
        assertEquals(NaturalInputModel.Phase.Confirming, m.phase)
        assertEquals(childB, m.proposal?.childID)
        val before = m.proposal
        m.answer(ParentingNaturalAnswer.Child(childA), ctx(ws2), now, calendar) // 已不在追问
        assertEquals(before, m.proposal)
    }

    @Test fun replyTextCompletesTheMissingTime() {
        val m = model("明天游泳")
        assertEquals(NaturalInputModel.Phase.Asking, m.phase)
        m.reply = "下午3点"
        m.answerWithReply(ctx(ws), now, calendar)
        assertEquals(NaturalInputModel.Phase.Confirming, m.phase)
        assertEquals("", m.reply)
    }

    @Test fun blankReplyIsIgnored() {
        val m = model("明天游泳")
        m.reply = "   "
        m.answerWithReply(ctx(ws), now, calendar)
        assertEquals(NaturalInputModel.Phase.Asking, m.phase)
    }

    @Test fun draftIsCappedAtTheInputLimit() {
        val m = NaturalInputModel()
        m.editDraft("好".repeat(3500))
        assertEquals(3000, m.draft.length)
        assertTrue(m.canSubmit)
    }

    @Test fun blankDraftCannotSubmitAndPreviewIsQuietForUnknownText() {
        val m = NaturalInputModel()
        m.editDraft("   ")
        assertFalse(m.canSubmit)
        m.submit(ctx(ws), now, calendar)
        assertNull(m.proposal)
        m.editDraft("哈哈哈哈")
        assertNull(m.preview(ctx(ws), now, calendar))
        m.editDraft("团团今天下午5点游泳")
        assertEquals(ParentingIntentKind.NewArrangement, m.preview(ctx(ws), now, calendar)?.kind)
        assertNull(m.proposal, "预览不提交")
    }

    @Test fun resetClearsEverythingAndNewSubmitUsesFreshIds() = blocking {
        val m = model("团团今天下午5点游泳")
        val first = Ledger()
        m.confirm(first) { ws }
        m.reset()
        assertEquals("", m.draft)
        assertNull(m.proposal)
        assertEquals(NaturalInputModel.Phase.Idle, m.phase)
        m.editDraft("团团今天下午5点游泳")
        m.submit(ctx(ws), now, calendar)
        val second = Ledger()
        m.confirm(second) { ws }
        assertNotEquals(first.calls[0].second, second.calls[0].second)
        assertNotEquals((first.calls[0].first as ParentingCommand.CreateDraft).id, (second.calls[0].first as ParentingCommand.CreateDraft).id)
    }

    @Test fun failedCanBeDismissedBackToTheCard() = blocking {
        val m = model("团团今天下午5点游泳")
        m.confirm(Ledger(failAt = setOf(1))) { ws }
        m.dismissFailure()
        assertEquals(NaturalInputModel.Phase.Confirming, m.phase)
    }
}
