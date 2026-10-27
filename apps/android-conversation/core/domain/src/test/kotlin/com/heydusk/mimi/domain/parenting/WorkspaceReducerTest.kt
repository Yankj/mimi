package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.common.DomainError
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 对应 iOS `ParentingWorkspaceTests` 里的纯领域部分（账本存储相关的用例在 :core:data 的 LedgerStoreTest 里）。
 * iOS 的 AI 整理（Intake/InlineIntake）用例依赖聊天会话，随 M5 移植。
 */
class WorkspaceReducerTest {
    private val now: Instant = Instant.ofEpochSecond(1_792_756_800) // 2026-10-23T12:00:00Z
    private val childID = UUID.fromString("C2000000-0000-4000-8000-000000000001")
    private val itemID = UUID.fromString("C3000000-0000-4000-8000-000000000001")
    private val goalID = UUID.fromString("C4000000-0000-4000-8000-000000000001")
    private val recordID = UUID.fromString("C5000000-0000-4000-8000-000000000001")

    private fun ParentingWorkspace.run(vararg commands: ParentingCommand): ParentingWorkspace {
        var w = this
        for (c in commands) w = w.applying(c, now)
        return w
    }

    private fun seedDraft(linkedGoal: Boolean = false): ParentingWorkspace {
        var w = ParentingWorkspace.EMPTY.run(ParentingCommand.AddChild(childID, "合成小星"))
        if (linkedGoal) w = w.run(ParentingCommand.AddGoal(goalID, childID, "合成阅读目标"))
        return w.run(
            ParentingCommand.CreateDraft(itemID, childID, "合成绘本时间", now.plusSeconds(3600), if (linkedGoal) goalID else null, "合成原始输入，尚未确认"),
        )
    }

    @Test fun emptyWorkspaceHasNoSeededChild() {
        assertEquals(ParentingWorkspace(), ParentingWorkspace.EMPTY)
        assertTrue(ParentingWorkspace.EMPTY.children.isEmpty())
        assertEquals(0, ParentingWorkspace.EMPTY.revision)
    }

    @Test fun draftConfirmEditCompleteCreatesIndependentRecord() {
        val draft = seedDraft(linkedGoal = true)
        assertEquals(ParentingArrangementState.Draft, draft.arrangements.first().state)
        assertTrue(draft.records.isEmpty())
        val confirmed = draft.run(ParentingCommand.ConfirmArrangement(itemID))
        assertEquals(ParentingArrangementState.Planned, confirmed.arrangements.first().state)
        val revisedDue = now.plusSeconds(7200)
        val edited = confirmed.run(ParentingCommand.EditArrangement(itemID, "合成绘本改到下午", revisedDue, goalID))
        assertEquals(itemID, edited.arrangements.first().id)
        assertEquals(revisedDue, edited.arrangements.first().dueAt)
        val completed = edited.run(ParentingCommand.CompleteArrangement(itemID, recordID, "合成观察：主动选了故事"))
        assertEquals(ParentingArrangementState.Completed, completed.arrangements.first().state)
        assertEquals(1, completed.records.size)
        val record = completed.records.first()
        assertEquals(recordID, record.id)
        assertNotEquals(itemID, record.id)
        assertEquals(itemID, record.arrangementID)
        assertEquals(childID, record.childID)
        assertEquals(goalID, record.goalID)
        assertEquals(ParentingRecordKind.Completion, record.kind)
        assertEquals("合成观察：主动选了故事", record.text)
    }

    @Test fun unconfirmedAndCancelledDraftCannotBecomeCompletedOrRevive() {
        val draft = seedDraft()
        assertFailsWith<DomainError.Invalid> { draft.run(ParentingCommand.CompleteArrangement(itemID, recordID, "合成未获确认")) }
        val cancelled = draft.run(ParentingCommand.CancelArrangement(itemID))
        assertFailsWith<DomainError.Invalid> { cancelled.run(ParentingCommand.CompleteArrangement(itemID, recordID, "合成迟到结果")) }
        assertFailsWith<DomainError.Invalid> { cancelled.run(ParentingCommand.ConfirmArrangement(itemID)) }
        assertEquals(ParentingArrangementState.Cancelled, cancelled.arrangements.first().state)
        assertTrue(cancelled.records.isEmpty())
    }

