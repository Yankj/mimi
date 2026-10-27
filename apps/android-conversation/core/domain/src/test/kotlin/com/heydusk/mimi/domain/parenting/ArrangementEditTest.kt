package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.common.DomainError
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 对应 iOS `ParentingArrangementEditTests` 的领域部分（改期事务 `reviseArrangement`、撤销、冲突确认、历史校验）。存储层部分见 :core:data。 */
class ArrangementEditTest {
    private val now: Instant = Instant.ofEpochSecond(1_792_756_800)
    private val childID = UUID.fromString("E2000000-0000-4000-8000-000000000001")
    private val arrangementID = UUID.fromString("E3000000-0000-4000-8000-000000000001")
    private val goalID = UUID.fromString("E4000000-0000-4000-8000-000000000001")
    private val changeID = UUID.fromString("E5000000-0000-4000-8000-000000000001")

    private fun ParentingWorkspace.run(vararg commands: ParentingCommand): ParentingWorkspace {
        var w = this
        for (c in commands) w = w.applying(c, now)
        return w
    }

    private fun seed(planned: Boolean = true, goal: Boolean = true): ParentingWorkspace {
        var w = ParentingWorkspace.EMPTY.run(ParentingCommand.AddChild(childID, "合成小星"))
        if (goal) w = w.run(ParentingCommand.AddGoal(goalID, childID, "合成阅读目标"))
        w = w.run(ParentingCommand.CreateDraft(arrangementID, childID, "合成绘本", now.plusSeconds(3600), if (goal) goalID else null, "合成原始安排来源"))
        return if (planned) w.run(ParentingCommand.ConfirmArrangement(arrangementID)) else w
    }

    private fun target(w: ParentingWorkspace) = ParentingArrangementEditTarget(w.arrangements.first { it.id == arrangementID }, w.revision)

    private fun revision(w: ParentingWorkspace, id: UUID = changeID, dueAt: Instant = now.plusSeconds(7200), acknowledged: List<UUID> = emptyList()): ParentingCommand {
        val frozen = target(w)
        return ParentingCommand.ReviseArrangement(id, frozen, frozen.arrangement.title, dueAt, frozen.arrangement.goalID, "改到明天下午五点", acknowledged)
    }

    @Test fun revisionAndUndoKeepIdentitySourceAndOneHistory() {
        val original = seed()
        val before = target(original).arrangement
        val revised = original.run(revision(original))
        val after = revised.arrangements.first()
        assertEquals(before.id, after.id); assertEquals(before.childID, after.childID); assertEquals(before.goalID, after.goalID)
        assertEquals(before.title, after.title); assertEquals(before.state, after.state)
        assertEquals(before.sourceText, after.sourceText); assertEquals(before.createdAt, after.createdAt)
        assertEquals(now.plusSeconds(7200), after.dueAt)
        val edit = revised.editHistory.first()
        assertEquals(changeID, edit.id); assertEquals(before, edit.before); assertEquals(after, edit.after)
        assertEquals("改到明天下午五点", edit.sourceText)
        assertTrue(edit.canUndo(revised))

        val undone = revised.run(ParentingCommand.UndoArrangementEdit(changeID, emptyList()))
        assertEquals(before.dueAt, undone.arrangements.first().dueAt)
        assertEquals(before.sourceText, undone.arrangements.first().sourceText)
        assertEquals(now, undone.editHistory.first().revertedAt)
        assertFalse(undone.editHistory.first().canUndo(undone))
        assertFailsWith<DomainError.Invalid> { undone.run(ParentingCommand.UndoArrangementEdit(changeID, emptyList())) }
    }

