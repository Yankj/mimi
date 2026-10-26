import Foundation

/// Grammar data and a bounded date/clock reader for the natural-input entry.
/// Everything here is source content (what a parent may say), never UI text. It has no network, model or UI dependency.
/// It reads only what the parent wrote: an unclear hour stays unknown instead of being guessed.
enum ParentingNaturalLexicon {
    // MARK: Intent vocabulary
    static let rescheduleTriggers = ["改到", "改成", "改期", "调到", "调成", "挪到", "推迟到", "延后到", "提前到", "换到", "改在", "调课", "改时间"]
    static let reviewMarkers = ["复盘", "回顾", "回看", "总结", "小结", "最近怎么样", "最近有什么", "进步了吗"]
    static let todayQueryMarkers = ["今天有什么", "今天有啥", "今天的安排", "今天安排", "看看今天", "看今天", "今天要做什么", "今天干什么", "今天做什么"]
    static let todayWords = ["今天", "今日", "今晚"]
    static let queryCues = ["有什么", "有啥", "什么安排", "啥安排", "安排吗", "做什么", "干什么", "看看", "看一下", "吗", "？", "?"]
    static let planQueryMarkers = ["怎么排", "这周安排", "本周安排", "这周有什么", "本周有什么", "下周安排", "下周有什么", "课表", "看看计划", "兴趣班怎么"]
    static let observationMarkers = ["观察", "学会", "第一次", "自己", "今天他", "今天她", "刚才", "刚刚", "开始会", "主动"]
    static let observationLeads = ["记一下", "记下", "记录一下", "记录"]
    static let arrangementWords = ["课", "班", "要带", "带", "接", "送", "提醒", "家长会", "考试", "体检", "预约", "活动", "比赛", "疫苗", "作业", "缴费", "交费", "报名", "游泳", "钢琴", "画画", "舞蹈", "英语", "足球", "篮球"]
    static let futureDateWords = ["明天", "明早", "明晚", "后天", "大后天"]
    static let fillerPrefixes = ["帮我记一下", "帮我记下", "让小咪记一下", "让小咪记下", "小咪记一下", "小咪", "帮我记", "记一下", "记下", "记录一下", "麻烦", "请", "帮我", "提醒我", "提醒"]
    static let trailingFillers = ["了", "吧", "哦", "啦", "呀", "呢", "。", "！", "!", ".", "，", ",", "、"]
    static let connectors = ["的", "要", "在", "去", "是", "有", "到", "于", "和", "，", ",", "。", "、", " "]
    static let dayPartLabels: [(label: String, hour: Int)] = [("上午", 9), ("下午", 15), ("晚上", 19)]

    // MARK: Time reading
    struct TimeReading: Equatable {
        /// Start of the stated day, if the text stated one.
        var day: Date?
        /// 0...23 when the text fixes the hour without doubt.
        var hour: Int?
        var minute: Int = 0
        /// 1...12 when a clock was written without a morning/afternoon word; the parent must choose.
        var ambiguousHour: Int?
        /// UTF-16 spans of the words that were date or clock evidence, for removal when deriving a title.
        var consumed: [NSRange] = []
        var hasClock: Bool { hour != nil || ambiguousHour != nil }
    }

    private static let part = "(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里)"
    private static let number = "([0-9]{1,2}|[零〇一二两三四五六七八九十]{1,3})"
    private static let colonPattern = part + "?\\s*" + number + "[:：]([0-9]{2})"
    private static let dianPattern = part + "?\\s*" + number + "[点时](?!点)(?:(半)|([0-9]{1,2})分?|([零〇一二两三四五六七八九十]{1,3})分)?"
    private static let rangeTail = "^\\s*(?:到|至|~|-|—)\\s*" + part + "?\\s*" + number + "(?:[点时](?!点)(?:半|[0-9]{1,2}分?)?|[:：][0-9]{2})"
    private static let isoPattern = "(?<![0-9])([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})(?![0-9])"
    private static let monthDayPattern = "([0-9]{1,2})月([0-9]{1,2})[日号]"
    private static let relativePattern = "大后天|后天|明天|明早|明晚|今天|今晚|今早|今日"
    private static let weekdayPattern = "(下个?|这个?|本)?(?:周|星期|礼拜)([一二三四五六日天])"

