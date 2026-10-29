package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.domain.common.trimSwift
import java.time.Instant
import java.util.regex.Matcher
import java.util.regex.Pattern

/** UTF-16 区间（与 Swift `NSRange` 的 location/length 同义）。 */
data class Span(val location: Int, val length: Int) {
    val end: Int get() = location + length
}

/**
 * 自然语言入口的语法数据与有界的日期/钟点读取器（iOS `ParentingNaturalLexicon`）。
 * 这里全是"家长可能说的话"的源内容，不是界面文案；不联网、不依赖模型或界面。
 * 只读家长写下的字：钟点不明就保持未知，绝不猜。
 */
internal object NaturalLexicon {
    val rescheduleTriggers = listOf("改到", "改成", "改期", "调到", "调成", "挪到", "推迟到", "延后到", "提前到", "换到", "改在", "调课", "改时间")
    val reviewMarkers = listOf("复盘", "回顾", "回看", "总结", "小结", "最近怎么样", "最近有什么", "进步了吗")
    val todayQueryMarkers = listOf("今天有什么", "今天有啥", "今天的安排", "今天安排", "看看今天", "看今天", "今天要做什么", "今天干什么", "今天做什么")
    val todayWords = listOf("今天", "今日", "今晚")
    val queryCues = listOf("有什么", "有啥", "什么安排", "啥安排", "安排吗", "做什么", "干什么", "看看", "看一下", "吗", "？", "?")
    val planQueryMarkers = listOf("怎么排", "这周安排", "本周安排", "这周有什么", "本周有什么", "下周安排", "下周有什么", "课表", "看看计划", "兴趣班怎么")
    val observationMarkers = listOf("观察", "学会", "第一次", "自己", "今天他", "今天她", "刚才", "刚刚", "开始会", "主动")
    val observationLeads = listOf("记一下", "记下", "记录一下", "记录")
    val arrangementWords = listOf(
        "课", "班", "要带", "带", "接", "送", "提醒", "家长会", "考试", "体检", "预约", "活动", "比赛", "疫苗", "作业",
        "缴费", "交费", "报名", "游泳", "钢琴", "画画", "舞蹈", "英语", "足球", "篮球",
    )
    val futureDateWords = listOf("明天", "明早", "明晚", "后天", "大后天")
    val fillerPrefixes = listOf("帮我记一下", "帮我记下", "让小咪记一下", "让小咪记下", "小咪记一下", "小咪", "帮我记", "记一下", "记下", "记录一下", "麻烦", "请", "帮我", "提醒我", "提醒")
    val trailingFillers = listOf("了", "吧", "哦", "啦", "呀", "呢", "。", "！", "!", ".", "，", ",", "、")
    val connectors = listOf("的", "要", "在", "去", "是", "有", "到", "于", "和", "，", ",", "。", "、", " ")
    val dayPartLabels: List<Pair<String, Int>> = listOf("上午" to 9, "下午" to 15, "晚上" to 19)

    data class TimeReading(
        /** 文字里写明的那一天的零点。 */
        var day: Instant? = null,
        /** 文字毫无疑问地确定了钟点时是 0...23。 */
        var hour: Int? = null,
        var minute: Int = 0,
        /** 写了钟点但没写上午/下午时是 1...12，必须由家长选。 */
        var ambiguousHour: Int? = null,
        /** 作为日期/钟点依据的字（UTF-16 区间），推导标题时要删掉。 */
        val consumed: MutableList<Span> = mutableListOf(),
    ) {
        val hasClock: Boolean get() = hour != null || ambiguousHour != null
    }

    private const val PART = "(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里)"
    private const val NUMBER = "([0-9]{1,2}|[零〇一二两三四五六七八九十]{1,3})"
    private const val FLAGS = Pattern.UNICODE_CHARACTER_CLASS
    private val colonPattern = Pattern.compile("$PART?\\s*$NUMBER[:：]([0-9]{2})", FLAGS)
    private val dianPattern = Pattern.compile("$PART?\\s*$NUMBER[点时](?!点)(?:(半)|([0-9]{1,2})分?|([零〇一二两三四五六七八九十]{1,3})分)?", FLAGS)
    private val rangeTail = Pattern.compile("^\\s*(?:到|至|~|-|—)\\s*$PART?\\s*$NUMBER(?:[点时](?!点)(?:半|[0-9]{1,2}分?)?|[:：][0-9]{2})", FLAGS)
    private val isoPattern = Pattern.compile("(?<![0-9])([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})(?![0-9])", FLAGS)
    private val monthDayPattern = Pattern.compile("([0-9]{1,2})月([0-9]{1,2})[日号]", FLAGS)
    private val relativePattern = Pattern.compile("大后天|后天|明天|明早|明晚|今天|今晚|今早|今日", FLAGS)
    private val weekdayPattern = Pattern.compile("(下个?|这个?|本)?(?:周|星期|礼拜)([一二三四五六日天])", FLAGS)

