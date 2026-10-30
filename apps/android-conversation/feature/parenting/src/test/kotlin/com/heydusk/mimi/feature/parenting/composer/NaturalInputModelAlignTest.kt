package com.heydusk.mimi.feature.parenting.composer

import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.domain.parenting.ParentingCommand
import com.heydusk.mimi.domain.parenting.ParentingIntentKind
import com.heydusk.mimi.domain.parenting.ParentingNaturalContext
import com.heydusk.mimi.domain.parenting.ParentingProfileScope
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import com.heydusk.mimi.feature.parenting.actions.ParentingActions
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** #147 卡上改「是谁的事」与 #148「先不记 / 确认记入档案」的状态机。 */
class NaturalInputModelAlignTest {
    private val now = Instant.parse("2026-10-26T01:00:00Z")
    private val calendar = MimiCalendar(ZoneOffset.UTC)
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val ws: ParentingWorkspace = ParentingWorkspace.EMPTY
        .applying(ParentingCommand.AddChild(a, "团团"), now)
        .applying(ParentingCommand.AddChild(b, "朵朵"), now.plusSeconds(1))

    private class Ledger(var w: ParentingWorkspace, val now: Instant) : ParentingActions by ParentingActions.None {
        val calls = mutableListOf<ParentingCommand>()
        override suspend fun applyCommand(command: ParentingCommand, operationID: UUID): Boolean {
            calls += command; w = w.applying(command, now); return true
        }
    }

    private fun model(text: String) = NaturalInputModel().also { it.editDraft(text); it.submit(ParentingNaturalContext(ws), now, calendar) }

    @Test fun childCanBeChangedOnTheCardBeforeSaving() = runBlocking {
        val m = model("老大下午2点上课")
        assertEquals(a, m.proposal?.childID)
        assertTrue(m.canChangeChild)
        m.changeChild(b, ParentingNaturalContext(ws), now, calendar)
        assertEquals(b, m.proposal?.childID)
        assertEquals("上课", m.proposal?.title)
        assertEquals(NaturalInputModel.Phase.Confirming, m.phase)
        val ledger = Ledger(ws, now)
        assertTrue(m.confirm(ledger) { ledger.w })
        assertFalse(m.canChangeChild, "locked once saved")
        assertEquals(b, (ledger.calls[0] as ParentingCommand.CreateDraft).childID)
    }

    @Test fun declineWritesNothingAndConfirmLandsALongTermFact() = runBlocking {
        val declined = model("记在我们家周三奶奶接园")
        assertEquals(ParentingIntentKind.ProfileFact, declined.proposal?.kind)
        assertFalse(declined.canChangeChild)
        val ledger = Ledger(ws, now)
        declined.decline()
        assertEquals(NaturalInputModel.Phase.Idle, declined.phase)
        assertTrue(ledger.calls.isEmpty())

        val m = model("记在我们家周三奶奶接园")
        assertTrue(m.confirm(ledger) { ledger.w })
        assertIs<NaturalInputModel.Phase.Saved>(m.phase)
        assertEquals("已记入档案", (m.phase as NaturalInputModel.Phase.Saved).receipt)
        assertEquals(1, ledger.w.profile.usableFacts(ParentingProfileScope.FAMILY).size)
        assertTrue(ledger.w.arrangements.isEmpty())
    }

    @Test fun siblingOnlyArchiveNeverReachesTheCard() = runBlocking {
        val m = model("长期记住二宝怕黑")
        assertEquals(NaturalInputModel.Phase.Asking, m.phase)
        val ledger = Ledger(ws, now)
        assertFalse(m.confirm(ledger) { ledger.w })
        assertTrue(ledger.calls.isEmpty())
    }
}
