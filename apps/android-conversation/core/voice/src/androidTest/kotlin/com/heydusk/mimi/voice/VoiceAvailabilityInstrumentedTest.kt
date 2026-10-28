package com.heydusk.mimi.voice

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.heydusk.mimi.domain.language.AppLanguage
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.junit.runner.RunWith

/**
 * 真机检查：这台手机的识别器可用性。**不开麦克风、不弹权限**，只读 `SpeechRecognizer.isRecognitionAvailable` 等。
 * 结果打到 logcat（tag `MimiVoice`），三档任意一档都算通过——它是诊断，不是门槛。
 */
@RunWith(AndroidJUnit4::class)
class VoiceAvailabilityInstrumentedTest {
    @Test fun reportsRecognizerTier() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val tier = AndroidSpeechSegmentSource(context).tier(AppLanguage.Chinese)
        android.util.Log.i("MimiVoice", "recognizer tier = $tier")
        assertNotNull(tier)
    }
}
