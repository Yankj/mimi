package com.heydusk.mimi.domain.dictation

import com.heydusk.mimi.domain.language.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 对应 iOS `VoiceFirstComposerTests` 里的纯听写用例（join / 转写分段 / 设备端档位）。按住说话的手势与控制器属 M3。 */
class DictationPolicyTest {
    @Test fun joinPutsNoSpaceBetweenChineseAndOneSpaceBetweenLatinWords() {
        assertEquals("记一下，团团游泳", DictationPolicy.join("记一下，", "团团游泳"))
        assertEquals("团团swim at 5", DictationPolicy.join("团团", "swim at 5"))
        assertEquals("hello world", DictationPolicy.join("hello", "world"))
        assertEquals("hello world", DictationPolicy.join("hello ", "world"))
        assertEquals("hello，world", DictationPolicy.join("hello", "，world"))
        assertEquals("团团", DictationPolicy.join("", "团团"))
        assertEquals("团团", DictationPolicy.join("团团", ""))
    }

    @Test fun transcriptKeepsCommittedSegmentsWhenTheRecognizerRestarts() {
        val t = DictationTranscript()
        t.update("团团周五"); assertEquals("团团周五", t.text)
        t.commit(); t.update("游泳改到六点"); assertEquals("团团周五游泳改到六点", t.text)
        t.update("游泳改到六点了"); assertEquals("团团周五游泳改到六点了", t.text, "a partial replaces only the current segment")
    }

    @Test fun onDeviceOnlyTierNeverAllowsTheServerTier() {
        assertTrue(DictationEngineTier.ServerRecognizer.usesNetwork)
        for (tier in listOf(DictationEngineTier.SpeechTranscriber, DictationEngineTier.DictationTranscriber, DictationEngineTier.OnDeviceRecognizer)) assertFalse(tier.usesNetwork)
        assertEquals(DictationEngineTier.ServerRecognizer, DictationEngineTier.choose(false, false, false, recognizerAvailable = true, recognizerOnDevice = false))
        assertEquals(DictationEngineTier.Unavailable, DictationEngineTier.choose(false, false, false, recognizerAvailable = false, recognizerOnDevice = false))
    }

    @Test fun localeFollowsTheAppLanguageAndRestartRulesGiveUpOnlyAfterRepeatedEmptyFailures() {
        assertEquals("en-US", DictationPolicy.localeIdentifier(AppLanguage.English))
        assertEquals("zh-CN", DictationPolicy.localeIdentifier(AppLanguage.Chinese))
        assertEquals(DictationRestart.Decision.Restart, DictationRestart.decide(failed = false, segmentHadText = false, emptyFailures = 0, sessionHasText = false))
        assertEquals(DictationRestart.Decision.Restart, DictationRestart.decide(true, false, 1, false))
        assertEquals(DictationRestart.Decision.Fail, DictationRestart.decide(true, false, 2, false))
        assertEquals(DictationRestart.Decision.Finish, DictationRestart.decide(true, false, 2, true))
    }
}
