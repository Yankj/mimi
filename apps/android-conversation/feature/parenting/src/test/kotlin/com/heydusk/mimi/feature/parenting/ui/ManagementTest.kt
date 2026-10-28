package com.heydusk.mimi.feature.parenting.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.heydusk.mimi.domain.parenting.ParentingArrangementState
import com.heydusk.mimi.domain.parenting.ParentingCommand
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M2b 新入口（改名、删除孩子/目标/安排、同一时间冲突确认、孩子筛选）——真实 Compose + 真实加密账本。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-w411dp-h891dp")
class ManagementTest {
    @get:Rule val rule = createComposeRule()
    private val h = JourneyHarness()
    private val ui = UiDriver(rule)
    private val base: Instant = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(3, ChronoUnit.HOURS)

    @After fun stop() = h.close()

    private fun start() {
        h.launch()
        rule.setContent { h.Host() }
        ui.await("empty ledger loaded") { ui.exists("parenting.hero.addChild") }
    }

    private fun child(name: String): UUID = UUID.randomUUID().also { h.seed(ParentingCommand.AddChild(it, name)) }
    private fun planned(childID: UUID, title: String, at: Instant, goal: UUID? = null): UUID =
        UUID.randomUUID().also { h.seed(ParentingCommand.CreateDraft(it, childID, title, at, goal, "src"), ParentingCommand.ConfirmArrangement(it)) }

    private fun openAll() {
        ui.click("parenting.tab.today")
        if (!ui.exists("parenting.today.addArrangement")) ui.click("parenting.today.allArrangements")
        ui.await("all page") { ui.exists("parenting.today.addArrangement") }
    }

    @Test fun renameChildFromTheDetailPage() {
        start()
        val c = child("Synthetic Old Name")
        ui.click("parenting.tab.family")
        ui.click("parenting.family.child.$c")
        ui.await("detail") { ui.exists("parenting.child.rename") }
        ui.click("parenting.child.rename")
        assertEquals("Synthetic Old Name", ui.text("parenting.child.name"))
        ui.type("parenting.child.name", "Synthetic New Name")
        ui.click("parenting.name.save")
        ui.await("renamed") { h.workspace.children.single().name == "Synthetic New Name" }
        ui.await("form closed") { !ui.exists("parenting.child.name") }
        h.launch()
        ui.await("reloaded") { h.model()?.loaded == true }
        assertEquals("Synthetic New Name", h.workspace.children.single().name)
    }

    @Test fun deleteArrangementAsksFirstAndKeepsTheRest() {
        start()
        val c = child("Synthetic Delete Child")
        val keep = planned(c, "keep", base)
        val drop = planned(c, "drop", base.plus(1, ChronoUnit.DAYS))
        openAll()
        ui.click("parenting.arrangement.menu.$drop")
        ui.click("parenting.arrangement.action.delete.$drop")
        // 先问：取消不删
        ui.click("parenting.confirm.dialog.cancel")
        assertEquals(2, h.workspace.arrangements.size)
        ui.click("parenting.arrangement.menu.$drop")
        ui.click("parenting.arrangement.action.delete.$drop")
        ui.click("parenting.confirm.dialog.ok")
        ui.await("deleted") { h.workspace.arrangements.size == 1 }
        assertEquals(keep, h.workspace.arrangements.single().id)
        ui.await("card gone") { !ui.exists("parenting.arrangement.$drop") }
    }

    @Test fun deleteGoalKeepsLinkedArrangementsUnlinked() {
        start()
        val c = child("Synthetic Goal Child")
        val g = UUID.randomUUID()
        h.seed(ParentingCommand.AddGoal(g, c, "Synthetic goal to delete"))
        val a = planned(c, "linked", base, g)
        ui.click("parenting.tab.growth")
        ui.click("parenting.growth.mode.goals")
        ui.click("parenting.goal.$g")
        ui.click("parenting.goal.delete")
        ui.click("parenting.confirm.dialog.ok")
        ui.await("goal deleted") { h.workspace.goals.isEmpty() }
        assertEquals(1, h.workspace.arrangements.size)
        assertNull(h.workspace.arrangements.single { it.id == a }.goalID)
        ui.await("back on goals list") { ui.exists("parenting.growth.noGoals") }
    }

