package com.heydusk.mimi.voice

import com.heydusk.mimi.domain.dictation.DictationEngineTier
import com.heydusk.mimi.domain.language.AppLanguage

/** 平台识别器一段话里可能报的错（Android `SpeechRecognizer.ERROR_*` 的归并）。 */
enum class SegmentError {
    /** 没听到话 / 听不出内容（`ERROR_SPEECH_TIMEOUT`、`ERROR_NO_MATCH`）：分段结束，不是故障。 */
    NoSpeech,
    /** 识别器忙或客户端状态错（`ERROR_RECOGNIZER_BUSY`、`ERROR_CLIENT`）：丢弃实例后重建。 */
    Busy,
    /** 网络/服务端/音频（`ERROR_NETWORK*`、`ERROR_SERVER*`、`ERROR_AUDIO`、`ERROR_TOO_MANY_REQUESTS`）。 */
    Transient,
    /** 没有麦克风权限（`ERROR_INSUFFICIENT_PERMISSIONS`）。 */
    Permission,
    /** 语言没有可用的识别包或不支持（`ERROR_LANGUAGE_*`）。 */
    LanguageUnavailable,
}

interface SegmentCallbacks {
    fun onReady()
    fun onPartial(text: String)
    /** 这一段结束并带回最终文字（可能为空）。 */
    fun onResult(text: String)
    fun onLevel(level: Float)
    fun onError(error: SegmentError)
}

/**
 * 「一段」识别：开始 → 若干部分结果 → 一个最终结果（停顿后识别器自己结束）或错误。
 * [ContinuousDictationEngine] 在它上面做停顿续听。Android 实现见 [AndroidSpeechSegmentSource]。
 */
interface SpeechSegmentSource {
    fun tier(language: AppLanguage): DictationEngineTier

    /** 开始一段；false = 平台拒绝（不可用）。回调都在主线程。同一时刻只会有一段。 */
    fun begin(language: AppLanguage, callbacks: SegmentCallbacks): Boolean

    /** 请求收尾：识别器应很快带着最终结果回调 `onResult`（或 `onError`）。 */
    fun finish()

    /** 立即丢弃当前段与识别器实例；之后不再有回调。 */
    fun abort()
}
