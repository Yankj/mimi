package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.common.MimiCalendar
import com.heydusk.mimi.domain.common.containsNul
import com.heydusk.mimi.domain.common.graphemeCount
import com.heydusk.mimi.domain.common.prefixGraphemes
import com.heydusk.mimi.domain.common.trimSwift
import com.heydusk.mimi.domain.common.uuidString
import java.time.Instant
import java.util.UUID

/** 录入 / 规划 / 复盘。只是后台标签：从不作为导航门槛，也不作为表单展示。 */
enum class ParentingIntentMode(val title: String) {
    Capture("录入"), Plan("规划"), Review("复盘"),
}

enum class ParentingIntentKind(val rawValue: String, val title: String) {
    NewArrangement("newArrangement", "加安排"),
    Reschedule("reschedule", "改期"),
    Observation("observation", "记一笔"),
    ViewToday("viewToday", "看今天"),
    ViewPlan("viewPlan", "看计划"),
    ReviewGrowth("reviewGrowth", "看成长回顾"),
    Unknown("unknown", "还不确定"),
    ;

    val mode: ParentingIntentMode?
        get() = when (this) {
            NewArrangement, Reschedule, Observation -> ParentingIntentMode.Capture
            ViewToday, ViewPlan -> ParentingIntentMode.Plan
            ReviewGrowth -> ParentingIntentMode.Review
            Unknown -> null
        }

    /** 只有这几种才可能产生账本命令，而且只在家长确认卡片之后。 */
    val writes: Boolean get() = this == NewArrangement || this == Reschedule || this == Observation
}

/** 声明顺序就是向家长提问的顺序：一次只问一个，最关键的先问。 */
enum class ParentingMissingField { Intent, Child, Target, Date, Time, Title, Content }

data class ParentingNaturalContext(
    val children: List<ParentingChild>,
    val arrangements: List<ParentingArrangement>,
    val activeChildID: UUID? = null,
) {
    constructor(workspace: ParentingWorkspace, activeChildID: UUID? = null) :
        this(workspace.children, workspace.arrangements, activeChildID)
}

/** 家长对追问的回答。每次重读都会重新套用，已经定下的不会再问。 */
data class ParentingNaturalOverrides(
    val kind: ParentingIntentKind? = null,
    val childID: UUID? = null,
    val targetID: UUID? = null,
    val dueAt: Instant? = null,
)

sealed interface ParentingNaturalAnswer {
    data class Intent(val kind: ParentingIntentKind) : ParentingNaturalAnswer
    data class Child(val id: UUID) : ParentingNaturalAnswer
    data class Target(val id: UUID) : ParentingNaturalAnswer
    data class DueAt(val date: Instant) : ParentingNaturalAnswer

    /** 缺少日期、钟点、标题或内容时的自由文字；与之前说过的话一起重读。 */
    data class Text(val text: String) : ParentingNaturalAnswer
}

data class ParentingFollowUp(
    val field: ParentingMissingField,
    /** 资源键。 */
    val question: String,
    val options: List<Option>,
    val allowsText: Boolean,
    /** 资源键。 */
    val textHint: String?,
) {
    sealed interface Label {
        data class Key(val key: String) : Label
        data class Verbatim(val text: String) : Label
        data class DateTime(val date: Instant) : Label
    }

    /** subtitle：选项下面第二行的文案键（「记下来 / 安排提醒」两个大按钮用）。 */
    data class Option(val id: String, val label: Label, val detail: Instant?, val answer: ParentingNaturalAnswer, val subtitle: String? = null)
}

