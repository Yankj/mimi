package com.heydusk.mimi.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.heydusk.mimi.domain.dictation.DictationEngineTier
import com.heydusk.mimi.domain.dictation.DictationPolicy
import com.heydusk.mimi.domain.language.AppLanguage
import java.util.Locale

/**
 * Android `SpeechRecognizer` 的一段式封装（计划 4.6）：
 * 1. API 31+ 且本机识别可用 → `createOnDeviceSpeechRecognizer`（音频不出设备）；
 * 2. 否则退到 `createSpeechRecognizer`（厂商/Google 语音服务，可能在线）——界面在首次说明与设置页里如实说明；
 * 3. 都没有 → 不可用，只剩键盘。
 * 识别器只能在主线程创建和使用。本机识别报语言包缺失时，如果系统识别器可用，当次会话改用系统识别器。
 */
class AndroidSpeechSegmentSource(
    private val context: Context,
    private val allowServerFallback: Boolean = true,
) : SpeechSegmentSource {
    private var recognizer: SpeechRecognizer? = null
    private var onDeviceInUse = false
    private var forceServer = false
    private var lastRmsAt = 0L

    override fun tier(language: AppLanguage): DictationEngineTier = chooseTier(onDeviceAvailable(), serverAvailable())

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun serverAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun begin(language: AppLanguage, callbacks: SegmentCallbacks): Boolean {
        ensureMainThread()
        val instance = recognizer ?: createRecognizer() ?: return false
        recognizer = instance
        instance.setRecognitionListener(listener(language, callbacks))
        return try {
            instance.startListening(intent(language))
            true
        } catch (_: RuntimeException) {
            discard()
            false
        }
    }

    override fun finish() {
        runCatching { recognizer?.stopListening() }
    }

    override fun abort() {
        discard()
        forceServer = false
    }

    private fun createRecognizer(): SpeechRecognizer? {
        val preferDevice = !forceServer && onDeviceAvailable()
        return when {
            preferDevice -> { onDeviceInUse = true; SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
            allowServerFallback && serverAvailable() -> { onDeviceInUse = false; SpeechRecognizer.createSpeechRecognizer(context) }
            else -> null
        }
    }

    private fun discard() {
        val old = recognizer ?: return
        recognizer = null
        runCatching { old.setRecognitionListener(null) }
        runCatching { old.cancel() }
        runCatching { old.destroy() }
    }

    private fun intent(language: AppLanguage): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            val tag = DictationPolicy.localeIdentifier(language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.forLanguageTag(tag).toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private fun listener(language: AppLanguage, callbacks: SegmentCallbacks) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = callbacks.onReady()
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            val now = System.nanoTime() / 1_000_000
            if (now - lastRmsAt < LEVEL_INTERVAL_MILLIS) return
            lastRmsAt = now
            callbacks.onLevel(normalise(rmsdB))
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let(callbacks::onPartial)
        }
        override fun onResults(results: Bundle?) {
            callbacks.onResult(firstResult(results).orEmpty())
        }
        override fun onError(error: Int) {
            val mapped = mapError(error)
            // 本机识别没有这个语言的包：当次会话改用系统识别器（如果有），文字内容由界面在首次说明里提前讲明。
            if (mapped == SegmentError.LanguageUnavailable && onDeviceInUse && allowServerFallback && serverAvailable() && !forceServer) {
                forceServer = true
                discard()
                if (begin(language, callbacks)) return
            }
            if (mapped == SegmentError.Busy) discard()
            callbacks.onError(mapped)
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "SpeechRecognizer must be used on the main thread" }
    }

    companion object {
        private const val LEVEL_INTERVAL_MILLIS = 50L // ≈20 Hz

        /** 本机识别 > 系统识别 > 不可用（与 iOS 同一个选择函数，见 [DictationEngineTier.choose]）。 */
        fun chooseTier(onDevice: Boolean, server: Boolean): DictationEngineTier =
            DictationEngineTier.choose(
                analyzerOS = false, speechTranscriberReady = false, dictationTranscriberReady = false,
                recognizerAvailable = onDevice || server, recognizerOnDevice = onDevice,
            )

        /** rmsdB 约 −2…10 → 0…1。 */
        fun normalise(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)

        /** `SpeechRecognizer.ERROR_*` → [SegmentError]。常量写成数字：它们是稳定的平台值，也让纯 JVM 测试不依赖平台类。 */
        fun mapError(code: Int): SegmentError = when (code) {
            6, 7 -> SegmentError.NoSpeech // SPEECH_TIMEOUT, NO_MATCH
            8, 5 -> SegmentError.Busy // RECOGNIZER_BUSY, CLIENT
            9 -> SegmentError.Permission // INSUFFICIENT_PERMISSIONS
            12, 13, 14 -> SegmentError.LanguageUnavailable // LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE, CANNOT_CHECK_SUPPORT
            else -> SegmentError.Transient // NETWORK_TIMEOUT 1, NETWORK 2, AUDIO 3, SERVER 4, TOO_MANY_REQUESTS 10, SERVER_DISCONNECTED 11, 15…
        }
    }
}

/** 主线程 Handler 调度。 */
class MainThreadScheduler(private val handler: Handler = Handler(Looper.getMainLooper())) : DictationScheduler {
    override fun postDelayed(delayMillis: Long, block: () -> Unit): DictationScheduler.Cancelable {
        val runnable = Runnable { block() }
        handler.postDelayed(runnable, delayMillis)
        return DictationScheduler.Cancelable { handler.removeCallbacks(runnable) }
    }
}
