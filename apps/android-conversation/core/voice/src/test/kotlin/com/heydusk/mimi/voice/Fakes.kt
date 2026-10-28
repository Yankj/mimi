package com.heydusk.mimi.voice

import com.heydusk.mimi.domain.dictation.DictationEngineTier
import com.heydusk.mimi.domain.language.AppLanguage

/** 手动时钟：测试里 `advance` 才会触发延时任务。 */
class ManualScheduler : DictationScheduler {
    private class Task(val at: Long, val block: () -> Unit) { var cancelled = false }
    private val tasks = mutableListOf<Task>()
    var now = 0L
        private set

    override fun postDelayed(delayMillis: Long, block: () -> Unit): DictationScheduler.Cancelable {
        val task = Task(now + delayMillis, block)
        tasks += task
        return DictationScheduler.Cancelable { task.cancelled = true }
    }

    fun advance(millis: Long) {
        now += millis
        tasks.filter { !it.cancelled && it.at <= now }.sortedBy { it.at }.forEach { task ->
            task.cancelled = true
            task.block()
        }
    }

    val pending: Int get() = tasks.count { !it.cancelled }
}

/** 一段一段由测试驱动的假识别源。 */
class FakeSegmentSource(var tier: DictationEngineTier = DictationEngineTier.OnDeviceRecognizer) : SpeechSegmentSource {
    var callbacks: SegmentCallbacks? = null
    var begins = 0
    var finishes = 0
    var aborts = 0
    var refuse = false

    override fun tier(language: AppLanguage) = tier
    override fun begin(language: AppLanguage, callbacks: SegmentCallbacks): Boolean {
        if (refuse) return false
        begins += 1
        this.callbacks = callbacks
        callbacks.onReady()
        return true
    }
    override fun finish() { finishes += 1 }
    override fun abort() { aborts += 1 }

    fun partial(text: String) = callbacks!!.onPartial(text)
    fun result(text: String) = callbacks!!.onResult(text)
    fun error(error: SegmentError) = callbacks!!.onError(error)
}

class RecordingListener : DictationEngine.Listener {
    val texts = mutableListOf<String>()
    val ends = mutableListOf<DictationEnd>()
    override fun onText(text: String) { texts += text }
    override fun onLevel(level: Float) = Unit
    override fun onEnd(reason: DictationEnd) { ends += reason }
}

class FakeDraft(override var draft: String = "") : DictationDraftSink {
    override fun editDraft(text: String) { draft = text }
}
