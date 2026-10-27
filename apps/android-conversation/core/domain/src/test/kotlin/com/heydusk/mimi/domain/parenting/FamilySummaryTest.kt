package com.heydusk.mimi.domain.parenting

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 对应 iOS `ParentingFamilySummaryTests`（6 个）。 */
class FamilySummaryTest {
    private val day = Instant.ofEpochSecond(1_800_000_000)
    private fun u(s: String) = UUID.fromString(s)
    private fun child(id: String, name: String) = ParentingChild(u(id), name, day)
    private fun goal(id: String, c: ParentingChild) = ParentingGoal(u(id), c.id, "Synthetic goal", day)
    private fun record(id: String, c: ParentingChild, kind: ParentingRecordKind) = ParentingRecord(u(id), c.id, null, null, kind, "Synthetic text", "", day, day)
    private fun arrangement(id: String, c: ParentingChild) = ParentingArrangement(u(id), c.id, "Synthetic arrangement", day, null, ParentingArrangementState.Planned, "", day, day)

    @Test fun accentSlotFollowsCreationOrderAndIsStable() {
        val older = ParentingChild(u("FF000000-0000-0000-0000-000000000000"), "A", day)
        val newer = ParentingChild(u("01000000-0000-0000-0000-000000000000"), "B", day.plusSeconds(60))
        val ws = ParentingWorkspace(children = listOf(newer, older))
        val slots = ParentingFamilySummary.children(ws).map { it.accentSlot }
        assertEquals(listOf(1, 0), slots, "the older child is slot 0 whatever the array order")
        assertEquals(slots, ParentingFamilySummary.children(ws).map { it.accentSlot })
        assertTrue(slots.all { it in 0 until ParentingGrowthInsights.ACCENT_SLOT_COUNT })
    }

    @Test fun initialUsesTheFirstCharacterOfTheTrimmedNickname() {
        assertEquals("M", ParentingFamilySummary.initial("  Mimi "))
        assertEquals("小", ParentingFamilySummary.initial("小米"))
        assertEquals("•", ParentingFamilySummary.initial("   "))
        assertEquals("•", ParentingFamilySummary.initial(""))
        assertEquals("👨‍👩‍👧", ParentingFamilySummary.initial("👨‍👩‍👧 家"), "a grapheme cluster is one character, as in Swift")
    }

    @Test fun summaryCountsOnlyThisChildsGoalsAndObservations() {
        val a = child("00000000-0000-0000-0000-0000000000A1", "Synthetic A"); val b = child("00000000-0000-0000-0000-0000000000B1", "Synthetic B")
        val ws = ParentingWorkspace(
            children = listOf(a, b),
            goals = listOf(goal("00000000-0000-0000-0000-0000000000C1", a), goal("00000000-0000-0000-0000-0000000000C2", a), goal("00000000-0000-0000-0000-0000000000C3", b)),
            arrangements = listOf(arrangement("00000000-0000-0000-0000-0000000000D1", a)),
            records = listOf(
                record("00000000-0000-0000-0000-0000000000E1", a, ParentingRecordKind.Observation),
                record("00000000-0000-0000-0000-0000000000E2", a, ParentingRecordKind.Completion),
                record("00000000-0000-0000-0000-0000000000E3", b, ParentingRecordKind.Observation),
            ),
        )
        val s = ParentingFamilySummary.children(ws)
        assertEquals(listOf(a.id, b.id), s.map { it.id })
        assertEquals(2, s[0].goalCount); assertEquals(1, s[0].observationCount, "A completion record is not an observation.")
        assertEquals(1, s[1].goalCount); assertEquals(1, s[1].observationCount)
        assertEquals("Synthetic A", s[0].name); assertEquals("S", s[0].initial); assertEquals(day, s[0].startedAt)
    }

    @Test fun noChildrenGivesNoCards() = assertTrue(ParentingFamilySummary.children(ParentingWorkspace.EMPTY).isEmpty())

    private fun launch(ws: ParentingWorkspace, reminders: Boolean = false) = FamilyLaunchChecklist.current(ws, reminders)

    @Test fun launchChecklistFollowsTheStartActionRules() {
        val empty = launch(ParentingWorkspace.EMPTY)
        assertEquals(3, empty.totalCount); assertEquals(0, empty.doneCount)
        val a = child("00000000-0000-0000-0000-0000000000A1", "Synthetic A")
        assertEquals(1, launch(ParentingWorkspace(children = listOf(a))).doneCount)
        val withArrangement = ParentingWorkspace(children = listOf(a), arrangements = listOf(arrangement("00000000-0000-0000-0000-0000000000D1", a)))
        assertEquals(2, launch(withArrangement).doneCount); assertEquals(3, launch(withArrangement, true).doneCount)
        val withObservation = ParentingWorkspace(children = listOf(a), records = listOf(record("00000000-0000-0000-0000-0000000000E1", a, ParentingRecordKind.Observation)))
        assertEquals(2, launch(withObservation).doneCount)
        assertEquals(listOf("child", "first-record", "reminders"), launch(withObservation).steps.map { it.id })
    }

    @Test fun routesAreValueEqualAndDistinct() {
        val id = UUID.randomUUID()
        assertEquals(ParentingFamilyRoute.Child(id), ParentingFamilyRoute.Child(id))
        assertNotEquals<ParentingFamilyRoute>(ParentingFamilyRoute.Child(id), ParentingFamilyRoute.Child(UUID.randomUUID()))
        assertEquals(2, setOf(ParentingFamilyRoute.Settings, ParentingFamilyRoute.Reminders, ParentingFamilyRoute.Settings).size)
    }
}
