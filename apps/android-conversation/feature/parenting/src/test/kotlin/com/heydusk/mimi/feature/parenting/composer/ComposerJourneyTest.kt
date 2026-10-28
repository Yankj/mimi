package com.heydusk.mimi.feature.parenting.composer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import com.heydusk.mimi.domain.dictation.DictationEngineTier
import com.heydusk.mimi.domain.parenting.ParentingArrangementState
import com.heydusk.mimi.domain.parenting.ParentingCommand
import com.heydusk.mimi.feature.parenting.ui.JourneyHarness
import com.heydusk.mimi.feature.parenting.ui.UiDriver
import com.heydusk.mimi.voice.DictationEnd
import com.heydusk.mimi.voice.MicrophonePermission
import com.heydusk.mimi.voice.ScriptedDictationEngine
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class FakePermission(var granted: Boolean = true, var grantOnRequest: Boolean = true) : MicrophonePermission {
    var requests = 0
    override fun isGranted() = granted
    override fun request(onResult: (Boolean) -> Unit) {
        requests += 1
        if (grantOnRequest) granted = true
        onResult(granted)
    }
}

/**
 * 对标 iOS `ParentingWorkspaceUITests` 里属于 M3 的旅程：#8 语音优先输入卡与 + 菜单、#14 确认卡与回执自动收起，
 * 以及 #4/#5/#6/#13 中「说一句话 → 理解 → 确认卡 → 保存」的那一半（云端 AI 整理在 M5）。
 * 真实 Compose 界面 + 真实加密账本；麦克风由 [ScriptedDictationEngine] 代替。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-w411dp-h891dp")
class ComposerJourneyTest {
    @get:Rule val rule = createComposeRule()
    private val h = JourneyHarness()
    private val ui = UiDriver(rule)
    private val engine = ScriptedDictationEngine()
    private val permission = FakePermission()
    private val prefs = VoicePrefs.InMemory(explained = true)
    private var settingsOpened = 0
    private val child = UUID.randomUUID()

    private fun deps(receiptMillis: Long = 0, noticeMillis: Long = 60_000) = VoiceDeps(
        engine = engine, prefs = prefs, permission = permission, openAppSettings = { settingsOpened += 1 },
        noticeMillis = noticeMillis, receiptMillis = receiptMillis,
    )

    private fun boot(deps: VoiceDeps = deps(), seed: List<ParentingCommand> = listOf(ParentingCommand.AddChild(child, "团团"))) {
        h.launch()
        rule.setContent { h.Host(voice = deps, now = NOW) }
        ui.await("ledger loaded") { h.model()?.loaded == true }
        if (seed.isNotEmpty()) h.seed(*seed.toTypedArray())
        if (seed.isNotEmpty()) ui.await("composer") { ui.exists("parenting.composer.mic") || ui.exists("parenting.natural.field") }
    }

    @After fun stop() = h.close()

    private fun showKeyboard() {
        ui.click("parenting.composer.keyboardToggle")
        ui.await("text field") { ui.exists("parenting.natural.field") }
    }

    private fun typeAndSend(text: String) {
        if (!ui.exists("parenting.natural.field")) showKeyboard()
        ui.type("parenting.natural.field", text)
        ui.await("send enabled") { ui.exists("parenting.natural.send") }
        ui.click("parenting.natural.send")
    }

    private fun arrangementsOf(title: String) = h.workspace.arrangements.filter { it.title == title }

    // 旅程 8：语音优先的空闲布局与 + 菜单

    @Test fun composerIsVoiceFirstWithPlusMenuAndManualFormsLast() {
        boot()
        assertTrue(ui.exists("parenting.composer.mic"))
        assertTrue(ui.exists("parenting.composer.keyboardToggle"))
        assertFalse(ui.exists("parenting.natural.field"), "空闲时只有按住说话的胶囊，没有文字框")
        ui.click("parenting.plus.menu")
        ui.await("menu") { ui.exists("parenting.plus.manualArrangement") }
        assertTrue(ui.exists("parenting.plus.manualObservation"), "手动表单排在 + 菜单最后")
        ui.click("parenting.plus.manualObservation")
        ui.await("manual observation form") { ui.exists("parenting.record.text") }
        ui.click("parenting.form.discard")
        ui.await("form closed") { !ui.exists("parenting.record.text") }
        assertTrue(h.workspace.records.isEmpty() && h.workspace.arrangements.isEmpty(), "放弃表单零写入")
        showKeyboard()
        assertTrue(ui.exists("parenting.natural.field"))
    }

    // 旅程 14：键盘 → 交给小咪 → 确认卡 → 确认 → 回执自动收起

    @Test fun typedSentenceBecomesConfirmCardAndOnlyConfirmWrites() {
        boot(deps(receiptMillis = 1_600))
        val revision = h.workspace.revision
        typeAndSend("团团今天下午5点游泳")
        ui.await("confirm card") { ui.exists("parenting.natural.confirm") }
        assertTrue(ui.exists("parenting.confirmCard.newArrangement"))
        assertTrue(ui.text("parenting.confirmCard.field.1").contains("游泳"), ui.text("parenting.confirmCard.field.1"))
        assertEquals(revision, h.workspace.revision, "确认之前零写入")
        assertTrue(h.workspace.arrangements.isEmpty())
        ui.click("parenting.natural.confirm")
        ui.await("saved and confirmed") { arrangementsOf("游泳").singleOrNull()?.state == ParentingArrangementState.Planned }
        ui.await("receipt") { ui.exists("parenting.natural.receipt") }
        rule.mainClock.advanceTimeBy(2_000)
        ui.await("receipt auto-dismissed") { !ui.exists("parenting.natural.sheet") }
        assertEquals("", ui.text("parenting.natural.field"), "保存后输入框清空")
    }

    @Test fun receiptStaysUntilTappedWhenAutoDismissIsOff() {
        boot(deps(receiptMillis = 0))
        typeAndSend("记一下，团团今天自己收好了书包")
        ui.await("card") { ui.exists("parenting.confirmCard.observation") }
        ui.click("parenting.natural.confirm")
        ui.await("saved") { h.workspace.records.size == 1 }
        ui.await("done") { ui.exists("parenting.natural.done") }
        rule.mainClock.advanceTimeBy(5_000)
        assertTrue(ui.exists("parenting.natural.done"))
        ui.click("parenting.natural.done")
        ui.await("closed") { !ui.exists("parenting.natural.sheet") }
    }

    @Test fun closingTheCardDiscardsWithZeroWrites() {
        boot()
        val revision = h.workspace.revision
        typeAndSend("团团今天下午5点游泳")
        ui.await("card") { ui.exists("parenting.natural.confirm") }
        ui.click("parenting.natural.close")
        ui.await("closed") { !ui.exists("parenting.natural.sheet") }
        assertEquals(revision, h.workspace.revision)
        assertEquals("", ui.text("parenting.natural.field"))
    }

    // 语音

    @Test fun tapMicDictatesIntoEditableTranscriptThenSendOpensTheCard() {
        boot()
        engine.autoTranscript = "团团今天下午5点游泳"
        val revision = h.workspace.revision
        ui.click("parenting.composer.mic")
        ui.await("listening") { ui.exists("parenting.composer.listening") }
        assertEquals("团团今天下午5点游泳", ui.text("parenting.composer.transcript"))
        ui.click("parenting.composer.listening") // 再点一下完成
        ui.await("preview") { ui.exists("parenting.composer.mic") && ui.exists("parenting.natural.send") }
        assertEquals("团团今天下午5点游泳", ui.text("parenting.composer.transcript"), "话留在可编辑的转写框里")
        assertEquals(revision, h.workspace.revision, "听写只是输入框里的字，零写入")
        ui.click("parenting.natural.send")
        ui.await("card") { ui.exists("parenting.natural.confirm") }
        assertEquals(revision, h.workspace.revision)
        ui.click("parenting.natural.confirm")
        ui.await("saved") { arrangementsOf("游泳").size == 1 }
    }

    @Test fun holdAndSlideUpSendsRightAway() {
        boot()
        engine.autoTranscript = "团团今天下午5点游泳"
        holdOn("parenting.composer.mic")
        ui.await("listening") { ui.exists("parenting.composer.listening") }
        assertTrue(hint().contains("上滑发送 · 左滑取消"), hint())
        rule.onNodeWithTagCompat("parenting.composer.listening").performTouchInput { moveBy(Offset(0f, -260f)) }
        ui.await("send zone") { hint().contains("松开发送") }
        rule.onNodeWithTagCompat("parenting.composer.listening").performTouchInput { up() }
        ui.await("card opened by swipe-up") { ui.exists("parenting.natural.confirm") }
        assertTrue(h.workspace.arrangements.isEmpty(), "上滑发送也只是打开确认卡")
        assertFalse(engine.running)
    }

    @Test fun holdAndSlideLeftCancelsAndRestoresTheDraft() {
        boot()
        engine.autoTranscript = "不该留下的话"
        holdOn("parenting.composer.mic")
        ui.await("listening") { ui.exists("parenting.composer.listening") }
        rule.onNodeWithTagCompat("parenting.composer.listening").performTouchInput { moveBy(Offset(-400f, 0f)) }
        ui.await("cancel zone") { hint().contains("松开取消") }
        rule.onNodeWithTagCompat("parenting.composer.listening").performTouchInput { up() }
        ui.await("back to idle") { ui.exists("parenting.composer.mic") }
        assertFalse(ui.exists("parenting.natural.sheet"))
        assertFalse(ui.exists("parenting.composer.transcript"), "取消后没有残留的转写")
        assertFalse(engine.running)
    }

    @Test fun holdAndReleaseKeepsTheWordsInThePreview() {
        boot()
        engine.autoTranscript = "记一下，团团今天自己穿了鞋"
        holdOn("parenting.composer.mic")
        ui.await("listening") { ui.exists("parenting.composer.listening") }
        rule.onNodeWithTagCompat("parenting.composer.listening").performTouchInput { up() }
        ui.await("preview") { ui.exists("parenting.composer.mic") && ui.exists("parenting.natural.send") }
        assertEquals("记一下，团团今天自己穿了鞋", ui.text("parenting.composer.transcript"))
        assertFalse(ui.exists("parenting.natural.sheet"))
    }

    @Test fun quickTapIsATapNotAHold() {
        boot()
        ui.click("parenting.composer.mic")
        ui.await("listening") { ui.exists("parenting.composer.listening") }
        assertTrue(hint().contains("再点一下完成"), hint())
    }

    // 首次说明与权限

    @Test fun firstPressExplainsBeforeAnySystemPrompt() {
        permission.granted = false
        boot(deps().let { VoiceDeps(engine, VoicePrefs.InMemory(false), permission, { settingsOpened += 1 }, noticeMillis = 60_000) })
        ui.click("parenting.composer.mic")
        ui.await("explanation") { ui.exists("parenting.composer.voiceExplain") }
        assertEquals(0, permission.requests, "说明之前不弹系统权限")
        assertEquals(0, engine.startCount)
        assertTrue(ui.text("parenting.composer.voiceExplain.text").contains("只在你的手机上完成"))
        ui.click("parenting.composer.voiceExplain.continue")
        ui.await("listening after grant") { ui.exists("parenting.composer.listening") }
        assertEquals(1, permission.requests)
        assertFalse(ui.exists("parenting.composer.voiceExplain"))
    }

    @Test fun onlineRecognizerIsDisclosedHonestly() {
        permission.granted = false
        engine.tier = DictationEngineTier.ServerRecognizer
        boot(VoiceDeps(engine, VoicePrefs.InMemory(false), permission, noticeMillis = 60_000))
        ui.click("parenting.composer.mic")
        ui.await("explanation") { ui.exists("parenting.composer.voiceExplain") }
        val text = ui.text("parenting.composer.voiceExplain.text")
        assertTrue(text.contains("在线识别"), text)
        assertFalse(text.contains("不会上传"), text)
    }

    @Test fun decliningTheExplanationSwitchesToKeyboard() {
        permission.granted = false
        boot(VoiceDeps(engine, VoicePrefs.InMemory(false), permission, noticeMillis = 60_000))
        ui.click("parenting.composer.mic")
        ui.await("explanation") { ui.exists("parenting.composer.voiceExplain") }
        ui.click("parenting.composer.voiceExplain.keyboard")
        ui.await("keyboard") { ui.exists("parenting.natural.field") }
        assertEquals(0, permission.requests)
    }

    @Test fun deniedPermissionShowsInlineHintWithSettingsLinkAndKeyboardStillWorks() {
        permission.granted = false
        permission.grantOnRequest = false
        boot(VoiceDeps(engine, VoicePrefs.InMemory(true), permission, { settingsOpened += 1 }, noticeMillis = 60_000))
        ui.click("parenting.composer.mic")
        ui.await("denied hint") { ui.exists("parenting.composer.voiceNotice.settings") }
        assertEquals(0, engine.startCount)
        ui.click("parenting.composer.voiceNotice.settings")
        assertEquals(1, settingsOpened)
        showKeyboard()
        typeAndSend("记一下，团团今天自己收好了书包")
        ui.await("card") { ui.exists("parenting.confirmCard.observation") }
    }

    @Test fun unsupportedDeviceOffersKeyboard() {
        engine.tier = DictationEngineTier.Unavailable
        boot()
        ui.click("parenting.composer.mic")
        ui.await("hint") { ui.exists("parenting.composer.voiceNotice.keyboard") }
        ui.click("parenting.composer.voiceNotice.keyboard")
        ui.await("keyboard") { ui.exists("parenting.natural.field") }
    }

    @Test fun silenceShowsAnEmptyHintThatClearsItself() {
        boot(deps(noticeMillis = 5_000))
        ui.click("parenting.composer.mic")
        ui.await("listening") { ui.exists("parenting.composer.listening") }
        ui.click("parenting.composer.listening")
        ui.await("empty hint") { ui.exists("parenting.composer.voiceNotice") }
        assertTrue(ui.text("parenting.composer.voiceNotice").contains("没听到内容"))
        rule.mainClock.advanceTimeBy(5_500)
        ui.await("hint gone") { !ui.exists("parenting.composer.voiceNotice") }
    }

    @Test fun interruptionKeepsWordsAndExplains() {
        boot()
        engine.autoTranscript = "团团今天"
        ui.click("parenting.composer.mic")
        ui.await("listening") { ui.exists("parenting.composer.listening") }
        rule.runOnUiThread { engine.endBy(DictationEnd.Interrupted) }
        ui.await("hint") { ui.exists("parenting.composer.voiceNotice") }
        assertTrue(ui.text("parenting.composer.voiceNotice").contains("被打断"))
        assertEquals("团团今天", ui.text("parenting.composer.transcript"))
    }

    // 追问（#4/#5/#6 的一半）

    @Test fun ambiguousChildAsksOneQuestionThenShowsTheCard() {
        val other = UUID.randomUUID()
        boot(seed = listOf(ParentingCommand.AddChild(child, "团团"), ParentingCommand.AddChild(other, "圆圆")))
        typeAndSend("今天下午5点游泳")
        ui.await("question") { ui.exists("parenting.natural.question") }
        assertTrue(ui.text("parenting.natural.question").contains("哪位孩子"), ui.text("parenting.natural.question"))
        assertTrue(h.workspace.arrangements.isEmpty(), "追问阶段零写入")
        ui.click("parenting.natural.child.${other.toString().uppercase()}")
        ui.await("card") { ui.exists("parenting.confirmCard.newArrangement") }
        assertTrue(ui.text("parenting.confirmCard.field.0").contains("圆圆"))
        ui.click("parenting.natural.confirm")
        ui.await("saved") { arrangementsOf("游泳").size == 1 }
        assertEquals(other, arrangementsOf("游泳").single().childID)
    }

    @Test fun missingTimeAsksThenReplyCompletesTheCard() {
        boot()
        typeAndSend("明天游泳")
        ui.await("question") { ui.exists("parenting.natural.question") }
        ui.type("parenting.natural.reply", "下午3点")
        ui.click("parenting.natural.replySend")
        ui.await("card") { ui.exists("parenting.confirmCard.newArrangement") }
        ui.click("parenting.natural.confirm")
        ui.await("saved") { arrangementsOf("游泳").size == 1 }
    }

    @Test fun rescheduleShowsBeforeAfterAndRevisesOnlyThatArrangement() {
        val id = UUID.randomUUID()
        val due = Instant.parse("2026-10-27T10:00:00Z")
        boot(seed = listOf(
            ParentingCommand.AddChild(child, "团团"),
            ParentingCommand.CreateDraft(id, child, "游泳", due, null, "游泳"),
            ParentingCommand.ConfirmArrangement(id),
        ))
        typeAndSend("游泳改到明天下午5点")
        ui.await("card") { ui.exists("parenting.confirmCard.reschedule") }
        assertTrue(ui.exists("parenting.confirmCard.change"))
        assertEquals(due, h.workspace.arrangements.single().dueAt, "确认之前时间没变")
        ui.click("parenting.natural.confirm")
        ui.await("revised") { h.workspace.arrangements.single().dueAt != due }
        assertEquals(1, h.workspace.arrangements.size)
        assertEquals(Instant.parse("2026-10-27T17:00:00Z"), h.workspace.arrangements.single().dueAt)
    }

    @Test fun viewIntentsNavigateAndNeverWrite() {
        boot()
        val revision = h.workspace.revision
        ui.click("parenting.tab.growth")
        showKeyboard()
        ui.click("parenting.natural.chip.today")
        ui.await("view card") { ui.exists("parenting.natural.confirm") }
        assertTrue(ui.exists("parenting.confirmCard.viewToday"))
        ui.click("parenting.natural.confirm")
        ui.await("closed") { !ui.exists("parenting.natural.sheet") }
        assertEquals(revision, h.workspace.revision)
        assertTrue(ui.exists("parenting.page.today"), "带我去看：回到今天")
    }

    @Test fun manualAdjustmentHandsOverToTheBlankFormWithoutSaving() {
        boot()
        val revision = h.workspace.revision
        typeAndSend("记一下，团团今天自己收好了书包")
        ui.await("card") { ui.exists("parenting.natural.manual") }
        ui.click("parenting.natural.manual")
        ui.await("form") { ui.exists("parenting.record.text") }
        assertEquals("", ui.text("parenting.record.text"), "手动调整不预填")
        assertEquals(revision, h.workspace.revision)
    }

    @Test fun failedWriteKeepsTheCardAndRetryWritesExactlyOnce() {
        boot()
        typeAndSend("团团今天下午5点游泳")
        ui.await("card") { ui.exists("parenting.natural.confirm") }
        h.blobs.failNextWrites = 1
        ui.click("parenting.natural.confirm")
        ui.await("failure shown") { ui.exists("parenting.natural.failure") }
        assertTrue(h.workspace.arrangements.isEmpty(), "失败 = 没写")
        assertTrue(ui.exists("parenting.confirmCard.newArrangement"), "卡片保留")
        ui.click("parenting.natural.confirm")
        ui.await("saved") { arrangementsOf("游泳").size == 1 }
        assertEquals(1, arrangementsOf("游泳").size)
    }

    @Test fun composerIsHiddenWithoutChildrenAndOnTheFamilyTab() {
        boot(seed = emptyList())
        assertFalse(ui.exists("parenting.natural.bar"))
        h.seed(ParentingCommand.AddChild(child, "团团"))
        ui.await("composer after child") { ui.exists("parenting.natural.bar") }
        ui.click("parenting.tab.family")
        ui.await("hidden on family") { !ui.exists("parenting.natural.bar") }
    }

    // 工具

    private fun hint(): String =
        rule.onNodeWithTagCompat("parenting.composer.listening").fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription).orEmpty()

    private fun holdOn(tag: String) {
        rule.onNodeWithTagCompat(tag).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(450)
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-10-26T01:00:00Z")
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithTagCompat(tag: String) =
    this.onNode(androidx.compose.ui.test.hasTestTag(tag))
