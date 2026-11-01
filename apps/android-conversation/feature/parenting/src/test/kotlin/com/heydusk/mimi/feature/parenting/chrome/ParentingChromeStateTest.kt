package com.heydusk.mimi.feature.parenting.chrome

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 一行双态状态机。纯 JVM，不依赖 Compose 或 Robolectric。
 * 数字来自 INTERACTION.md §2.3 / §4.1。
 */
class ParentingChromeStateTest {
    private enum class Tab { Today, Growth, Family }

    private fun state(tab: Tab = Tab.Today, reduceMotion: Boolean = false) =
        ParentingChromeState(tab = tab, reduceMotion = reduceMotion)

    @Test
    fun budgetsMatchTheInteractionTable() {
        assertEquals(3, ParentingChromeMode.entries.size)
        assertEquals(300L, ParentingChromeMotion.INPUT_TO_PICK_TOTAL_MS)
        assertEquals(50L, ParentingChromeMotion.RESIGN_MS)
        assertEquals(250L, ParentingChromeMotion.INPUT_TO_PICK_MORPH_MS)
        assertEquals(280L, ParentingChromeMotion.PICK_TO_INPUT_MS)
        assertEquals(280L, ParentingChromeMotion.DETAIL_PRESENT_MS)
        assertEquals(240L, ParentingChromeMotion.DETAIL_DISMISS_MS)
        assertEquals(120L, ParentingChromeMotion.REDUCE_MOTION_MS)
        assertEquals(0.32f, ParentingChromeMotion.SPRING_RESPONSE)
        assertEquals(0.86f, ParentingChromeMotion.SPRING_DAMPING)
        assertTrue(ParentingChromeMotion.FAMILY_CROSSFADE_MS in 120L..200L)
    }

    @Test
    fun typeChipResignsThenMorphsAndLocksFor300ms() {
        val start = state()
        val tapped = start.tapTypeChip(showsComposer = true)
        assertEquals(ParentingChromeMode.Input, tapped.mode)
        assertTrue(tapped.animating)
        assertEquals(1, tapped.resignFocusToken)
        assertEquals(ChromeStep.ResignThenPickTab, tapped.step)
        assertEquals(50L, tapped.stepMs)
        assertTrue(tapped.typeChipVisible(true))

        val mid = tapped.advance(49)
        assertEquals(ParentingChromeMode.Input, mid.mode)
        assertEquals(1L, mid.stepMs)

        val picking = tapped.advance(50)
        assertEquals(ParentingChromeMode.PickTab, picking.mode)
        assertTrue(picking.animating)
        assertTrue(picking.showPickTabSlot)
        assertFalse(picking.showComposerSlot)
        assertEquals(250L, picking.stepMs)

        assertSame(picking, picking.tapTypeChip(true))
        assertSame(picking, picking.dismissPickTab())
        assertSame(picking, picking.selectFromPick(Tab.Growth, true))
        assertSame(picking, picking.switchSteadyTab(Tab.Family))

        val open = tapped.advance(300)
        assertEquals(ParentingChromeMode.PickTab, open.mode)
        assertFalse(open.animating)
        assertEquals(ChromeStep.Idle, open.step)
        assertEquals(Tab.Today, open.tab)
    }

    @Test
    fun dismissAndSelectingTheCurrentTabReturnToInputWithoutChangingTab() {
        val open = state().tapTypeChip(true).advance(300)
        val dismissed = open.dismissPickTab()
        assertEquals(Tab.Today, dismissed.tab)
        assertEquals(ParentingChromeMode.Input, dismissed.mode)
        assertTrue(dismissed.animating)
        assertEquals(280L, dismissed.stepMs)
        assertSame(dismissed, dismissed.dismissPickTab())
        assertSame(dismissed, dismissed.tapTypeChip(true))
        val done = dismissed.advance(280)
        assertEquals(ParentingChromeMode.Input, done.mode)
        assertFalse(done.animating)
        assertTrue(done.typeChipVisible(true))

        val again = done.tapTypeChip(true).advance(300)
        val same = again.selectFromPick(Tab.Today, itemShowsComposer = true)
        assertEquals(Tab.Today, same.tab)
        assertEquals(ParentingChromeMode.Input, same.mode)
        assertEquals(280L, same.stepMs)
    }

    @Test
    fun selectingAnotherTabWritesItThenMorphsBack() {
        val open = state().tapTypeChip(true).advance(300)
        val growth = open.selectFromPick(Tab.Growth, itemShowsComposer = true)
        assertEquals(Tab.Growth, growth.tab)
        assertEquals(ParentingChromeMode.Input, growth.mode)
        assertTrue(growth.showComposerSlot)
        assertFalse(growth.showPickTabSlot)
        val done = growth.advance(280)
        assertFalse(done.animating)
        assertEquals(Tab.Growth, done.tab)
        assertTrue(done.typeChipVisible(true))
    }

