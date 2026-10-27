package com.heydusk.mimi.domain.parenting

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 对应 iOS `ParentingTodayDeckTests`（15 个）。周存储的往返在 :core:data 的 DataStore 测试里。 */
class TodayDeckTest {
    private val calendar = T.calendar
    private val child = UUID.fromString("D2000000-0000-4000-8000-000000000001")
    private val sibling = UUID.fromString("D2000000-0000-4000-8000-000000000002")
    private fun at(day: Int, hour: Int = 0, minute: Int = 0): Instant = T.at(2026, 10, day, hour, minute)
    private val monday10 get() = at(26, 10)
    private fun id(n: Int) = UUID.fromString("D3000000-0000-4000-8000-" + n.toString().padStart(12, '0'))
    private val P = ParentingArrangementState.Planned
    private fun item(n: Int, state: ParentingArrangementState, due: Instant, owner: UUID? = null) =
        ParentingArrangement(id(n), owner ?: child, "事项$n", due, null, state, "", at(1), at(1))
    private fun ledger(children: Boolean = true, arrangements: List<ParentingArrangement> = emptyList()) = ParentingWorkspace(
        children = if (children) listOf(ParentingChild(child, "小星", at(1)), ParentingChild(sibling, "小月", at(2))) else emptyList(),
        arrangements = arrangements,
    )
    private fun deck(ws: ParentingWorkspace, now: Instant? = null, context: ParentingStartContext = ParentingStartContext(), limit: Int = ParentingTodayDeck.RAIL_LIMIT) =
        ParentingTodayDeck.build(ws, now ?: monday10, calendar, context, limit)

    @Test fun heroIsExactlyTheStartActionResolverResult() {
        val ws = ledger(arrangements = listOf(item(1, P, at(26, 13)), item(2, ParentingArrangementState.Draft, at(27, 9))))
        assertEquals(ParentingStartAction.resolve(ParentingStartTab.Today, ws, monday10, calendar, ParentingStartContext()), deck(ws).hero)
        assertEquals(ParentingStartAction.ConfirmAll(listOf(id(2))), deck(ws).hero, "drafts come first")
    }

    @Test fun noChildMeansNickNameHeroAndEmptyRail() {
        val d = deck(ledger(children = false))
        assertEquals(ParentingStartAction.StartWithNickname, d.hero)
        assertNull(d.childID); assertNull(d.heroArrangement)
        assertTrue(d.rail.isEmpty()); assertEquals(0, d.hiddenOpenCount)
    }

    @Test fun heroArrangementIsOnlySetForNextWithComplete() {
        assertEquals(id(1), deck(ledger(arrangements = listOf(item(1, P, at(26, 13))))).heroArrangement?.id)
        val overdue = ledger(arrangements = listOf(item(1, P, at(24, 9))))
        assertEquals(ParentingStartAction.HandleOverdue(listOf(id(1))), deck(overdue).hero)
        assertNull(deck(overdue).heroArrangement)
    }

    @Test fun railListsTheNextThingsAfterTheHeroInTimeOrderAndTodayBeforeLaterDays() {
        val ws = ledger(arrangements = listOf(item(1, P, at(26, 13)), item(2, P, at(27, 8)), item(3, P, at(26, 18)), item(4, P, at(26, 15))))
        val d = deck(ws)
        assertEquals(id(1), d.heroArrangement?.id)
        assertEquals(listOf(id(4), id(3), id(2)), d.rail.map { it.id })
        assertEquals(listOf(ParentingTodayDeck.RailItem.When.LaterToday, ParentingTodayDeck.RailItem.When.LaterToday, ParentingTodayDeck.RailItem.When.AnotherDay), d.rail.map { it.whenLabel })
        assertEquals(listOf("事项4", "事项3", "事项2"), d.rail.map { it.title })
    }

    @Test fun railIsCappedAtThreeAndCountsTheRestAsHidden() {
        val many = (1..7).map { item(it, P, at(26, 12 + it)) }
        val ws = ledger(arrangements = many)
        val d = deck(ws, at(26, 9))
        assertEquals(id(1), d.heroArrangement?.id)
        assertEquals(listOf(id(2), id(3), id(4)), d.rail.map { it.id })
        assertEquals(3, d.hiddenOpenCount, "items 5, 6 and 7 are only in 全部安排")
        assertEquals(2, deck(ws, at(26, 9), limit = 2).rail.size)
        assertEquals(0, deck(ws, at(26, 9), limit = 0).rail.size)
    }

    @Test fun railNeverRepeatsTheHeroAndSkipsFinishedCancelledPassedAndOtherChildren() {
        val ws = ledger(arrangements = listOf(
            item(1, P, at(26, 13)), item(2, ParentingArrangementState.Completed, at(26, 14)), item(3, ParentingArrangementState.Cancelled, at(26, 15)),
            item(4, P, at(26, 9)), item(5, P, at(26, 16), sibling), item(6, P, at(26, 17)),
        ))
        val d = deck(ws)
        assertEquals(id(1), d.heroArrangement?.id)
        assertEquals(listOf(id(6)), d.rail.map { it.id })
        assertFalse(d.rail.any { it.id == d.heroArrangement?.id })
    }

    @Test fun railStillShowsWhatIsNextWhenTheHeroIsConfirmAllOrOverdue() {
        val pending = ledger(arrangements = listOf(item(1, ParentingArrangementState.Draft, at(27, 9)), item(2, P, at(26, 15)), item(3, P, at(26, 12))))
        assertEquals(listOf(id(3), id(2)), deck(pending).rail.map { it.id })
        assertEquals(1, deck(pending).hiddenOpenCount, "the draft is behind the confirm-all hero, not on screen as a card")
        val overdue = ledger(arrangements = listOf(item(1, P, at(24, 9)), item(2, P, at(26, 15))))
        assertEquals(listOf(id(2)), deck(overdue).rail.map { it.id })
    }

