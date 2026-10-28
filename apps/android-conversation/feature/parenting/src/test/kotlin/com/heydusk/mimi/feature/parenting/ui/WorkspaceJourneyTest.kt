package com.heydusk.mimi.feature.parenting.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.heydusk.mimi.domain.parenting.ParentingArrangementState
import com.heydusk.mimi.domain.parenting.ParentingRecordKind
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 对标 iOS `ParentingWorkspaceUITests` 的账本旅程：真实 Compose 界面 + 真实加密账本；
 * 「冷启动」= 同一目录与密钥上重建会话和整棵 UI，只能读回磁盘上的密文。
 * 没有 AI 整理（M5）和语音输入卡（M3），所以安排由手动表单创建；其余断言与 iOS 一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-w411dp-h891dp")
class WorkspaceJourneyTest {
    @get:Rule val rule = createComposeRule()
    private val h = JourneyHarness()
    private val ui = UiDriver(rule)

    @Before fun start() {
        h.launch()
        rule.setContent { h.Host() }
        ui.await("empty ledger loaded") { ui.exists("parenting.hero.addChild") }
    }

    @After fun stop() = h.close()

    private fun coldRelaunch() {
        h.launch()
        ui.await("ledger reloaded") { ui.exists("parenting.tabBar") && h.model()?.loaded == true }
    }

    private fun addChild(name: String) {
        ui.click("parenting.hero.addChild")
        ui.type("parenting.child.name", name)
        ui.click("parenting.name.save")
        ui.await("child saved") { !ui.exists("parenting.child.name") && h.workspace.children.isNotEmpty() }
        ui.await("today entry") { ui.exists("parenting.today.allArrangements") }
    }

    private fun addGoal(goal: String) {
        ui.click("parenting.tab.growth")
        ui.click("parenting.growth.mode.goals")
        ui.click("parenting.addGoal")
        ui.type("parenting.goal.title", goal)
        ui.click("parenting.goal.save")
        ui.await("goal saved") { !ui.exists("parenting.goal.title") && h.workspace.goals.isNotEmpty() }
    }

    private fun showAllArrangements() {
        ui.click("parenting.tab.today")
        if (!ui.exists("parenting.today.addArrangement")) ui.click("parenting.today.allArrangements")
        ui.await("all page") { ui.exists("parenting.today.addArrangement") }
    }

    /** 手动表单：标题、（可选）目标、明确选时间、保存。 */
    private fun createArrangement(title: String, goal: String? = null, source: String = "") {
        showAllArrangements()
        ui.click("parenting.today.addArrangement")
        ui.type("parenting.arrangement.title", title)
        if (goal != null) {
            ui.click("parenting.editor.goal")
            ui.click("parenting.editor.goal.option.${h.workspace.goals.first { it.title == goal }.id}")
        }
        if (source.isNotEmpty()) ui.type("parenting.arrangement.source", source)
        // 没有明确选时间之前不能保存
        assertFalse(ui.clickable("parenting.arrangement.save"))
        ui.click("parenting.arrangement.time")
        ui.click("parenting.time.confirm")
        ui.await("save enabled") { ui.clickable("parenting.arrangement.save") }
        ui.click("parenting.arrangement.save")
        ui.await("arrangement saved") { !ui.exists("parenting.arrangement.title") && h.workspace.arrangements.isNotEmpty() }
    }

    private fun confirm(id: UUID) {
        ui.click("parenting.confirm.$id")
        ui.await("planned") { ui.exists("parenting.complete.$id") }
    }

    private fun openAdjust(id: UUID) {
        ui.click("parenting.adjust.$id")
        ui.await("adjust form") { ui.exists("parenting.arrangement.title") }
    }

    private fun showCompleted() {
        showAllArrangements()
        if (!ui.exists("parenting.arrangement.completed.${h.workspace.arrangements.single().id}")) ui.click("parenting.completedHistory")
    }

