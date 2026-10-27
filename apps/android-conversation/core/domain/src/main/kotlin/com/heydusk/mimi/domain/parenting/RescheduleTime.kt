package com.heydusk.mimi.domain.parenting

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.regex.MatchResult
import java.util.regex.Pattern

/**
 * 对"改期建议"的有界校验器（iOS `ParentingRescheduleTime`），不是自然语言排程引擎。
 * 被选中的安排从不作为输入：旧日期和旧钟点不能为缺失的证据背书。
 */
internal object RescheduleTime {
    data class Verification(val dueAt: Instant?, val clarification: String?)

    private const val CLARIFICATION = "请补充一个明确日期和钟点，例如“明天下午五点”或“2027-02-18 17:00”。有歧义或夏令时变化的时间请手动选择后核对。"
    private const val FLAGS = Pattern.UNICODE_CHARACTER_CLASS

    private val datePattern = Pattern.compile("(?<![0-9])[0-9]{4}-[0-9]{2}-[0-9]{2}(?![0-9])|今天|明天|后天", FLAGS)
    private val clockPattern = Pattern.compile(
        "(?<![0-9零〇一二两三四五六七八九十:：])(?:(凌晨|早上|上午|中午|下午|傍晚|晚上)\\s*)?([0-9]{1,2}|[零〇一二两三四五六七八九十]{1,3})" +
            "(?:[:：]([0-9]{2})(?:[:：]([0-9]{2}))?|[点时](?:([0-9]{1,2}|[零〇一二两三四五六七八九十]{1,3})分?|(半))?)" +
            "(?![0-9零〇一二两三四五六七八九十半刻分:：]|\\.[0-9])",
        FLAGS,
    )
    private val absolutePattern = Pattern.compile(
        "(?<![0-9])([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(Z|[+-][0-9]{2}:[0-9]{2})(?![0-9:+-]|\\.[0-9])", FLAGS,
    )
    // 不支持的时间操作或额外的日期/时区线索保持可编辑，不去猜。
    private val unsupportedPattern = Pattern.compile(
        "大后天|昨天|前天|周[一二三四五六日天1-7]|星期|[上下本这]周|[0-9一二两三四五六七八九十]+月[0-9一二两三四五六七八九十]+|取消|每[天周月]|每天|每星期|推迟|延后|提前|小时后|分钟后|一刻|三刻|左右|大约|大概|之前|之后|以前|以后|[点时分]多|不是|不要|不改|不用|或者|或是|也许|UTC|GMT|北京时间|当地时间|纽约时间|时区|[A-Za-z_]+/[A-Za-z_]+",
        FLAGS,
    )
    private val extraTime1 = Pattern.compile("[+-][0-9]{2}:?[0-9]{2}|[上下]午|凌晨|早上|中午|傍晚|晚上", FLAGS)
    private val extraTime2 = Pattern.compile("[0-9]+\\s*[:：]|[:：]\\s*[0-9]+", FLAGS)
    private val extraTime3 = Pattern.compile("(?i)(?<![A-Za-z])(?:Z|AM|PM)(?![A-Za-z])", FLAGS)

    fun verify(proposed: Instant, sourceText: String, evidence: String, now: Instant, timeZone: ZoneId): Verification {
        if (matches(unsupportedPattern, sourceText).isNotEmpty() || !sourceText.contains(evidence)) return unresolved()
        val absolute = matches(absolutePattern, sourceText)
        if (absolute.isNotEmpty()) {
            if (absolute.size != 1) return unresolved()
            val match = absolute[0]
            val expression = capture(0, match, sourceText) ?: return unresolved()
            if (!evidence.contains(expression)) return unresolved()
            val year = number(1, match, sourceText) ?: return unresolved()
            val month = number(2, match, sourceText) ?: return unresolved()
            val day = number(3, match, sourceText) ?: return unresolved()
            val hour = number(4, match, sourceText) ?: return unresolved()
            val minute = number(5, match, sourceText) ?: return unresolved()
            val second = number(6, match, sourceText) ?: return unresolved()
            val offset = capture(7, match, sourceText) ?: return unresolved()
            val zone = fixedZone(offset) ?: return unresolved()
            if (!noOtherTime(removing(listOf(match), sourceText))) return unresolved()
            val expected = strictDate(year, month, day, hour, minute, second, zone) ?: return unresolved()
            if (expected != proposed) return unresolved()
            return Verification(expected, null)
        }

        val dates = matches(datePattern, sourceText)
        val clocks = matches(clockPattern, sourceText)
        if (dates.size != 1 || clocks.size != 1) return unresolved()
        val date = dates[0]
        val clock = clocks[0]
        val dateText = capture(0, date, sourceText) ?: return unresolved()
        val clockText = capture(0, clock, sourceText) ?: return unresolved()
        if (!evidence.contains(dateText) || !evidence.contains(clockText)) return unresolved()
        if (!noOtherTime(removing(listOf(date, clock), sourceText))) return unresolved()
        val ymd = dateFields(dateText, now, timeZone) ?: return unresolved()
        val hms = clockFields(clock, sourceText, relative = !dateText.contains("-")) ?: return unresolved()
        val expected = strictDate(ymd.year, ymd.month, ymd.day, hms.hour, hms.minute, hms.second, timeZone) ?: return unresolved()
        if (expected != proposed) return unresolved()
        return Verification(expected, null)
    }

    private fun unresolved() = Verification(null, CLARIFICATION)

