package com.heydusk.mimi.voice

import com.heydusk.mimi.domain.dictation.DictationEngineTier
import com.heydusk.mimi.domain.language.AppLanguage

/** 一次听写会话结束的原因（iOS `NativeVoiceController.DictationEnd`）。 */
enum class DictationEnd { Finished, Failed, TimedOut, Denied, Unsupported, Interrupted, Cancelled }

/**
 * 听写引擎：对界面隐藏「识别器一次只听一段」的细节。会话从 [startDictation] 到 `onEnd` 结束，期间文字通过 `onText`
 * 以「已提交的分段 + 当前分段」的整体形式给出（见 [com.heydusk.mimi.domain.dictation.DictationTranscript]）。
 * 所有方法与回调都在主线程。
 */
interface DictationEngine {
    enum class Phase { Idle, Starting, Listening, Finishing }

    interface Listener {
        /** 整段会话到目前为止的文字（已提交分段 + 当前分段）。 */
        fun onText(text: String)
        /** 输入电平 0…1，仅用于界面，不落盘。 */
        fun onLevel(level: Float)
        fun onEnd(reason: DictationEnd)
    }

    val phase: Phase

    /** 用哪一档识别器（本机 / 系统在线 / 不可用）；设置页的诊断行和首次说明的文字都用它。 */
    fun tier(language: AppLanguage): DictationEngineTier

    /** false = 现在无法开始（不可用）；此时不会再有任何回调。 */
    fun startDictation(language: AppLanguage, listener: Listener): Boolean

    /** 家长点了停止/松手：收尾——等最后一段结果落下再 `onEnd(Finished)`。 */
    fun stopDictation()

    /** 立即结束，不等结果；`onEnd(Cancelled)`。会话空闲时什么也不做。 */
    fun cancelDictation()
}

/** 麦克风运行时权限。界面实现用 Activity Result；测试用假的。 */
interface MicrophonePermission {
    fun isGranted(): Boolean
    /** 弹出系统对话框；`onResult` 在主线程。已经授予时同步回调 true。 */
    fun request(onResult: (Boolean) -> Unit)
}

/** 简单的延时调度（Android 用主线程 Handler；测试用手动时钟）。 */
interface DictationScheduler {
    fun interface Cancelable { fun cancel() }
    fun postDelayed(delayMillis: Long, block: () -> Unit): Cancelable
}
