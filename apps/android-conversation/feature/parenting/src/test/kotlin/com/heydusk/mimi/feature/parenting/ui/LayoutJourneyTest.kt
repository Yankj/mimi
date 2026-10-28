package com.heydusk.mimi.feature.parenting.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.heydusk.mimi.domain.parenting.ParentingCommand
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 对标 iOS 的布局类旅程：三 Tab 各自的标题、大标题折叠、悬浮底栏不遮最后一项、手动表单放弃无草稿、200% 字体 + 深色冒烟。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-w411dp-h891dp")
class LayoutJourneyTest {
    @get:Rule val rule = createComposeRule()
    private val h = JourneyHarness()
    private val ui = UiDriver(rule)

    @After fun stop() = h.close()

    private fun start(dark: Boolean = false, fontScale: Float = 1f) {
        h.launch()
        rule.setContent { h.Host(dark = dark, fontScale = fontScale) }
        ui.await("empty ledger loaded") { ui.exists("parenting.hero.addChild") }
    }

    private fun seedChild(arrangements: Int = 0): UUID {
        val child = UUID.randomUUID()
        val base = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(3, ChronoUnit.HOURS)
        h.seed(ParentingCommand.AddChild(child, "Synthetic Layout Child"))
        for (i in 0 until arrangements) {
            val id = UUID.randomUUID()
            h.seed(
                ParentingCommand.CreateDraft(id, child, "Synthetic arrangement $i", base.plus(i.toLong(), ChronoUnit.DAYS), null, "Synthetic source $i"),
                ParentingCommand.ConfirmArrangement(id),
            )
        }
        ui.await("today entry") { ui.exists("parenting.today.allArrangements") }
        return child
    }

    private fun openAll() {
        ui.click("parenting.today.allArrangements")
        ui.await("all page") { ui.exists("parenting.today.addArrangement") }
    }

    @Test fun threeTabsAreReachableEachWithItsOwnTitle() {
        start()
        assertTrue(ui.exists("parenting.tab.family"), "底栏一开始就在")
        seedChild()
        val titles = mutableListOf<String>()
        for (tab in listOf("today", "growth", "family", "today")) {
            ui.click("parenting.tab.$tab")
            ui.await("page $tab") { ui.exists("parenting.page.$tab") }
            titles += ui.text("parenting.title")
        }
        assertEquals(titles[0], titles[3])
        assertEquals(3, titles.take(3).toSet().size, "三个 Tab 各有自己的大标题: $titles")
    }

    @Test fun largeTitleCollapsesIntoTheInlineBarWhenScrolledAndComesBack() {
        start()
        seedChild(arrangements = 12)
        openAll()
        assertTrue(ui.exists("parenting.title"))
        assertFalse(ui.exists("parenting.title.inline"), "页面顶部只有大标题")
        val page = rule.onNodeWithTag("parenting.page.today")
        page.performTouchInput { swipeUp() }
        page.performTouchInput { swipeUp() }
        ui.await("collapsed") { ui.exists("parenting.title.inline") }
        assertEquals(ui.text("parenting.title.inline"), "全部安排")
        repeat(4) { page.performTouchInput { swipeDown() } }
        ui.await("expanded") { !ui.exists("parenting.title.inline") }
    }

    @Test fun floatingTabBarNeverCoversTheLastItem() {
        start()
        seedChild(arrangements = 12)
        openAll()
        val barTop = ui.top("parenting.tabBar")
        val page = rule.onNodeWithTag("parenting.page.today")
        repeat(6) { page.performTouchInput { swipeUp() } }
        ui.await("end of page") { ui.exists("parenting.storageBoundary") }
        rule.waitForIdle()
        assertTrue(ui.bottom("parenting.storageBoundary") <= barTop + 1, "页面末尾的最后一项必须停在悬浮底栏之上")
        rule.onNodeWithTag("parenting.tabBar").assertIsDisplayed()
    }

    @Test fun manualArrangementFormDiscardLeavesNoDraft() {
        start()
        seedChild()
        openAll()
        ui.click("parenting.today.addArrangement")
        ui.type("parenting.arrangement.source", "Synthetic manual entry that is discarded.")
        assertFalse(ui.clickable("parenting.arrangement.save"), "没有标题和明确时间之前不能保存")
        ui.click("parenting.arrangement.cancel")
        ui.click("parenting.arrangement.discard") // 有改动：先确认放弃
        ui.await("form closed") { !ui.exists("parenting.arrangement.title") }
        assertEquals(0, h.workspace.arrangements.size, "放弃不留任何草稿")
        assertTrue(ui.exists("parenting.all.empty"))
        // 没有任何改动时直接关
        ui.click("parenting.today.addArrangement")
        ui.click("parenting.arrangement.cancel")
        ui.await("closed without confirm") { !ui.exists("parenting.arrangement.title") }
        assertFalse(ui.exists("parenting.arrangement.discard"))
    }

    @Test fun darkTheme200PercentFontEmptyAndEditorSmoke() {
        start(dark = true, fontScale = 2f)
        assertTrue(ui.exists("parenting.hero.addChild"))
        for (tab in listOf("today", "growth", "family")) rule.onNodeWithTag("parenting.tab.$tab").assertIsDisplayed()
        // 空账本的三个 Tab 都能打开
        for (tab in listOf("growth", "family", "today")) {
            ui.click("parenting.tab.$tab")
            ui.await("page $tab") { ui.exists("parenting.page.$tab") && ui.exists("parenting.title") }
        }
        seedChild()
        rule.onNodeWithTag("parenting.tabBar").assertIsDisplayed()
        openAll()
        ui.click("parenting.today.addArrangement")
        val long = "Synthetic long text remains editable and can be discarded. ".repeat(8)
        ui.type("parenting.arrangement.source", long)
        assertEquals(long, ui.text("parenting.arrangement.source"))
        assertFalse(ui.clickable("parenting.arrangement.save"))
        ui.click("parenting.arrangement.cancel")
        ui.click("parenting.arrangement.discard")
        ui.await("discarded") { !ui.exists("parenting.arrangement.title") }
        assertEquals(0, h.workspace.arrangements.size)
        assertNotEquals(0, ui.top("parenting.tabBar").toInt())
    }
}