    static func read(_ text: String, now: Date, calendar: Calendar) -> TimeReading {
        var result = TimeReading()
        var hint: String?
        let ns = text as NSString
        let full = NSRange(location: 0, length: ns.length)
        let today = calendar.startOfDay(for: now)

        if let m = first(isoPattern, in: text, range: full),
           let y = int(m, 1, ns), let mo = int(m, 2, ns), let d = int(m, 3, ns),
           let date = calendar.date(from: DateComponents(year: y, month: mo, day: d)),
           calendar.component(.month, from: date) == mo, calendar.component(.day, from: date) == d {
            result.day = calendar.startOfDay(for: date); result.consumed.append(m.range)
        } else if let m = first(monthDayPattern, in: text, range: full), let mo = int(m, 1, ns), let d = int(m, 2, ns) {
            let year = calendar.component(.year, from: today)
            for candidate in [year, year + 1] {
                if let date = calendar.date(from: DateComponents(year: candidate, month: mo, day: d)),
                   calendar.component(.month, from: date) == mo, calendar.component(.day, from: date) == d, date >= today {
                    result.day = date; result.consumed.append(m.range); break
                }
            }
        } else if let m = first(relativePattern, in: text, range: full) {
            let word = ns.substring(with: m.range)
            let offsets: [String: Int] = ["大后天": 3, "后天": 2, "明天": 1, "明早": 1, "明晚": 1, "今天": 0, "今晚": 0, "今早": 0, "今日": 0]
            if let offset = offsets[word] {
                result.day = calendar.date(byAdding: .day, value: offset, to: today); result.consumed.append(m.range)
                if word.hasSuffix("晚") { hint = "晚上" } else if word.hasSuffix("早") { hint = "上午" }
            }
        } else if let m = first(weekdayPattern, in: text, range: full), let letter = group(m, 2, ns) {
            let weekdays: [String: Int] = ["一": 2, "二": 3, "三": 4, "四": 5, "五": 6, "六": 7, "日": 1, "天": 1]
            if let weekday = weekdays[letter] {
                let todayIndex = (calendar.component(.weekday, from: today) + 5) % 7
                let targetIndex = (weekday + 5) % 7
                let prefix = group(m, 1, ns) ?? ""
                let delta: Int
                if prefix.hasPrefix("下") { delta = -todayIndex + 7 + targetIndex }
                else if prefix.hasPrefix("这") || prefix.hasPrefix("本") { delta = -todayIndex + targetIndex }
                else { delta = (targetIndex - todayIndex + 7) % 7 }
                result.day = calendar.date(byAdding: .day, value: delta, to: today); result.consumed.append(m.range)
            }
        }

        // Clock: prefer the colon form, then the "点" form.
        var clock: NSTextCheckingResult?
        var minute = 0
        if let m = first(colonPattern, in: text, range: full), let mm = int(m, 3, ns), mm < 60 {
            clock = m; minute = mm
        } else if let m = first(dianPattern, in: text, range: full) {
            clock = m
            if group(m, 3, ns) != nil { minute = 30 }
            else if let v = int(m, 4, ns) { minute = v }
            else if let v = group(m, 5, ns).flatMap(chineseNumber) { minute = v }
            if minute > 59 { clock = nil }
        }
        if let m = clock, let rawHour = group(m, 2, ns).flatMap(chineseNumber), rawHour <= 24 {
            let word = group(m, 1, ns) ?? hint
            var end = m.range.location + m.range.length
            result.consumed.append(m.range)
            let rest = NSRange(location: end, length: ns.length - end)
            if let tail = first(rangeTail, in: text, range: rest) { result.consumed.append(tail.range); end = tail.range.location + tail.range.length }
            result.minute = minute
            if let word {
                switch word {
                case "凌晨": result.hour = rawHour == 12 ? 0 : rawHour
                case "早上", "早晨", "上午": result.hour = rawHour
                case "中午": result.hour = rawHour <= 4 ? rawHour + 12 : rawHour
                default: // 下午 傍晚 晚上 夜里
                    if rawHour == 12 { result.ambiguousHour = 12 } else { result.hour = rawHour < 12 ? rawHour + 12 : rawHour }
                }
                if let h = result.hour, h > 23 { result.hour = nil }
            } else if rawHour >= 13 || rawHour == 0 {
                result.hour = rawHour == 24 ? nil : rawHour
            } else {
                result.ambiguousHour = rawHour
            }
        }
        return result
    }