    private val relativeOffsets = mapOf("大后天" to 3, "后天" to 2, "明天" to 1, "明早" to 1, "明晚" to 1, "今天" to 0, "今晚" to 0, "今早" to 0, "今日" to 0)
    private val weekdays = mapOf("一" to 2, "二" to 3, "三" to 4, "四" to 5, "五" to 6, "六" to 7, "日" to 1, "天" to 1)

    fun read(text: String, now: Instant, calendar: MimiCalendar): TimeReading {
        val result = TimeReading()
        var hint: String? = null
        val today = calendar.startOfDay(now)

        var handled = false
        first(isoPattern, text, 0)?.let { m ->
            val y = int(m, 1); val mo = int(m, 2); val d = int(m, 3)
            if (y != null && mo != null && d != null) {
                val date = calendar.date(y, mo, d)
                if (date != null && calendar.month(date) == mo && calendar.day(date) == d) {
                    result.day = calendar.startOfDay(date); result.consumed.add(span(m)); handled = true
                }
            }
        }
        if (!handled) {
            val md = first(monthDayPattern, text, 0)
            val mdMo = md?.let { int(it, 1) }
            val mdD = md?.let { int(it, 2) }
            if (md != null && mdMo != null && mdD != null) {
                val year = calendar.year(today)
                for (candidate in listOf(year, year + 1)) {
                    val date = calendar.date(candidate, mdMo, mdD)
                    if (date != null && calendar.month(date) == mdMo && calendar.day(date) == mdD && date >= today) {
                        result.day = date; result.consumed.add(span(md)); break
                    }
                }
            } else {
                val rel = first(relativePattern, text, 0)
                if (rel != null) {
                    val word = rel.group()
                    val offset = relativeOffsets[word]
                    if (offset != null) {
                        result.day = calendar.addingDays(today, offset); result.consumed.add(span(rel))
                        if (word.endsWith("晚")) hint = "晚上" else if (word.endsWith("早")) hint = "上午"
                    }
                } else {
                    val wd = first(weekdayPattern, text, 0)
                    val letter = wd?.let { group(it, 2) }
                    if (wd != null && letter != null) {
                        val weekday = weekdays[letter]
                        if (weekday != null) {
                            val todayIndex = (calendar.weekday(today) + 5) % 7
                            val targetIndex = (weekday + 5) % 7
                            val prefix = group(wd, 1) ?: ""
                            val delta = when {
                                prefix.startsWith("下") -> -todayIndex + 7 + targetIndex
                                prefix.startsWith("这") || prefix.startsWith("本") -> -todayIndex + targetIndex
                                else -> (targetIndex - todayIndex + 7) % 7
                            }
                            result.day = calendar.addingDays(today, delta); result.consumed.add(span(wd))
                        }
                    }
                }
            }
        }

        // 钟点：先冒号写法，再"点"写法。
        var clock: Matcher? = null
        var minute = 0
        val colon = first(colonPattern, text, 0)
        val colonMinute = colon?.let { int(it, 3) }
        if (colon != null && colonMinute != null && colonMinute < 60) {
            clock = colon; minute = colonMinute
        } else {
            val dian = first(dianPattern, text, 0)
            if (dian != null) {
                clock = dian
                if (group(dian, 3) != null) minute = 30
                else {
                    val v = int(dian, 4)
                    if (v != null) minute = v
                    else group(dian, 5)?.let(::chineseNumber)?.let { minute = it }
                }
                if (minute > 59) clock = null
            }
        }
        val m = clock
        val rawHour = m?.let { group(it, 2) }?.let(::chineseNumber)
        if (m != null && rawHour != null && rawHour <= 24) {
            val word = group(m, 1) ?: hint
            var end = m.end()
            result.consumed.add(span(m))
            val tail = first(rangeTail, text, end)
            if (tail != null) { result.consumed.add(span(tail)); end = tail.end() }
            result.minute = minute
            if (word != null) {
                when (word) {
                    "凌晨" -> result.hour = if (rawHour == 12) 0 else rawHour
                    "早上", "早晨", "上午" -> result.hour = rawHour
                    "中午" -> result.hour = if (rawHour <= 4) rawHour + 12 else rawHour
                    else -> { // 下午 傍晚 晚上 夜里
                        if (rawHour == 12) result.ambiguousHour = 12
                        else result.hour = if (rawHour < 12) rawHour + 12 else rawHour
                    }
                }
                val h = result.hour
                if (h != null && h > 23) result.hour = null
            } else if (rawHour >= 13 || rawHour == 0) {
                result.hour = if (rawHour == 24) null else rawHour
            } else {
                result.ambiguousHour = rawHour
            }
        }
        return result
    }

