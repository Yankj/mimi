package com.heydusk.mimi.voice

import com.heydusk.mimi.domain.dictation.DictationEngineTier
import com.heydusk.mimi.domain.dictation.DictationRestart
import com.heydusk.mimi.domain.dictation.DictationTranscript
import com.heydusk.mimi.domain.language.AppLanguage

/**
 * 在一段一段的 [SpeechSegmentSource] 之上做连续听写（计划 4.6 第 3 条）：
 * 停顿（最终结果）→ 提交这一段、立刻开始下一段，所以说过的话不会丢；
 * 没听到话的段（`NoSpeech`）算「分段结束」而不是故障；连续 [DictationRestart.MAX_EMPTY_FAILURES] 段都没有文字就收尾。
 * 家长点停止后最多再等 [FINAL_WAIT_MILLIS] 毫秒的最终结果。
 */
class ContinuousDictationEngine(
    private val source: SpeechSegmentSource,
    private val scheduler: DictationScheduler,
) : DictationEngine {
    override var phase: DictationEngine.Phase = DictationEngine.Phase.Idle
        private set

    private var listener: DictationEngine.Listener? = null
    private var language: AppLanguage = AppLanguage.Chinese
    private var transcript = DictationTranscript()
    private var userStopping = false
    private var emptyFailures = 0
    private var segmentHadText = false
    private var onlyNoSpeech = true
    private var generation = 0
    private var finalWait: DictationScheduler.Cancelable? = null

    override fun tier(language: AppLanguage): DictationEngineTier = source.tier(language)

    override fun startDictation(language: AppLanguage, listener: DictationEngine.Listener): Boolean {
        if (phase != DictationEngine.Phase.Idle) return false
        if (source.tier(language) == DictationEngineTier.Unavailable) return false
        this.language = language
        this.listener = listener
        transcript = DictationTranscript()
        userStopping = false
        emptyFailures = 0
        onlyNoSpeech = true
        phase = DictationEngine.Phase.Starting
        if (!beginSegment()) {
            phase = DictationEngine.Phase.Idle
            this.listener = null
            return false
        }
        return true
    }

    override fun stopDictation() {
        if (phase == DictationEngine.Phase.Idle || userStopping) return
        userStopping = true
        phase = DictationEngine.Phase.Finishing
        finalWait = scheduler.postDelayed(FINAL_WAIT_MILLIS) { end(DictationEnd.Finished) }
        source.finish()
    }

    override fun cancelDictation() {
        if (phase == DictationEngine.Phase.Idle) return
        end(DictationEnd.Cancelled)
    }

    private fun beginSegment(): Boolean {
        segmentHadText = false
        generation += 1
        val mine = generation
        return source.begin(language, object : SegmentCallbacks {
            override fun onReady() {
                if (mine == generation && phase == DictationEngine.Phase.Starting) phase = DictationEngine.Phase.Listening
            }

            override fun onPartial(text: String) {
                if (mine != generation || phase == DictationEngine.Phase.Idle) return
                if (text.isNotEmpty()) segmentHadText = true
                transcript.update(text)
                listener?.onText(transcript.text)
            }

            override fun onResult(text: String) {
                if (mine != generation || phase == DictationEngine.Phase.Idle) return
                if (text.isNotEmpty()) {
                    segmentHadText = true
                    transcript.update(text)
                    listener?.onText(transcript.text)
                }
                // 空的最终结果 = 这一段什么也没听到。
                if (segmentHadText) segmentEnded(failed = false, error = null) else segmentEnded(failed = true, error = SegmentError.NoSpeech)
            }

            override fun onLevel(level: Float) {
                if (mine != generation || phase == DictationEngine.Phase.Idle) return
                listener?.onLevel(level)
            }

            override fun onError(error: SegmentError) {
                if (mine != generation || phase == DictationEngine.Phase.Idle) return
                segmentEnded(failed = true, error = error)
            }
        })
    }

    private fun segmentEnded(failed: Boolean, error: SegmentError?) {
        if (phase == DictationEngine.Phase.Idle) return
        // 致命的：立刻结束，不管是否在收尾。
        when (error) {
            SegmentError.Permission -> return end(DictationEnd.Denied)
            SegmentError.LanguageUnavailable -> {
                // 没文字就是「不支持」；已经有文字则保留文字、正常收尾。
                return end(if (transcript.text.isEmpty()) DictationEnd.Unsupported else DictationEnd.Finished)
            }
            else -> Unit
        }
        if (segmentHadText) transcript.commit() else transcript.update("")
        // 家长已停止：识别器报的错（没听到话、结束超时）不是故障。
        if (userStopping) return end(DictationEnd.Finished)
        if (failed && error != SegmentError.NoSpeech) onlyNoSpeech = false
        val sessionHasText = transcript.text.isNotEmpty()
        val decision = DictationRestart.decide(failed, segmentHadText, emptyFailures, sessionHasText)
        if (failed && !segmentHadText) emptyFailures += 1 else emptyFailures = 0
        when (decision) {
            DictationRestart.Decision.Restart -> {
                if (error == SegmentError.Busy) source.abort()
                if (!beginSegment()) end(if (sessionHasText) DictationEnd.Finished else DictationEnd.Failed)
            }
            DictationRestart.Decision.Finish -> end(DictationEnd.Finished)
            // 一直没听到话 = 空结果（提示「没听到内容」）；别的错连续出现 = 失败。
            DictationRestart.Decision.Fail -> end(if (onlyNoSpeech) DictationEnd.Finished else DictationEnd.Failed)
        }
    }

    private fun end(reason: DictationEnd) {
        if (phase == DictationEngine.Phase.Idle) return
        finalWait?.cancel()
        finalWait = null
        generation += 1 // 丢弃迟到的回调
        phase = DictationEngine.Phase.Idle
        source.abort()
        val target = listener
        listener = null
        target?.onEnd(reason)
    }

    companion object {
        const val FINAL_WAIT_MILLIS = 2_000L
    }
}