    @Test fun staleSnapshotNoChangeAndInvalidGoalAreRejected() {
        val original = seed()
        val oldCommand = revision(original)
        val changed = original.run(ParentingCommand.RenameChild(childID, "合成已改昵称"))
        assertFailsWith<DomainError.Conflict> { changed.run(oldCommand) }
        val forged = target(changed).let { it.copy(arrangement = it.arrangement.copy(sourceText = "合成伪造旧快照")) }
        val mismatch = ParentingCommand.ReviseArrangement(changeID, forged, forged.arrangement.title, now.plusSeconds(7200), goalID, "明天17:00", emptyList())
        assertFailsWith<DomainError.Conflict> { changed.run(mismatch) }
        val noChange = revision(changed, dueAt = target(changed).arrangement.dueAt)
        assertFailsWith<DomainError.Invalid> { changed.run(noChange) }
        val current = target(changed)
        val invalidGoal = ParentingCommand.ReviseArrangement(changeID, current, current.arrangement.title, now.plusSeconds(7200), UUID.randomUUID(), "", emptyList())
        assertFailsWith<DomainError.Invalid> { changed.run(invalidGoal) }
    }

    @Test fun sameMinuteAcrossChildrenRequiresExactCurrentAcknowledgementAndStableOrder() {
        var current = seed()
        val otherChild = UUID.randomUUID()
        val earlier = UUID.fromString("00000000-0000-4000-8000-0000000000E1")
        val later = UUID.fromString("00000000-0000-4000-8000-0000000000E2")
        val draft = UUID.fromString("00000000-0000-4000-8000-0000000000E3")
        val cancelled = UUID.fromString("00000000-0000-4000-8000-0000000000E4")
        val due = now.plusSeconds(7200)
        current = current.run(ParentingCommand.AddChild(otherChild, "合成另一个孩子"))
        for ((id, offset, planned) in listOf(Triple(later, 50L, true), Triple(earlier, 10L, true), Triple(draft, 20L, false), Triple(cancelled, 30L, true))) {
            current = current.run(ParentingCommand.CreateDraft(id, otherChild, "合成同刻事项", due.plusSeconds(offset), null, ""))
            if (planned) current = current.run(ParentingCommand.ConfirmArrangement(id))
        }
        current = current.run(ParentingCommand.CancelArrangement(cancelled))
        assertEquals(listOf(earlier, later), current.sameTimeConflicts(arrangementID, due).map { it.id })
        val frozen = current
        for (ack in listOf(emptyList(), listOf(earlier), listOf(earlier, later, later), listOf(earlier, later, draft))) {
            assertFailsWith<DomainError.Invalid> { frozen.run(revision(frozen, dueAt = due, acknowledged = ack)) }
        }
        val stale = revision(frozen, dueAt = due, acknowledged = listOf(later, earlier))
        val changed = frozen.run(ParentingCommand.ConfirmArrangement(draft))
        assertFailsWith<DomainError.Conflict> { changed.run(stale) }
        assertFailsWith<DomainError.Invalid> { changed.run(revision(changed, dueAt = due, acknowledged = listOf(earlier, later))) }
        val accepted = changed.run(revision(changed, dueAt = due, acknowledged = listOf(later, draft, earlier)))
        assertEquals(1, accepted.editHistory.size)
        assertEquals(due, accepted.arrangements.first { it.id == arrangementID }.dueAt)
        assertEquals(listOf(earlier, draft, later), accepted.sameTimeConflicts(arrangementID, due).map { it.id })
    }

    @Test fun sameClockManualEditAndReturnToAfterCannotReviveUndo() {
        val original = seed()
        val revised = original.run(revision(original))
        val after = revised.arrangements.first()
        val manual = revised.run(ParentingCommand.EditArrangement(arrangementID, "合成手工改名", after.dueAt, after.goalID))
        val restored = manual.run(ParentingCommand.EditArrangement(arrangementID, after.title, after.dueAt, after.goalID))
        assertEquals(after, restored.arrangements.first(), "The fixed clock makes this an actual ABA snapshot")
        val edit = restored.editHistory.first()
        assertEquals("改到明天下午五点", edit.sourceText)
        assertEquals(now, edit.supersededAt)
        assertFalse(edit.canUndo(restored))
        assertFailsWith<DomainError.Invalid> { restored.run(ParentingCommand.UndoArrangementEdit(changeID, emptyList())) }
    }

