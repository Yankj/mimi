package com.heydusk.mimi.feature.parenting.composer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.heydusk.mimi.domain.dictation.DictationEngineTier
import com.heydusk.mimi.domain.language.AppLanguage
import com.heydusk.mimi.voice.DictationController
import com.heydusk.mimi.voice.DictationEngine
import com.heydusk.mimi.voice.MicrophonePermission

/** 输入卡显示哪种输入。默认语音；键盘一步之遥；手动表单不在这里（在 + 菜单里）。 */
enum class ParentingInputMode { Voice, Keyboard }

/** 输入行此刻的样子（纯函数：由模式、是否在听写、草稿是否为空推出；有单测）。 */
enum class ParentingVoicePresentation {
    /** 大的「按住说话」胶囊，还没说话。 */
    VoiceIdle,
    /** 胶囊显示电平和计时；上方可编辑的转写框实时填字。 */
    Listening,
    /** 话在可编辑的转写框里，麦克风没开：可以改、可以再说、可以发送。 */
    Preview,
    /** 文字框，右边是麦克风（有字后变成发送）。 */
    Keyboard,
    /** 键盘布局下麦克风在听（字落进文字框）。 */
    KeyboardListening,
    ;

    companion object {
        fun make(mode: ParentingInputMode, dictating: Boolean, draftIsEmpty: Boolean): ParentingVoicePresentation = when {
            mode == ParentingInputMode.Voice && dictating -> Listening
            mode == ParentingInputMode.Voice -> if (draftIsEmpty) VoiceIdle else Preview
            dictating -> KeyboardListening
            else -> Keyboard
        }
    }
}

/** 「已经讲过首次说明」的开关。界面同步读；:app 里由 DataStore 支撑。 */
interface VoicePrefs {
    fun isExplained(): Boolean
    fun setExplained()

    class InMemory(private var explained: Boolean = false) : VoicePrefs {
        override fun isExplained() = explained
        override fun setExplained() { explained = true }
    }
}

internal val NO_OP: () -> Unit = {}

/** 输入卡需要的语音依赖。`permission == null` 时界面自己用系统权限对话框（真机）；测试传假的。 */
class VoiceDeps(
    val engine: DictationEngine,
    val prefs: VoicePrefs = VoicePrefs.InMemory(),
    val permission: MicrophonePermission? = null,
    /** 跳到本应用的系统设置页（权限被拒后的「去设置」）。 */
    val openAppSettings: () -> Unit = NO_OP,
    /** 手势区域变化时的触感。 */
    val haptic: (DictationController.HoldZone) -> Unit = {},
    /** 当前语言；默认由界面的应用语言决定。 */
    val language: (() -> AppLanguage)? = null,
    /** 语音提示条多久自动消失（权限被拒 / 不支持的两条不自动消失）。 */
    val noticeMillis: Long = 5_000,
    /** 「已保存」回执多久后自动收起（iOS 1.6 秒）；0 = 不自动收起（读屏用户、测试）。 */
    val receiptMillis: Long = 1_600,
)

/**
 * 一张输入卡的语音状态：输入模式、首次说明、听写控制器（在这张卡里独立，不和别处共用）。
 * 它填的草稿是 [NaturalInputModel.draft]。
 */
class ParentingVoiceState(
    private val deps: VoiceDeps,
    val permission: MicrophonePermission,
    language: () -> AppLanguage,
) {
    var mode by mutableStateOf(ParentingInputMode.Voice)
        private set
    /** 第一次按麦克风时，在弹系统权限之前先显示的一句话。 */
    var explaining by mutableStateOf(false)
        private set

    val controller = DictationController(deps.engine, deps.language ?: language).also {
        it.reportsSilenceAndInterruption = true
        it.onZoneHaptic = deps.haptic
    }

    val tier: DictationEngineTier get() = controller.tier
    val noticeMillis: Long get() = deps.noticeMillis
    val receiptMillis: Long get() = deps.receiptMillis

    /** 这次听写会不会经系统在线识别（用于首次说明与设置页的如实告知）。 */
    val mayUseOnlineRecognition: Boolean get() = tier == DictationEngineTier.ServerRecognizer

    fun presentation(draftIsEmpty: Boolean, dictating: Boolean) =
        ParentingVoicePresentation.make(mode, dictating, draftIsEmpty)

    /**
     * 麦克风手势入口。第一次按只显示说明（还不弹系统权限），返回 false。
     * 已经说明过但权限还没给：弹系统权限，给了就以「点一下开始」的方式开始（手指早就松了），返回 false。
     */
    fun requestStart(hold: Boolean): Boolean {
        if (explaining) return false
        if (controller.tier == DictationEngineTier.Unavailable) {
            controller.setNotice(DictationController.Notice.Unsupported)
            return false
        }
        if (!permission.isGranted()) {
            if (!deps.prefs.isExplained()) { explaining = true; return false }
            askSystemThenStart()
            return false
        }
        if (hold) controller.beginHold() else controller.toggle()
        return true
    }

    /** 继续：记下已讲过，然后请系统权限；给了就开始听。 */
    fun acceptExplanation() {
        deps.prefs.setExplained()
        explaining = false
        if (permission.isGranted()) controller.toggle() else askSystemThenStart()
    }

    fun declineExplanation() {
        explaining = false
        mode = ParentingInputMode.Keyboard
    }

    private fun askSystemThenStart() {
        permission.request { granted ->
            if (granted) controller.toggle() else controller.setNotice(DictationController.Notice.PermissionDenied)
        }
    }

    fun switchMode(next: ParentingInputMode) {
        if (next == mode) return
        controller.stopNow()
        explaining = false
        mode = next
    }

    fun toggleMode() = switchMode(if (mode == ParentingInputMode.Voice) ParentingInputMode.Keyboard else ParentingInputMode.Voice)

    fun openAppSettings() = deps.openAppSettings()
}
