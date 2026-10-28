package com.heydusk.mimi.voice

import com.heydusk.mimi.domain.dictation.DictationEngineTier
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidSpeechSegmentSourceTest {
    @Test fun errorCodesMapToSegmentErrors() {
        val expected = mapOf(
            1 to SegmentError.Transient, 2 to SegmentError.Transient, 3 to SegmentError.Transient, 4 to SegmentError.Transient,
            5 to SegmentError.Busy, 6 to SegmentError.NoSpeech, 7 to SegmentError.NoSpeech, 8 to SegmentError.Busy,
            9 to SegmentError.Permission, 10 to SegmentError.Transient, 11 to SegmentError.Transient,
            12 to SegmentError.LanguageUnavailable, 13 to SegmentError.LanguageUnavailable, 14 to SegmentError.LanguageUnavailable,
            15 to SegmentError.Transient, 99 to SegmentError.Transient,
        )
        expected.forEach { (code, mapped) -> assertEquals(mapped, AndroidSpeechSegmentSource.mapError(code), "code $code") }
    }

    @Test fun tierPrefersOnDeviceThenSystemThenUnavailable() {
        assertEquals(DictationEngineTier.OnDeviceRecognizer, AndroidSpeechSegmentSource.chooseTier(onDevice = true, server = true))
        assertEquals(DictationEngineTier.OnDeviceRecognizer, AndroidSpeechSegmentSource.chooseTier(onDevice = true, server = false))
        assertEquals(DictationEngineTier.ServerRecognizer, AndroidSpeechSegmentSource.chooseTier(onDevice = false, server = true))
        assertEquals(DictationEngineTier.Unavailable, AndroidSpeechSegmentSource.chooseTier(onDevice = false, server = false))
    }

    @Test fun levelIsNormalisedAndClamped() {
        assertEquals(0f, AndroidSpeechSegmentSource.normalise(-10f))
        assertEquals(1f, AndroidSpeechSegmentSource.normalise(30f))
        assertEquals(0.5f, AndroidSpeechSegmentSource.normalise(4f))
    }
}