    @Test fun undoRequiresExactCurrentAcknowledgementForRestoredMinute() {
        val original = seed()
        val restoredDue = original.arrangements.first().dueAt
        val revised = original.run(revision(original))
        val firstConflict = UUID.randomUUID(); val laterConflict = UUID.randomUUID()
        val conflictAdded = revised.run(
            ParentingCommand.CreateDraft(firstConflict, childID, "合成后来同刻安排", restoredDue.plusSeconds(30), null, ""),
            ParentingCommand.ConfirmArrangement(firstConflict),
        )
        assertTrue(conflictAdded.editHistory.first().canUndo(conflictAdded))
        for (ack in listOf(emptyList(), listOf(firstConflict, firstConflict), listOf(firstConflict, UUID.randomUUID()))) {
            assertFailsWith<DomainError.Invalid> { conflictAdded.run(ParentingCommand.UndoArrangementEdit(changeID, ack)) }
        }
        val latest = conflictAdded.run(
            ParentingCommand.CreateDraft(laterConflict, childID, "合成再次变化的同刻安排", restoredDue.plusSeconds(45), null, ""),
            ParentingCommand.ConfirmArrangement(laterConflict),
        )
        assertFailsWith<DomainError.Invalid> { latest.run(ParentingCommand.UndoArrangementEdit(changeID, listOf(firstConflict))) }
        val undone = latest.run(ParentingCommand.UndoArrangementEdit(changeID, listOf(laterConflict, firstConflict)))
        assertEquals(restoredDue, undone.arrangements.first { it.id == arrangementID }.dueAt)
        assertNotNull(undone.editHistory.first().revertedAt)
    }

    @Test fun completionAndCancellationSupersedeUndoWithoutErasingCompletionFacts() {
        for (complete in listOf(false, true)) {
            val original = seed()
            val revised = original.run(revision(original))
            val command = if (complete) ParentingCommand.CompleteArrangement(arrangementID, UUID.randomUUID(), "合成完成事实") else ParentingCommand.CancelArrangement(arrangementID)
            val changed = revised.run(command)
            assertEquals(now, changed.editHistory.first().supersededAt)
            assertFalse(changed.editHistory.first().canUndo(changed))
            assertEquals(if (complete) ParentingArrangementState.Completed else ParentingArrangementState.Cancelled, changed.arrangements.first().state)
            assertEquals(if (complete) 1 else 0, changed.records.size)
            assertFailsWith<DomainError.Invalid> { changed.run(ParentingCommand.UndoArrangementEdit(changeID, emptyList())) }
            assertFailsWith<DomainError.Invalid> { changed.run(revision(changed, id = UUID.randomUUID())) }
            assertEquals(revised.editHistory.size, changed.editHistory.size)
        }
    }

    @Test fun actualDraftConfirmationSupersedesUndoButPlannedNoOpDoesNot() {
        for (wasPlanned in listOf(false, true)) {
            val original = seed(planned = wasPlanned)
            val revised = original.run(revision(original))
            val confirmed = revised.run(ParentingCommand.ConfirmArrangement(arrangementID))
            val edit = confirmed.editHistory.first()
            assertEquals(wasPlanned, edit.canUndo(confirmed))
            assertEquals(wasPlanned, edit.supersededAt == null)
            if (wasPlanned) {
                val undone = confirmed.run(ParentingCommand.UndoArrangementEdit(changeID, emptyList()))
                assertEquals(original.arrangements.first().dueAt, undone.arrangements.first().dueAt)
            } else {
                assertFailsWith<DomainError.Invalid> { confirmed.run(ParentingCommand.UndoArrangementEdit(changeID, emptyList())) }
            }
            assertEquals(revised.editHistory.size, confirmed.editHistory.size)
        }
    }

