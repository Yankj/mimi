package com.heydusk.mimi.domain.parenting

import java.time.Instant
import java.util.UUID

/**
 * 确认卡片可展示的封闭类型目录。界面层用固定的 Compose 组件渲染每一种，没有自由格式的标记或脚本通路，
 * 所以卡片永远不会显示目录之外的东西。文案都是资源键（经 UIStrings 取词），符号名沿用 iOS 的 SF Symbols 名，
 * 由 Android 界面映射到图标。
 */
data class ParentingConfirmCard(
    val kind: ParentingIntentKind,
    val blocks: List<Block>,
    /** 主按钮的资源键。 */
    val confirmTitle: String,
    /** 按钮是否会写账本。 */
    val writes: Boolean,
) {
    enum class Tone { Normal, Changed, Unknown, Warning }

    sealed interface Value {
        /** 家长输入的或已保存的名字。 */
        data class Text(val text: String) : Value
        data class Date(val date: Instant) : Value
        data class Key(val key: String) : Value
    }

    data class Field(val label: String, val value: Value, val tone: Tone) { val id: String get() = label }
    data class Conflict(val id: UUID, val title: String, val at: Instant)

    sealed interface Block {
        data class Headline(val title: String, val symbol: String) : Block
        data class Fields(val fields: List<Field>) : Block
        data class Change(val label: String, val before: Instant, val after: Instant) : Block
        data class Quote(val label: String, val text: String) : Block
        data class Conflicts(val title: String, val items: List<Conflict>) : Block
        data class Note(val text: String, val tone: Tone) : Block
    }

    companion object {
        /** 从就绪的提案构建卡片；还缺任何东西就返回 null：卡片从不替代提问。 */
        fun make(p: ParentingNaturalProposal, workspace: ParentingWorkspace, remindersEnabled: Boolean?, now: Instant): ParentingConfirmCard? {
            if (!p.isReadyToConfirm) return null
            val childName = p.childID?.let { id -> workspace.children.firstOrNull { it.id == id }?.name }
            when (p.kind) {
                ParentingIntentKind.NewArrangement -> {
                    val title = p.title; val due = p.dueAt
                    if (childName == null || title == null || due == null) return null
                    val blocks = mutableListOf<Block>(
                        Block.Headline("新安排", "calendar.badge.plus"),
                        Block.Fields(listOf(
                            Field("孩子", Value.Text(childName), Tone.Normal),
                            Field("事项", Value.Text(title), Tone.Normal),
                            Field("时间", Value.Date(due), Tone.Normal),
                        )),
                    )
                    val conflicts = conflictItems(workspace, null, due)
                    if (conflicts.isNotEmpty()) blocks.add(Block.Conflicts("同一时间已有安排，请核对", conflicts))
                    if (due < now) blocks.add(Block.Note("这个时间已经过去，请确认。", Tone.Warning))
                    when (p.assumedDay) {
                        ParentingNaturalProposal.AssumedDay.Today -> blocks.add(Block.Note("没说日期，先定在今天；不对可以点“手动调整”。", Tone.Unknown))
                        ParentingNaturalProposal.AssumedDay.Tomorrow -> blocks.add(Block.Note("没说日期，今天这个时间已过，先定在明天；不对可以点“手动调整”。", Tone.Unknown))
                        ParentingNaturalProposal.AssumedDay.None -> Unit
                    }
                    when (p.assumedTime) {
                        ParentingNaturalProposal.AssumedTime.None -> Unit
                        ParentingNaturalProposal.AssumedTime.NineOClock -> blocks.add(Block.Note("没说几点，先定在 9:00；不对可以点“手动调整”。", Tone.Unknown))
                        ParentingNaturalProposal.AssumedTime.DayPart -> blocks.add(Block.Note("没说几点，先按你说的时段定了常用时间；不对可以点“手动调整”。", Tone.Unknown))
                        ParentingNaturalProposal.AssumedTime.LaterThisPart -> blocks.add(Block.Note("今天这个时段的常用时间已过，先排在最近的半点；不对可以点“手动调整”。", Tone.Unknown))
                        ParentingNaturalProposal.AssumedTime.NextDay -> blocks.add(Block.Note("今天这个时间已过，先排在明天；要改回今天，点“手动调整”。", Tone.Unknown))
                    }
                    blocks.add(Block.Quote("你说的原话", p.sourceText))
                    blocks.add(Block.Note("确认后才进入今天的安排。", Tone.Normal))
                    reminderNote(remindersEnabled)?.let { blocks.add(it) }
                    return ParentingConfirmCard(p.kind, blocks, "确认安排", true)
                }
                ParentingIntentKind.Reschedule -> {
                    val title = p.title; val due = p.dueAt; val id = p.targetID
                    val original = id?.let { i -> workspace.arrangements.firstOrNull { it.id == i } }
                    if (childName == null || title == null || due == null || id == null || original == null) return null
                    val blocks = mutableListOf<Block>(
                        Block.Headline("改期", "arrow.triangle.2.circlepath"),
                        Block.Fields(listOf(
                            Field("孩子", Value.Text(childName), Tone.Normal),
                            Field("事项", Value.Text(title), Tone.Normal),
                        )),
                        Block.Change("时间", original.dueAt, due),
                    )
                    val conflicts = conflictItems(workspace, id, due)
                    if (conflicts.isNotEmpty()) blocks.add(Block.Conflicts("同一时间已有安排，确认即表示已知悉", conflicts))
                    if (p.inheritedDate) blocks.add(Block.Note("日期沿用原安排；不对可以点“手动调整”。", Tone.Unknown))
                    if (due < now) blocks.add(Block.Note("这个时间已经过去，请确认。", Tone.Warning))
                    blocks.add(Block.Quote("你说的原话", p.sourceText))
                    blocks.add(Block.Note("只改这一项安排，不影响其他安排。可以在安排里撤销这次调整。", Tone.Normal))
                    reminderNote(remindersEnabled)?.let { blocks.add(it) }
                    return ParentingConfirmCard(p.kind, blocks, "确认改期", true)
                }
                ParentingIntentKind.Observation -> {
                    val text = p.observationText
                    if (childName == null || text == null) return null
                    return ParentingConfirmCard(
                        p.kind,
                        listOf(
                            Block.Headline("成长观察", "leaf"),
                            Block.Fields(listOf(Field("孩子", Value.Text(childName), Tone.Normal))),
                            Block.Quote("记下的内容", text),
                            Block.Note("只记录这一笔，不会提醒你，也不会写入长期记忆；之后可以修改或删除。要提醒就点上面的“加安排”。", Tone.Normal),
                        ),
                        "保存观察", true,
                    )
                }
                ParentingIntentKind.ProfileFact -> {
                    val fact = p.profileFact ?: return null
                    if (!fact.canOfferConfirmCard) return null
                    val scopeLabel = when (val id = fact.scope.childID) {
                        null -> "我们家"
                        else -> workspace.children.firstOrNull { it.id == id }?.name ?: return null
                    }
                    return ParentingConfirmCard(
                        p.kind,
                        listOf(
                            Block.Headline("记入档案", "person.text.rectangle"),
                            Block.Fields(listOf(
                                Field("范围", Value.Text(scopeLabel), Tone.Normal),
                                Field("长期事实", Value.Text(fact.text), Tone.Normal),
                            )),
                            Block.Quote("你说的原话", p.sourceText),
                            Block.Note("确认后才记成这台设备上的长期事实。先不记就不会写入。", Tone.Normal),
                        ),
                        "确认记入档案", true,
                    )
                }
                ParentingIntentKind.ViewToday, ParentingIntentKind.ViewPlan, ParentingIntentKind.ReviewGrowth -> {
                    val title = when (p.kind) {
                        ParentingIntentKind.ReviewGrowth -> "看成长回顾"
                        ParentingIntentKind.ViewPlan -> "看计划"
                        else -> "看今天"
                    }
                    return ParentingConfirmCard(
                        p.kind,
                        listOf(
                            Block.Headline(title, if (p.kind == ParentingIntentKind.ReviewGrowth) "leaf" else "sun.max"),
                            Block.Note("这是查看，不会写入或修改任何内容。", Tone.Normal),
                        ),
                        "带我去看", false,
                    )
                }
                ParentingIntentKind.Unknown -> return null
            }
        }

        private fun conflictItems(workspace: ParentingWorkspace, excluding: UUID?, due: Instant): List<Conflict> =
            workspace.sameTimeConflicts(excluding ?: UUID.randomUUID(), due).map { Conflict(it.id, it.title, it.dueAt) }

        private fun reminderNote(enabled: Boolean?): Block? {
            if (enabled == null) return null
            return if (enabled) Block.Note("提醒按你的设置发送：每天最多 2 条，21:00–08:00 不打扰。", Tone.Normal)
            else Block.Note("提醒没有开启；可以在“我们家”里打开。", Tone.Unknown)
        }
    }
}

/** 共享孩子选择器的选中规则（iOS `ParentingChildSwitcherRules`，#147）。 */
sealed interface ParentingChildSwitcherSelection {
    data object All : ParentingChildSwitcherSelection
    data class Child(val id: UUID) : ParentingChildSwitcherSelection
}

object ParentingChildSwitcherRules {
    fun showsAllChip(childCount: Int, allowsAll: Boolean): Boolean = allowsAll && childCount > 1

    /** fallsBackToFirst = false 用于提问（「这是哪位孩子的事？」）：什么都不预选，不猜孩子。 */
    fun resolve(selected: UUID?, childIDs: List<UUID>, allowsAll: Boolean, fallsBackToFirst: Boolean = true): ParentingChildSwitcherSelection {
        if (selected != null && selected in childIDs) return ParentingChildSwitcherSelection.Child(selected)
        if (showsAllChip(childIDs.size, allowsAll)) return ParentingChildSwitcherSelection.All
        if (fallsBackToFirst && childIDs.isNotEmpty()) return ParentingChildSwitcherSelection.Child(childIDs.first())
        return ParentingChildSwitcherSelection.All
    }
}