    @Test fun repeatedCompletionCreatesOnlyOneRecordAndBlankNoteUsesTitle() {
        val planned = seedDraft().run(ParentingCommand.ConfirmArrangement(itemID))
        val completed = planned.run(ParentingCommand.CompleteArrangement(itemID, recordID, "  "))
        val again = completed.run(ParentingCommand.CompleteArrangement(itemID, UUID.randomUUID(), "合成重复点击"))
        assertEquals(completed.arrangements.first().title, completed.records.first().text)
        assertEquals("  ", completed.records.first().sourceText)
        assertEquals(completed.records, again.records)
        assertEquals(1, again.records.size)
    }

    @Test fun deletingGoalClearsReferencesAndKeepsArrangementAndObservation() {
        val w = seedDraft(linkedGoal = true).run(ParentingCommand.AddObservation(recordID, childID, goalID, "合成成长观察"))
        val removed = w.run(ParentingCommand.DeleteGoal(goalID))
        assertTrue(removed.goals.isEmpty())
        assertEquals(1, removed.arrangements.size)
        assertNull(removed.arrangements.first().goalID)
        assertEquals(1, removed.records.size)
        assertNull(removed.records.first().goalID)
        removed.validate()
    }

    @Test fun deletingChildRemovesOnlyThatChildsReferences() {
        val otherChild = UUID.fromString("C2000000-0000-4000-8000-0000000000FF")
        val otherRecord = UUID.fromString("C5000000-0000-4000-8000-0000000000FF")
        val w = seedDraft(linkedGoal = true).run(
            ParentingCommand.AddObservation(recordID, childID, goalID, "合成将删除观察"),
            ParentingCommand.AddChild(otherChild, "合成小月"),
            ParentingCommand.AddObservation(otherRecord, otherChild, null, "合成保留观察"),
        )
        val removed = w.run(ParentingCommand.DeleteChild(childID))
        assertEquals(listOf(otherChild), removed.children.map { it.id })
        assertTrue(removed.goals.isEmpty())
        assertTrue(removed.arrangements.isEmpty())
        assertEquals(listOf(otherRecord), removed.records.map { it.id })
        removed.validate()
    }

    @Test fun deletingArrangementPreservesIndependentEditableCompletionRecord() {
        val completed = seedDraft().run(
            ParentingCommand.ConfirmArrangement(itemID), ParentingCommand.CompleteArrangement(itemID, recordID, "合成完成观察"),
        )
        val removed = completed.run(ParentingCommand.DeleteArrangement(itemID))
        assertTrue(removed.arrangements.isEmpty())
        assertEquals(1, removed.records.size)
        assertEquals(recordID, removed.records.first().id)
        assertNull(removed.records.first().arrangementID)
        val edited = removed.run(ParentingCommand.EditRecord(recordID, "合成更正后的观察"))
        assertEquals("合成更正后的观察", edited.records.first().text)
        assertEquals("合成完成观察", edited.records.first().sourceText)
        assertTrue(edited.run(ParentingCommand.DeleteRecord(recordID)).records.isEmpty())
    }

    @Test fun wrongChildOrGoalIsRejectedWithoutChangingTheDraft() {
        val draft = seedDraft(linkedGoal = true)
        assertFailsWith<DomainError.Invalid> {
            draft.run(ParentingCommand.CreateDraft(UUID.randomUUID(), UUID.randomUUID(), "合成无效关联", now, goalID, ""))
        }
        // 目标属于另一个孩子也不行
        val other = UUID.randomUUID()
        val two = draft.run(ParentingCommand.AddChild(other, "合成另一个"))
        assertFailsWith<DomainError.Invalid> { two.run(ParentingCommand.CreateDraft(UUID.randomUUID(), other, "合成错配", now, goalID, "")) }
        assertEquals(1, draft.arrangements.size)
    }