    // 1 ───────────────────────────────────────────────────────────────────────────────────
    @Test fun parentingJourneyPersistsAcrossColdRelaunch() {
        val goal = "Synthetic shared reading goal"
        val note = "Synthetic parent observed a question about the story."
        assertEquals(0, ui.countPrefix("parenting.goal."))
        assertEquals(0, ui.countPrefix("parenting.arrangement."))
        addChild("Synthetic Child")
        addGoal(goal)
        createArrangement("Read together", goal, source = "Read a short story together.")
        val id = h.workspace.arrangements.single().id
        val goalID = h.workspace.goals.single().id
        val saved = h.workspace.arrangements.single()
        assertEquals(ParentingArrangementState.Draft, saved.state)
        assertEquals(goalID, saved.goalID)
        assertEquals("Read a short story together.", saved.sourceText)

        // 草稿等待明确确认：没有完成按钮
        showAllArrangements()
        assertTrue(ui.exists("parenting.confirm.$id"))
        assertFalse(ui.exists("parenting.complete.$id"))
        confirm(id)
        assertFalse(ui.exists("parenting.confirm.$id"))
        val confirmedTime = ui.text("parenting.arrangement.time.$id")
        assertTrue(confirmedTime.isNotEmpty())
        assertTrue(ui.text("parenting.arrangement.goal.$id").contains(goal))

        // 调整标题与时间
        openAdjust(id)
        assertEquals("Read together", ui.text("parenting.arrangement.title"))
        assertFalse(ui.clickable("parenting.arrangement.save"), "未改动时不能保存调整")
        ui.type("parenting.arrangement.title", "Read together adjusted")
        ui.click("parenting.arrangement.time")
        ui.click("parenting.time.hour.plus")
        ui.click("parenting.time.confirm")
        ui.await("adjust save enabled") { ui.clickable("parenting.arrangement.save") }
        ui.click("parenting.arrangement.save")
        ui.await("adjusted") { !ui.exists("parenting.arrangement.title") && h.workspace.arrangements.single().title == "Read together adjusted" }
        val adjustedTime = ui.text("parenting.arrangement.time.$id")
        assertNotEquals(confirmedTime, adjustedTime)
        assertEquals(id, h.workspace.arrangements.single().id, "调整保持同一个安排")

        // 完成并写备注
        ui.click("parenting.complete.$id")
        ui.type("parenting.record.text", note)
        ui.click("parenting.record.save")
        ui.await("completed") { h.workspace.arrangements.single().state == ParentingArrangementState.Completed }
        assertFalse(ui.exists("parenting.complete.$id"))
        ui.click("parenting.completedHistory")
        ui.await("completed card") { ui.exists("parenting.arrangement.completed.$id") }
        assertEquals(adjustedTime, ui.text("parenting.arrangement.time.$id"), "完成保留安排原有时间")
        assertTrue(ui.text("parenting.arrangement.goal.$id").contains(goal))
        val record = h.workspace.records.single()
        assertEquals(ParentingRecordKind.Completion, record.kind)
        assertEquals(id, record.arrangementID)
        assertEquals(goalID, record.goalID)

        // 完成卡片上的记录链接 → 成长时间线里同一条记录
        ui.click("parenting.completionRecord.${record.id}")
        ui.await("timeline") { ui.exists("parenting.record.body.${record.id}") }
        assertEquals(note, ui.text("parenting.record.body.${record.id}"))
        assertTrue(ui.text("parenting.record.goal.${record.id}").contains(goal))

        // 目标回顾里也是同一条记录
        ui.click("parenting.tab.growth")
        ui.click("parenting.growth.mode.goals")
        ui.click("parenting.goal.$goalID")
        ui.await("goal review") { ui.exists("parenting.goal.review.title") }
        assertEquals(note, ui.text("parenting.record.body.${record.id}"))

        // 冷启动：同一 UUID、同一时间、同一完成状态、同一记录
        coldRelaunch()
        assertEquals(1, h.workspace.arrangements.size)
        showAllArrangements()
        ui.click("parenting.completedHistory")
        ui.await("reopened completed") { ui.exists("parenting.arrangement.completed.$id") }
        assertEquals(adjustedTime, ui.text("parenting.arrangement.time.$id"))
        assertFalse(ui.exists("parenting.confirm.$id"))
        assertFalse(ui.exists("parenting.complete.$id"))
        assertFalse(ui.exists("parenting.adjust.$id"))
        assertTrue(ui.text("parenting.arrangement.goal.$id").contains(goal))
        ui.click("parenting.completionRecord.${record.id}")
        ui.await("reopened record") { ui.exists("parenting.record.body.${record.id}") }
        assertEquals(note, ui.text("parenting.record.body.${record.id}"))
    }

