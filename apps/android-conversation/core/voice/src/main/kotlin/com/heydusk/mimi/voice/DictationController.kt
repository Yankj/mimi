package com.heydusk.mimi.voice

import com.heydusk.mimi.domain.dictation.DictationPolicy
import com.heydusk.mimi.domain.language.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 听写要写入的草稿（输入卡的文字）。 */
interface DictationDraftSink {
    val draft: String
    fun editDraft(text: String)
}

/**
 * 一次听写会话的控制器（iOS `ComposerDictation`）：点一下开始/停止，按住说话（上滑发送、左滑取消），
 * 说的话实时进入草稿；停止/取消/打断各有明确的结果；识别器的问题用 [Notice] 提示，从不弹模态框。
 * 家长在听写中自己打字时，立刻把草稿还给他们。**识别出的文字从不写账本**——它只是输入框里的字。
 * 全部在主线程。
 */
class DictationController(
    private val engine: DictationEngine,
    var language: () -> AppLanguage = { AppLanguage.Chinese },
) {
    enum class Notice { PermissionDenied, Unsupported, Failed, Empty, Interrupted }
    enum class Mode { Tap, Hold }
    enum class HoldZone { None, Cancel, Send }

    private val _dictating = MutableStateFlow(false)
    private val _mode = MutableStateFlow(Mode.Tap)
    private val _zone = MutableStateFlow(HoldZone.None)
    private val _notice = MutableStateFlow<Notice?>(null)
    private val _level = MutableStateFlow(0f)

    /** 从手势开始到引擎报告结束（含正在开始/收尾）。 */
    val dictating: StateFlow<Boolean> = _dictating.asStateFlow()
    val mode: StateFlow<Mode> = _mode.asStateFlow()
    val zone: StateFlow<HoldZone> = _zone.asStateFlow()
    val notice: StateFlow<Notice?> = _notice.asStateFlow()
    val level: StateFlow<Float> = _level.asStateFlow()

    /** 空/打断也给提示（输入卡打开；别处可以关）。 */
    var reportsSilenceAndInterruption = true
    var focusInput: () -> Unit = {}
    var onSend: (() -> Unit)? = null
    /** 手势区域变化时的触感反馈（无 → 发送/取消 = 强，回到无 = 轻）。 */
    var onZoneHaptic: (HoldZone) -> Unit = {}

    private var sink: DictationDraftSink? = null
    private var prefix = ""
    private var lastWritten: String? = null
    private var sendWhenDone = false
    private var userStopped = false
    private var userCancelled = false

    val tier get() = engine.tier(language())

    fun attach(sink: DictationDraftSink) { this.sink = sink }
    fun setNotice(value: Notice?) { _notice.value = value }
    fun clearNotice() { _notice.value = null }

    fun toggle() { if (_dictating.value) stop() else start(Mode.Tap) }

    // 按住说话

    fun beginHold() {
        if (_dictating.value) return
        _zone.value = HoldZone.None
        start(Mode.Hold)
    }

    /** 手指移动了（相对按下点，单位 dp）。左优先于上：取消是更安全的解释。 */
    fun updateHold(dxDp: Float, dyDp: Float) {
        if (!_dictating.value || _mode.value != Mode.Hold) return
        val next = when {
            dxDp < -CANCEL_THRESHOLD_DP -> HoldZone.Cancel
            dyDp < -SEND_THRESHOLD_DP -> HoldZone.Send
            else -> HoldZone.None
        }
        if (next == _zone.value) return
        _zone.value = next
        onZoneHaptic(next)
    }

    fun endHold() {
        if (!_dictating.value || _mode.value != Mode.Hold) return
        when (_zone.value) {
            HoldZone.Cancel -> cancel()
            HoldZone.Send -> confirmAndSend()
            HoldZone.None -> stop()
        }
        _zone.value = HoldZone.None
    }

    // 会话

    private fun start(mode: Mode) {
        val sink = sink ?: return
        if (_dictating.value) return
        // 上一个没收干净的会话绝不能挡住麦克风：先清掉。
        if (engine.phase != DictationEngine.Phase.Idle) engine.cancelDictation()
        prefix = sink.draft
        lastWritten = sink.draft
        sendWhenDone = false
        userCancelled = false
        userStopped = false
        _mode.value = mode
        _dictating.value = true
        _notice.value = null
        _level.value = 0f
        val accepted = engine.startDictation(language(), object : DictationEngine.Listener {
            override fun onText(text: String) = apply(text)
            override fun onLevel(level: Float) { _level.value = level }
            override fun onEnd(reason: DictationEnd) = ended(reason)
        })
        if (!accepted && _dictating.value) {
            // 引擎当场拒绝（不可用）：没有会话，也没有回调。
            _dictating.value = false
            lastWritten = null
            _notice.value = Notice.Unsupported
            focusInput()
        }
    }

    /** 第二次点击 / ✓ / 松手：收尾进草稿。家长停止之后识别器报的「没听到话」之类不算故障。 */
    fun stop() {
        if (!_dictating.value) return
        userStopped = true
        if (engine.phase == DictationEngine.Phase.Idle) ended(DictationEnd.Cancelled) else engine.stopDictation()
    }

    /** 上滑 / TalkBack「发送」：收尾，最终文字落下后再发送一次。 */
    fun confirmAndSend() {
        if (!_dictating.value) return
        sendWhenDone = true
        stop()
    }

    /** ✕ / 左滑：丢掉这次会话说的所有话，草稿恢复到之前。 */
    fun cancel() {
        val sink = sink ?: return
        if (!_dictating.value) return
        val restore = prefix
        sendWhenDone = false
        userCancelled = true
        stopNow()
        sink.editDraft(restore)
    }

    /** 发送 / 退到后台 / 离开页面：立刻停，草稿保持现状。 */
    fun stopNow() {
        if (!_dictating.value) return
        sendWhenDone = false
        userStopped = true
        engine.cancelDictation()
        if (_dictating.value) ended(DictationEnd.Cancelled) // 引擎本来就空闲
    }

    private fun ended(reason: DictationEnd) {
        val hadText = lastWritten != null && lastWritten != prefix
        val send = sendWhenDone && hadText
        val cancelledByUser = userCancelled
        _dictating.value = false
        lastWritten = null
        sendWhenDone = false
        _zone.value = HoldZone.None
        _level.value = 0f
        val stoppedByUser = userStopped
        userStopped = false
        userCancelled = false
        when (reason) {
            DictationEnd.Denied -> { _notice.value = Notice.PermissionDenied; focusInput() }
            DictationEnd.Unsupported -> { _notice.value = Notice.Unsupported; focusInput() }
            DictationEnd.Failed, DictationEnd.TimedOut -> {
                if (!hadText && !stoppedByUser) { _notice.value = Notice.Failed; focusInput() }
                else if (!hadText && reportsSilenceAndInterruption && !cancelledByUser) _notice.value = Notice.Empty
            }
            DictationEnd.Finished ->
                if (!hadText && reportsSilenceAndInterruption && !cancelledByUser) _notice.value = Notice.Empty
            DictationEnd.Interrupted -> if (reportsSilenceAndInterruption) _notice.value = Notice.Interrupted
            DictationEnd.Cancelled -> Unit
        }
        if (send && reason != DictationEnd.Interrupted) onSend?.invoke()
    }

    private fun apply(text: String) {
        val sink = sink ?: return
        if (!_dictating.value) return
        // 听写中家长自己打字了：把输入框还给他们。
        val written = lastWritten
        if (written != null && sink.draft != written) { stopNow(); return }
        sink.editDraft(DictationPolicy.join(prefix, text)) // 中文前不加空格
        lastWritten = sink.draft // 输入框实际存下的（可能被截断/规范化）
    }

    companion object {
        /** 与 iOS 同一组阈值（点 → dp）：左滑 90 取消，上滑 70 发送，按住 0.3 秒进入「按住说话」。 */
        const val CANCEL_THRESHOLD_DP = 90f
        const val SEND_THRESHOLD_DP = 70f
        const val HOLD_DELAY_MILLIS = 300L
    }
}