data class ParentingNaturalProposal(
    val sourceText: String,
    var kind: ParentingIntentKind,
    var childID: UUID? = null,
    var targetID: UUID? = null,
    var title: String? = null,
    var dueAt: Instant? = null,
    /** 改期时家长只说了新钟点，于是沿用了原来的那一天。 */
    var inheritedDate: Boolean = false,
    var observationText: String? = null,
    var missing: List<ParentingMissingField> = emptyList(),
    val overrides: ParentingNaturalOverrides = ParentingNaturalOverrides(),
    internal var childChoices: List<UUID> = emptyList(),
    internal var targetChoices: List<UUID> = emptyList(),
    internal var ambiguousHour: Int? = null,
    internal var ambiguousDay: Instant? = null,
    /** 家长没说时间时补了什么（卡片上会写明）。 */
    var assumedTime: AssumedTime = AssumedTime.None,
    /** 只说了钟点或时段时假定的那一天：今天，或今天已过时的明天。 */
    var assumedDay: AssumedDay = AssumedDay.None,
    /** 两边都有证据（或只有很弱的安排线索）：只问一次，两个大按钮。 */
    var ambiguousIntent: Boolean = false,
) {
    enum class AssumedTime { None, NineOClock, DayPart, LaterThisPart, NextDay }
    enum class AssumedDay { None, Today, Tomorrow }
    val defaultedTime: Boolean get() = assumedTime != AssumedTime.None
    val mode: ParentingIntentMode? get() = kind.mode
    val isReadyToConfirm: Boolean get() = missing.isEmpty() && kind != ParentingIntentKind.Unknown
}

object ParentingNaturalInput {
    const val MAX_LENGTH = 3000

    private val lexicon = NaturalLexicon

    fun classify(
        rawText: String,
        context: ParentingNaturalContext,
        now: Instant,
        calendar: MimiCalendar,
        overrides: ParentingNaturalOverrides = ParentingNaturalOverrides(),
    ): ParentingNaturalProposal {
        val text = lexicon.normalized(rawText)
        val p = ParentingNaturalProposal(sourceText = text, kind = ParentingIntentKind.Unknown, overrides = overrides)
        if (text.isEmpty() || text.graphemeCount() > MAX_LENGTH || text.containsNul()) {
            p.missing = listOf(ParentingMissingField.Intent); return p
        }
        val reading = lexicon.read(text, now, calendar)
        var kind = overrides.kind ?: ParentingIntentKind.Unknown
        if (overrides.kind == null) {
            val verdict = detectVerdict(text, reading, now, calendar)
            kind = verdict.first; p.ambiguousIntent = verdict.second
        }
        p.kind = kind
        val missing = mutableListOf<ParentingMissingField>()
        when (kind) {
            ParentingIntentKind.Unknown -> missing.add(ParentingMissingField.Intent)
            ParentingIntentKind.ViewToday, ParentingIntentKind.ViewPlan, ParentingIntentKind.ReviewGrowth -> Unit
            ParentingIntentKind.NewArrangement -> fillArrangement(p, missing, text, reading, context, now, calendar)
            ParentingIntentKind.Observation -> fillObservation(p, missing, text, context)
            ParentingIntentKind.Reschedule -> fillReschedule(p, missing, text, context, now, calendar)
        }
        p.missing = missing.distinct().sorted()
        return p
    }

    // MARK: 意图
    /**
     * iOS #143 `detectVerdict`：second = true 表示「只问一次」（kind 为 Unknown，家长选 记下来 / 安排提醒）。
     * 顺序：硬意图（回顾、查询、改期）优先；寒暄没有可存的；然后看证据。单独的 今天/今晚 不算安排证据（安排要有未来锚点），
     * 了/昨天/刚/第一次 偏向记录，完全没有证据默认记录，只有两边冲突（或只有很弱的安排线索）才问。
     */
    internal fun detectVerdict(text: String, reading: NaturalLexicon.TimeReading, now: Instant, calendar: MimiCalendar): Pair<ParentingIntentKind, Boolean> {
        fun has(words: List<String>) = words.any { text.contains(it) }
        if (has(lexicon.reviewMarkers)) return ParentingIntentKind.ReviewGrowth to false
        if (has(lexicon.todayQueryMarkers) ||
            (has(lexicon.todayWords) && has(lexicon.queryCues) && !reading.hasClock && !has(lexicon.rescheduleTriggers) && !lexicon.hasCompletedParticle(text))
        ) return ParentingIntentKind.ViewToday to false
        if (has(lexicon.planQueryMarkers)) return ParentingIntentKind.ViewPlan to false
        if (has(lexicon.rescheduleTriggers)) return ParentingIntentKind.Reschedule to false
        val hasLetters = text.any { it in '\u4e00'..'\u9fff' || it in '0'..'9' }
        if (!hasLetters) return ParentingIntentKind.Unknown to false
        // 只有引导语（「记一下」）：是记录，卡片会问记什么。
        if (lexicon.contentWithoutLeads(text).isEmpty() && lexicon.observationLeads.any { text.startsWith(it) }) return ParentingIntentKind.Observation to false
        val e = lexicon.intentEvidence(text, reading, now, calendar)
        if (lexicon.isChitchatOrQuestion(text, e)) return ParentingIntentKind.Unknown to false
        if (e.arrangement == 0) return ParentingIntentKind.Observation to false
        if (e.record <= 1) return if (e.arrangement >= 2) ParentingIntentKind.NewArrangement to false else ParentingIntentKind.Unknown to true
        val d = e.arrangement - e.record
        if (d >= 3) return ParentingIntentKind.NewArrangement to false
        if (d <= -2) return ParentingIntentKind.Observation to false
        return ParentingIntentKind.Unknown to true
    }

