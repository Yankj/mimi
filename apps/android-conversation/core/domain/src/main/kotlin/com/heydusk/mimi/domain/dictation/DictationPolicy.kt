package com.heydusk.mimi.domain.dictation

import com.heydusk.mimi.domain.language.AppLanguage
import java.text.BreakIterator
import java.util.Locale

/** 不需要音频的听写规则（iOS `DictationPolicy`）。 */
object DictationPolicy {
    /** 识别语言跟随应用语言（设置 › 语言），不写死 zh-CN。 */
    fun localeIdentifier(language: AppLanguage): String = if (language == AppLanguage.English) "en-US" else "zh-CN"

    /**
     * 把听写文字接到草稿（或前面的分段）后面。中日韩文字之间不插空格（中文从不用空格），
     * 换行/空格/中日韩标点之后也不插；拉丁单词之间插一个。
     */
    fun join(head: String, tail: String): String {
        val last = lastCharacter(head) ?: return head + tail
        val first = firstCharacter(tail) ?: return head + tail
        if (isWhitespace(last) || isWhitespace(first)) return head + tail
        if (isCjk(last) || isCjk(first)) return head + tail
        if (isPunctuation(first)) return head + tail
        return "$head $tail"
    }

    // Swift 的 Character 是字形簇；这里按字形簇取首尾，再按其码点判断。
    private fun firstCharacter(s: String): String? {
        if (s.isEmpty()) return null
        val it = BreakIterator.getCharacterInstance(Locale.ROOT)
        it.setText(s)
        val end = it.next()
        return s.substring(0, end)
    }

    private fun lastCharacter(s: String): String? {
        if (s.isEmpty()) return null
        val it = BreakIterator.getCharacterInstance(Locale.ROOT)
        it.setText(s)
        val start = it.preceding(s.length)
        return s.substring(start)
    }

    /** Swift `Character.isWhitespace`：整个簇是空白（以首个标量判断，簇里的组合符不改变结果）。 */
    private fun isWhitespace(c: String): Boolean {
        val cp = c.codePointAt(0)
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp)
    }

    private fun isPunctuation(c: String): Boolean = when (Character.getType(c.codePointAt(0)).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
        else -> false
    }

    internal fun isCjk(c: String): Boolean {
        var i = 0
        while (i < c.length) {
            val v = c.codePointAt(i)
            i += Character.charCount(v)
            if (v in 0x4E00..0x9FFF || v in 0x3400..0x4DBF || v in 0x3000..0x303F || v in 0xFF00..0xFFEF || v in 0x3040..0x30FF || v in 0xAC00..0xD7AF) return true
        }
        return false
    }
}

/** 一次听写会话跨识别分段的文字。一个分段在停顿（最终结果）、识别错误或单次请求时限时结束；它的文字被提交，新分段开始，所以说过的话不会丢。 */
class DictationTranscript {
    var committed: String = ""
        private set
    var current: String = ""
        private set

    val text: String get() = DictationPolicy.join(committed, current)
    fun update(partial: String) { current = partial }
    fun commit() { committed = text; current = "" }
}

object DictationRestart {
    /** 连续多少个分段在没有任何文字的情况下以错误结束就放弃（麦克风坏了、识别器挂了）。 */
    const val MAX_EMPTY_FAILURES = 3

    enum class Decision { Restart, Finish, Fail }

    /** 用户仍在听写（不是点了停止/松手之后）时某个分段结束时调用。 */
    fun decide(failed: Boolean, segmentHadText: Boolean, emptyFailures: Int, sessionHasText: Boolean): Decision {
        if (!failed) return Decision.Restart // 停顿 → 继续听
        if (segmentHadText || emptyFailures + 1 < MAX_EMPTY_FAILURES) return Decision.Restart
        return if (sessionHasText) Decision.Finish else Decision.Fail
    }
}

/**
 * 根因 1：用哪个识别器，最好的在前。iOS 用 SpeechAnalyzer/DictationTranscriber/SFSpeechRecognizer；
 * Android 的对应档位（`SpeechRecognizer` 的设备端 / 服务端识别）由 M3 映射，选择规则保持同一函数。
 */
enum class DictationEngineTier {
    SpeechTranscriber, DictationTranscriber, OnDeviceRecognizer, ServerRecognizer, Unavailable,
    ;

    /** 识别请求有时长上限（服务端约 1 分钟）；到点之前轮换继续。 */
    val segmentLimitSeconds: Double? get() = if (this == OnDeviceRecognizer || this == ServerRecognizer) RECOGNIZER_SEGMENT_LIMIT_SECONDS else null
    val usesNetwork: Boolean get() = this == ServerRecognizer

    companion object {
        const val RECOGNIZER_SEGMENT_LIMIT_SECONDS = 55.0

        fun choose(
            analyzerOS: Boolean, speechTranscriberReady: Boolean, dictationTranscriberReady: Boolean,
            recognizerAvailable: Boolean, recognizerOnDevice: Boolean,
        ): DictationEngineTier {
            if (analyzerOS && speechTranscriberReady) return SpeechTranscriber
            if (analyzerOS && dictationTranscriberReady) return DictationTranscriber
            if (!recognizerAvailable) return Unavailable
            return if (recognizerOnDevice) OnDeviceRecognizer else ServerRecognizer
        }
    }
}
