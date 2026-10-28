package com.heydusk.mimi.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.pm.ResolveInfo
import org.robolectric.RuntimeEnvironment
import com.heydusk.mimi.domain.dictation.DictationEngineTier
import com.heydusk.mimi.domain.language.AppLanguage
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

/** 用 Robolectric 的识别器影子验证平台封装：意图参数、回调归并、识别器实例的创建/丢弃。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSpeechSegmentSourceRobolectricTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private class Recorder : SegmentCallbacks {
        val events = mutableListOf<String>()
        override fun onReady() { events += "ready" }
        override fun onPartial(text: String) { events += "partial:$text" }
        override fun onResult(text: String) { events += "result:$text" }
        override fun onLevel(level: Float) { events += "level:$level" }
        override fun onError(error: SegmentError) { events += "error:$error" }
    }

    private fun bundle(vararg texts: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(*texts))
    }

    /** `SpeechRecognizer.isRecognitionAvailable` 看的是有没有 RecognitionService 可解析。 */
    private fun setServerRecognizerInstalled(installed: Boolean) {
        val pm = shadowOf(context.packageManager)
        val intent = Intent("android.speech.RecognitionService")
        pm.removeResolveInfosForIntent(intent, "com.example.speech")
        if (installed) pm.addResolveInfoForIntent(intent, ResolveInfo().apply {
            serviceInfo = android.content.pm.ServiceInfo().apply { packageName = "com.example.speech"; name = "Service" }
        })
    }

    @Before fun reset() {
        ShadowSpeechRecognizer.reset()
        setServerRecognizerInstalled(true)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
    }

    @Test fun tierFollowsPlatformAvailability() {
        val source = AndroidSpeechSegmentSource(context)
        assertEquals(DictationEngineTier.ServerRecognizer, source.tier(AppLanguage.Chinese))
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        assertEquals(DictationEngineTier.OnDeviceRecognizer, source.tier(AppLanguage.Chinese))
        setServerRecognizerInstalled(false)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        assertEquals(DictationEngineTier.Unavailable, source.tier(AppLanguage.Chinese))
        assertFalse(source.begin(AppLanguage.Chinese, Recorder()))
    }

    @Test fun beginSendsChineseFreeFormIntentAndForwardsPartialsAndResults() {
        val source = AndroidSpeechSegmentSource(context)
        val rec = Recorder()
        assertTrue(source.begin(AppLanguage.Chinese, rec))
        val shadow = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        val intent: Intent = shadowOf(shadow).lastRecognizerIntent
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        assertEquals("zh-CN", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        shadowOf(shadow).triggerOnPartialResults(bundle("团团今天"))
        shadowOf(shadow).triggerOnResults(bundle("团团今天下午游泳", "别的候选"))
        assertEquals(listOf("partial:团团今天", "result:团团今天下午游泳"), rec.events)
    }

    @Test fun platformErrorsAreMapped() {
        val source = AndroidSpeechSegmentSource(context)
        val rec = Recorder()
        source.begin(AppLanguage.English, rec)
        val shadow = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        shadow.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        shadow.triggerOnError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        shadow.triggerOnError(SpeechRecognizer.ERROR_NETWORK)
        assertEquals(listOf("error:NoSpeech", "error:Permission", "error:Transient"), rec.events)
    }

    @Test fun abortDestroysTheRecognizerAndDropsLaterCallbacks() {
        val source = AndroidSpeechSegmentSource(context)
        val rec = Recorder()
        source.begin(AppLanguage.Chinese, rec)
        val latest = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        source.abort()
        assertTrue(shadowOf(latest).isDestroyed, "识别器实例被销毁，不会再有回调")
        assertTrue(rec.events.isEmpty(), rec.events.toString())
    }

    @Test fun engineOnRealSourceStreamsTextIntoTheListener() {
        val engine = ContinuousDictationEngine(AndroidSpeechSegmentSource(context), ManualScheduler())
        val listener = RecordingListener()
        assertTrue(engine.startDictation(AppLanguage.Chinese, listener))
        val shadow = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        shadow.triggerOnPartialResults(bundle("记一下"))
        shadow.triggerOnResults(bundle("记一下团团自己穿了鞋"))
        assertTrue(listener.texts.last().contains("记一下团团自己穿了鞋"), listener.texts.toString())
        engine.cancelDictation()
        assertEquals(listOf(DictationEnd.Cancelled), listener.ends)
    }
}
