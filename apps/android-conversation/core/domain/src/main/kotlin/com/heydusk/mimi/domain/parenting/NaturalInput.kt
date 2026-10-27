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
    NewArrangement("newArrangement", "记一件安排"),
    Reschedule("reschedule", "改期"),
    Observation("observation", "记一个观察"),
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

    data class Option(val id: String, val label: Label, val detail: Instant?, val answer: ParentingNaturalAnswer)
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
) {
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
        val text = rawText.trimSwift()
        val p = ParentingNaturalProposal(sourceText = text, kind = ParentingIntentKind.Unknown, overrides = overrides)
        if (text.isEmpty() || text.graphemeCount() > MAX_LENGTH || text.containsNul()) {
            p.missing = listOf(ParentingMissingField.Intent); return p
        }
        val reading = lexicon.read(text, now, calendar)
        val kind = overrides.kind ?: detect(text, reading, now, calendar)
        p.kind = kind
        val missing = mutableListOf<ParentingMissingField>()
        when (kind) {
            ParentingIntentKind.Unknown -> missing.add(ParentingMissingField.Intent)
            ParentingIntentKind.ViewToday, ParentingIntentKind.ViewPlan, ParentingIntentKind.ReviewGrowth -> Unit
            ParentingIntentKind.NewArrangement -> fillArrangement(p, missing, text, reading, context, calendar)
            ParentingIntentKind.Observation -> fillObservation(p, missing, text, context)
            ParentingIntentKind.Reschedule -> fillReschedule(p, missing, text, context, now, calendar)
        }
        p.missing = missing.distinct().sorted()
        return p
    }

    // MARK: 意图
    internal fun detect(text: String, reading: NaturalLexicon.TimeReading, now: Instant, calendar: MimiCalendar): ParentingIntentKind {
        fun has(words: List<String>) = words.any { text.contains(it) }
        if (has(lexicon.reviewMarkers)) return ParentingIntentKind.ReviewGrowth
        if (has(lexicon.todayQueryMarkers) ||
            (has(lexicon.todayWords) && has(lexicon.queryCues) && !reading.hasClock && !has(lexicon.rescheduleTriggers))
        ) return ParentingIntentKind.ViewToday
        if (has(lexicon.planQueryMarkers)) return ParentingIntentKind.ViewPlan
        if (has(lexicon.rescheduleTriggers)) return ParentingIntentKind.Reschedule
        val today = calendar.startOfDay(now)
        val future = (reading.day?.let { it > today } ?: false) || has(lexicon.futureDateWords)
        val observed = has(lexicon.observationMarkers) || lexicon.observationLeads.any { text.startsWith(it) }
        val past = lexicon.endsWithPastMarker(text)
        if ((observed || past) && !future && !reading.hasClock) return ParentingIntentKind.Observation
        if (reading.day != null || reading.hasClock || has(lexicon.arrangementWords)) return ParentingIntentKind.NewArrangement
        if (lexicon.observationLeads.any { text.startsWith(it) }) return ParentingIntentKind.Observation
        return ParentingIntentKind.Unknown
    }

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
        reading: NaturalLexicon.TimeReading, context: ParentingNaturalContext, calendar: MimiCalendar,
    ) {
        resolveChild(p, missing, text, context)
        val residual = stripChildNames(lexicon.removing(reading.consumed, text), context.children)
        val title = lexicon.cleanTitle(residual)
        if (title.isEmpty()) missing.add(ParentingMissingField.Title) else p.title = title.prefixGraphemes(160)
        p.overrides.dueAt?.let { p.dueAt = it; return }
        val day = reading.day
        if (day == null) {
            missing.add(ParentingMissingField.Date)
            p.ambiguousHour = reading.ambiguousHour
            if (!reading.hasClock) missing.add(ParentingMissingField.Time)
            return
        }
        val hour = reading.hour
        val date = if (hour != null) lexicon.date(day, hour, reading.minute, calendar) else null
        if (date != null) {
            p.dueAt = date
        } else {
            missing.add(ParentingMissingField.Time); p.ambiguousHour = reading.ambiguousHour; p.ambiguousDay = day
        }
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
                val kinds = listOf(ParentingIntentKind.NewArrangement, ParentingIntentKind.Observation)
                ParentingFollowUp(
                    field, "想记一件安排，还是记下一个观察？",
                    kinds.map { ParentingFollowUp.Option("intent." + it.rawValue, ParentingFollowUp.Label.Key(it.title), null, ParentingNaturalAnswer.Intent(it)) },
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