    internal fun detect(text: String, reading: NaturalLexicon.TimeReading, now: Instant, calendar: MimiCalendar): ParentingIntentKind =
        detectVerdict(text, reading, now, calendar).first

    /** 有日期、没钟点、也没说时段时补的钟点。 */
    const val DEFAULT_HOUR = 9

    // MARK: 字段
    private fun resolveChild(p: ParentingNaturalProposal, missing: MutableList<ParentingMissingField>, text: String, context: ParentingNaturalContext) {
        val forced = p.overrides.childID
        if (forced != null && context.children.any { it.id == forced }) { p.childID = forced; return }
        val named = context.children.filter { it.name.isNotEmpty() && text.contains(it.name) }
        if (named.size == 1) { p.childID = named[0].id; return }
        if (named.size > 1) { missing.add(ParentingMissingField.Child); p.childChoices = named.map { it.id }; return }
        if (context.children.size == 1) { p.childID = context.children[0].id; return }
        val active = context.activeChildID
        if (active != null && context.children.any { it.id == active }) { p.childID = active; return }
        missing.add(ParentingMissingField.Child); p.childChoices = context.children.map { it.id }
    }

    private fun stripChildNames(text: String, children: List<ParentingChild>): String {
        var out = text
        for (child in children) if (child.name.isNotEmpty()) out = out.replace(child.name, " ")
        return out
    }

    private fun fillArrangement(
        p: ParentingNaturalProposal, missing: MutableList<ParentingMissingField>, text: String,
        reading: NaturalLexicon.TimeReading, context: ParentingNaturalContext, now: Instant, calendar: MimiCalendar,
    ) {
        resolveChild(p, missing, text, context)
        val residual = stripChildNames(lexicon.removing(reading.consumed, text), context.children)
        val title = lexicon.cleanTitle(residual)
        if (title.isEmpty()) missing.add(ParentingMissingField.Title) else p.title = title.prefixGraphemes(160)

        val part = lexicon.dayPart(text)
        val hasAnchor = reading.day != null || reading.hasClock || part != null
        val today = calendar.startOfDay(now)
        fun tomorrow(d: Instant) = calendar.addingDays(d, 1)

        p.overrides.dueAt?.let { forced ->
            // 家长给没有日期的钟点选了上午/下午：今天，已过则明天。
            if (reading.day == null && forced <= now) { p.dueAt = tomorrow(forced); p.assumedDay = ParentingNaturalProposal.AssumedDay.Tomorrow }
            else { p.dueAt = forced; if (reading.day == null && hasAnchor) p.assumedDay = ParentingNaturalProposal.AssumedDay.Today }
            return
        }
        // 日期、钟点、时段都没有：问哪一天（不问几点）。
        if (!hasAnchor) { missing.add(ParentingMissingField.Date); return }
        val statedDay = reading.day != null
        val day = reading.day ?: today
        if (!statedDay) p.assumedDay = ParentingNaturalProposal.AssumedDay.Today

        // 没有上下午的钟点（「5点」）问 上午 / 下午。
        if (reading.hour == null && reading.ambiguousHour != null) {
            missing.add(ParentingMissingField.Time); p.ambiguousHour = reading.ambiguousHour; p.ambiguousDay = day
            if (!statedDay) p.assumedDay = ParentingNaturalProposal.AssumedDay.None
            return
        }
        val hour = reading.hour
        if (hour != null) {
            var date = lexicon.date(day, hour, reading.minute, calendar)
            if (date != null) {
                // 说了的钟点照原样（过去了卡片会提示）；只有假定的那一天才顺延到明天。
                if (!statedDay && date <= now) { date = tomorrow(date); p.assumedDay = ParentingNaturalProposal.AssumedDay.Tomorrow }
                p.dueAt = date
                return
            }
        }
        // 没钟点：用时段的常用钟点，没说时段就 9:00。
        var date = lexicon.date(day, part?.hour ?: DEFAULT_HOUR, 0, calendar)
        if (date == null) { missing.add(ParentingMissingField.Time); p.ambiguousDay = day; return }
        p.assumedTime = if (part == null) ParentingNaturalProposal.AssumedTime.NineOClock else ParentingNaturalProposal.AssumedTime.DayPart
        if (calendar.isSameDay(day, now) && date <= now) {
            val minutes = (calendar.hour(now) * 60 + calendar.minute(now)) / 30 * 30 + 30
            val later = if (part != null && minutes < part.windowEnd * 60) lexicon.date(day, minutes / 60, minutes % 60, calendar) else null
            if (later != null) { date = later; p.assumedTime = ParentingNaturalProposal.AssumedTime.LaterThisPart }
            else {
                date = tomorrow(date)
                if (statedDay) p.assumedTime = ParentingNaturalProposal.AssumedTime.NextDay else p.assumedDay = ParentingNaturalProposal.AssumedDay.Tomorrow
            }
        }
        p.dueAt = date
    }

