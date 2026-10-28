package com.heydusk.mimi.voice

import com.heydusk.mimi.domain.dictation.DictationEngineTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DictationControllerTest {
    private val engine = ScriptedDictationEngine()
    private val draft = FakeDraft()
    private val sent = mutableListOf<String>()
    private val controller = DictationController(engine).also { c ->
        c.attach(draft)
        c.onSend = { sent += draft.draft }
    }

    @Test fun tapStreamsIntoDraftAfterWhatWasTypedAndStops() {
        draft.draft = "今天"
        controller.toggle()
        assertTrue(controller.dictating.value)
        engine.say("下午三点")
        assertEquals("今天下午三点", draft.draft) // 中文之间不加空格
        controller.toggle()
        assertFalse(controller.dictating.value)
        assertEquals("今天下午三点", draft.draft)
        assertNull(controller.notice.value)
    }

    @Test fun englishPrefixGetsASpace() {
        draft.draft = "buy"
        controller.toggle()
        engine.say("milk")
        assertEquals("buy milk", draft.draft)
    }

    @Test fun cancelRestoresPreviousDraft() {
        draft.draft = "先写的"
        controller.toggle()
        engine.say("后来说的")
        controller.cancel()
        assertEquals("先写的", draft.draft)
        assertFalse(controller.dictating.value)
        assertNull(controller.notice.value) // 用户取消：不提示「没听到」
    }

    @Test fun holdZonesLeftBeatsUp() {
        controller.beginHold()
        val haptics = mutableListOf<DictationController.HoldZone>()
        controller.onZoneHaptic = { haptics += it }
        controller.updateHold(0f, -69f)
        assertEquals(DictationController.HoldZone.None, controller.zone.value)
        controller.updateHold(0f, -71f)
        assertEquals(DictationController.HoldZone.Send, controller.zone.value)
        controller.updateHold(-91f, -71f)
        assertEquals(DictationController.HoldZone.Cancel, controller.zone.value)
        controller.updateHold(0f, 0f)
        assertEquals(listOf(DictationController.HoldZone.Send, DictationController.HoldZone.Cancel, DictationController.HoldZone.None), haptics)
    }

    @Test fun releaseInNoneZoneKeepsText() {
        controller.beginHold()
        engine.say("记一下")
        controller.endHold()
        assertEquals("记一下", draft.draft)
        assertTrue(sent.isEmpty())
    }

    @Test fun releaseInSendZoneSendsOnceWithFinalText() {
        controller.beginHold()
        engine.say("明天上钢琴课")
        controller.updateHold(0f, -80f)
        controller.endHold()
        assertEquals(listOf("明天上钢琴课"), sent)
        assertFalse(controller.dictating.value)
    }

    @Test fun releaseInCancelZoneDiscards() {
        draft.draft = "A"
        controller.beginHold()
        engine.say("丢掉")
        controller.updateHold(-100f, 0f)
        controller.endHold()
        assertEquals("A", draft.draft)
        assertTrue(sent.isEmpty())
    }

    @Test fun sendWithNoTextDoesNotSendAndShowsEmptyHint() {
        controller.beginHold()
        controller.updateHold(0f, -80f)
        controller.endHold()
        assertTrue(sent.isEmpty())
        assertEquals(DictationController.Notice.Empty, controller.notice.value)
    }

    @Test fun typingDuringDictationHandsTheInputBack() {
        controller.toggle()
        engine.say("你好")
        draft.draft = "你好，我自己改了" // 家长键盘输入
        engine.say("你好吗")
        assertFalse(controller.dictating.value)
        assertEquals("你好，我自己改了", draft.draft)
        assertFalse(engine.running)
    }

    @Test fun deniedFailedUnsupportedInterruptedNotices() {
        engine.failNextStartWith = DictationEnd.Denied
        controller.toggle()
        assertEquals(DictationController.Notice.PermissionDenied, controller.notice.value)
        engine.failNextStartWith = DictationEnd.Unsupported
        controller.toggle()
        assertEquals(DictationController.Notice.Unsupported, controller.notice.value)
        engine.failNextStartWith = DictationEnd.Failed
        controller.toggle()
        assertEquals(DictationController.Notice.Failed, controller.notice.value)
        controller.toggle()
        engine.endBy(DictationEnd.Interrupted)
        assertEquals(DictationController.Notice.Interrupted, controller.notice.value)
    }

    @Test fun interruptedDuringSendDoesNotSend() {
        controller.beginHold()
        engine.say("要发的")
        controller.confirmAndSend()
        // ScriptedDictationEngine.stop 同步以 Finished 结束，这里改成被打断来验证。
        assertEquals(listOf("要发的"), sent)
        sent.clear()
        controller.beginHold()
        engine.say("又说")
        engine.endBy(DictationEnd.Interrupted)
        assertTrue(sent.isEmpty())
    }

    @Test fun silenceAndInterruptionHintsCanBeTurnedOff() {
        controller.reportsSilenceAndInterruption = false
        controller.toggle()
        controller.toggle()
        assertNull(controller.notice.value)
    }

    @Test fun unavailableEngineShowsUnsupportedWithoutSession() {
        engine.tier = DictationEngineTier.Unavailable
        controller.toggle()
        assertFalse(controller.dictating.value)
        assertEquals(DictationController.Notice.Unsupported, controller.notice.value)
    }

    @Test fun staleSessionNeverBlocksTheMic() {
        val orphan = object : DictationEngine.Listener {
            override fun onText(text: String) = Unit
            override fun onLevel(level: Float) = Unit
            override fun onEnd(reason: DictationEnd) = Unit
        }
        engine.startDictation(com.heydusk.mimi.domain.language.AppLanguage.Chinese, orphan)
        controller.toggle()
        assertTrue(controller.dictating.value)
        assertEquals(2, engine.startCount)
    }

    @Test fun stopNowKeepsDraft() {
        controller.toggle()
        engine.say("留下")
        controller.stopNow()
        assertEquals("留下", draft.draft)
        assertFalse(controller.dictating.value)
    }

    @Test fun typingDuringHoldWritesNothingAfterwards() {
        controller.beginHold()
        draft.draft = "手动"
        engine.say("不该写入")
        assertEquals("手动", draft.draft)
    }
}