    private fun matches(pattern: Pattern, text: String): List<MatchResult> {
        val m = pattern.matcher(text)
        val out = mutableListOf<MatchResult>()
        while (m.find()) out.add(m.toMatchResult())
        return out
    }

    private fun capture(i: Int, match: MatchResult, text: String): String? {
        if (i > match.groupCount()) return null
        val s = match.start(i)
        if (s < 0) return null
        return text.substring(s, match.end(i))
    }

    private fun number(i: Int, match: MatchResult, text: String): Int? =
        capture(i, match, text)?.let { s -> if (s.isNotEmpty() && s.all { it in '0'..'9' }) s.toIntOrNull() else null }

    private fun removing(matches: List<MatchResult>, text: String): String {
        val sb = StringBuilder(text)
        for (r in matches.sortedByDescending { it.start() }) sb.replace(r.start(), r.end(), " ")
        return sb.toString()
    }

    private fun noOtherTime(text: String): Boolean =
        matches(datePattern, text).isEmpty() && matches(clockPattern, text).isEmpty() &&
            matches(unsupportedPattern, text).isEmpty() && matches(extraTime1, text).isEmpty() &&
            matches(extraTime2, text).isEmpty() && matches(extraTime3, text).isEmpty()

    private fun fixedZone(text: String): ZoneId? {
        if (text == "Z") return ZoneOffset.UTC
        val pieces = text.drop(1).split(":")
        if (pieces.size != 2) return null
        val hour = pieces[0].toIntOrNull() ?: return null
        val minute = pieces[1].toIntOrNull() ?: return null
        if (hour > 23 || minute > 59) return null
        val seconds = (if (text.first() == '-') -1 else 1) * (hour * 3600 + minute * 60)
        return runCatching { ZoneOffset.ofTotalSeconds(seconds) as ZoneId }.getOrNull()
    }

    private data class Day(val year: Int, val month: Int, val day: Int)
    private data class Clock(val hour: Int, val minute: Int, val second: Int)

    private fun dateFields(text: String, now: Instant, zone: ZoneId): Day? {
        if (text.contains("-")) {
            val fields = text.split("-").mapNotNull { it.toIntOrNull() }
            if (fields.size != 3) return null
            return Day(fields[0], fields[1], fields[2])
        }
        val days = when (text) { "今天" -> 0; "明天" -> 1; "后天" -> 2; else -> return null }
        // 先在当地取日历日，再按民用日期加天数；被跳过的当地日不会变成它的后一天。
        val civil: LocalDate = LocalDateTime.ofInstant(now, zone).toLocalDate().plusDays(days.toLong())
        return Day(civil.year, civil.monthValue, civil.dayOfMonth)
    }

    private fun clockFields(match: MatchResult, text: String, relative: Boolean): Clock? {
        val period = capture(1, match, text)
        val hourText = capture(2, match, text) ?: return null
        val inputHour = chineseOrAscii(hourText) ?: return null
        val colonMinute = number(3, match, text)
        val colonSecond = number(4, match, text)
        val minuteText = capture(5, match, text)
        val half = capture(6, match, text) != null
        if (period == null) {
            // 单独的"五点"/"5点"不知道是哪个时段；冒号写法明确是 24 小时制。
            val minute = colonMinute ?: return null
            if (hourText.toIntOrNull() == null) return null
            return Clock(inputHour, minute, colonSecond ?: 0)
        }
        if (!relative) return null
        val hour = when (period) {
            "凌晨" -> if (inputHour in 0..5) inputHour else return null
            "早上", "上午" -> if (inputHour in 1..11) inputHour else return null
            "中午" -> if (inputHour == 12) 12 else return null
            "下午" -> if (inputHour in 1..11) inputHour + 12 else return null
            "傍晚" -> if (inputHour in 5..7) inputHour + 12 else return null
            "晚上" -> if (inputHour in 6..11) inputHour + 12 else return null
            else -> return null
        }
        val minute = when {
            colonMinute != null -> colonMinute
            half -> 30
            minuteText != null -> chineseOrAscii(minuteText) ?: return null
            else -> 0
        }
        return Clock(hour, minute, colonSecond ?: 0)
    }

    private val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)

    private fun chineseOrAscii(text: String): Int? {
        if (text.isNotEmpty() && text.all { it in '0'..'9' }) return text.toIntOrNull()
        if (text.length == 1) return if (text[0] == '十') 10 else digits[text[0]]
        val pieces = text.split("十")
        if (pieces.size != 2 || pieces[0].length > 1 || pieces[1].length > 1) return null
        val tens = pieces[0].firstOrNull()?.let { digits[it] } ?: (if (pieces[0].isEmpty()) 1 else -1)
        val units = pieces[1].firstOrNull()?.let { digits[it] } ?: (if (pieces[1].isEmpty()) 0 else -1)
        if (tens !in 1..9 || units !in 0..9) return null
        return tens * 10 + units
    }

    /**
     * 严格解释当地钟点：不存在（夏令时空档）或不唯一（回拨重复）都返回 null，绝不猜。
     * 用 java.time 的 `getValidOffsets`：恰好一个有效偏移才是唯一的瞬间。
     */
    fun strictDate(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, zone: ZoneId): Instant? {
        if (year !in 1..9999 || month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        val local = runCatching { LocalDateTime.of(year, month, day, hour, minute, second) }.getOrNull() ?: return null
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.size != 1) return null
        return local.toInstant(offsets[0])
    }
}