    private fun fillObservation(p: ParentingNaturalProposal, missing: MutableList<ParentingMissingField>, text: String, context: ParentingNaturalContext) {
        resolveChild(p, missing, text, context)
        val body = lexicon.stripLead(text)
        if (body.isEmpty()) missing.add(ParentingMissingField.Content) else p.observationText = body.prefixGraphemes(8000)
    }

    private fun fillReschedule(
        p: ParentingNaturalProposal, missing: MutableList<ParentingMissingField>, text: String,
        context: ParentingNaturalContext, now: Instant, calendar: MimiCalendar,
    ) {
        val trigger = lexicon.rescheduleTriggers
            .mapNotNull { word -> text.indexOf(word).takeIf { it >= 0 }?.let { word to it } }
            .minByOrNull { it.second }
        val pre = trigger?.let { text.substring(0, it.second) } ?: text
        val post = trigger?.let { text.substring(it.second + it.first.length) } ?: ""
        val preReading = lexicon.read(pre, now, calendar)
        val postReading = lexicon.read(post, now, calendar)
        val named = context.children.filter { it.name.isNotEmpty() && text.contains(it.name) }
        var pool = context.arrangements.filter { it.state.isOpen }
        val forcedChild = p.overrides.childID
        if (forcedChild != null) pool = pool.filter { it.childID == forcedChild }
        else if (named.size == 1) pool = pool.filter { it.childID == named[0].id }

        var target: ParentingArrangement? = null
        p.overrides.targetID?.let { forced -> target = context.arrangements.firstOrNull { it.id == forced && it.state.isOpen } }
        if (target == null) {
            val residual = stripChildNames(lexicon.removing(preReading.consumed, pre), context.children)
            val wanted = lexicon.bigrams(lexicon.cleanTitle(residual))
            val scored = pool.map { item ->
                val name = stripChildNames(item.title, context.children)
                val overlap = lexicon.bigrams(name).intersect(wanted).size
                val sameDay = preReading.day?.let { calendar.isSameDay(it, item.dueAt) } ?: false
                item to (overlap * 10 + if (sameDay) 5 else 0)
            }.sortedWith(compareByDescending<Pair<ParentingArrangement, Int>> { it.second }.thenBy { it.first.dueAt })
            val best = scored.firstOrNull()
            if (best != null && best.second >= 10 && (scored.getOrNull(1)?.let { it.second < best.second } ?: true)) {
                target = best.first
            } else {
                missing.add(ParentingMissingField.Target)
                p.targetChoices = if (scored.isEmpty()) emptyList() else scored.take(4).map { it.first.id }
            }
        }
        val t = target ?: return
        p.targetID = t.id; p.childID = t.childID; p.title = t.title

        p.overrides.dueAt?.let {
            p.dueAt = it; p.inheritedDate = postReading.day == null; checkChanged(p, missing, t); return
        }
        val day = postReading.day ?: calendar.startOfDay(t.dueAt)
        p.inheritedDate = postReading.day == null
        val hour = postReading.hour
        val date = if (hour != null) lexicon.date(day, hour, postReading.minute, calendar) else null
        if (date != null) {
            p.dueAt = date; checkChanged(p, missing, t)
        } else {
            missing.add(ParentingMissingField.Time); p.ambiguousHour = postReading.ambiguousHour; p.ambiguousDay = day
        }
    }