    @Test
    fun familySkipsTheComposerMorphAndIgnoresSteadyTapsWhileLocked() {
        val open = state().tapTypeChip(true).advance(300)
        val family = open.selectFromPick(Tab.Family, itemShowsComposer = false)
        assertEquals(Tab.Family, family.tab)
        assertEquals(ParentingChromeMode.Input, family.mode)
        assertTrue(family.animating)
        assertEquals(ParentingChromeMotion.FAMILY_CROSSFADE_MS, family.stepMs)
        assertFalse(family.typeChipVisible(false))
        assertSame(family, family.switchSteadyTab(Tab.Today))
        val done = family.advance(ParentingChromeMotion.FAMILY_CROSSFADE_MS)
        assertFalse(done.animating)
        val again = done.switchSteadyTab(Tab.Growth)
        assertEquals(Tab.Growth, again.tab)
        assertEquals(ParentingChromeMode.Input, again.mode)
        assertEquals(done.rootEpoch + 1, again.rootEpoch)
        val same = again.switchSteadyTab(Tab.Growth)
        assertEquals(again.rootEpoch + 1, same.rootEpoch)
        assertEquals(Tab.Growth, same.tab)
    }

    @Test
    fun detailHidesTheChipAndRefusesPickTab() {
        val start = state()
        val detail = start.enterDetail()
        assertEquals(ParentingChromeMode.Detail, detail.mode)
        assertTrue(detail.naturalPresented)
        assertFalse(detail.animating)
        assertTrue(detail.showComposerSlot)
        assertFalse(detail.showPickTabSlot)
        assertFalse(detail.typeChipVisible(true))
        assertSame(detail, detail.tapTypeChip(true))
        assertSame(detail, detail.dismissPickTab())

        val back = detail.dismissDetail()
        assertEquals(ParentingChromeMode.Input, back.mode)
        assertFalse(back.naturalPresented)
        assertTrue(back.typeChipVisible(true))
        assertSame(back, back.dismissDetail())
    }

    @Test
    fun focusCancelsAnInFlightMorphAndDismissesDetail() {
        val resigning = state().tapTypeChip(true)
        val focused = resigning.onComposerFocused()
        assertEquals(ParentingChromeMode.Input, focused.mode)
        assertFalse(focused.animating)
        assertTrue(focused.transitionId != resigning.transitionId)
        val idle = state()
        assertSame(idle, idle.onComposerFocused())

        val detail = state().enterDetail()
        val fromDetail = detail.onComposerFocused()
        assertEquals(ParentingChromeMode.Input, fromDetail.mode)
        assertFalse(fromDetail.naturalPresented)
        assertTrue(fromDetail.typeChipVisible(true))

        val picking = state().tapTypeChip(true).advance(300)
        val fromPick = picking.onComposerFocused()
        assertEquals(ParentingChromeMode.Input, fromPick.mode)
        assertFalse(fromPick.animating)
    }

    @Test
    fun submitDuringPickTabCancelsIntoDetail() {
        val picking = state().tapTypeChip(true).advance(40)
        assertTrue(picking.animating)
        val detail = picking.enterDetail()
        assertEquals(ParentingChromeMode.Detail, detail.mode)
        assertTrue(detail.naturalPresented)
        assertFalse(detail.animating)
        assertFalse(detail.typeChipVisible(true))
        assertSame(detail, detail.tapTypeChip(true))
    }

    @Test
    fun reduceMotionIsASingle120msLockAndChipTapRequiresAComposer() {
        val start = state()
        assertSame(start, start.tapTypeChip(showsComposer = false))
        val reduced = state(reduceMotion = true).tapTypeChip(true)
        assertEquals(ParentingChromeMode.PickTab, reduced.mode)
        assertEquals(1, reduced.resignFocusToken)
        assertEquals(120L, reduced.stepMs)
        assertTrue(reduced.animating)
        val open = reduced.advance(120)
        assertFalse(open.animating)
        val back = open.dismissPickTab()
        assertEquals(120L, back.stepMs)
        assertEquals(ParentingChromeMode.Input, back.mode)
    }

    @Test
    fun revealLeavesPickTabWithoutDroppingDetail() {
        val picking = state().tapTypeChip(true).advance(300)
        val today = picking.reveal(Tab.Today)
        assertEquals(ParentingChromeMode.Input, today.mode)
        assertFalse(today.animating)
        assertEquals(Tab.Today, today.tab)

        val detail = state(Tab.Growth).enterDetail().reveal(Tab.Today)
        assertEquals(ParentingChromeMode.Detail, detail.mode)
        assertTrue(detail.naturalPresented)
        assertEquals(Tab.Today, detail.tab)
    }
}