    @Test fun laterRevisionAndUndoNeverReviveEarlierHistoryAtSameClock() {
        val original = seed()
        val first = original.run(revision(original))
        val secondID = UUID.randomUUID()
        val second = first.run(revision(first, id = secondID, dueAt = now.plusSeconds(10800)))
        assertEquals(2, second.editHistory.size)
        assertEquals(now, second.editHistory.first().supersededAt)
        val undone = second.run(ParentingCommand.UndoArrangementEdit(secondID, emptyList()))
        assertEquals(first.arrangements, undone.arrangements)
        assertFalse(undone.editHistory.first().canUndo(undone))
        assertFailsWith<DomainError.Invalid> { undone.run(ParentingCommand.UndoArrangementEdit(changeID, emptyList())) }
    }

    @Test fun deleteArrangementChildOrGoalCleansHistoryAndCannotResurrectDeletedReferences() {
        for (deletion in listOf<ParentingCommand>(ParentingCommand.DeleteArrangement(arrangementID), ParentingCommand.DeleteChild(childID), ParentingCommand.DeleteGoal(goalID))) {
            val original = seed()
            val deleted = original.run(revision(original)).run(deletion)
            assertTrue(deleted.editHistory.isEmpty())
            assertFailsWith<DomainError.Missing> { deleted.run(ParentingCommand.UndoArrangementEdit(changeID, emptyList())) }
        }
    }

    @Test fun historyValidationRejectsCorruption() {
        val revised = seed().let { it.run(revision(it)) }
        fun mutate(f: (ParentingArrangementEdit) -> ParentingArrangementEdit): ParentingWorkspace =
            revised.copy(editHistory = listOf(f(revised.editHistory[0])))
        assertFailsWith<DomainError.Corrupt> { revised.copy(editHistory = revised.editHistory + revised.editHistory[0]).validate() }
        assertFailsWith<DomainError.Corrupt> { mutate { it.copy(after = it.after.copy(childID = UUID.randomUUID())) }.validate() }
        assertFailsWith<DomainError.Corrupt> { mutate { it.copy(after = it.after.copy(sourceText = "合成被替换的原始来源")) }.validate() }
        assertFailsWith<DomainError.Corrupt> { mutate { it.copy(after = it.after.copy(state = ParentingArrangementState.Completed)) }.validate() }
        assertFailsWith<DomainError.Corrupt> { mutate { it.copy(after = it.after.copy(createdAt = now.minusSeconds(1))) }.validate() }
        assertFailsWith<DomainError.Invalid> { mutate { it.copy(before = it.before.copy(goalID = UUID.randomUUID())) }.validate() }
        assertFailsWith<DomainError.Corrupt> { mutate { it.copy(revertedAt = now.minusSeconds(1)) }.validate() }
        assertFailsWith<DomainError.Corrupt> { mutate { it.copy(supersededAt = now.minusSeconds(1)) }.validate() }
        // 之后的手工修改不要求当前安排等于 `after`
        val manual = revised.run(ParentingCommand.EditArrangement(arrangementID, "合成后续手改", now.plusSeconds(14400), null))
        manual.validate()
        assertEquals(1, manual.editHistory.size)
        assertNull(manual.editHistory.first().revertedAt)
    }

    @Test fun cannotReviseOrEditCompletedOrCancelledArrangements() {
        val done = seed().run(ParentingCommand.CompleteArrangement(arrangementID, UUID.randomUUID(), ""))
        assertFailsWith<DomainError.Invalid> { done.run(ParentingCommand.EditArrangement(arrangementID, "改名", now, null)) }
        assertFailsWith<DomainError.Invalid> { done.run(revision(done, id = UUID.randomUUID())) }
        assertFailsWith<DomainError.Invalid> { done.run(ParentingCommand.CancelArrangement(arrangementID)) }
    }
}