    private fun checkChanged(p: ParentingNaturalProposal, missing: MutableList<ParentingMissingField>, target: ParentingArrangement) {
        val due = p.dueAt ?: return
        if (Math.floorDiv(due.epochSecond, 60L) == Math.floorDiv(target.dueAt.epochSecond, 60L)) {
            p.dueAt = null; missing.add(ParentingMissingField.Time)
        }
    }

    // MARK: 追问
    /** 只问一个问题：第一个缺的字段。已经清楚的字段不会再问。 */
    fun followUp(p: ParentingNaturalProposal, context: ParentingNaturalContext, calendar: MimiCalendar): ParentingFollowUp? {
        val field = p.missing.minOrNull() ?: return null
        return when (field) {
            ParentingMissingField.Intent -> {
                // 两个大按钮，绝不在这里问时间：记下来（已经发生，不提醒）/ 安排提醒（到点提醒）。
                ParentingFollowUp(
                    field, if (p.ambiguousIntent) "这条怎么记？" else "小咪没太听明白，这条怎么记？",
                    listOf(
                        ParentingFollowUp.Option("intent." + ParentingIntentKind.Observation.rawValue, ParentingFollowUp.Label.Key("记下来"), null,
                            ParentingNaturalAnswer.Intent(ParentingIntentKind.Observation), subtitle = "已经发生，只记录，不提醒"),
                        ParentingFollowUp.Option("intent." + ParentingIntentKind.NewArrangement.rawValue, ParentingFollowUp.Label.Key("安排提醒"), null,
                            ParentingNaturalAnswer.Intent(ParentingIntentKind.NewArrangement), subtitle = "还没发生，到点提醒我"),
                    ),
                    allowsText = false, textHint = null,
                )
            }
            ParentingMissingField.Child -> {
                val choices = p.childChoices.mapNotNull { id -> context.children.firstOrNull { it.id == id } }
                ParentingFollowUp(
                    field, if (choices.isEmpty()) "还没有孩子。请先添加孩子的昵称，再记这件事。" else "这是哪位孩子的事？",
                    choices.map { ParentingFollowUp.Option("child." + it.id.uuidString, ParentingFollowUp.Label.Verbatim(it.name), null, ParentingNaturalAnswer.Child(it.id)) },
                    allowsText = false, textHint = null,
                )
            }
            ParentingMissingField.Target -> {
                val choices = p.targetChoices.mapNotNull { id -> context.arrangements.firstOrNull { it.id == id } }
                ParentingFollowUp(
                    field, if (choices.isEmpty()) "还没有可以改期的安排。先记一件安排吧。" else "要改哪一项安排？",
                    choices.map { ParentingFollowUp.Option("target." + it.id.uuidString, ParentingFollowUp.Label.Verbatim(it.title), it.dueAt, ParentingNaturalAnswer.Target(it.id)) },
                    allowsText = false, textHint = null,
                )
            }
            ParentingMissingField.Date ->
                ParentingFollowUp(field, "哪一天？", emptyList(), allowsText = true, textHint = "例如：周五、明天、10月30日")
            ParentingMissingField.Time -> {
                val hour = p.ambiguousHour
                val day = p.ambiguousDay
                if (hour != null && day != null) {
                    var hours = if (hour == 12) listOf(12) else listOf(hour, hour + 12)
                    val target = p.targetID?.let { id -> context.arrangements.firstOrNull { it.id == id } }
                    if (target != null && calendar.hour(target.dueAt) >= 12 && hours.size == 2) hours = hours.reversed()
                    val minute = lexicon.read(p.sourceText, day, calendar).minute
                    val options = hours.mapNotNull { h ->
                        val date = lexicon.date(day, h, minute, calendar) ?: return@mapNotNull null
                        ParentingFollowUp.Option("time.$h", ParentingFollowUp.Label.DateTime(date), null, ParentingNaturalAnswer.DueAt(date))
                    }
                    ParentingFollowUp(field, "是上午还是下午？", options, allowsText = false, textHint = null)
                } else {
                    ParentingFollowUp(
                        field, if (p.kind == ParentingIntentKind.Reschedule) "改到几点？" else "几点开始？",
                        emptyList(), allowsText = true, textHint = "例如：下午5点、18:30",
                    )
                }
            }
            ParentingMissingField.Title ->
                ParentingFollowUp(field, "是什么事？", emptyList(), allowsText = true, textHint = "一句话就行，例如：游泳课")
            ParentingMissingField.Content ->
                ParentingFollowUp(field, "想记下什么？", emptyList(), allowsText = true, textHint = "例如：今天他自己收好了书包")
        }
    }