    fun date(day: Instant, hour: Int, minute: Int, calendar: MimiCalendar): Instant? = calendar.date(day, hour, minute, 0)

    // MARK: 意图证据（iOS #143）。单独的「今天/今晚」两边都不算：安排要有未来锚点，记录看已经发生的痕迹。权重见 [intentEvidence]。
    val needPattern = Regex("(?<![不想主重自])要(?![是求紧])|得(?=[带去交打做买准给])|需要|记得|别忘|不要忘|打算|准备|约好|约了")
    val reminderWords = listOf("提醒", "预约", "报名", "缴费", "交费", "帮我安排")
    val scheduleNouns = listOf("课", "班", "家长会", "考试", "体检", "疫苗", "活动", "比赛", "作业", "游泳", "钢琴", "画画", "舞蹈", "英语", "足球", "篮球", "接种", "面试")
    val pastWords = listOf("刚才", "刚刚", "刚", "已经", "终于", "昨天", "昨晚", "昨夜", "前天", "上周", "上个月", "以前", "之前", "早就")
    val milestoneWords = listOf("第一次", "学会", "会了", "自己", "居然", "主动", "开始会", "观察", "今天他", "今天她", "第一声")
    val behaviorWords = listOf("睡", "吃", "喝", "玩", "哭", "笑", "走", "说", "叫", "拉", "烧", "咳", "看", "读", "夸", "视频", "乖", "不肯", "不愿", "一直", "摔", "醒", "尿", "吐")
    val stateComplementPattern = Regex("[睡吃玩哭笑走说看跑跳写画唱喝]得")
    val dayPartPattern = Regex("今晚|晚上|夜里|傍晚|下午|上午|中午|早上|早晨|凌晨")
    val lateClockPattern = Regex("[点时]半?才|[点时]半?就(?!要)")
    val negationPattern = Regex("没(?!有?问题|关系|事)")
    val assistantNames = listOf("小咪")
    val chitchatWords = listOf("谢谢", "感谢", "好的", "好吧", "你好", "您好", "嗯", "哦", "哈哈", "在吗", "拜拜", "再见", "晚安", "没事", "算了")
    val softQuestionWords = listOf("会不会", "是不是", "怎么样", "为什么", "多少", "天气", "下雨", "下雪", "几点了", "什么时候", "能不能", "可不可以")

    /** 只说了时段时的常用钟点；windowEnd = 仍算「这个时段」的最晚小时（不含）。 */
    data class DayPart(val hour: Int, val windowEnd: Int)
    private val dayParts: List<Pair<Regex, DayPart>> = listOf(
        Regex("明早|今早|早上|早晨") to DayPart(8, 10), Regex("上午") to DayPart(9, 12), Regex("中午") to DayPart(12, 14),
        Regex("下午") to DayPart(15, 18), Regex("傍晚") to DayPart(18, 19), Regex("晚上|今晚|明晚|夜里") to DayPart(19, 23),
    )
    fun dayPart(text: String): DayPart? = dayParts.firstOrNull { it.first.containsMatchIn(text) }?.second