    // 2 ───────────────────────────────────────────────────────────────────────────────────
    @Test fun rescheduleAndUndoPersistAcrossColdRelaunch() {
        val goal = "Synthetic retained growth goal"
        val change = "Change to 2027-02-18T17:00:00Z."
        addChild("Synthetic Reschedule Child")
        addGoal(goal)
        createArrangement("Synthetic original arrangement", goal, source = "Synthetic original request remains the arrangement source.")
        val id = h.workspace.arrangements.single().id
        val goalID = h.workspace.goals.single().id
        confirm(id)
        val originalTime = ui.text("parenting.arrangement.time.$id")

        openAdjust(id)
        // 调整说明默认留空：不会悄悄重发原话
        assertEquals("", ui.text("parenting.arrangement.source"))
        ui.type("parenting.arrangement.source", change)
        assertFalse(ui.clickable("parenting.arrangement.save"), "没有改动前核对区不出现、保存禁用")
        ui.click("parenting.arrangement.time")
        ui.click("parenting.time.day.plus")
        ui.click("parenting.time.confirm")
        ui.await("review shown") { ui.exists("parenting.adjustment.review") }
        assertNotEquals(ui.text("parenting.adjustment.candidate.originalTime"), ui.text("parenting.adjustment.proposedTime"))
        ui.click("parenting.arrangement.save")
        ui.await("revised") { !ui.exists("parenting.arrangement.title") && h.workspace.editHistory.size == 1 }
        val changedTime = ui.text("parenting.arrangement.time.$id")
        assertNotEquals(originalTime, changedTime)
        assertEquals("Synthetic original arrangement", h.workspace.arrangements.single().title, "调整不换标题")
        assertEquals(goalID, h.workspace.arrangements.single().goalID)
        assertEquals(id, h.workspace.arrangements.single().id)
        val editID = h.workspace.editHistory.single().id

        coldRelaunch()
        showAllArrangements()
        assertEquals(changedTime, ui.text("parenting.arrangement.time.$id"))
        ui.click("parenting.adjustmentHistory.$id")
        ui.await("history") { ui.exists("parenting.adjustment.source.$editID") }
        assertEquals(change, ui.text("parenting.adjustment.source.$editID"))

        ui.click("parenting.arrangement.menu.$id")
        ui.click("parenting.undoAdjustment.$id")
        ui.await("undone") { ui.text("parenting.arrangement.time.$id") == originalTime }
        assertTrue(ui.exists("parenting.complete.$id"))

        coldRelaunch()
        showAllArrangements()
        assertEquals(originalTime, ui.text("parenting.arrangement.time.$id"))
        ui.click("parenting.adjustmentHistory.$id")
        ui.await("history again") { ui.exists("parenting.adjustment.source.$editID") }
        assertEquals(change, ui.text("parenting.adjustment.source.$editID"))
        assertTrue(ui.exists("parenting.adjustment.reverted.$editID"))
        ui.click("parenting.tab.growth")
        ui.click("parenting.growth.mode.timeline")
        assertEquals(0, ui.countPrefix("parenting.record.body."), "改期与撤销都不会生成完成记录")
        assertEquals(0, h.workspace.records.size)
    }

    // 10 ──────────────────────────────────────────────────────────────────────────────────
    @Test fun plannedCancellationPersistsAcrossColdRelaunch() {
        val title = "Synthetic arrangement to cancel"
        addChild("Synthetic Cancellation Child")
        createArrangement(title)
        val id = h.workspace.arrangements.single().id
        assertTrue(ui.text("parenting.storageBoundary").isNotEmpty())
        confirm(id)
        val plannedTime = ui.text("parenting.arrangement.time.$id")

        ui.click("parenting.arrangement.menu.$id")
        ui.click("parenting.arrangement.action.cancel.$id")
        ui.click("parenting.confirm.dialog.ok")
        ui.await("cancelled") { h.workspace.arrangements.single().state == ParentingArrangementState.Cancelled }
        assertFalse(ui.exists("parenting.complete.$id"))
        ui.click("parenting.cancelledHistory")
        ui.await("cancelled card") { ui.exists("parenting.arrangement.cancelled.$id") }
        assertEquals(plannedTime, ui.text("parenting.arrangement.time.$id"))
        assertFalse(ui.exists("parenting.confirm.$id") || ui.exists("parenting.complete.$id") || ui.exists("parenting.adjust.$id"))
        assertEquals(0, ui.countPrefix("parenting.completionRecord."))
        assertEquals(0, h.workspace.records.size, "取消不生成完成记录")

        coldRelaunch()
        showAllArrangements()
        ui.click("parenting.cancelledHistory")
        ui.await("cancelled after relaunch") { ui.exists("parenting.arrangement.cancelled.$id") }
        assertEquals(plannedTime, ui.text("parenting.arrangement.time.$id"))
        assertEquals(0, h.workspace.records.size)
    }