    @Test fun duplicateIdentifiersAreAConflict() {
        val w = seedDraft()
        assertFailsWith<DomainError.Conflict> { w.run(ParentingCommand.AddChild(childID, "重复")) }
        assertFailsWith<DomainError.Conflict> { w.run(ParentingCommand.AddObservation(itemID, childID, null, "和安排同一个 id")) }
    }

    @Test fun suggestionsChoiceDoesNotChangeExistingArrangements() {
        val draft = seedDraft()
        val enabled = draft.run(ParentingCommand.SetSuggestionsEnabled(true))
        val paused = enabled.run(ParentingCommand.SetSuggestionsEnabled(false))
        assertTrue(enabled.suggestionsEnabled)
        assertFalse(paused.suggestionsEnabled)
        assertEquals(draft.arrangements, paused.arrangements)
    }

    @Test fun invalidDecodedWorkspaceReferencesAreRejectedBeforeMutation() {
        val child = ParentingChild(childID, "合成小星", now)
        val goal = ParentingGoal(goalID, UUID.randomUUID(), "合成孤立目标", now)
        val corrupt = ParentingWorkspace(revision = 1, children = listOf(child), goals = listOf(goal))
        assertFailsWith<DomainError.Corrupt> { corrupt.validate() }
        assertFailsWith<DomainError.Corrupt> { corrupt.applying(ParentingCommand.RenameChild(childID, "合成不能覆盖"), now) }
        assertEquals("合成小星", corrupt.children.first().name)
    }

    @Test fun textLimitsCountGraphemesNotUtf16Units() {
        val w = ParentingWorkspace.EMPTY
        // 40 个家庭 emoji（每个是 7 个码点、11 个 UTF-16 单元，但只是 1 个字形簇）
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66"
        val forty = family.repeat(40)
        assertTrue(forty.length > 40)
        assertEquals(forty, w.applying(ParentingCommand.AddChild(childID, forty), now).children.first().name)
        assertFailsWith<DomainError.Invalid> { w.applying(ParentingCommand.AddChild(childID, family.repeat(41)), now) }
        assertFailsWith<DomainError.Invalid> { w.applying(ParentingCommand.AddChild(childID, "   "), now) }
        assertFailsWith<DomainError.Invalid> { w.applying(ParentingCommand.AddChild(childID, "a\u0000b"), now) }
    }

    @Test fun nameIsTrimmedButObservationKeepsSourceText() {
        val w = ParentingWorkspace.EMPTY.run(ParentingCommand.AddChild(childID, "  小星  "))
        assertEquals("小星", w.children.first().name)
        val o = w.run(ParentingCommand.AddObservation(recordID, childID, null, "  写下的话  "))
        assertEquals("写下的话", o.records.first().text)
        assertEquals("  写下的话  ", o.records.first().sourceText)
    }

    @Test fun deleteAllRecordsEmptiesEverythingButKeepsSuggestionsSetting() {
        val w = seedDraft(linkedGoal = true).run(ParentingCommand.SetSuggestionsEnabled(true))
        val wiped = w.run(ParentingCommand.DeleteAllRecords)
        assertTrue(wiped.children.isEmpty() && wiped.goals.isEmpty() && wiped.arrangements.isEmpty() && wiped.records.isEmpty() && wiped.editHistory.isEmpty())
        assertTrue(wiped.suggestionsEnabled)
        assertEquals(w.revision + 1, wiped.revision)
    }

    @Test fun everyCommandBumpsTheRevisionByOne() {
        val w = seedDraft()
        assertEquals(2, w.revision)
        assertEquals(3, w.run(ParentingCommand.ConfirmArrangement(itemID)).revision)
        // 已经是 planned 再确认是空操作，但仍然是一次已保存的命令
        assertEquals(4, w.run(ParentingCommand.ConfirmArrangement(itemID), ParentingCommand.ConfirmArrangement(itemID)).revision)
    }