    /** 全角数字/字母转半角，全角/不换行空格转空格，中文与标点旁的空格去掉（语音转写会在「，」后加空格）。标点保留。 */
    fun normalized(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i); i += Character.charCount(cp)
            when (cp) {
                in 0xFF10..0xFF19, in 0xFF21..0xFF3A, in 0xFF41..0xFF5A -> sb.appendCodePoint(cp - 0xFEE0)
                0x3000, 0x00A0 -> sb.append(' ')
                else -> sb.appendCodePoint(cp)
            }
        }
        var text = sb.toString().trimSwift()
        val cjk = "[\\u4e00-\\u9fff，。、！？：；,.!?:;“”‘’「」（）()]"
        text = text.replace(Regex("[ \t]+"), " ")
        text = text.replace(Regex("(?<=$cjk)[ \t]+"), "")
        text = text.replace(Regex("[ \t]+(?=$cjk)"), "")
        return text
    }

    data class IntentEvidence(var arrangement: Int = 0, var record: Int = 0)

    /** 有完成态「了」（排除 为了/了解/受不了… 这类固定词）。 */
    fun hasCompletedParticle(text: String): Boolean {
        var t = text
        for (w in listOf("为了", "了解", "了不起", "了得", "受不了", "不了", "除了", "最后了")) t = t.replace(w, "")
        return t.contains("了")
    }

    /** 今年已过去的「X月X日」是过去（read 会滚到明年，不能算未来）。 */
    private fun isPastMonthDay(text: String, today: Instant, calendar: MimiCalendar): Boolean {
        if (text.contains("明年")) return false
        val m = first(monthDayPattern, text, 0) ?: return false
        val mo = int(m, 1) ?: return false
        val d = int(m, 2) ?: return false
        val date = calendar.date(calendar.year(today), mo, d) ?: return false
        return date < today
    }

    fun intentEvidence(text: String, reading: TimeReading, now: Instant, calendar: MimiCalendar): IntentEvidence {
        fun has(words: List<String>) = words.any { text.contains(it) }
        val today = calendar.startOfDay(now)
        val pastMonthDay = isPastMonthDay(text, today, calendar)
        val pastDay = reading.day?.let { it < today } ?: false
        val future = ((reading.day?.let { it > today } ?: false) && !pastMonthDay) || has(futureDateWords)
        val pastWord = has(pastWords) || pastMonthDay || pastDay
        val completed = hasCompletedParticle(text)
        val pastMark = completed || pastWord
        val lateClock = lateClockPattern.containsMatchIn(text)
        val lead = observationLeads.any { text.startsWith(it) }
        val e = IntentEvidence()
        if (future) e.arrangement += 3
        if (reading.hasClock && !pastMark && !lateClock) e.arrangement += 2
        if (needPattern.containsMatchIn(text)) e.arrangement += 2
        if (has(reminderWords)) e.arrangement += if (text.contains("提醒")) 3 else 2
        if (has(scheduleNouns)) e.arrangement += 1
        if (dayPartPattern.containsMatchIn(text) && !pastMark && !reading.hasClock) e.arrangement += 1
        if (pastWord) e.record += 3
        if (completed) e.record += 3
        if (has(milestoneWords)) e.record += 2
        if (stateComplementPattern.containsMatchIn(text)) e.record += 2
        if (negationPattern.containsMatchIn(text) || lateClock) e.record += 2
        if (has(behaviorWords)) e.record += 1
        if (lead && !future) e.record += 3
        return e
    }

    private fun bodyChars(text: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i); i += Character.charCount(cp)
            if (!isWhitespace(cp) && !isPunctuation(cp)) sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    /** 去掉空白、标点、「记一下」类引导语与助手名之后剩下的内容；为空 = 没有要记的东西。 */
    fun contentWithoutLeads(text: String): String {
        var stripped = bodyChars(text)
        for (w in observationLeads + assistantNames) stripped = stripped.replace(w, "")
        return stripped
    }

    /** 寒暄或与孩子无关的提问（「谢谢小咪」「明天会不会下雨」）：没有可记录或安排的。 */
    fun isChitchatOrQuestion(text: String, evidence: IntentEvidence): Boolean {
        fun has(words: List<String>) = words.any { text.contains(it) }
        val body = bodyChars(text)
        val stripped = contentWithoutLeads(text)
        if (body.codePointCount(0, body.length) < 2) return true
        if (chitchatWords.any { stripped.startsWith(it) } && stripped.codePointCount(0, stripped.length) <= 6 && evidence.arrangement == 0 && evidence.record == 0) return true
        val asks = Regex("(吗|呢|[?？])[。.!！~～ ]*$").containsMatchIn(text)
        val soft = has(softQuestionWords) && evidence.record == 0
        return (asks || soft) && !needPattern.containsMatchIn(text) && !text.contains("提醒")
    }

    // MARK: 文本工具

    fun removing(ranges: List<Span>, text: String): String {
        val sb = StringBuilder(text)
        for (r in ranges.sortedByDescending { it.location }) {
            if (r.location + r.length <= sb.length) sb.replace(r.location, r.location + r.length, " ")
        }
        return sb.toString()
    }

    /** 两字窗口集合，用于把口述名称和已存标题做廉价相似度比较。 */
    fun bigrams(text: String): Set<String> {
        val chars = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            i += s.length
            if (!isWhitespace(cp) && !isPunctuation(cp)) chars.add(s)
        }
        if (chars.size < 2) return if (chars.size == 1) setOf(chars[0]) else emptySet()
        return (0 until chars.size - 1).map { chars[it] + chars[it + 1] }.toSet()
    }

    private fun isWhitespace(cp: Int) = Character.isWhitespace(cp) || Character.isSpaceChar(cp)
    private fun isPunctuation(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
        else -> false
    }

    fun cleanTitle(raw: String): String {
        var text = raw.trimSwift()
        var changed = true
        val prefixes = fillerPrefixes.sortedByDescending { it.length }
        while (changed) {
            changed = false
            for (prefix in prefixes) {
                if (text.startsWith(prefix)) {
                    text = text.substring(prefix.length).trimSwift(); changed = true
                }
            }
            for (item in connectors + trailingFillers) {
                if (item.isEmpty()) continue
                if (text.startsWith(item)) { text = text.substring(item.length); changed = true }
                if (text.endsWith(item)) { text = text.substring(0, text.length - item.length); changed = true }
            }
            text = text.trimSwift()
        }
        return text
    }

    /** 句子以完成态语气词"了"结尾（忽略尾部标点与语气词）。 */
    fun endsWithPastMarker(text: String): Boolean {
        var tail = text
        while (tail.isNotEmpty()) {
            val last = tail.last().toString()
            if (last in trailingFillers && last != "了") tail = tail.dropLast(1) else break
        }
        return tail.endsWith("了")
    }

    fun stripLead(raw: String): String {
        var text = raw.trimSwift()
        for (lead in (fillerPrefixes + observationLeads).sortedByDescending { it.length }) {
            if (text.startsWith(lead)) { text = text.substring(lead.length); break }
        }
        while (text.isNotEmpty() && "，,。:：、 ".indexOf(text.first()) >= 0) text = text.substring(1)
        return text.trimSwift()
    }

    fun chineseNumber(text: String): Int? {
        text.toIntOrNull()?.let { if (text.all { c -> c in '0'..'9' || c == '-' || c == '+' }) return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        val chars = text.toList()
        if (chars.isEmpty()) return null
        val tenAt = chars.indexOf('十')
        if (tenAt >= 0) {
            val tens = if (tenAt == 0) 1 else digits[chars[0]]
            val ones = if (tenAt + 1 < chars.size) digits[chars[tenAt + 1]] else 0
            if (tens == null || ones == null || chars.size > 3) return null
            return tens * 10 + ones
        }
        if (chars.size != 1) return null
        return digits[chars[0]]
    }

    // MARK: 正则
    /** `NSRegularExpression.firstMatch(in:range:)`：从 [from, 末尾) 区间匹配，`^` 在区间起点生效，回看不越过区间。 */
    private fun first(pattern: Pattern, text: String, from: Int): Matcher? {
        val m = pattern.matcher(text)
        m.region(from, text.length)
        return if (m.find()) m else null
    }

    private fun span(m: Matcher) = Span(m.start(), m.end() - m.start())
    private fun group(m: Matcher, i: Int): String? = if (i <= m.groupCount()) m.group(i) else null
    private fun int(m: Matcher, i: Int): Int? = group(m, i)?.let { s -> if (s.all { it in '0'..'9' }) s.toIntOrNull() else null }
}
