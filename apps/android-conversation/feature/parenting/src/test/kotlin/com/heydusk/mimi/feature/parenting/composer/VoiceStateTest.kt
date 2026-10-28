package com.heydusk.mimi.feature.parenting.composer

import com.heydusk.mimi.domain.dictation.DictationEngineTier
import com.heydusk.mimi.domain.language.AppLanguage
import com.heydusk.mimi.voice.DictationController
import com.heydusk.mimi.voice.ScriptedDictationEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceStateTest {
    private val engine = ScriptedDictationEngine()
    private val permission = FakePermission(granted = true)
    private val prefs = VoicePrefs.InMemory(false)
    private var settings = 0

    private fun state() = ParentingVoiceState(VoiceDeps(engine, prefs, permission, { settings += 1 }), permission) { AppLanguage.Chinese }
        .also { it.controller.attach(object : com.heydusk.mimi.voice.DictationDraftSink {
            override var draft = ""
            override fun editDraft(text: String) { draft = text }
        }) }

    @Test fun presentationIsAPureFunctionOfModeDictatingAndDraft() {
        val v = ParentingInputMode.Voice
        val k = ParentingInputMode.Keyboard
        assertEquals(ParentingVoicePresentation.VoiceIdle, ParentingVoicePresentation.make(v, false, true))
        assertEquals(ParentingVoicePresentation.Preview, ParentingVoicePresentation.make(v, false, false))
        assertEquals(ParentingVoicePresentation.Listening, ParentingVoicePresentation.make(v, true, true))
        assertEquals(ParentingVoicePresentation.Listening, ParentingVoicePresentation.make(v, true, false))
        assertEquals(ParentingVoicePresentation.Keyboard, ParentingVoicePresentation.make(k, false, true))
        assertEquals(ParentingVoicePresentation.Keyboard, ParentingVoicePresentation.make(k, false, false))
        assertEquals(ParentingVoicePresentation.KeyboardListening, ParentingVoicePresentation.make(k, true, false))
    }

    @Test fun grantedPermissionStartsImmediately() {
        val s = state()
        assertTrue(s.requestStart(hold = false))
        assertTrue(s.controller.dictating.value)
    }

    @Test fun firstPressWithoutPermissionOnlyExplains() {
        permission.granted = false
        val s = state()
        assertFalse(s.requestStart(hold = true))
        assertTrue(s.explaining)
        assertEquals(0, permission.requests)
        assertEquals(0, engine.startCount)
        assertFalse(s.requestStart(hold = false), "说明还开着时再按不会越过它")
    }

    @Test fun acceptingRemembersAndStartsAsTapAfterGrant() {
        permission.granted = false
        val s = state()
        s.requestStart(hold = false)
        s.acceptExplanation()
        assertTrue(prefs.isExplained())
        assertEquals(1, permission.requests)
        assertTrue(s.controller.dictating.value)
        assertEquals(DictationController.Mode.Tap, s.controller.mode.value)
    }

    @Test fun afterExplainedAndRevokedTheSystemPromptComesWithoutRepeatingTheExplanation() {
        permission.granted = false
        prefs.setExplained()
        val s = state()
        assertFalse(s.requestStart(hold = false))
        assertFalse(s.explaining)
        assertEquals(1, permission.requests)
        assertTrue(s.controller.dictating.value, "授权后直接开始")
    }

    @Test fun refusedPermissionRaisesTheInlineNoticeNotASession() {
        permission.granted = false
        permission.grantOnRequest = false
        prefs.setExplained()
        val s = state()
        s.requestStart(hold = false)
        assertEquals(DictationController.Notice.PermissionDenied, s.controller.notice.value)
        assertEquals(0, engine.startCount)
    }

    @Test fun decliningMovesToKeyboardAndStopsAnyDictation() {
        permission.granted = false
        val s = state()
        s.requestStart(hold = false)
        s.declineExplanation()
        assertEquals(ParentingInputMode.Keyboard, s.mode)
        assertFalse(s.explaining)
    }

    @Test fun unavailableEngineSaysSoWithoutAskingForPermission() {
        permission.granted = false
        engine.tier = DictationEngineTier.Unavailable
        val s = state()
        assertFalse(s.requestStart(hold = false))
        assertEquals(DictationController.Notice.Unsupported, s.controller.notice.value)
        assertEquals(0, permission.requests)
        assertFalse(s.explaining)
    }

    @Test fun switchingModeStopsDictation() {
        val s = state()
        s.requestStart(hold = false)
        s.switchMode(ParentingInputMode.Keyboard)
        assertFalse(s.controller.dictating.value)
        assertFalse(engine.running)
    }

    @Test fun onlineRecognitionIsFlaggedForTheDisclosure() {
        engine.tier = DictationEngineTier.ServerRecognizer
        assertTrue(state().mayUseOnlineRecognition)
        engine.tier = DictationEngineTier.OnDeviceRecognizer
        assertFalse(state().mayUseOnlineRecognition)
    }
}