    // 11 ──────────────────────────────────────────────────────────────────────────────────
    @Test fun observationEditAndDeleteAcrossColdReads() {
        val goal = "Synthetic everyday observation goal"
        val original = "Synthetic parent noticed a question during a story."
        addChild("Synthetic Observation Child")
        addGoal(goal)
        val goalID = h.workspace.goals.single().id
        ui.click("parenting.goal.$goalID")
        ui.click("parenting.addObservation.forGoal")
        ui.type("parenting.record.text", original)
        ui.click("parenting.record.save")
        ui.await("record saved") { h.workspace.records.size == 1 }
        val rid = h.workspace.records.single().id
        assertEquals(goalID, h.workspace.records.single().goalID)
        ui.await("review shows record") { ui.exists("parenting.record.body.$rid") }
        assertEquals(original, ui.text("parenting.record.body.$rid"))
        assertTrue(ui.text("parenting.record.goal.$rid").contains(goal))
        assertEquals(0, h.workspace.arrangements.size)

        ui.click("parenting.record.menu.$rid")
        ui.click("parenting.record.action.edit.$rid")
        assertEquals(original, ui.text("parenting.record.text"))
        ui.type("parenting.record.text", "$original Corrected after checking.")
        ui.click("parenting.record.save")
        val corrected = "$original Corrected after checking."
        ui.await("corrected") { h.workspace.records.single().text == corrected }
        assertEquals(corrected, ui.text("parenting.record.body.$rid"))
        // 原话保留、有改动时间
        ui.click("parenting.record.provenance.$rid")
        ui.await("source") { ui.exists("parenting.record.source.$rid") }
        assertEquals(original, ui.text("parenting.record.source.$rid"))
        assertTrue(ui.exists("parenting.record.correctedAt.$rid"))

        coldRelaunch()
        ui.click("parenting.tab.growth")
        ui.click("parenting.growth.mode.timeline")
        ui.await("record after relaunch") { ui.exists("parenting.record.body.$rid") }
        assertEquals(corrected, ui.text("parenting.record.body.$rid"))
        assertTrue(ui.text("parenting.record.goal.$rid").contains(goal))
        ui.click("parenting.record.provenance.$rid")
        ui.await("source after relaunch") { ui.exists("parenting.record.source.$rid") }
        assertEquals(original, ui.text("parenting.record.source.$rid"))

        ui.click("parenting.record.menu.$rid")
        ui.click("parenting.record.action.delete.$rid")
        ui.click("parenting.confirm.dialog.ok")
        ui.await("deleted") { h.workspace.records.isEmpty() }
        assertFalse(ui.exists("parenting.record.body.$rid"))

        coldRelaunch()
        ui.click("parenting.tab.growth")
        ui.click("parenting.growth.mode.timeline")
        assertEquals(0, ui.countPrefix("parenting.record.body."))
        ui.click("parenting.back")
        ui.click("parenting.growth.mode.goals")
        ui.await("goal retained") { ui.exists("parenting.goal.$goalID") }
        assertEquals(1, h.workspace.children.size)
        assertEquals(0, h.workspace.arrangements.size)
    }

    // 12 ──────────────────────────────────────────────────────────────────────────────────
    @Test fun ledgerWriteFailureRetainsInputRetriesAndColdReadsOneChild() {
        val name = "Synthetic Rollback Child"
        h.blobs.failNextWrites = 1
        ui.click("parenting.hero.addChild")
        ui.type("parenting.child.name", name)
        ui.click("parenting.name.save")
        ui.await("form error") { ui.exists("parenting.form.error") }
        assertTrue(ui.text("parenting.form.error").contains("无法保存"))
        assertEquals(name, ui.text("parenting.child.name"), "失败后原输入保留")
        assertEquals(0, h.workspace.children.size)
        ui.await("save re-enabled") { ui.clickable("parenting.name.save") }
        ui.click("parenting.name.save")
        ui.await("retry saved") { !ui.exists("parenting.child.name") && h.workspace.children.size == 1 }
        assertFalse(ui.exists("parenting.form.error"))
        assertEquals(0, ui.countPrefix("parenting.arrangement."))

        coldRelaunch()
        assertEquals(1, h.workspace.children.size)
        assertFalse(ui.exists("parenting.hero.addChild"))
        ui.click("parenting.tab.growth")
        ui.await("filter") { ui.exists("parenting.childFilter") }
        assertEquals(1, ui.countPrefix("parenting.childFilter.").let { it })
        assertEquals(name, h.workspace.children.single().name)
    }
}
