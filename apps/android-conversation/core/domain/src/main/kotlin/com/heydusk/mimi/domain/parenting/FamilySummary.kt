package com.heydusk.mimi.domain.parenting

import com.heydusk.mimi.domain.common.trimSwift
import java.text.BreakIterator
import java.time.Instant
import java.util.UUID

/** 「我们家」里一个孩子档案展示的内容。只有本地账本已有的数据：昵称、目标、观察。没有照片、身高体重或发育字段，也不编造。 */
data class ParentingFamilyChildSummary(
    val id: UUID,
    val name: String,
    /** 昵称的第一个字符（头像上显示）；昵称为空时是 "•"。 */
    val initial: String,
    /** 孩子的强调色槽（创建顺序，循环）。今天、成长、我们家的头像环用同一个槽。 */
    val accentSlot: Int,
    val goalCount: Int,
    /** 只数观察；完成记录是另一种，不在这里计数。 */
    val observationCount: Int,
    val startedAt: Instant,
)

/** 「我们家」页的账本纯投影。不碰存储、网络，也没有新字段。 */
object ParentingFamilySummary {
    fun initial(name: String): String {
        val trimmed = name.trimSwift()
        if (trimmed.isEmpty()) return "•"
        val it = BreakIterator.getCharacterInstance()
        it.setText(trimmed)
        return trimmed.substring(0, it.next())
    }

    fun summary(child: ParentingChild, workspace: ParentingWorkspace) = ParentingFamilyChildSummary(
        child.id, child.name, initial(child.name),
        ParentingGrowthInsights.accentSlot(child.id, workspace),
        workspace.goals.count { it.childID == child.id },
        workspace.records.count { it.childID == child.id && it.kind == ParentingRecordKind.Observation },
        child.createdAt,
    )

    fun children(workspace: ParentingWorkspace): List<ParentingFamilyChildSummary> = workspace.children.map { summary(it, workspace) }
}

/** 启航卡的一步。`title` 是资源键（界面用 ui(...) 取本地化文案）。 */
data class FamilyLaunchStep(val id: String, val title: String, val isDone: Boolean)

/**
 * 启航 n/3 就是 `ParentingStartAction.launchChecklist`：决定「我们家」起手动作的同一套规则。
 * 这里只放三步的文案键。
 */
data class FamilyLaunchChecklist(val steps: List<FamilyLaunchStep>) {
    val doneCount: Int get() = steps.count { it.isDone }
    val totalCount: Int get() = steps.size
    /** 前两步必做；提醒步可选，完成或未出现都算清单可收起。 */
    val isFinished: Boolean get() = steps.filter { it.id != "reminders" }.all { it.isDone }

    companion object {
        fun from(checklist: ParentingLaunchChecklist) = FamilyLaunchChecklist(
            listOf(
                FamilyLaunchStep("child", "添加孩子的昵称", ParentingLaunchChecklist.Step.AddChild in checklist.done),
                FamilyLaunchStep("first-record", "记下第一件安排或观察", ParentingLaunchChecklist.Step.RecordFirstThing in checklist.done),
                FamilyLaunchStep("reminders", "到点提醒", ParentingLaunchChecklist.Step.EnableReminders in checklist.done),
            ),
        )

        fun current(workspace: ParentingWorkspace, remindersEnabled: Boolean): FamilyLaunchChecklist =
            from(ParentingStartAction.launchChecklist(workspace, ParentingStartContext(remindersEnabled = remindersEnabled)))
    }
}

/** 压在「我们家」之上的页面。 */
sealed interface ParentingFamilyRoute {
    data class Child(val id: UUID) : ParentingFamilyRoute
    data object Reminders : ParentingFamilyRoute
    data object Settings : ParentingFamilyRoute
}