    @Test fun deleteChildRemovesTheirDataAfterConfirmation() {
        start()
        val c = child("Synthetic Gone Child")
        planned(c, "gone with the child", base)
        ui.click("parenting.tab.family")
        ui.click("parenting.family.child.$c")
        ui.click("parenting.child.delete")
        ui.click("parenting.confirm.dialog.cancel")
        assertEquals(1, h.workspace.children.size)
        ui.click("parenting.child.delete")
        ui.click("parenting.confirm.dialog.ok")
        ui.await("child deleted") { h.workspace.children.isEmpty() }
        assertEquals(0, h.workspace.arrangements.size)
        ui.await("back to the empty family tab") { !ui.exists("parenting.child.delete") }
    }

    @Test fun adjustingOntoAnotherPlannedTimeNeedsAcknowledgement() {
        start()
        val c = child("Synthetic Conflict Child")
        val a = planned(c, "first", base)
        val b = planned(c, "second", base.plus(1, ChronoUnit.DAYS))
        openAll()
        ui.click("parenting.adjust.$b")
        ui.click("parenting.arrangement.time")
        ui.click("parenting.time.day.minus")
        ui.click("parenting.time.confirm")
        ui.await("conflicts shown") { ui.exists("parenting.adjustment.conflicts") }
        assertFalse(ui.clickable("parenting.arrangement.save"), "同一时间已有安排：确认前不能保存")
        ui.click("parenting.adjustment.conflict.ack")
        ui.await("save enabled") { ui.clickable("parenting.arrangement.save") }
        ui.click("parenting.arrangement.save")
        ui.await("revised") { h.workspace.arrangements.first { it.id == b }.dueAt == base }
        assertEquals(ParentingArrangementState.Planned, h.workspace.arrangements.first { it.id == b }.state)
        assertEquals(base, h.workspace.arrangements.first { it.id == a }.dueAt, "原有安排不动")
        // 调整之后别的安排占了 b 原来的时间 → 撤销要先确认冲突（弹确认）；取消则不撤销
        planned(c, "took the old slot", base.plus(1, ChronoUnit.DAYS))
        ui.click("parenting.arrangement.menu.$b")
        ui.click("parenting.undoAdjustment.$b")
        ui.await("undo asks") { ui.exists("parenting.confirm.dialog") }
        ui.click("parenting.confirm.dialog.cancel")
        assertEquals(base, h.workspace.arrangements.first { it.id == b }.dueAt)
        ui.click("parenting.arrangement.menu.$b")
        ui.click("parenting.undoAdjustment.$b")
        ui.await("undo asks again") { ui.exists("parenting.confirm.dialog") }
        ui.click("parenting.confirm.dialog.ok")
        ui.await("undone") { h.workspace.arrangements.first { it.id == b }.dueAt == base.plus(1, ChronoUnit.DAYS) }
    }

    @Test fun childFilterScopesTheArrangementList() {
        start()
        val c1 = child("Synthetic First Child")
        val c2 = child("Synthetic Second Child")
        val a1 = planned(c1, "for first", base)
        val a2 = planned(c2, "for second", base.plus(1, ChronoUnit.DAYS))
        openAll()
        val first = ui.exists("parenting.arrangement.$a1")
        val second = ui.exists("parenting.arrangement.$a2")
        assertTrue(first != second, "筛选一次只显示一个孩子的安排")
        val other = if (first) c2 else c1
        ui.click("parenting.childFilter.$other")
        ui.await("switched") { ui.exists("parenting.arrangement.${if (first) a2 else a1}") }
        assertFalse(ui.exists("parenting.arrangement.${if (first) a1 else a2}"))
    }
}