    @Test fun passedHeroLeavesTheRailEmptyBecauseNothingIsAhead() {
        val d = deck(ledger(arrangements = listOf(item(1, P, at(26, 8)), item(2, P, at(26, 9)))))
        assertEquals(ParentingStartAction.NextWithComplete(id(2), at(26, 9), true), d.hero)
        assertEquals(id(2), d.heroArrangement?.id)
        assertTrue(d.rail.isEmpty())
        assertEquals(1, d.hiddenOpenCount, "the earlier passed item is only in 全部安排")
    }

    @Test fun railFollowsTheChosenChild() {
        val ws = ledger(arrangements = listOf(item(1, P, at(26, 13)), item(2, P, at(26, 15), sibling), item(3, P, at(26, 16), sibling)))
        val d = deck(ws, context = ParentingStartContext(childID = sibling))
        assertEquals(sibling, d.childID); assertEquals(id(2), d.heroArrangement?.id); assertEquals(listOf(id(3)), d.rail.map { it.id })
    }

    @Test fun railTreatsTheMinuteOfNowAsNotAheadAndMidnightBoundaryAsAnotherDay() {
        val ws = ledger(arrangements = listOf(item(1, P, monday10), item(2, P, at(26, 23, 59)), item(3, P, at(27, 0, 0))))
        val d = deck(ws)
        assertEquals(id(2), d.heroArrangement?.id, "due exactly now counts as passed, so the next one ahead is the hero")
        assertEquals(listOf(id(3)), d.rail.map { it.id })
        assertEquals(ParentingTodayDeck.RailItem.When.AnotherDay, d.rail.first().whenLabel)
    }

    @Test fun buildingTheDeckNeverChangesTheLedger() {
        fun make() = ledger(arrangements = listOf(item(1, P, at(26, 13)), item(2, ParentingArrangementState.Draft, at(27, 9))))
        val ws = make(); deck(ws)
        assertEquals(make(), ws)
    }

    @Test fun undoRemainingSecondsRoundUpAndNeverGoNegative() {
        val ws = ParentingWorkspace(
            children = listOf(ParentingChild(child, "小星", at(1))), arrangements = listOf(item(1, ParentingArrangementState.Completed, at(26, 9))),
            records = listOf(ParentingRecord(id(100), child, null, id(1), ParentingRecordKind.Completion, "事项1", "", monday10, monday10)),
        )
        val undo = assertNotNull(ParentingCompletionUndo.make(ws, id(1), id(100), monday10), "completed by this record")
        fun s(ms: Long) = undo.remainingSeconds(monday10.plusMillis(ms))
        assertEquals(3, undo.remainingSeconds(monday10)); assertEquals(3, s(200)); assertEquals(2, s(1000)); assertEquals(1, s(2010))
        assertEquals(0, s(3000)); assertEquals(0, s(60_000))
        assertEquals(Duration.ofSeconds(3), undo.timeUntilExpiry(monday10))
        assertEquals(Duration.ZERO, undo.timeUntilExpiry(monday10.plusSeconds(10)))
        assertTrue(undo.isActive(monday10.plusMillis(2990))); assertFalse(undo.isActive(monday10.plusSeconds(3)))
        assertEquals(1, s(2990))
    }

    @Test fun weeklyReviewStatusCountsCalendarDaysNotHours() {
        fun status(last: Instant?, now: Instant) = ParentingWeeklyReviewStatus.make(last, now, calendar)
        assertEquals(ParentingWeeklyReviewStatus.Never, status(null, monday10))
        assertEquals(ParentingWeeklyReviewStatus.Today, status(at(26, 0, 5), at(26, 23, 59)))
        assertEquals(ParentingWeeklyReviewStatus.DaysAgo(1), status(at(25, 23, 59), at(26, 0, 1)))
        assertEquals(ParentingWeeklyReviewStatus.DaysAgo(7), status(at(19, 20), monday10))
        assertEquals(ParentingWeeklyReviewStatus.Today, status(at(28, 9), monday10), "a future date from a clock change is never negative")
    }

    @Test fun weeklyReviewStoreKeyIsPerSubjectAndLowercase() {
        assertEquals("mimi.parenting.weeklyReview.lastAt.v1." + id(1).toString().lowercase(), ParentingWeeklyReviewStore.key(id(1)))
        assertTrue(ParentingWeeklyReviewStore.key(id(1)) != ParentingWeeklyReviewStore.key(id(2)))
    }

    @Test fun storedReviewDateFeedsTheGrowthResolverThroughTheContext() {
        val record = ParentingRecord(id(100), child, null, null, ParentingRecordKind.Observation, "观察", "观察", at(24, 9), at(24, 9))
        val ws = ParentingWorkspace(children = listOf(ParentingChild(child, "小星", at(1))), records = listOf(record))
        fun resolve(ctx: ParentingStartContext) = ParentingStartAction.resolve(ParentingStartTab.Growth, ws, monday10, calendar, ctx)
        assertEquals(ParentingStartAction.WeeklyReview(ParentingWeeklyReviewReason.SevenDaysSinceLastReview), resolve(ParentingStartContext(lastWeeklyReviewAt = at(18, 9))))
        assertEquals(ParentingStartAction.RecordObservation(child), resolve(ParentingStartContext(lastWeeklyReviewAt = at(25, 9))))
    }
}
