package com.heydusk.mimi.voice

import com.heydusk.mimi.domain.dictation.DictationEngineTier
import com.heydusk.mimi.domain.language.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinuousDictationEngineTest {
    private val source = FakeSegmentSource()
    private val scheduler = ManualScheduler()
    private val engine = ContinuousDictationEngine(source, scheduler)
    private val listener = RecordingListener()

    private fun start() = engine.startDictation(AppLanguage.Chinese, listener)

    @Test fun partialsStreamAndPauseStartsNextSegmentWithoutLosingText() {
        assertTrue(start())
        assertEquals(DictationEngine.Phase.Listening, engine.phase)
        source.partial("明天")
        source.result("明天下午三点")
        assertEquals(2, source.begins) // 停顿后立刻续听
        source.partial("接小朋友")
        assertEquals("明天下午三点接小朋友", listener.texts.last().replace(" ", ""))
        engine.stopDictation()
        assertEquals(1, source.finishes)
        source.result("接小朋友放学")
        assertEquals(listOf(DictationEnd.Finished), listener.ends)
        assertEquals(DictationEngine.Phase.Idle, engine.phase)
        assertTrue(listener.texts.last().contains("接小朋友放学"))
    }

    @Test fun threeSilentSegmentsFinishAsEmptyNotFailure() {
        start()
        repeat(3) { source.error(SegmentError.NoSpeech) }
        assertEquals(listOf(DictationEnd.Finished), listener.ends)
        assertTrue(listener.texts.isEmpty())
    }

    @Test fun repeatedRealErrorsFail() {
        start()
        repeat(3) { source.error(SegmentError.Transient) }
        assertEquals(listOf(DictationEnd.Failed), listener.ends)
    }

    @Test fun silenceInTheMiddleAfterTextFinishesWithText() {
        start()
        source.result("你好")
        repeat(3) { source.error(SegmentError.NoSpeech) }
        assertEquals(listOf(DictationEnd.Finished), listener.ends)
        assertEquals("你好", listener.texts.last())
    }

    @Test fun permissionErrorIsDeniedImmediately() {
        start()
        source.error(SegmentError.Permission)
        assertEquals(listOf(DictationEnd.Denied), listener.ends)
        assertEquals(1, source.aborts)
    }

    @Test fun languageUnavailableWithoutTextIsUnsupportedWithTextIsFinished() {
        start()
        source.error(SegmentError.LanguageUnavailable)
        assertEquals(listOf(DictationEnd.Unsupported), listener.ends)
        val second = RecordingListener()
        engine.startDictation(AppLanguage.English, second)
        source.partial("hello")
        source.error(SegmentError.LanguageUnavailable)
        assertEquals(listOf(DictationEnd.Finished), second.ends)
    }

    @Test fun busyErrorRebuildsRecognizerAndRestarts() {
        start()
        source.error(SegmentError.Busy)
        assertEquals(2, source.begins)
        assertEquals(1, source.aborts)
        assertTrue(listener.ends.isEmpty())
    }

    @Test fun stopWaitsAtMostTwoSecondsForFinalResult() {
        start()
        source.partial("好")
        engine.stopDictation()
        assertTrue(listener.ends.isEmpty())
        scheduler.advance(ContinuousDictationEngine.FINAL_WAIT_MILLIS - 1)
        assertTrue(listener.ends.isEmpty())
        scheduler.advance(1)
        assertEquals(listOf(DictationEnd.Finished), listener.ends)
        assertEquals(0, scheduler.pending)
    }

    @Test fun errorAfterUserStopIsNotAFailure() {
        start()
        source.partial("好")
        engine.stopDictation()
        source.error(SegmentError.Transient)
        assertEquals(listOf(DictationEnd.Finished), listener.ends)
    }

    @Test fun cancelEndsAtOnceAndLateCallbacksAreIgnored() {
        start()
        val stale = source.callbacks!!
        engine.cancelDictation()
        assertEquals(listOf(DictationEnd.Cancelled), listener.ends)
        stale.onPartial("迟到")
        stale.onError(SegmentError.Transient)
        assertEquals(listOf(DictationEnd.Cancelled), listener.ends)
        assertTrue(listener.texts.isEmpty())
    }

    @Test fun unavailableOrRefusedDoesNotStart() {
        source.tier = DictationEngineTier.Unavailable
        assertFalse(start())
        source.tier = DictationEngineTier.OnDeviceRecognizer
        source.refuse = true
        assertFalse(start())
        assertEquals(DictationEngine.Phase.Idle, engine.phase)
        source.refuse = false
        assertTrue(start())
        assertFalse(engine.startDictation(AppLanguage.Chinese, RecordingListener())) // 已经在听
    }

    @Test fun emptyFinalResultCountsAsSilentSegment() {
        start()
        source.result("")
        assertEquals(2, source.begins)
        repeat(2) { source.result("") }
        assertEquals(listOf(DictationEnd.Finished), listener.ends)
    }
}
