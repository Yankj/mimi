package com.heydusk.mimi.voice

import com.heydusk.mimi.domain.dictation.DictationEngineTier
import com.heydusk.mimi.domain.language.AppLanguage

/**
 * 脚本化的假识别引擎（iOS `ScriptedDictationTranscriber` 的对应物）：JVM / Robolectric 测试里没有麦克风，
 * 由测试用 [say]、[level]、[endBy] 驱动会话。停止/取消同步结束。
 */
class ScriptedDictationEngine(
    var tier: DictationEngineTier = DictationEngineTier.OnDeviceRecognizer,
) : DictationEngine {
    override var phase: DictationEngine.Phase = DictationEngine.Phase.Idle
        private set

    private var listener: DictationEngine.Listener? = null

    /** 会话一开始就立刻给出的文字（模拟「说完一整句」）；null = 等测试调用 [say]。 */
    var autoTranscript: String? = null

    /** 非 null：下一次启动立刻以此原因结束（模拟被拒/失败）。 */
    var failNextStartWith: DictationEnd? = null

    var startCount = 0
        private set
    var lastLanguage: AppLanguage? = null
        private set

    val running: Boolean get() = phase != DictationEngine.Phase.Idle

    override fun tier(language: AppLanguage): DictationEngineTier = tier

    override fun startDictation(language: AppLanguage, listener: DictationEngine.Listener): Boolean {
        if (phase != DictationEngine.Phase.Idle || tier == DictationEngineTier.Unavailable) return false
        startCount += 1
        lastLanguage = language
        this.listener = listener
        phase = DictationEngine.Phase.Listening
        failNextStartWith?.let { reason -> failNextStartWith = null; endBy(reason); return true }
        autoTranscript?.let(listener::onText)
        return true
    }

    override fun stopDictation() {
        if (phase == DictationEngine.Phase.Idle) return
        endBy(DictationEnd.Finished)
    }

    override fun cancelDictation() {
        if (phase == DictationEngine.Phase.Idle) return
        endBy(DictationEnd.Cancelled)
    }

    /** 测试：识别器给出了（部分）文字。 */
    fun say(text: String) { listener?.onText(text) }

    /** 测试：输入电平。 */
    fun level(value: Float) { listener?.onLevel(value) }

    /** 测试：引擎自己结束（被打断、识别失败、时限……）。 */
    fun endBy(reason: DictationEnd) {
        if (phase == DictationEngine.Phase.Idle) return
        val target = listener
        listener = null
        phase = DictationEngine.Phase.Idle
        target?.onEnd(reason)
    }
}