    /** 把原话和一个回答一起重读；之前的回答继续有效。 */
    fun applying(
        answer: ParentingNaturalAnswer, p: ParentingNaturalProposal, context: ParentingNaturalContext,
        now: Instant, calendar: MimiCalendar,
    ): ParentingNaturalProposal {
        var overrides = p.overrides
        var text = p.sourceText
        when (answer) {
            is ParentingNaturalAnswer.Intent -> overrides = overrides.copy(kind = answer.kind)
            is ParentingNaturalAnswer.Child -> overrides = overrides.copy(childID = answer.id)
            is ParentingNaturalAnswer.Target -> overrides = overrides.copy(targetID = answer.id)
            is ParentingNaturalAnswer.DueAt -> overrides = overrides.copy(dueAt = answer.date)
            is ParentingNaturalAnswer.Text -> {
                val trimmed = answer.text.trimSwift()
                if (trimmed.isNotEmpty()) text = p.sourceText + " " + trimmed
                // 已确定的意图保持不变：之后的回答不能把观察变成安排。
                if (p.kind != ParentingIntentKind.Unknown) overrides = overrides.copy(kind = p.kind)
            }
        }
        return classify(text, context, now, calendar, overrides)
    }

    // MARK: 写入
    data class WriteIDs(val record: UUID = UUID.randomUUID())

    /** 已确认的卡片对应的命令（按顺序）。缺东西或只是查看时为空。 */
    fun commands(p: ParentingNaturalProposal, workspace: ParentingWorkspace, ids: WriteIDs): List<ParentingCommand> {
        if (!p.isReadyToConfirm) return emptyList()
        return when (p.kind) {
            ParentingIntentKind.NewArrangement -> {
                val child = p.childID; val title = p.title; val due = p.dueAt
                if (child == null || title == null || due == null) return emptyList()
                listOf(
                    ParentingCommand.CreateDraft(ids.record, child, title, due, null, p.sourceText),
                    ParentingCommand.ConfirmArrangement(ids.record),
                )
            }
            ParentingIntentKind.Observation -> {
                val child = p.childID; val text = p.observationText
                if (child == null || text == null) return emptyList()
                listOf(ParentingCommand.AddObservation(ids.record, child, null, text))
            }
            ParentingIntentKind.Reschedule -> {
                val id = p.targetID; val due = p.dueAt
                val item = id?.let { i -> workspace.arrangements.firstOrNull { it.id == i } }
                if (id == null || due == null || item == null) return emptyList()
                val conflicts = workspace.sameTimeConflicts(id, due).map { it.id }
                listOf(
                    ParentingCommand.ReviseArrangement(
                        ids.record, ParentingArrangementEditTarget(item, workspace.revision), item.title, due, item.goalID,
                        p.sourceText, conflicts,
                    ),
                )
            }
            else -> emptyList()
        }
    }
}