    // ---- 重新打开（撤销「完成」）----
    @Test fun reopenIsAnIdempotentUndoOfCompletion() {
        val planned = seedDraft(linkedGoal = true).run(ParentingCommand.ConfirmArrangement(itemID))
        val completed = planned.run(ParentingCommand.CompleteArrangement(itemID, recordID, ""))
        assertEquals(1, completed.records.size)
        val reopened = completed.run(ParentingCommand.ReopenArrangement(itemID))
        assertEquals(ParentingArrangementState.Planned, reopened.arrangements.first().state)
        assertTrue(reopened.records.isEmpty())
        val again = reopened.run(ParentingCommand.ReopenArrangement(itemID))
        assertEquals(reopened.arrangements, again.arrangements)
        assertEquals(reopened.records, again.records)
        val second = again.run(ParentingCommand.CompleteArrangement(itemID, UUID.randomUUID(), ""))
        assertEquals(ParentingArrangementState.Completed, second.arrangements.first().state)
        assertEquals(1, second.records.size)
    }

    @Test fun reopenKeepsWordsTheParentWroteAsAnObservation() {
        val planned = seedDraft().run(ParentingCommand.ConfirmArrangement(itemID))
        val completed = planned.run(ParentingCommand.CompleteArrangement(itemID, recordID, "他今天自己穿好了鞋"))
        val reopened = completed.run(ParentingCommand.ReopenArrangement(itemID))
        assertEquals(1, reopened.records.size)
        assertEquals(ParentingRecordKind.Observation, reopened.records.first().kind)
        assertNull(reopened.records.first().arrangementID)
        assertEquals("他今天自己穿好了鞋", reopened.records.first().text)
        reopened.validate()
    }

    @Test fun reopenRefusesDraftAndCancelled() {
        val draft = seedDraft()
        assertFailsWith<DomainError.Invalid> { draft.run(ParentingCommand.ReopenArrangement(itemID)) }
        assertFailsWith<DomainError.Invalid> { draft.run(ParentingCommand.CancelArrangement(itemID), ParentingCommand.ReopenArrangement(itemID)) }
        assertFailsWith<DomainError.Missing> { draft.run(ParentingCommand.ReopenArrangement(UUID.randomUUID())) }
    }

    // ---- 起手动作里的单次点击保护 ----
    @Test fun oneTapGateAllowsOneCompletionInFlight() {
        val gate = ParentingOneTapGate()
        assertTrue(gate.begin(now))
        assertFalse(gate.begin(now), "a second tap while the first save is running is ignored")
        gate.finish(null)
        assertTrue(gate.begin(now), "a failed save does not start a cooldown, so the parent can retry at once")
    }

    @Test fun oneTapGateIgnoresTheNextArrangementRightAfterACompletion() {
        val gate = ParentingOneTapGate()
        assertTrue(gate.begin(now))
        gate.finish(now)
        assertFalse(gate.begin(now.plusMillis(100)), "the hero already shows the next item; a double tap must not complete it")
        assertFalse(gate.begin(now.plusMillis(790)))
        assertTrue(gate.begin(now.plusMillis(850)), "a deliberate later tap works")
    }

    @Test fun weeklyReviewKeyIsPerSubjectLowercase() {
        val s = UUID.fromString("ABCDEF00-0000-4000-8000-000000000001")
        assertEquals("mimi.parenting.weeklyReview.lastAt.v1.abcdef00-0000-4000-8000-000000000001", ParentingWeeklyReviewStore.key(s))
    }

    @Test fun completionUndoWindowIsAbsolute() {
        val planned = seedDraft().run(ParentingCommand.ConfirmArrangement(itemID))
        val completed = planned.run(ParentingCommand.CompleteArrangement(itemID, recordID, ""))
        val undo = assertNotNull(ParentingCompletionUndo.make(completed, itemID, recordID, now))
        assertEquals(3, undo.remainingSeconds(now))
        assertEquals(1, undo.remainingSeconds(now.plusMillis(2001)))
        assertEquals(0, undo.remainingSeconds(now.plusSeconds(5)))
        assertTrue(undo.isActive(now.plusMillis(2999)))
        assertFalse(undo.isActive(now.plusSeconds(3)))
        assertEquals(java.time.Duration.ZERO, undo.timeUntilExpiry(now.plusSeconds(10)))
        assertNull(ParentingCompletionUndo.make(planned, itemID, recordID, now), "not completed → no undo")
        assertNull(ParentingCompletionUndo.make(completed, itemID, UUID.randomUUID(), now), "wrong record → no undo")
    }
}