    static func date(on day: Date, hour: Int, minute: Int, calendar: Calendar) -> Date? {
        calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day)
    }

    // MARK: Text helpers
    static func removing(_ ranges: [NSRange], from text: String) -> String {
        let ns = NSMutableString(string: text)
        for r in ranges.sorted(by: { $0.location > $1.location }) where r.location + r.length <= ns.length { ns.replaceCharacters(in: r, with: " ") }
        return ns as String
    }

    /// Shared two-character windows, a cheap similarity for matching a spoken name to a saved title.
    static func bigrams(_ text: String) -> Set<String> {
        let chars = Array(text.filter { !$0.isWhitespace && !$0.isPunctuation })
        guard chars.count >= 2 else { return chars.count == 1 ? [String(chars)] : [] }
        return Set((0..<(chars.count - 1)).map { String(chars[$0...($0 + 1)]) })
    }

    static func cleanTitle(_ raw: String) -> String {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        var changed = true
        while changed {
            changed = false
            for prefix in fillerPrefixes.sorted(by: { $0.count > $1.count }) where text.hasPrefix(prefix) {
                text.removeFirst(prefix.count); text = text.trimmingCharacters(in: .whitespacesAndNewlines); changed = true
            }
            for item in connectors + trailingFillers {
                if text.hasPrefix(item) && !item.isEmpty { text.removeFirst(item.count); changed = true }
                if text.hasSuffix(item) && !item.isEmpty { text.removeLast(item.count); changed = true }
            }
            text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return text
    }

    /// True when the sentence ends in the completed-action particle, ignoring trailing punctuation and softeners.
    static func endsWithPastMarker(_ text: String) -> Bool {
        var tail = text
        while let last = tail.last, trailingFillers.contains(String(last)), last != "了" { tail.removeLast() }
        return tail.hasSuffix("了")
    }

    static func stripLead(_ raw: String) -> String {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        for lead in (fillerPrefixes + observationLeads).sorted(by: { $0.count > $1.count }) where text.hasPrefix(lead) {
            text.removeFirst(lead.count); break
        }
        while let f = text.first, "，,。:：、 ".contains(f) { text.removeFirst() }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func chineseNumber(_ text: String) -> Int? {
        if let n = Int(text) { return n }
        let digits: [Character: Int] = ["零": 0, "〇": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9]
        let chars = Array(text)
        guard !chars.isEmpty else { return nil }
        if let tenAt = chars.firstIndex(of: "十") {
            let tens = tenAt == 0 ? 1 : digits[chars[0]]
            let ones = tenAt + 1 < chars.count ? digits[chars[tenAt + 1]] : 0
            guard let tens, let ones, chars.count <= 3 else { return nil }
            return tens * 10 + ones
        }
        guard chars.count == 1, let v = digits[chars[0]] else { return nil }
        return v
    }

    // MARK: Regex plumbing
    private static func first(_ pattern: String, in text: String, range: NSRange) -> NSTextCheckingResult? {
        (try? NSRegularExpression(pattern: pattern))?.firstMatch(in: text, range: range)
    }
    private static func group(_ m: NSTextCheckingResult, _ i: Int, _ ns: NSString) -> String? {
        guard i < m.numberOfRanges, m.range(at: i).location != NSNotFound else { return nil }
        return ns.substring(with: m.range(at: i))
    }
    private static func int(_ m: NSTextCheckingResult, _ i: Int, _ ns: NSString) -> Int? { group(m, i, ns).flatMap { Int($0) } }
}
